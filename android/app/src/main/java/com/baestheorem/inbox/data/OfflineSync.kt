package com.baestheorem.inbox.data

import com.baestheorem.inbox.gmail.GHistoryList
import com.baestheorem.inbox.gmail.GHistoryRecord
import com.baestheorem.inbox.gmail.GLabel
import com.baestheorem.inbox.gmail.GMessage
import com.baestheorem.inbox.gmail.AuthExpiredException
import com.baestheorem.inbox.gmail.GMessageList
import com.baestheorem.inbox.gmail.GProfile
import com.baestheorem.inbox.gmail.GmailException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

interface MailSyncGateway {
    suspend fun profile(): GProfile
    suspend fun labels(): List<GLabel>
    suspend fun list(query: String, page: String?): GMessageList
    /** format=minimal: the current labels of a message already saved, a third of a full fetch. */
    suspend fun minimal(id: String): GMessage
    suspend fun message(id: String): GMessage
    suspend fun attachment(id: String, attachmentId: String): ByteArray
    suspend fun history(start: String, page: String?): GHistoryList
}

/**
 * Follows Google's "Synchronizing clients with Gmail" guide. A full sync runs on the first
 * connection and again only when Gmail no longer has the history since the saved cursor.
 * Otherwise history.list says what changed: label changes are applied locally, and only
 * new messages are downloaded. Every step is durable, so a run that ends (time budget,
 * lost connection, process death) resumes where it stopped:
 *
 * - the full sync checkpoints after each message,
 * - a history pass saves its next page token after applying each page,
 * - messages to download wait in a queue in the database.
 *
 * Partial work never empties a previously downloaded mailbox.
 */
