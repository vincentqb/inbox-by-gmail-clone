package com.baestheorem.inbox.data

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.baestheorem.inbox.auth.AuthStore
import com.baestheorem.inbox.gmail.AuthExpiredException
import com.baestheorem.inbox.gmail.Bundles
import com.baestheorem.inbox.gmail.BundleGroup
import com.baestheorem.inbox.gmail.GMessageList
import com.baestheorem.inbox.gmail.GThread
import com.baestheorem.inbox.gmail.GmailClient
import com.baestheorem.inbox.gmail.GmailException
import com.baestheorem.inbox.gmail.InboxData
import com.baestheorem.inbox.gmail.MessageDetail
import com.baestheorem.inbox.gmail.ThreadDetail
import com.baestheorem.inbox.gmail.ThreadSummary
import com.baestheorem.inbox.gmail.UnsubInfo
import com.baestheorem.inbox.gmail.UnsubMethod
import com.baestheorem.inbox.gmail.UnsubResolver
import com.baestheorem.inbox.gmail.buildRawMessage
import com.baestheorem.inbox.gmail.collectAttachments
import com.baestheorem.inbox.gmail.decodeBody
import com.baestheorem.inbox.gmail.findBodyUnsubscribe
import com.baestheorem.inbox.gmail.gmailPermalink
import com.baestheorem.inbox.gmail.headerValue
import com.baestheorem.inbox.gmail.parseAddr
import com.baestheorem.inbox.gmail.parseUnsubscribe
import com.baestheorem.inbox.gmail.resolveInlineImages
import com.baestheorem.inbox.gmail.splitQuotedHtml
import com.baestheorem.inbox.gmail.summarize
import com.baestheorem.inbox.work.MailWork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

sealed class MailMode(val title: String) {
    data object Inbox : MailMode("Inbox")
    data object Snoozed : MailMode("Snoozed")
    data object Done : MailMode("Done")
    data object Sent : MailMode("Sent")
    data object Drafts : MailMode("Drafts")
    data object Outbox : MailMode("Outbox")
    data class BundleFilter(val name: String) : MailMode(name)
    data class Search(val q: String) : MailMode("Search")
}

data class Snack(val text: String, val hasUndo: Boolean)

