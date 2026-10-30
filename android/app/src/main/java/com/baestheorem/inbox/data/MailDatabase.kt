package com.baestheorem.inbox.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.AtomicFile
import com.baestheorem.inbox.gmail.GLabel
import com.baestheorem.inbox.gmail.GMessage
import com.baestheorem.inbox.gmail.GPayload
import com.baestheorem.inbox.gmail.GThread
import com.baestheorem.inbox.gmail.Net
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/**
 * Account-owned index and durable outbox. Bodies and attachments live in atomic private
 * files so a large message cannot exceed SQLite's CursorWindow limit.
 *
 * All entry points serialize account changes with writes. A worker for the old account
 * cannot add a file or queue a send after a switch, even if its HTTP request finishes late.
 * Call on Dispatchers.IO; no database or file work belongs on the Compose thread.
 */
class MailDatabase(context: Context, name: String = "offline-mail.db") :
    SQLiteOpenHelper(context, name, null, 1) {
    private val root = File(context.filesDir, "offline-mail")

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE state (name TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE messages (id TEXT PRIMARY KEY, thread_id TEXT NOT NULL, " +
                "ts INTEGER NOT NULL, metadata TEXT NOT NULL, complete INTEGER NOT NULL DEFAULT 0, " +
                "generation TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL("CREATE INDEX messages_thread ON messages(thread_id)")
        db.execSQL("CREATE INDEX messages_date ON messages(ts)")
        db.execSQL(
            "CREATE TABLE drafts (id TEXT PRIMARY KEY, status TEXT NOT NULL, updated INTEGER NOT NULL, " +
                "thread_id TEXT, data TEXT NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun owner(): String = stateValue("owner") ?: ""

    /** Returns true only when previously stored mail belonged to a different account. */
    @Synchronized
    fun activate(account: String): Boolean {
        val normalized = normalize(account)
        require(normalized.isNotEmpty()) { "An account must be verified before storing mail." }
        val previous = owner()
        if (previous == normalized) return false
        clear()
        putState("owner", normalized)
        return previous.isNotEmpty()
    }

    @Synchronized
    fun clear() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("messages", null, null)
            db.delete("drafts", null, null)
            db.delete("state", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (root.exists() && !root.deleteRecursively()) throw IOException("Could not clear downloaded mail.")
    }

    private fun checkOwner(account: String) {
        if (normalize(account).isEmpty() || owner() != normalize(account)) throw AccountChangedException()
    }

    private fun stateValue(key: String): String? = readableDatabase.query(
        "state", arrayOf("value"), "name=?", arrayOf(key), null, null, null,
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun putState(key: String, value: String) {
        writableDatabase.insertWithOnConflict(
            "state", null, ContentValues().apply { put("name", key); put("value", value) },
            SQLiteDatabase.CONFLICT_REPLACE,
        ).also { if (it == -1L) throw IOException("Could not save local mail state.") }
    }

    @Synchronized
    fun syncState(account: String): OfflineSyncState {
        checkOwner(account)
        return stateValue("sync")?.let {
            Net.json.decodeFromString(OfflineSyncState.serializer(), it)
        } ?: OfflineSyncState()
    }

    @Synchronized
    fun saveSyncState(account: String, state: OfflineSyncState) {
        checkOwner(account)
        putState("sync", Net.json.encodeToString(OfflineSyncState.serializer(), state))
    }

    @Synchronized
    fun saveLabels(account: String, labels: List<GLabel>) {
        checkOwner(account)
        putState("labels", Net.json.encodeToString(ListSerializer(GLabel.serializer()), labels))
    }

    @Synchronized
    fun labels(account: String): List<GLabel> {
        checkOwner(account)
        return stateValue("labels")?.let {
            Net.json.decodeFromString(ListSerializer(GLabel.serializer()), it)
        } ?: emptyList()
    }

    @Synchronized
    fun saveDisplayName(account: String, name: String) {
        checkOwner(account)
        putState("displayName", name)
    }

    @Synchronized
    fun displayName(account: String): String {
        checkOwner(account)
        return stateValue("displayName") ?: ""
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun messageDir(account: String, id: String): File =
        File(File(root, digest(normalize(account))), digest(id))

    private fun bodyFile(account: String, id: String) = File(messageDir(account, id), "body.json")
    private fun attachmentFile(account: String, id: String, aid: String) =
        File(File(messageDir(account, id), "attachments"), digest(aid))

    private fun writeAtomic(file: File, bytes: ByteArray) {
        if (!file.parentFile!!.exists() && !file.parentFile!!.mkdirs()) {
            throw IOException("Could not create offline storage.")
        }
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (e: Throwable) {
            atomic.failWrite(stream)
            throw e
        }
    }

    private fun metadata(message: GMessage): GMessage {
        fun strip(part: GPayload): GPayload = part.copy(
            body = part.body?.copy(data = null),
            parts = part.parts.map(::strip),
        )
        return message.copy(payload = message.payload?.let(::strip))
    }

    @Synchronized
    fun hasMessage(account: String, id: String): Boolean {
        checkOwner(account)
        return readableDatabase.rawQuery("SELECT id FROM messages WHERE id=?", arrayOf(id))
            .use { it.moveToFirst() && bodyFile(account, id).isFile }
    }

    @Synchronized
    fun putMessage(account: String, message: GMessage, generation: String? = null) {
        checkOwner(account)
        val previousGeneration = readableDatabase.query(
            "messages", arrayOf("generation"), "id=?", arrayOf(message.id), null, null, null,
        ).use { if (it.moveToFirst()) it.getString(0) else "" }
        writeAtomic(bodyFile(account, message.id), Net.json.encodeToString(GMessage.serializer(), message).toByteArray())
        val values = ContentValues().apply {
            put("id", message.id)
            put("thread_id", message.threadId ?: message.id)
            put("ts", message.internalDate?.toLongOrNull() ?: 0)
            put("metadata", Net.json.encodeToString(GMessage.serializer(), metadata(message)))
            put("complete", 0)
            put("generation", generation ?: previousGeneration)
        }
        if (writableDatabase.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_REPLACE) == -1L) {
            throw IOException("Could not save downloaded email.")
        }
    }

    /** Preserve body files while applying Gmail's latest labels and inventory generation. */
    @Synchronized
    fun updateMetadata(account: String, message: GMessage, generation: String? = null) {
        checkOwner(account)
        val values = ContentValues().apply {
            put("metadata", Net.json.encodeToString(GMessage.serializer(), metadata(message)))
            generation?.let { put("generation", it) }
        }
        writableDatabase.update("messages", values, "id=?", arrayOf(message.id))
    }

    @Synchronized
    fun message(account: String, id: String): GMessage? {
        checkOwner(account)
        val meta = readableDatabase.query(
            "messages", arrayOf("metadata"), "id=?", arrayOf(id), null, null, null,
        ).use { if (it.moveToFirst()) Net.json.decodeFromString(GMessage.serializer(), it.getString(0)) else null }
            ?: return null
        val file = bodyFile(account, id)
        if (!file.exists()) return null
        val full = Net.json.decodeFromString(GMessage.serializer(), AtomicFile(file).openRead().use {
            it.bufferedReader().readText()
        })
        return full.copy(labelIds = meta.labelIds)
    }

    @Synchronized
    fun messages(account: String, cutoff: Long, withBodies: Boolean = false): List<GMessage> {
        checkOwner(account)
        val metadata = readableDatabase.query(
            "messages", arrayOf("metadata"), "ts>=?", arrayOf(cutoff.toString()), null, null, "ts DESC",
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) add(Net.json.decodeFromString(GMessage.serializer(), cursor.getString(0)))
        } }.filter { OfflinePolicy.includes(it, cutoff) && bodyFile(account, it.id).isFile }
        return if (withBodies) metadata.mapNotNull { message(account, it.id) } else metadata
    }

    @Synchronized
    fun thread(account: String, threadId: String, cutoff: Long): GThread? {
        checkOwner(account)
        val ids = readableDatabase.query(
            "messages", arrayOf("id"), "thread_id=? AND ts>=?", arrayOf(threadId, cutoff.toString()),
            null, null, "ts ASC",
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        val messages = ids.mapNotNull { message(account, it) }.filter { OfflinePolicy.includes(it, cutoff) }
        return if (messages.isEmpty()) null else GThread(threadId, messages)
    }

    @Synchronized
    fun attachment(account: String, id: String, aid: String): ByteArray? {
        checkOwner(account)
        val file = attachmentFile(account, id, aid)
        return if (file.exists()) AtomicFile(file).openRead().use { it.readBytes() } else null
    }

    @Synchronized
    fun putAttachment(account: String, id: String, aid: String, bytes: ByteArray) {
        checkOwner(account)
        writeAtomic(attachmentFile(account, id, aid), bytes)
    }

    @Synchronized
    fun markComplete(account: String, id: String) {
        checkOwner(account)
        writableDatabase.update("messages", ContentValues().apply { put("complete", 1) }, "id=?", arrayOf(id))
    }

    @Synchronized
    fun counts(account: String): Pair<Int, Int> {
        checkOwner(account)
        return readableDatabase.rawQuery("SELECT COUNT(*), COALESCE(SUM(complete),0) FROM messages", null)
            .use { it.moveToFirst(); it.getInt(0) to it.getInt(1) }
    }

    @Synchronized
    fun removeMessage(account: String, id: String) {
        checkOwner(account)
        writableDatabase.delete("messages", "id=?", arrayOf(id))
        val dir = messageDir(account, id)
        if (dir.exists() && !dir.deleteRecursively()) throw IOException("Could not remove downloaded email.")
    }

    @Synchronized
    fun prune(account: String, cutoff: Long, generation: String? = null) {
        checkOwner(account)
        val selection = if (generation == null) "ts<?" else "ts<? OR generation!=?"
        val args = if (generation == null) arrayOf(cutoff.toString()) else arrayOf(cutoff.toString(), generation)
        val ids = readableDatabase.query(
            "messages", arrayOf("id"), selection, args, null, null, null,
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        ids.forEach { removeMessage(account, it) }
    }

    @Synchronized
    fun draft(account: String, id: String): LocalDraft? {
        checkOwner(account)
        return readableDatabase.query(
            "drafts", arrayOf("data"), "id=?", arrayOf(id), null, null, null,
        ).use { if (it.moveToFirst()) Net.json.decodeFromString(LocalDraft.serializer(), it.getString(0)) else null }
    }

    @Synchronized
    fun drafts(account: String): List<LocalDraft> {
        checkOwner(account)
        return readableDatabase.query("drafts", arrayOf("data"), null, null, null, null, "updated DESC")
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(Net.json.decodeFromString(LocalDraft.serializer(), cursor.getString(0)))
                }
            }
    }

    @Synchronized
    fun replyDraft(account: String, threadId: String): LocalDraft? =
        drafts(account).firstOrNull { it.threadId == threadId && it.status == DraftStatus.DRAFT }

    private fun writeDraft(draft: LocalDraft) {
        val values = ContentValues().apply {
            put("id", draft.id); put("status", draft.status.name); put("updated", draft.updatedAt)
            put("thread_id", draft.threadId)
            put("data", Net.json.encodeToString(LocalDraft.serializer(), draft))
        }
        if (writableDatabase.insertWithOnConflict("drafts", null, values, SQLiteDatabase.CONFLICT_REPLACE) == -1L) {
            throw IOException("Could not save draft.")
        }
    }

    /** A late autosave can never put an already queued/sent email back in Drafts. */
    @Synchronized
    fun saveDraft(draft: LocalDraft): LocalDraft {
        checkOwner(draft.account)
        val existing = draft(draft.account, draft.id)
        if (existing != null && existing.status != DraftStatus.DRAFT) return existing
        val saved = draft.copy(status = DraftStatus.DRAFT, raw = null, error = null)
        writeDraft(saved)
        return saved
    }

    @Synchronized
    fun queue(account: String, id: String, raw: String, now: Long): LocalDraft {
        checkOwner(account)
        val existing = draft(account, id) ?: throw IOException("Save the draft before sending.")
        if (existing.status != DraftStatus.DRAFT) return existing
        val queued = existing.copy(status = DraftStatus.QUEUED, raw = raw, updatedAt = now)
        writeDraft(queued)
        return queued
    }

    /** Durable claim, before a single send request is made. All workers share this lock. */
    @Synchronized
    fun claim(account: String, id: String, now: Long): LocalDraft? {
        checkOwner(account)
        val existing = draft(account, id) ?: return null
        if (existing.status !in setOf(DraftStatus.QUEUED, DraftStatus.RETRY) || existing.nextAttemptAt > now) return null
        return existing.copy(
            status = DraftStatus.SENDING, attempts = existing.attempts + 1, error = null, updatedAt = now,
        ).also(::writeDraft)
    }

    @Synchronized
    fun setDeliveryState(
        account: String, id: String, status: DraftStatus, error: String? = null, nextAttemptAt: Long = 0,
        expectedStatus: DraftStatus? = null,
    ) {
        checkOwner(account)
        val existing = draft(account, id) ?: return
        if (expectedStatus != null && existing.status != expectedStatus) return
        writeDraft(existing.copy(
            status = status, error = error, nextAttemptAt = nextAttemptAt,
            updatedAt = System.currentTimeMillis(),
            body = if (status == DraftStatus.SENT) "" else existing.body,
            raw = if (status == DraftStatus.SENT) null else existing.raw,
        ))
    }

    @Synchronized
    fun retry(account: String, id: String) {
        checkOwner(account)
        val existing = draft(account, id) ?: return
        if (existing.status !in setOf(DraftStatus.RETRY, DraftStatus.FAILED, DraftStatus.UNKNOWN)) return
        writeDraft(existing.copy(status = DraftStatus.QUEUED, error = null, nextAttemptAt = 0))
    }

    @Synchronized
    fun returnToDraft(account: String, id: String) {
        checkOwner(account)
        val existing = draft(account, id) ?: return
        if (existing.status in setOf(DraftStatus.SENDING, DraftStatus.SENT)) return
        writeDraft(existing.copy(status = DraftStatus.DRAFT, raw = null, error = null, nextAttemptAt = 0))
    }

    @Synchronized
    fun deleteDraft(account: String, id: String) {
        checkOwner(account)
        val existing = draft(account, id) ?: return
        if (existing.status == DraftStatus.SENDING) return
        // A tombstone also prevents a late editor save from recreating a discarded draft.
        writeDraft(existing.copy(
            status = DraftStatus.SENT, body = "", raw = null, updatedAt = System.currentTimeMillis(),
        ))
    }

    @Synchronized
    fun pruneSentDrafts(account: String, before: Long) {
        checkOwner(account)
        writableDatabase.delete(
            "drafts", "status=? AND updated<?", arrayOf(DraftStatus.SENT.name, before.toString()),
        )
    }

    companion object {
        fun normalize(account: String): String = account.trim().lowercase(Locale.ROOT)
    }
}