class OfflineSync(
    private val db: MailDatabase,
    private val gateway: MailSyncGateway,
    private val now: () -> Long = System::currentTimeMillis,
    private val changed: suspend () -> Unit = {},
    /** Waits until background sync may spend this many Gmail quota units. */
    private val pace: suspend (Int) -> Unit = {},
    /** Usable bytes for downloaded mail. */
    private val freeSpace: () -> Long = { Long.MAX_VALUE },
) {
    private var snoozedLabel: String? = null

    /** False means the time budget ended; the next run resumes this pass. */
    suspend fun run(account: String, deadline: Long = Long.MAX_VALUE): Boolean {
        pace(GmailCost.PROFILE)
        val profile = gateway.profile()
        if (MailDatabase.normalize(profile.emailAddress.orEmpty()) != MailDatabase.normalize(account)) {
            throw AccountChangedException()
        }
        pace(GmailCost.LABELS)
        val labels = gateway.labels()
        db.saveLabels(account, labels)
        snoozedLabel = labels.firstOrNull { it.name == SNOOZED_LABEL }?.id
        var state = db.syncState(account)
        if (state.historyId == null && state.checkpoint == null) {
            state = state.copy(checkpoint = FullSyncCheckpoint(OfflinePolicy.cutoff(now()), profile.historyId))
            db.saveSyncState(account, state)
        }
        if (state.checkpoint != null && !inventory(account, deadline)) return false
        val caughtUp = try {
            partial(account, deadline)
        } catch (e: GmailException) {
            if (e.code != 404) throw e
            // Gmail keeps history for about a week, sometimes less. Keep the cache and run a
            // full sync, capturing the cursor BEFORE listing so changes made meanwhile replay.
            pace(GmailCost.PROFILE)
            db.saveSyncState(account, db.syncState(account).copy(
                historyId = null, historyPage = null,
                checkpoint = FullSyncCheckpoint(OfflinePolicy.cutoff(now()), gateway.profile().historyId),
            ))
            inventory(account, deadline) && partial(account, deadline)
        }
        if (!caughtUp || !drain(account, deadline)) return false
        // Mail and labels are current now; attachments come last and can't hold up the rest.
        evict(account)
        db.saveSyncState(account, db.syncState(account).copy(lastSync = now()))
        changed()
        return completeDownloads(account, deadline)
    }

    // MARK: Full sync

    private suspend fun inventory(account: String, deadline: Long): Boolean {
        var state = db.syncState(account)
        var checkpoint = state.checkpoint ?: return true
        while (true) {
            currentCoroutineContext().ensureActive()
            if (now() >= deadline) return false
            if (!checkpoint.pageLoaded) {
                val page = try {
                    pace(GmailCost.LIST)
                    gateway.list(OfflinePolicy.query(checkpoint.cutoff), checkpoint.nextPage)
                } catch (e: GmailException) {
                    if (e.code != 400 || checkpoint.nextPage == null) throw e
                    // A saved page token can expire between runs.
                    pace(GmailCost.PROFILE)
                    checkpoint = FullSyncCheckpoint(OfflinePolicy.cutoff(now()), gateway.profile().historyId)
                    db.saveSyncState(account, state.copy(checkpoint = checkpoint))
                    continue
                }
                checkpoint = checkpoint.copy(
                    nextPage = page.nextPageToken, remaining = page.messages.map { it.id }, pageLoaded = true,
                )
                db.saveSyncState(account, state.copy(checkpoint = checkpoint))
            }
            while (checkpoint.remaining.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                if (now() >= deadline) return false
                val id = checkpoint.remaining.first()
                // Counted first, so a message that fails (or crashes the run) every time is skipped.
                if (db.noteInventoryTry(account, id) <= MAX_TRIES) {
                    // Google's guide: full content the first time, then format=minimal for labels.
                    if (db.hasMessage(account, id)) refreshLabels(account, id, checkpoint.generation)
                    else fetchNew(account, id, checkpoint.cutoff, checkpoint.generation)
                }
                checkpoint = checkpoint.copy(remaining = checkpoint.remaining.drop(1))
                db.saveSyncState(account, state.copy(checkpoint = checkpoint))
                changed()
            }
            if (checkpoint.nextPage == null) {
                db.finishInventory(account, checkpoint.cutoff, checkpoint.generation, snoozedLabel)
                state = db.syncState(account).copy(
                    checkpoint = null, historyId = checkpoint.startHistoryId, historyPage = null,
                    lastFullSync = now(), lastSync = now(),
                )
                db.saveSyncState(account, state)
                changed()
                return true
            }
            checkpoint = checkpoint.copy(pageLoaded = false)
            db.saveSyncState(account, state.copy(checkpoint = checkpoint))
        }
    }

    // MARK: Partial sync

    private suspend fun partial(account: String, deadline: Long): Boolean {
        val saved = db.syncState(account)
        val start = saved.historyId ?: return true
        val resume = saved.historyPage?.takeIf { it.start == start }
        var page = resume?.nextPage
        var newest = resume?.newest ?: start
        while (true) {
            currentCoroutineContext().ensureActive()
            if (now() >= deadline) return false
            val result = try {
                pace(GmailCost.HISTORY)
                gateway.history(start, page)
            } catch (e: GmailException) {
                if (e.code != 400 || page == null) throw e
                // An expired page token: replaying from the cursor in order reaches the same state.
                page = null
                newest = start
                db.saveSyncState(account, db.syncState(account).copy(historyPage = null))
                continue
            }
            db.transaction { result.history.forEach { apply(account, it) } }
            result.historyId?.let { newest = it }
            page = result.nextPageToken
            val state = db.syncState(account)
            db.saveSyncState(account,
                if (page == null) state.copy(historyId = newest, historyPage = null, lastSync = now())
                else state.copy(historyPage = HistoryPage(start, page, newest)),
            )
            changed()
            // The cursor has moved past this page; its downloads wait in the queue.
            if (!drain(account, deadline)) return false
            if (page == null) return true
        }
    }

    /** One history record, applied in order. Local only: downloads go to the queue. */
    private fun apply(account: String, record: GHistoryRecord) {
        val typed = HashSet<String>()
        for (added in record.messagesAdded) {
            val m = added.message
            typed += m.id
            // Gmail drafts and spam arrive here too; their labels say not to bother.
            if (!db.hasMessage(account, m.id) && !OfflinePolicy.excluded(m.labelIds)) {
                db.enqueue(account, m.id, QueueKind.NEW)
            }
        }
        for (deleted in record.messagesDeleted) {
            typed += deleted.message.id
            if (db.hasMessage(account, deleted.message.id)) db.removeMessage(account, deleted.message.id)
            else db.dequeue(account, deleted.message.id)
        }
        for (change in record.labelsAdded) {
            typed += change.message.id
            val saved = db.applyLabelDelta(account, change.message.id, added = change.labelIds, removed = emptyList())
            // Queued earlier in this pass, then moved to Spam or Trash: don't download it.
            if (!saved && OfflinePolicy.excluded(change.labelIds)) db.dequeue(account, change.message.id)
        }
        for (change in record.labelsRemoved) {
            typed += change.message.id
            val saved = db.applyLabelDelta(account, change.message.id, added = emptyList(), removed = change.labelIds)
            // Back from Spam or Trash: download it if it belongs in the cache.
            if (!saved && OfflinePolicy.excluded(change.labelIds)) db.enqueue(account, change.message.id, QueueKind.NEW)
        }
        // A change type this client doesn't parse: ask Gmail for the message's current state.
        for (ref in record.messages) {
            if (ref.id in typed) continue
            db.enqueue(account, ref.id, if (db.hasMessage(account, ref.id)) QueueKind.VERIFY else QueueKind.NEW)
        }
    }

    /** Works through the download queue, newest first. */
    private suspend fun drain(account: String, deadline: Long): Boolean {
        while (true) {
            val batch = db.queued(account, 50)
            if (batch.isEmpty()) return true
            for ((id, kind, tries) in batch) {
                currentCoroutineContext().ensureActive()
                if (now() >= deadline) return false
                // Counted first, so an id that fails (or crashes the run) every time is dropped.
                db.noteQueueTry(account, id)
                when {
                    tries >= MailDatabase.MAX_QUEUE_TRIES -> Unit
                    kind == QueueKind.VERIFY -> refreshLabels(account, id)
                    // Saved by an earlier attempt; [completeDownloads] finishes its attachments.
                    db.hasMessage(account, id) -> Unit
                    else -> fetchNew(account, id, OfflinePolicy.cutoff(now()))
                }
                db.dequeue(account, id)
                changed()
            }
        }
    }

    // MARK: Downloads

    private suspend fun fetchNew(account: String, id: String, cutoff: Long, generation: String? = null) {
        val message = try {
            pace(GmailCost.FULL)
            gateway.message(id)
        } catch (e: GmailException) {
            if (e.code == 404) return // deleted before we got to it
            throw e
        }
        val retained = !OfflinePolicy.excluded(message.labelIds) && (
            (message.internalDate?.toLongOrNull() ?: 0) >= cutoff ||
                db.threadKept(account, message.threadId ?: message.id, cutoff, snoozedLabel)
            )
        if (!retained) return
        db.putMessage(account, message, generation ?: db.syncState(account).checkpoint?.generation)
        db.noteDownloadAttempt(account, id)
        tryDownloadParts(account, message)
    }

    private suspend fun refreshLabels(account: String, id: String, generation: String? = null) {
        val message = try {
            pace(GmailCost.MINIMAL)
            gateway.minimal(id)
        } catch (e: GmailException) {
            if (e.code != 404) throw e
            db.removeMessage(account, id) // deleted while the pass was running
            return
        }
        db.applyLabels(account, id, message.labelIds, generation)
    }

    /**
     * Saved messages still missing attachments or text parts, newest first, from one snapshot
     * per run. Each attempt is counted before it starts: a message that fails, or crashes the
     * run, sinks behind the others and stops being tried in the background after
     * [MailDatabase.MAX_DOWNLOAD_ATTEMPTS]; opening it still works. With low storage only
     * missing text is fetched, and files wait until there is room.
     */
    private suspend fun completeDownloads(account: String, deadline: Long): Boolean {
        val low = freeSpace() < LOW_STORAGE
        var failuresInARow = 0
        for (id in db.incomplete(account, 200, textOnly = low)) {
            currentCoroutineContext().ensureActive()
            if (now() >= deadline) return false
            if (!low && freeSpace() < LOW_STORAGE) return true // the next run asks for text only
            val message = db.message(account, id)
            if (message == null) {
                db.removeMessage(account, id) // row without its body file
                continue
            }
            db.noteDownloadAttempt(account, id)
            if (tryDownloadParts(account, message)) failuresInARow = 0
            else if (++failuresInARow >= 3) return true // offline or rate limited: the next run tries again
            changed()
        }
        return true
    }

    /** Attachment trouble leaves the message incomplete for a later run; it never stops the sync. */
    private suspend fun tryDownloadParts(account: String, message: GMessage): Boolean = try {
        downloadParts(account, message)
        true
    } catch (e: AccountChangedException) {
        throw e
    } catch (e: AuthExpiredException) {
        throw e
    } catch (e: IOException) {
        false
    }

    /**
     * Text delivered through the attachment endpoint first, so a message is readable even if
     * a large file fails. With low storage the files wait; the message stays incomplete.
     * Stops quietly if a sync removes the message meanwhile.
     */
    private suspend fun downloadParts(account: String, message: GMessage) {
        val id = message.id
        val text = mutableMapOf<String, ByteArray>()
        for (aid in OfflinePolicy.missingTextIds(message.payload)) {
            val bytes = db.attachment(account, id, aid) ?: fetchAttachment(id, aid)?.also {
                if (!db.putAttachment(account, id, aid, it)) return
            }
            if (bytes != null) text[aid] = bytes
        }
        if (text.isNotEmpty()) {
            val hydrated = message.copy(payload = OfflinePolicy.hydrateTextParts(message.payload, text))
            if (!db.updateBody(account, hydrated)) return
        }
        for (aid in OfflinePolicy.fileIds(message.payload)) {
            if (db.attachment(account, id, aid) != null) continue
            if (freeSpace() < LOW_STORAGE) return
            val bytes = fetchAttachment(id, aid) ?: continue
            if (!db.putAttachment(account, id, aid, bytes)) return
        }
        db.markComplete(account, id)
    }

    /** Null when Gmail says the attachment is gone or invalid: skip it, don't stall the rest. */
    private suspend fun fetchAttachment(id: String, aid: String): ByteArray? = try {
        pace(GmailCost.ATTACHMENT)
        gateway.attachment(id, aid)
    } catch (e: GmailException) {
        if (e.code == 400 || e.code == 404) null else throw e
    }

    // MARK: Retention

    /**
     * Daily, in small batches, oldest first: threads nobody kept leave the phone. A full
     * batch means more are waiting, so the next run continues instead of waiting a day.
     * Low storage trims attachments (text stays) until there is headroom again.
     */
    private fun evict(account: String) {
        val state = db.syncState(account)
        if (now() - state.lastEviction >= DAY) {
            val evicted = db.evict(account, OfflinePolicy.cutoff(now()), snoozedLabel, EVICTION_BATCH)
            if (evicted < EVICTION_BATCH) db.saveSyncState(account, db.syncState(account).copy(lastEviction = now()))
        }
        if (freeSpace() >= LOW_STORAGE) return
        db.trimAttachments(account, enough = { freeSpace() >= TRIM_TARGET }, max = TRIM_LIMIT)
    }

    companion object {
        private const val DAY = 24 * 60 * 60 * 1000L
        private const val EVICTION_BATCH = 100
        private const val TRIM_LIMIT = 500
        /** Tries at one full-sync message before it is skipped. */
        private const val MAX_TRIES = 5
        /** Below this, attachments stop downloading. */
        const val LOW_STORAGE = 500L * 1024 * 1024
        /** Trimming continues until this much is free, so downloads and trims don't alternate. */
        const val TRIM_TARGET = 750L * 1024 * 1024
    }
}