class MailStore(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()

    val mode = MutableStateFlow<MailMode>(MailMode.Inbox)
    val inbox = MutableStateFlow(InboxData())
    val listItems = MutableStateFlow<List<ThreadSummary>>(emptyList())
    val loading = MutableStateFlow(false)
    val errorText = MutableStateFlow<String?>(null)
    val pinnedOnly = MutableStateFlow(false)
    val expandedBundles = MutableStateFlow<Set<String>>(emptySet())
    val snack = MutableStateFlow<Snack?>(null)
    val userEmail = MutableStateFlow("")
    val displayName = MutableStateFlow("")
    val deepLinkThreadId = MutableStateFlow<String?>(null)
    val authExpired: StateFlow<Boolean> = GmailClient.authExpired

    val pageSize = 50

    private var inboxRows: List<ThreadSummary> = emptyList()
    private var inboxNextToken: String? = null
    private var listNextToken: String? = null
    private var bundleOverrides: Map<String, String> = emptyMap()
    private var snackUndo: (suspend () -> Unit)? = null
    private var snackDismiss: Job? = null
    private var loadingMore = false
    private val refreshLock = Mutex()

    // Parsed threads by id, so back-and-reopen is instant and costs no quota. An
    // entry is trusted while the list row still names the same newest message
    // (or, with no row to check, for two minutes); history sync drops it.
    private data class CachedThread(val detail: ThreadDetail, val lastMid: String, val at: Long)

    private val threadCache = LinkedHashMap<String, CachedThread>()
    private val threadCacheCap = 30

    val nextPageToken: String?
        get() = if (!canAccessGmail()) null else when (mode.value) {
            is MailMode.Inbox, is MailMode.BundleFilter -> inboxNextToken
            is MailMode.Drafts, is MailMode.Outbox -> null
            else -> listNextToken
        }

    init {
        viewModelScope.launch {
            OfflineMail.revision.collectLatest {
                delay(500)
                if (userEmail.value.isNotBlank() && !canAccessGmail()) {
                    refreshLock.withLock { refreshCached() }
                }
            }
        }
        viewModelScope.launch {
            combine(Connectivity.online, authExpired) { online, expired -> online && !expired }
                .distinctUntilChanged().collect {
                    if (userEmail.value.isNotBlank()) refresh()
                }
        }
    }

    fun canAccessGmail(): Boolean = AuthStore.isSignedIn && Connectivity.online.value && !authExpired.value

    fun requireConnection(): Boolean {
        if (canAccessGmail()) return true
        showSnack(if (authExpired.value) "Sign in again to update Gmail" else "Connect to update Gmail")
        return false
    }

    // MARK: Lifecycle

    fun bootstrap() {
        viewModelScope.launch {
            val version = AuthStore.sessionVersion
            try {
                if (userEmail.value.isNotBlank() && userEmail.value != AuthStore.account) clearForSignOut()
                userEmail.value = AuthStore.account
                if (AuthStore.account.isNotBlank()) {
                    OfflineMail.activate(AuthStore.account)
                    displayName.value = withContext(Dispatchers.IO) { OfflineMail.db.displayName(AuthStore.account) }
                    refreshLock.withLock { refreshCached() }
                }
                if (!canAccessGmail()) return@launch
                val email = GmailClient.profileEmail()
                if (version != AuthStore.sessionVersion) return@launch
                AuthStore.account = email
                userEmail.value = email
                OfflineMail.activate(email)
                displayName.value = GmailClient.displayName()
                withContext(Dispatchers.IO) { OfflineMail.db.saveDisplayName(email, displayName.value) }
                MailWork.schedule(ctx)
            } catch (e: IOException) {
                Log.w("MailStore", "profile lookup failed", e)
            }
            refresh()
        }
    }

    fun clearForSignOut() {
        inboxRows = emptyList()
        inbox.value = InboxData()
        listItems.value = emptyList()
        threadCache.clear()
        userEmail.value = ""
        displayName.value = ""
        inboxNextToken = null
        listNextToken = null
        errorText.value = null
        mode.value = MailMode.Inbox
        deepLinkThreadId.value = null
    }

    fun switchMode(newMode: MailMode) {
        if (newMode == mode.value) return
        mode.value = newMode
        listItems.value = emptyList()
        listNextToken = null
        errorText.value = null
        viewModelScope.launch { refresh() }
    }

    /** A caller arriving mid-refresh joins the one in flight instead of racing it. */
    suspend fun refresh() {
        refreshLock.withLock {
            if (mode.value in setOf(MailMode.Drafts, MailMode.Outbox)) {
                loading.value = false
                errorText.value = null
                return@withLock
            }
            if (!canAccessGmail()) {
                refreshCached()
                return@withLock
            }
            when (mode.value) {
                is MailMode.Inbox, is MailMode.BundleFilter -> refreshInbox()
                else -> refreshList()
            }
        }
    }

    /** The offline views use the same summaries/bundles as the online inbox. */
    private suspend fun refreshCached(): Boolean {
        val account = AuthStore.account
        if (account.isBlank()) return false
        val selectedMode = mode.value
        val snapshot = withContext(Dispatchers.IO) {
            if (OfflineMail.db.owner() != MailDatabase.normalize(account)) return@withContext null
            val messages = OfflineMail.db.messages(
                account, OfflinePolicy.cutoff(System.currentTimeMillis()), withBodies = selectedMode is MailMode.Search,
            )
            messages to OfflineMail.db.labels(account)
        } ?: return false
        if (selectedMode != mode.value || account != AuthStore.account) return false
        val (all, labels) = snapshot
        bundleOverrides = labels.mapNotNull { l ->
            l.name?.takeIf { it.startsWith("Bundle/") }?.let { l.id to it.removePrefix("Bundle/") }
        }.toMap()
        val snoozedLabel = labels.firstOrNull { it.name == "Snoozed" }?.id
        val selected = when (selectedMode) {
            is MailMode.Inbox, is MailMode.BundleFilter -> all.filter { "INBOX" in it.labelIds }
            is MailMode.Sent -> all.filter { "SENT" in it.labelIds }
            is MailMode.Snoozed -> all.filter { snoozedLabel != null && snoozedLabel in it.labelIds }
            is MailMode.Done -> all.filter { "INBOX" !in it.labelIds && "SENT" !in it.labelIds }
            is MailMode.Search -> {
                val terms = selectedMode.q.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
                all.filter { m ->
                    val text = buildString {
                        append(m.snippet).append(' ')
                        m.payload?.headers?.forEach { append(it.value).append(' ') }
                        append(decodeBody(m.payload).text)
                    }.lowercase()
                    terms.all { it in text }
                }
            }
            else -> emptyList()
        }
        var rows = withContext(Dispatchers.Default) {
            summarize(selected, bundleOverrides, selectedMode is MailMode.Sent)
        }
        rows = applyBodyUnsub(rows, mutableListOf())
        inboxNextToken = null
        listNextToken = null
        if (selectedMode is MailMode.Inbox || selectedMode is MailMode.BundleFilter) {
            inboxRows = rows
            inbox.value = inbox.value.copy(
                inboxTotal = rows.size, inboxUnread = rows.count { it.unread },
            )
            partition()
        } else {
            listItems.value = if (selectedMode is MailMode.Snoozed) rows.map { it.copy(wake = SnoozeStore.wake(it.id)) }
            else rows
        }
        loading.value = false
        errorText.value = null
        return all.isNotEmpty()
    }

    fun refreshAsync() {
        viewModelScope.launch { refresh() }
    }

    // MARK: Inbox

    private suspend fun refreshInbox() {
        if (inboxRows.isEmpty()) loading.value = true
        try {
            applyHistory()
            val labels = GmailClient.listLabels()
            bundleOverrides = labels.mapNotNull { l ->
                val n = l.name ?: return@mapNotNull null
                if (n.startsWith("Bundle/")) l.id to n.removePrefix("Bundle/") else null
            }.toMap()

            val resp = GmailClient.listMessages(labelIds = listOf("INBOX"), maxResults = pageSize)
            val ids = resp.messages.map { it.id }
            var msgs = GmailClient.batchMeta(ids)
            val mainThreadIds = msgs.map { it.threadId ?: it.id }.toSet()

            // Pins are a shortlist, not a page: an old star must surface even when
            // newer mail buries it, so the starred inbox merges into page one.
            val starResp = GmailClient.listMessages(labelIds = listOf("INBOX", "STARRED"), maxResults = 50)
            val starIds = starResp.messages.map { it.id }.filter { it !in ids }
            if (starIds.isNotEmpty()) msgs = msgs + GmailClient.batchMeta(starIds)

            var rows = summarize(msgs, bundleOverrides, outgoing = false)
                .filter { it.pinned || it.id in mainThreadIds }
            val missing = mutableListOf<String>()
            rows = applyBodyUnsub(rows, missing)
            inboxRows = rows
            inboxNextToken = resp.nextPageToken
            scanBodies(missing)
            try {
                val info = GmailClient.labelInfo("INBOX")
                inbox.value = inbox.value.copy(
                    inboxTotal = info.threadsTotal,
                    inboxUnread = info.threadsUnread,
                )
            } catch (e: IOException) {
                Log.w("MailStore", "label counts unavailable", e)
            }
            partition()
            Prefs.noteSeenUnread(msgs.filter { "UNREAD" in it.labelIds }.map { it.id })
            errorText.value = null
        } catch (e: IOException) {
            if (!refreshCached()) errorText.value = friendly(e)
        } finally {
            loading.value = false
        }
    }

    fun loadMore() {
        if (loadingMore || !canAccessGmail()) return
        loadingMore = true
        viewModelScope.launch {
            try {
                when (val m = mode.value) {
                    is MailMode.Inbox, is MailMode.BundleFilter -> {
                        val token = inboxNextToken ?: return@launch
                        val resp = GmailClient.listMessages(
                            labelIds = listOf("INBOX"), maxResults = pageSize, pageToken = token,
                        )
                        val msgs = GmailClient.batchMeta(resp.messages.map { it.id })
                        val have = inboxRows.map { it.id }.toSet()
                        val missing = mutableListOf<String>()
                        val rows = applyBodyUnsub(
                            summarize(msgs, bundleOverrides, outgoing = false).filter { it.id !in have },
                            missing,
                        )
                        inboxRows = inboxRows + rows
                        inboxNextToken = resp.nextPageToken
                        partition()
                        scanBodies(missing)
                    }
                    else -> {
                        val token = listNextToken ?: return@launch
                        val (resp, outgoing) = fetchListPage(token)
                        val msgs = GmailClient.batchMeta(resp.messages.map { it.id })
                        val have = listItems.value.map { it.id }.toSet()
                        var rows = summarize(msgs, bundleOverrides, outgoing)
                            .filter { it.id !in have }
                        if (m is MailMode.Snoozed) {
                            rows = rows.map { it.copy(wake = SnoozeStore.wake(it.id)) }
                        }
                        val missing = mutableListOf<String>()
                        if (!outgoing) rows = applyBodyUnsub(rows, missing)
                        listItems.value = listItems.value + rows
                        listNextToken = resp.nextPageToken
                        scanBodies(missing)
                    }
                }
            } catch (e: IOException) {
                Log.w("MailStore", "load more failed", e)
            } finally {
                loadingMore = false
            }
        }
    }

    private fun partition() {
        val pinned = inboxRows.filter { it.pinned }.sortedByDescending { it.ts }
        // Smart-sort: float Gmail-flagged IMPORTANT + unread mail to the top,
        // preserving recency order within each group.
        val primary = inboxRows.filter { !it.pinned && it.bundle == null }
            .sortedWith(
                compareBy<ThreadSummary> { if (it.important && it.unread) 0 else 1 }
                    .thenByDescending { it.ts }
            )
        val groups = HashMap<String, MutableList<ThreadSummary>>()
        for (r in inboxRows) {
            if (r.pinned) continue
            r.bundle?.let { groups.getOrPut(it) { mutableListOf() }.add(r) }
        }
        val bundles = Bundles.order.mapNotNull { name ->
            val items = groups[name]?.sortedByDescending { it.ts } ?: return@mapNotNull null
            if (items.isEmpty()) return@mapNotNull null
            val meta = Bundles.meta[name]
            BundleGroup(name, meta?.icon ?: "inbox", meta?.color ?: 0xFF5F6368, items)
        }
        inbox.value = inbox.value.copy(pinned = pinned, primary = primary, bundles = bundles)
    }

    // MARK: Other views

    private suspend fun fetchListPage(pageToken: String?): Pair<GMessageList, Boolean> =
        when (val m = mode.value) {
            is MailMode.Snoozed -> {
                val sid = GmailClient.ensureLabel("Snoozed")
                GmailClient.listMessages(listOf(sid), maxResults = pageSize, pageToken = pageToken) to false
            }
            is MailMode.Done -> GmailClient.listMessages(
                q = "-in:inbox -in:sent -in:draft -in:trash -in:spam -in:chats",
                maxResults = pageSize, pageToken = pageToken,
            ) to false
            is MailMode.Sent -> GmailClient.listMessages(
                listOf("SENT"), maxResults = pageSize, pageToken = pageToken,
            ) to true
            is MailMode.Search -> GmailClient.listMessages(
                q = m.q, maxResults = pageSize, pageToken = pageToken,
            ) to false
            else -> GMessageList() to false
        }

    private suspend fun refreshList() {
        if (listItems.value.isEmpty()) loading.value = true
        try {
            applyHistory()
            val (resp, outgoing) = fetchListPage(null)
            val msgs = GmailClient.batchMeta(resp.messages.map { it.id })
            var rows = summarize(msgs, bundleOverrides, outgoing)
            if (mode.value is MailMode.Snoozed) {
                rows = rows.map { it.copy(wake = SnoozeStore.wake(it.id)) }
            }
            val missing = mutableListOf<String>()
            if (!outgoing) rows = applyBodyUnsub(rows, missing)
            listItems.value = rows
            listNextToken = resp.nextPageToken
            scanBodies(missing)
            errorText.value = null
        } catch (e: IOException) {
            if (!refreshCached()) errorText.value = friendly(e)
        } finally {
            loading.value = false
        }
    }

    // MARK: Body-buried unsubscribe (port of apply_body_unsub + the scan worker)

    /** Fill rows from the scan cache; collect message ids that still need a scan. */
    private fun applyBodyUnsub(
        rows: List<ThreadSummary>,
        missing: MutableList<String>,
    ): List<ThreadSummary> = rows.map { row ->
        if (row.unsub != null || row.messageId.isEmpty()) return@map row
        val cached = UnsubScanCache.lookup(row.messageId)
        when {
            cached == null -> {
                missing.add(row.messageId)
                row
            }
            cached.isNotEmpty() -> row.copy(
                unsub = UnsubInfo(UnsubMethod.LINK, cached, null, "unsubscribe", fromBody = true)
            )
            else -> row
        }
    }

    /** One-time background body scan per message; hits update loaded rows in place. */
    private fun scanBodies(mids: List<String>) {
        val fresh = UnsubScanCache.claimForScan(mids)
        if (fresh.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            var found = false
            for (mid in fresh) {
                val html = GmailClient.messageBodyHtml(mid)
                if (html == null) {
                    UnsubScanCache.release(mid)
                    continue
                }
                val info = findBodyUnsubscribe(html)
                UnsubScanCache.put(mid, info?.httpsUrl ?: "")
                if (info != null) found = true
                delay(250) // gentle on the quota
            }
            if (found) withContext(Dispatchers.Main) { applyScansToLoadedRows() }
        }
    }

    private fun applyScansToLoadedRows() {
        fun fill(rows: List<ThreadSummary>): Pair<List<ThreadSummary>, Boolean> {
            var changed = false
            val out = rows.map { r ->
                if (r.unsub != null) return@map r
                val url = UnsubScanCache.lookup(r.messageId)
                if (url.isNullOrEmpty()) r else {
                    changed = true
                    r.copy(unsub = UnsubInfo(UnsubMethod.LINK, url, null, "unsubscribe", fromBody = true))
                }
            }
            return out to changed
        }
        val (newInbox, inboxChanged) = fill(inboxRows)
        inboxRows = newInbox
        val (newList, _) = fill(listItems.value)
        listItems.value = newList
        if (inboxChanged) partition()
    }

    /**
     * Ask Gmail what moved since last time; the client evicts its metadata for
     * those messages, and we drop the matching reader caches.
     */
    private suspend fun applyHistory() {
        val changed = GmailClient.syncHistory()
        if (changed.all) threadCache.clear()
        else for (tid in changed.threads) threadCache.remove(tid)
    }

    fun friendly(error: Throwable): String = when (error) {
        is AuthExpiredException -> error.message ?: "Signed out"
        is GmailException -> "Gmail ${error.code}: ${error.bodyText.take(160)}"
        else -> error.message ?: "Something went wrong"
    }

    /** Snackbar-sized: the status code is what tells a 429 from a dead network. */
    fun shortError(error: Throwable): String = when (error) {
        is GmailException -> "Gmail ${error.code}"
        is IOException -> "no connection"
        else -> (error.message ?: "error").take(40)
    }

    // MARK: Local mutation helpers

    private fun removeEverywhere(threadId: String) {
        inboxRows = inboxRows.filterNot { it.id == threadId }
        listItems.value = listItems.value.filterNot { it.id == threadId }
        threadCache.remove(threadId)
        partition()
    }

    private fun mutateEverywhere(threadId: String, change: (ThreadSummary) -> ThreadSummary) {
        inboxRows = inboxRows.map { if (it.id == threadId) change(it) else it }
        listItems.value = listItems.value.map { if (it.id == threadId) change(it) else it }
        partition()
    }

    // MARK: Snackbar

    fun showSnack(text: String, undo: (suspend () -> Unit)? = null) {
        snack.value = Snack(text, undo != null)
        snackUndo = undo
        snackDismiss?.cancel()
        snackDismiss = viewModelScope.launch {
            delay(5000)
            dismissSnack()
        }
    }

    fun snackUndoTapped() {
        val undo = snackUndo
        dismissSnack()
        if (undo != null) viewModelScope.launch { undo() }
    }

    fun dismissSnack() {
        snack.value = null
        snackUndo = null
    }

    // MARK: Actions (optimistic, with undo)

    fun markDone(t: ThreadSummary) {
        if (!requireConnection()) return
        removeEverywhere(t.id)
        viewModelScope.launch {
            try {
                GmailClient.modifyThread(t.id, remove = listOf("INBOX"))
            } catch (e: IOException) {
                showSnack("Couldn't archive")
                refresh()
            }
        }
        showSnack("Done") {
            runCatching { GmailClient.modifyThread(t.id, add = listOf("INBOX")) }
            refresh()
        }
    }

    /** The Done view's "move back to inbox". */
    fun restoreToInbox(t: ThreadSummary) {
        if (!requireConnection()) return
        removeEverywhere(t.id)
        viewModelScope.launch {
            try {
                GmailClient.modifyThread(t.id, add = listOf("INBOX"))
            } catch (e: IOException) {
                showSnack("Couldn't move")
                refresh()
            }
        }
        showSnack("Moved to Inbox")
    }

    fun togglePin(t: ThreadSummary) {
        if (!requireConnection()) return
        val newVal = !t.pinned
        mutateEverywhere(t.id) { it.copy(pinned = newVal) }
        viewModelScope.launch {
            try {
                GmailClient.modifyThread(
                    t.id,
                    add = if (newVal) listOf("STARRED") else emptyList(),
                    remove = if (newVal) emptyList() else listOf("STARRED"),
                )
            } catch (e: IOException) {
                showSnack(if (newVal) "Couldn't pin" else "Couldn't unpin")
                refresh()
            }
        }
    }

    fun snoozeThread(t: ThreadSummary, wake: Long, desc: String) {
        if (!requireConnection()) return
        removeEverywhere(t.id)
        viewModelScope.launch {
            try {
                val sid = GmailClient.ensureLabel("Snoozed")
                GmailClient.modifyThread(t.id, add = listOf(sid), remove = listOf("INBOX"))
                SnoozeStore.add(t.id, wake, t.subject)
            } catch (e: IOException) {
                showSnack("Couldn't snooze")
                refresh()
            }
        }
        showSnack("Snoozed until $desc") {
            val sid = runCatching { GmailClient.ensureLabel("Snoozed") }.getOrNull()
            runCatching {
                GmailClient.modifyThread(
                    t.id, add = listOf("INBOX"),
                    remove = sid?.let { listOf(it) } ?: emptyList(),
                )
            }
            SnoozeStore.remove(t.id)
            refresh()
        }
    }

    fun unsnooze(t: ThreadSummary) {
        if (!requireConnection()) return
        removeEverywhere(t.id)
        viewModelScope.launch {
            try {
                val sid = GmailClient.ensureLabel("Snoozed")
                GmailClient.modifyThread(t.id, add = listOf("INBOX"), remove = listOf(sid))
                SnoozeStore.remove(t.id)
            } catch (e: IOException) {
                showSnack("Couldn't unsnooze")
                refresh()
            }
        }
        showSnack("Moved to Inbox")
    }

    fun spam(t: ThreadSummary) {
        if (!requireConnection()) return
        removeEverywhere(t.id)
        viewModelScope.launch {
            try {
                GmailClient.modifyThread(t.id, add = listOf("SPAM"), remove = listOf("INBOX", "UNREAD"))
            } catch (e: IOException) {
                showSnack("Couldn't report spam")
                refresh()
            }
        }
        // Undo restores INBOX and clears SPAM so Gmail stops re-filing the thread
        showSnack("Reported spam") {
            runCatching {
                GmailClient.modifyThread(t.id, add = listOf("INBOX"), remove = listOf("SPAM"))
            }
            refresh()
        }
    }

    fun toggleUnread(t: ThreadSummary) {
        if (!requireConnection()) return
        val newUnread = !t.unread
        mutateEverywhere(t.id) { it.copy(unread = newUnread) }
        viewModelScope.launch {
            runCatching {
                GmailClient.modifyThread(
                    t.id,
                    add = if (newUnread) listOf("UNREAD") else emptyList(),
                    remove = if (newUnread) emptyList() else listOf("UNREAD"),
                )
            }
        }
    }

    fun markReadLocally(threadId: String) {
        mutateEverywhere(threadId) { it.copy(unread = false) }
        if (canAccessGmail()) {
            viewModelScope.launch { runCatching { GmailClient.modifyThread(threadId, remove = listOf("UNREAD")) } }
        }
    }

    fun sweep(b: BundleGroup) {
        if (!requireConnection()) return
        val items = b.items
        for (r in items) removeEverywhere(r.id)
        viewModelScope.launch {
            for (r in items) runCatching { GmailClient.modifyThread(r.id, remove = listOf("INBOX")) }
        }
        showSnack("${items.size} swept") {
            for (r in items) runCatching { GmailClient.modifyThread(r.id, add = listOf("INBOX")) }
            refresh()
        }
    }

    /**
     * Where the Mac and the iPhone fire a things:/// deep link, Android shares:
     * any task app, notes app or chat takes the subject plus the same
     * inboxclone:// backlink and Gmail fallback the other clients write.
     */
    fun share(t: ThreadSummary) {
        val backlink = "inboxclone://thread/${t.id}"
        val text = buildString {
            append(t.subject).append("\n\n")
            append(backlink).append("\n\n")
            append("Gmail: ").append(t.permalink).append("\n\n")
            append("From: ").append(t.sender).append("\n")
            append(t.snippet)
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, t.subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, "Send to").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(chooser)
    }

    fun openUrl(url: String) {
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: android.content.ActivityNotFoundException) {
            showSnack("No app can open that link")
        }
    }

    // MARK: Reader

    suspend fun loadThread(threadId: String): ThreadDetail {
        val account = AuthStore.account
        val cached = withContext(Dispatchers.IO) {
            if (account.isBlank() || OfflineMail.db.owner() != MailDatabase.normalize(account)) null
            else OfflineMail.db.thread(account, threadId, OfflinePolicy.cutoff(System.currentTimeMillis()))
        }
        if (!canAccessGmail()) {
            val thread = cached ?: throw IOException("This email has not downloaded yet. Connect to download it.")
            return withContext(Dispatchers.Default) { parseThread(thread, threadId).detail }
        }
        val row = inboxRows.firstOrNull { it.id == threadId }
            ?: listItems.value.firstOrNull { it.id == threadId }
        threadCache[threadId]?.let { c ->
            val fresh = row?.let { it.messageId == c.lastMid }
                ?: (System.currentTimeMillis() - c.at < 120_000)
            if (fresh) {
                if (row?.unread == true) markReadLocally(threadId)
                return c.detail
            }
            threadCache.remove(threadId)
        }
        val th = try {
            GmailClient.getThread(threadId)
        } catch (e: IOException) {
            cached ?: throw e
        }
        if (account != AuthStore.account) throw AccountChangedException()
        // Store text immediately; the resumable worker finishes any attachments.
        if (account.isNotBlank()) OfflineMail.cacheOpenedThread(account, th)
        val readable = withContext(Dispatchers.IO) {
            th.copy(messages = th.messages.map { message ->
                val full = if (message.payload?.body?.data == null &&
                    OfflinePolicy.attachmentIds(message.payload).isNotEmpty()) {
                    val bytes = mutableMapOf<String, ByteArray>()
                    fun textParts(part: com.baestheorem.inbox.gmail.GPayload?) {
                        if (part == null) return
                        if (part.mimeType in setOf("text/plain", "text/html") && part.filename.isNullOrEmpty()) {
                            part.body?.attachmentId?.let { bytes[it] = byteArrayOf() }
                        }
                        part.parts.forEach(::textParts)
                    }
                    textParts(message.payload)
                    for (aid in bytes.keys.toList()) bytes[aid] = GmailClient.attachmentData(message.id, aid)
                    message.copy(payload = OfflinePolicy.hydrateTextParts(message.payload, bytes))
                } else message
                if (full != message && account.isNotBlank() &&
                    OfflinePolicy.includes(full, OfflinePolicy.cutoff(System.currentTimeMillis()))) {
                    OfflineMail.db.putMessage(account, full)
                    if (OfflinePolicy.attachmentIds(full.payload).all { OfflineMail.db.attachment(account, full.id, it) != null }) {
                        OfflineMail.db.markComplete(account, full.id)
                    }
                }
                full
            })
        }
        // Base64 body decode plus the quote-split and unsubscribe regexes over a
        // big newsletter is real CPU; parse off the main thread.
        val parsed = withContext(Dispatchers.Default) { parseThread(readable, threadId) }
        parsed.bodyUnsubHit?.let { (mid, url) -> UnsubScanCache.put(mid, url) }
        if (row?.unread != false) markReadLocally(threadId)
        val lastMid = parsed.detail.messages.lastOrNull()?.id ?: ""
        threadCache[threadId] = CachedThread(parsed.detail, lastMid, System.currentTimeMillis())
        while (threadCache.size > threadCacheCap) {
            val oldest = threadCache.keys.firstOrNull() ?: break
            threadCache.remove(oldest)
        }
        return parsed.detail
    }

    private data class ParsedThread(val detail: ThreadDetail, val bodyUnsubHit: Pair<String, String>?)

    private fun parseThread(th: GThread, threadId: String): ParsedThread {
        val messages = mutableListOf<MessageDetail>()
        var lastRfc = ""
        val allRfc = mutableListOf<String>()
        var subject = ""
        var unsub: UnsubInfo? = null // the most recent message's List-Unsubscribe wins
        var bodyHit: Pair<String, String>? = null
        val real = th.messages.filter { "DRAFT" !in it.labelIds }
        for (m in real) {
            val headers = m.payload?.headers ?: emptyList()
            val a = parseAddr(headerValue(headers, "From"))
            if (subject.isEmpty()) subject = headerValue(headers, "Subject")
            val body = decodeBody(m.payload)
            val resolved = resolveInlineImages(body.html, m.id, m.payload)
            val split = splitQuotedHtml(resolved)
            val rfc = headerValue(headers, "Message-ID")
            if (rfc.isNotEmpty()) {
                lastRfc = rfc
                allRfc.add(rfc)
            }
            parseUnsubscribe(headers)?.let { unsub = it }
            val ts = m.internalDate?.toLongOrNull() ?: 0L
            messages.add(
                MessageDetail(
                    id = m.id,
                    sender = a.name,
                    senderEmail = a.email,
                    to = headerValue(headers, "To"),
                    cc = headerValue(headers, "Cc"),
                    bcc = headerValue(headers, "Bcc"),
                    date = com.baestheorem.inbox.gmail.formatTime(ts),
                    details = com.baestheorem.inbox.gmail.messageDetails(headers, ts),
                    text = body.text,
                    html = split.visible,
                    quotedHtml = split.quoted,
                    attachments = collectAttachments(m.payload, m.id, hideInlineImages = false),
                    unread = "UNREAD" in m.labelIds,
                )
            )
        }
        // No header? Fall back to an opt-out link buried in the body, newest first
        if (unsub == null) {
            for (m in messages.reversed()) {
                val u = findBodyUnsubscribe(m.html + (m.quotedHtml ?: "")) ?: continue
                unsub = u
                bodyHit = m.id to (u.httpsUrl ?: "")
                break
            }
        }
        val detail = ThreadDetail(
            id = threadId,
            subject = subject.ifEmpty { "(no subject)" },
            messages = messages,
            rfcMessageId = lastRfc,
            allRfcIds = allRfc,
            permalink = gmailPermalink(threadId),
            pinned = real.lastOrNull()?.labelIds?.contains("STARRED") == true,
            unsub = unsub,
        )
        return ParsedThread(detail, bodyHit)
    }

    /**
     * Never address a reply to yourself: pick the newest message from the other
     * side; reply-all folds in everyone else on that message minus you.
     */
    fun replyRecipients(d: ThreadDetail, all: Boolean): Pair<String, String> {
        val me = userEmail.value.lowercase()
        val last = d.messages.lastOrNull { it.senderEmail.lowercase() != me }
            ?: d.messages.lastOrNull()
            ?: return "" to ""
        val to = last.senderEmail
        if (!all) return to to ""
        val ccList = mutableListOf<String>()
        for (raw in (last.to + "," + last.cc).split(",")) {
            val addr = parseAddr(raw).email
            val low = addr.lowercase()
            if (addr.isNotEmpty() && low != me && low != to.lowercase() &&
                ccList.none { it.lowercase() == low }
            ) ccList.add(addr)
        }
        return to to ccList.joinToString(", ")
    }

    fun replyAllAvailable(d: ThreadDetail): Boolean = replyRecipients(d, true).second.isNotEmpty()

    suspend fun newDraft(): LocalDraft {
        val draft = LocalDraft(account = AuthStore.account, fromName = displayName.value)
        return OfflineMail.save(draft).await()
    }

    fun replyTemplate(detail: ThreadDetail, replyAll: Boolean): LocalDraft {
        val (to, cc) = replyRecipients(detail, replyAll)
        return LocalDraft(
            account = AuthStore.account, to = to, cc = cc,
            subject = if (detail.subject.startsWith("re:", true)) detail.subject else "Re: ${detail.subject}",
            fromName = displayName.value, threadId = detail.id,
            inReplyTo = detail.rfcMessageId, references = detail.allRfcIds.joinToString(" "),
        )
    }

    suspend fun loadReplyDraft(detail: ThreadDetail): LocalDraft =
        OfflineMail.replyDraft(detail.id).await() ?: replyTemplate(detail, false)

    // MARK: Unsubscribe (RFC 2369 / RFC 8058, port of api_unsubscribe)

    // Walks the whole opt-out ladder before it will hand anything to the browser:
    //   1. RFC 8058 one-click, when the sender advertises it
    //   2. the sender's unsubscribe page, driven to completion by UnsubResolver
    //   3. a one-click POST the sender never advertised (plenty of ESPs honor it anyway)
    //   4. the page again in a real browser engine, for opt-outs that only exist in JS
    //   5. the List-Unsubscribe mailto
    //   6. the browser, and only once every one of those has failed
    fun unsubscribe(info: UnsubInfo, sender: String) {
        if (!requireConnection()) return
        viewModelScope.launch {
            showSnack("Unsubscribing from $sender…")
            val url = info.httpsUrl

            // 1. A 2xx here is RFC 8058's own success signal.
            if (info.method == UnsubMethod.ONE_CLICK && url != null) {
                val ok = withContext(Dispatchers.IO) { UnsubResolver.oneClickPost(url).first }
                if (ok) {
                    showSnack("Unsubscribed from $sender")
                    return@launch
                }
            }

            // 2. Redirects, the confirm form, the "which address?" box, the reason
            //    dropdown, the second confirm screen: all of it, in-app.
            if (url != null) {
                val email = userEmail.value.ifEmpty { null }
                val res = withContext(Dispatchers.IO) { UnsubResolver.resolve(url, email) }
                if (res.ok) {
                    showSnack(
                        if (res.confirmed) "Unsubscribed from $sender"
                        else "Unsubscribe submitted to $sender"
                    )
                    return@launch
                }
            }

            // 3. Unadvertised one-click. A bare 2xx proves nothing (the endpoint may have
            //    just re-served the confirm page), so only the sender's own wording counts.
            //    Skipped for body-scraped links: findBodyUnsubscribe guesses at which link
            //    is the opt-out, and a guess does not earn a POST.
            if (info.method != UnsubMethod.ONE_CLICK && !info.fromBody && url != null) {
                val done = withContext(Dispatchers.IO) {
                    val (ok, _, body) = UnsubResolver.oneClickPost(url)
                    ok && UnsubResolver.saysDone(body)
                }
                if (done) {
                    showSnack("Unsubscribed from $sender")
                    return@launch
                }
            }

            // 4. Nothing in the raw HTML, so the opt-out may only exist once the page's
            //    scripts have run. Drive a real browser engine at it, offscreen.
            if (url != null) {
                val res = UnsubWebDriver.drive(ctx, url, userEmail.value.ifEmpty { null })
                if (res.ok) {
                    showSnack(
                        if (res.confirmed) "Unsubscribed from $sender"
                        else "Unsubscribe submitted to $sender"
                    )
                    return@launch
                }
            }

            // 5. Mail the list owner. Slower than the web route, but no browser either.
            val to = info.mailto
            if (!to.isNullOrEmpty()) {
                val raw = buildRawMessage(
                    to = to, cc = null, subject = info.mailtoSubject,
                    body = "Please unsubscribe this address from your mailing list.",
                    fromName = displayName.value, fromEmail = userEmail.value,
                    inReplyTo = null, references = null,
                )
                try {
                    GmailClient.send(raw, null)
                    showSnack("Unsubscribe email sent to $sender")
                    return@launch
                } catch (e: IOException) {
                    // fall through to the page
                }
            }

            // 6. Out of automatic options, so hand over the page.
            if (url != null) {
                openUrl(url)
                showSnack("Their page needs a human, so I opened it")
            } else {
                showSnack("Couldn't unsubscribe from $sender")
            }
        }
    }
}
