package com.baestheorem.inbox.data

import android.content.Context
import com.baestheorem.inbox.auth.AuthStore
import com.baestheorem.inbox.gmail.GmailClient
import com.baestheorem.inbox.gmail.GThread
import com.baestheorem.inbox.gmail.buildRawMessage
import com.baestheorem.inbox.work.MailWork
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** App-lifetime writer: closing an editor/Activity does not cancel its pending save. */
object OfflineMail {
    private lateinit var context: Context
    lateinit var db: MailDatabase
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val syncLock = Mutex()
    private val _drafts = MutableStateFlow<List<LocalDraft>>(emptyList())
    val drafts: StateFlow<List<LocalDraft>> = _drafts
    private val _status = MutableStateFlow(OfflineStatus())
    val status: StateFlow<OfflineStatus> = _status
    val revision = MutableStateFlow(0L)
    private lateinit var outbox: OutboxProcessor

    fun init(app: Context) {
        if (::context.isInitialized) return
        context = app.applicationContext
        db = MailDatabase(context)
        outbox = OutboxProcessor(db, GmailClient, changed = { publish() })
        scope.launch {
            for (command in writes) command()
        }
        scope.launch {
            if (AuthStore.isSignedIn && AuthStore.account.isNotBlank()) {
                try { activate(AuthStore.account) } catch (e: IOException) {
                    _status.value = _status.value.copy(error = e.message)
                }
            } else if (!AuthStore.isSignedIn) {
                // Finish clearing if the process stopped during sign-out.
                clear()
            }
        }
    }

    suspend fun activate(account: String) = withContext(Dispatchers.IO) {
        if (MailDatabase.normalize(account) != MailDatabase.normalize(AuthStore.account) || !AuthStore.isSignedIn) {
            throw AccountChangedException()
        }
        val switched = db.activate(account)
        if (switched) {
            SnoozeStore.clear()
            UnsubScanCache.clear()
            Prefs.clearSeen()
        }
        publish()
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        MailWork.cancel(context)
        db.clear()
        SnoozeStore.clear()
        UnsubScanCache.clear()
        Prefs.clearSeen()
        context.cacheDir.resolve("attachments").deleteRecursively()
        _drafts.value = emptyList()
        _status.value = OfflineStatus()
        revision.value++
    }

    suspend fun publish() {
        val account = AuthStore.account
        if (!AuthStore.isSignedIn || account.isBlank() || db.owner() != MailDatabase.normalize(account)) return
        db.pruneSentDrafts(account, System.currentTimeMillis() - 30 * 24 * 60 * 60_000L)
        _drafts.value = db.drafts(account).filter { it.status != DraftStatus.SENT && !it.isEmpty }
        val counts = db.counts(account)
        _status.value = _status.value.copy(
            available = counts.first, complete = counts.second, lastSync = db.syncState(account).lastSync,
        )
        revision.value++
    }

    fun save(draft: LocalDraft): Deferred<LocalDraft> = write {
        val saved = db.saveDraft(draft.copy(updatedAt = System.currentTimeMillis()))
        publish()
        saved
    }

    /** Read after earlier editor writes, including an editor destroyed by rotation. */
    fun load(id: String): Deferred<LocalDraft?> = write { db.draft(AuthStore.account, id) }

    fun replyDraft(threadId: String): Deferred<LocalDraft?> = write {
        db.replyDraft(AuthStore.account, threadId)
    }

    /** Keep a just-opened recent message even before the inventory reaches it. */
    suspend fun cacheOpenedThread(account: String, thread: GThread) = withContext(Dispatchers.IO) {
        val cutoff = OfflinePolicy.cutoff(System.currentTimeMillis())
        for (message in thread.messages.filter { OfflinePolicy.includes(it, cutoff) }) {
            if (db.hasMessage(account, message.id)) db.updateMetadata(account, message)
            else {
                db.putMessage(account, message, db.syncState(account).checkpoint?.generation)
                if (OfflinePolicy.attachmentIds(message.payload).isEmpty()) db.markComplete(account, message.id)
            }
        }
        publish()
        syncNow()
    }

    fun queue(draft: LocalDraft): Deferred<LocalDraft> = write {
        if (!draft.to.contains("@")) throw IOException("Enter a recipient email address.")
        val saved = db.saveDraft(draft)
        if (saved.status != DraftStatus.DRAFT) return@write saved
        val now = System.currentTimeMillis()
        val raw = try {
            buildRawMessage(
                saved.to, saved.cc, saved.subject, saved.body, saved.fromName, saved.account,
                saved.inReplyTo, saved.references, saved.rfcMessageId, now,
            )
        } catch (e: IllegalArgumentException) {
            throw IOException(e.message, e)
        }
        val queued = db.queue(saved.account, saved.id, raw, now)
        publish()
        MailWork.send(context)
        queued
    }

    fun discard(draft: LocalDraft): Deferred<Unit> = write {
        db.deleteDraft(draft.account, draft.id)
        publish()
    }

    fun editQueued(draft: LocalDraft): Deferred<Unit> = write {
        db.returnToDraft(draft.account, draft.id)
        publish()
    }

    private fun <T> write(block: suspend () -> T): Deferred<T> {
        val result = CompletableDeferred<T>()
        if (writes.trySend {
                try { result.complete(block()) } catch (e: Exception) {
                    result.completeExceptionally(if (e is IOException) e else IOException("Could not save local mail.", e))
                }
            }.isFailure) result.completeExceptionally(IOException("Draft storage is unavailable."))
        return result
    }

    suspend fun sync(deadline: Long): Boolean = syncLock.withLock {
        val account = AuthStore.account
        if (!AuthStore.isSignedIn || account.isBlank()) return@withLock true
        activate(account)
        _status.value = _status.value.copy(syncing = true, error = null)
        try {
            OfflineSync(db, GmailClient.boundSyncGateway(), changed = { publish() }).run(account, deadline)
        } catch (e: IOException) {
            _status.value = _status.value.copy(error = e.message ?: "Download paused. Will retry when connected.")
            throw e
        } finally {
            _status.value = _status.value.copy(syncing = false)
            publish()
        }
    }

    suspend fun sendPending(): Boolean {
        val account = AuthStore.account
        if (!AuthStore.isSignedIn || account.isBlank()) return false
        activate(account)
        return outbox.drain(account, System.currentTimeMillis() + 8 * 60_000)
    }

    suspend fun retry(draft: LocalDraft) = withContext(Dispatchers.IO) {
        outbox.retry(draft.account, draft.id)
        MailWork.send(context)
    }

    fun syncNow() = MailWork.sync(context)
}
