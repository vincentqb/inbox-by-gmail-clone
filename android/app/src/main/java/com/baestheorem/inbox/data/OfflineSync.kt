package com.baestheorem.inbox.data

import com.baestheorem.inbox.gmail.GHistoryList
import com.baestheorem.inbox.gmail.GLabel
import com.baestheorem.inbox.gmail.GMessage
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
    suspend fun metadata(id: String): GMessage
    suspend fun message(id: String): GMessage
    suspend fun attachment(id: String, attachmentId: String): ByteArray
    suspend fun history(start: String, page: String?): GHistoryList
}

/**
 * Resumable inventory plus a separate durable Gmail history cursor. Checkpoints only
 * advance after each message (including attachments) has been saved successfully.
 * Partial work never empties a previously downloaded mailbox.
 */
class OfflineSync(
    private val db: MailDatabase,
    private val gateway: MailSyncGateway,
    private val now: () -> Long = System::currentTimeMillis,
    private val changed: suspend () -> Unit = {},
) {
    /** False means the WorkManager time budget ended; the next run resumes this pass. */
    suspend fun run(account: String, deadline: Long = Long.MAX_VALUE): Boolean {
        val profile = gateway.profile()
        if (MailDatabase.normalize(profile.emailAddress.orEmpty()) != MailDatabase.normalize(account)) {
            throw AccountChangedException()
        }
        db.prune(account, OfflinePolicy.cutoff(now()))
        db.saveLabels(account, gateway.labels())
        var state = db.syncState(account)
        val fullDue = state.historyId == null || now() - state.lastFullSync >= 24 * 60 * 60 * 1000L
        if (state.checkpoint == null && fullDue) {
            state = state.copy(checkpoint = FullSyncCheckpoint(OfflinePolicy.cutoff(now()), profile.historyId))
            db.saveSyncState(account, state)
        }
        if (state.checkpoint != null) {
            if (!inventory(account, deadline)) return false
        }
        state = db.syncState(account)
        val start = state.historyId
        if (start != null) {
            try {
                if (!incremental(account, start, deadline)) return false
            } catch (e: GmailException) {
                if (e.code != 404) throw e
                // Gmail only retains history for a limited time. Keep the cache and rebuild
                // the inventory; capture the cursor BEFORE scanning, so changes aren't lost.
                db.saveSyncState(account, state.copy(
                    historyId = null,
                    checkpoint = FullSyncCheckpoint(OfflinePolicy.cutoff(now()), gateway.profile().historyId),
                ))
                return inventory(account, deadline)
            }
        }
        db.prune(account, OfflinePolicy.cutoff(now()))
        state = db.syncState(account)
        db.saveSyncState(account, state.copy(lastSync = now()))
        changed()
        return true
    }

    private suspend fun inventory(account: String, deadline: Long): Boolean {
        var state = db.syncState(account)
        var checkpoint = state.checkpoint ?: return true
        while (true) {
            currentCoroutineContext().ensureActive()
            if (now() >= deadline) return false
            if (!checkpoint.pageLoaded) {
                val page = try {
                    gateway.list(OfflinePolicy.query(checkpoint.cutoff), checkpoint.nextPage)
                } catch (e: GmailException) {
                    if (e.code != 400 || checkpoint.nextPage == null) throw e
                    // A saved page token can expire between runs.
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
                download(account, checkpoint.remaining.first(), checkpoint.cutoff, checkpoint.generation)
                checkpoint = checkpoint.copy(remaining = checkpoint.remaining.drop(1))
                db.saveSyncState(account, state.copy(checkpoint = checkpoint))
                changed()
            }
            if (checkpoint.nextPage == null) {
                db.prune(account, OfflinePolicy.cutoff(now()), checkpoint.generation)
                state = state.copy(
                    checkpoint = null, historyId = checkpoint.startHistoryId, lastFullSync = now(), lastSync = now(),
                )
                db.saveSyncState(account, state)
                changed()
                return true
            }
            checkpoint = checkpoint.copy(pageLoaded = false)
            db.saveSyncState(account, state.copy(checkpoint = checkpoint))
        }
    }

    private suspend fun incremental(account: String, start: String, deadline: Long): Boolean {
        var page: String? = null
        var newest = start
        do {
            if (now() >= deadline) return false
            val result = gateway.history(start, page)
            for (id in result.history.flatMap { it.messages }.map { it.id }.distinct()) {
                currentCoroutineContext().ensureActive()
                if (now() >= deadline) return false
                download(account, id, OfflinePolicy.cutoff(now()))
            }
            result.historyId?.let { newest = it }
            page = result.nextPageToken
            changed()
        } while (page != null)
        // Replay a partial page after a failure, rather than skipping its failed message.
        val state = db.syncState(account)
        db.saveSyncState(account, state.copy(historyId = newest, lastSync = now()))
        return true
    }

    private suspend fun download(account: String, id: String, cutoff: Long, generation: String? = null) {
        try {
            var message = if (db.hasMessage(account, id)) gateway.metadata(id) else gateway.message(id)
            if (!OfflinePolicy.includes(message, cutoff)) {
                db.removeMessage(account, id)
                return
            }
            if (db.hasMessage(account, id)) {
                db.updateMetadata(account, message, generation)
                message = db.message(account, id) ?: gateway.message(id)
            } else {
                db.putMessage(account, message, generation)
            }
            val attachments = mutableMapOf<String, ByteArray>()
            for (aid in OfflinePolicy.attachmentIds(message.payload)) {
                val bytes = db.attachment(account, id, aid) ?: gateway.attachment(id, aid).also {
                    db.putAttachment(account, id, aid, it)
                }
                // Only text needs to be kept in memory for MIME hydration. Images/files
                // remain on disk, and the WebView/attachment viewer reads them on demand.
                if (needsText(message.payload, aid)) attachments[aid] = bytes
            }
            val hydrated = message.copy(payload = OfflinePolicy.hydrateTextParts(message.payload, attachments))
            db.putMessage(account, hydrated, generation ?: db.syncState(account).checkpoint?.generation)
            db.markComplete(account, id)
        } catch (e: GmailException) {
            if (e.code != 404) throw e
            db.removeMessage(account, id) // deleted while the pass was running
        }
    }

    private fun needsText(part: com.baestheorem.inbox.gmail.GPayload?, aid: String): Boolean =
        part != null && (
            part.body?.attachmentId == aid && part.mimeType in setOf("text/plain", "text/html") ||
                part.parts.any { needsText(it, aid) }
            )
}
