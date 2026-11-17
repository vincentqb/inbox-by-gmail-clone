package com.baestheorem.inbox.data

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
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

/** What a queued message id still needs from Gmail. */
enum class QueueKind { NEW, VERIFY }

/**
 * Account-owned index and durable outbox. Bodies and attachments live in atomic private
 * files so a large message cannot exceed SQLite's CursorWindow limit.
 *
 * All entry points serialize account changes with writes. A worker for the old account
 * cannot add a file or queue a send after a switch, even if its HTTP request finishes late.
 * Call on Dispatchers.IO; no database or file work belongs on the Compose thread.
 *
 * Version 2 adds what thread-level retention, resumable history sync and offline search
 * need: a label column, the threads the user opened, a queue of message ids to fetch, and
 * an FTS4 index whose docid is the message row's rowid (rows are updated in place).
 */
class MailDatabase(context: Context, name: String = "offline-mail.db") :
    SQLiteOpenHelper(context, name, null, VERSION) {
    private val root = File(context.filesDir, "offline-mail")

    override fun onCreate(db: SQLiteDatabase) {
        // Fresh installs take the same path as upgrades, so every migration runs everywhere.
        createVersion1(db)
        upgrade(db, 1)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = upgrade(db, oldVersion)

    private fun upgrade(db: SQLiteDatabase, from: Int) {
        if (from < 2) migrateTo2(db)
    }

    private fun createVersion1(db: SQLiteDatabase) {
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

    private fun migrateTo2(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN labels TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE messages ADD COLUMN files INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN trimmed INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN in_search INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN text_ok INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE messages SET text_ok = complete") // version 1 completed text with the files
        db.execSQL("CREATE INDEX messages_pending ON messages(complete, trimmed)")
        db.execSQL("CREATE TABLE threads (thread_id TEXT PRIMARY KEY, opened INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE fetch_queue (id TEXT PRIMARY KEY, kind TEXT NOT NULL, tries INTEGER NOT NULL DEFAULT 0)")
        createSearchIndex(db)
        // Labels and the attachment tree were only inside the metadata JSON before.
        val rows = db.query("messages", arrayOf("id", "metadata"), null, null, null, null, null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
        }
        for ((id, json) in rows) {
            val meta = runCatching { Net.json.decodeFromString(GMessage.serializer(), json) }.getOrNull() ?: continue
            db.update("messages", ContentValues().apply {
                put("labels", labelColumn(meta.labelIds))
                put("files", if (OfflinePolicy.fileIds(meta.payload).isEmpty()) 0 else 1)
            }, "id=?", arrayOf(id))
        }
    }

    /** unicode61 folds case beyond ASCII; older SQLite builds fall back to the simple tokenizer. */
    private fun createSearchIndex(db: SQLiteDatabase) {
        try {
            db.execSQL("CREATE VIRTUAL TABLE message_search USING fts4(content, tokenize=unicode61)")
        } catch (e: SQLiteException) {
            db.execSQL("CREATE VIRTUAL TABLE message_search USING fts4(content)")
        }
    }

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
            for (table in listOf("messages", "drafts", "state", "threads", "fetch_queue", "message_search")) {
                db.delete(table, null, null)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (root.exists() && !root.deleteRecursively()) throw IOException("Could not clear downloaded mail.")
    }

    /** One SQLite transaction (one disk sync) for many small writes, such as a history page. */
    @Synchronized
    fun <T> transaction(block: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
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

    /**
     * Counts one more try at the full sync's current message and returns the count. Kept
     * apart from the checkpoint, so it costs a few bytes rather than a rewrite of the page.
     */
    @Synchronized
    fun noteInventoryTry(account: String, id: String): Int {
        checkOwner(account)
        val (lastId, lastTries) = stateValue("inventoryTry")?.split(' ', limit = 2)
            ?.let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 0) } ?: ("" to 0)
        val tries = if (lastId == id) lastTries + 1 else 1
        putState("inventoryTry", "$id $tries")
        return tries
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
    private fun attachmentDir(account: String, id: String) = File(messageDir(account, id), "attachments")
    private fun attachmentFile(account: String, id: String, aid: String) =
        File(attachmentDir(account, id), digest(aid))

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

    private fun metadataOf(id: String): GMessage? = readableDatabase.query(
        "messages", arrayOf("metadata"), "id=?", arrayOf(id), null, null, null,
    ).use { if (it.moveToFirst()) Net.json.decodeFromString(GMessage.serializer(), it.getString(0)) else null }

    @Synchronized
    fun hasMessage(account: String, id: String): Boolean {
        checkOwner(account)
        return readableDatabase.rawQuery("SELECT id FROM messages WHERE id=?", arrayOf(id))
            .use { it.moveToFirst() && bodyFile(account, id).isFile }
    }

    /**
     * Saves a full message and (re)indexes it for search. The row is updated in place, so
     * its rowid, which is the message's docid in the search index, never changes.
     */
    fun putMessage(account: String, message: GMessage, generation: String? = null) =
        writeMessage(account, message, generation, OfflinePolicy.searchText(message)) // text built unlocked

    @Synchronized
    private fun writeMessage(account: String, message: GMessage, generation: String?, text: String) {
        checkOwner(account)
        val previous = readableDatabase.query(
            "messages", arrayOf("rowid", "generation"), "id=?", arrayOf(message.id), null, null, null,
        ).use { if (it.moveToFirst()) it.getLong(0) to it.getString(1) else null }
        writeAtomic(bodyFile(account, message.id), Net.json.encodeToString(GMessage.serializer(), message).toByteArray())
        val values = ContentValues().apply {
            put("thread_id", message.threadId ?: message.id)
            put("ts", message.internalDate?.toLongOrNull() ?: 0)
            put("metadata", Net.json.encodeToString(GMessage.serializer(), metadata(message)))
            put("labels", labelColumn(message.labelIds))
            put("files", if (OfflinePolicy.fileIds(message.payload).isEmpty()) 0 else 1)
            put("text_ok", if (OfflinePolicy.missingTextIds(message.payload).isEmpty()) 1 else 0)
            put("complete", 0)
            put("trimmed", 0)
            put("in_search", 1)
            put("generation", generation ?: previous?.second ?: "")
        }
        transaction {
            val db = writableDatabase
            val rowid = if (previous != null) {
                db.update("messages", values, "id=?", arrayOf(message.id))
                previous.first
            } else {
                values.put("id", message.id)
                db.insert("messages", null, values).also {
                    if (it == -1L) throw IOException("Could not save downloaded email.")
                }
            }
            index(db, rowid, text)
        }
    }

    /**
     * Saves fuller text for a message already saved (body parts fetched later), keeping its
     * current labels, download state and generation. False, and nothing written, when the
     * message is no longer saved: a sync may have removed it meanwhile.
     */
    fun updateBody(account: String, message: GMessage): Boolean =
        writeBody(account, message, OfflinePolicy.searchText(message)) // text built unlocked

    @Synchronized
    private fun writeBody(account: String, message: GMessage, text: String): Boolean {
        checkOwner(account)
        val rowid = readableDatabase.query("messages", arrayOf("rowid"), "id=?", arrayOf(message.id), null, null, null)
            .use { if (it.moveToFirst()) it.getLong(0) else null } ?: return false
        // Labels come from the row (see [message]); the body file's copy is never read back.
        writeAtomic(bodyFile(account, message.id), Net.json.encodeToString(GMessage.serializer(), message).toByteArray())
        transaction {
            val textOk = if (OfflinePolicy.missingTextIds(message.payload).isEmpty()) 1 else 0
            writableDatabase.update("messages", ContentValues().apply { put("text_ok", textOk); put("in_search", 1) },
                "id=?", arrayOf(message.id))
            index(writableDatabase, rowid, text)
        }
        return true
    }

    private fun index(db: SQLiteDatabase, rowid: Long, text: String) {
        db.delete("message_search", "docid=?", arrayOf(rowid.toString()))
        val values = ContentValues().apply {
            put("docid", rowid)
            put("content", text)
        }
        if (db.insert("message_search", null, values) == -1L) throw IOException("Could not index downloaded email.")
    }

    /**
     * Replaces a saved message's labels, leaving its headers, date and body alone. A
     * message moved to Spam or Trash leaves the cache. False when it isn't saved.
     */
    @Synchronized
    fun applyLabels(account: String, id: String, labelIds: List<String>, generation: String? = null): Boolean {
        checkOwner(account)
        val meta = metadataOf(id) ?: return false
        if (OfflinePolicy.excluded(labelIds)) {
            removeMessage(account, id)
            return true
        }
        val values = ContentValues().apply {
            put("metadata", Net.json.encodeToString(GMessage.serializer(), meta.copy(labelIds = labelIds)))
            put("labels", labelColumn(labelIds))
            generation?.let { put("generation", it) }
        }
        writableDatabase.update("messages", values, "id=?", arrayOf(id))
        return true
    }

    /** One history record's label change, applied in order. False when the message isn't saved. */
    @Synchronized
    fun applyLabelDelta(account: String, id: String, added: List<String>, removed: List<String>): Boolean {
        checkOwner(account)
        val current = metadataOf(id)?.labelIds ?: return false
        val next = current.filterNot { it in removed } + added.filterNot { it in current || it in removed }
        return applyLabels(account, id, next)
    }

    @Synchronized
    fun message(account: String, id: String): GMessage? {
        checkOwner(account)
        val meta = metadataOf(id) ?: return null
        val file = bodyFile(account, id)
        if (!file.exists()) return null
        val full = Net.json.decodeFromString(GMessage.serializer(), AtomicFile(file).openRead().use {
            it.bufferedReader().readText()
        })
        return full.copy(labelIds = meta.labelIds)
    }

    /** Everything saved, newest first, as metadata (no body text). */
    @Synchronized
    fun messages(account: String): List<GMessage> {
        checkOwner(account)
        return readableDatabase.query(
            "messages", arrayOf("metadata"), null, null, null, null, "ts DESC",
        ).use(::decodeRows).filter { !OfflinePolicy.excluded(it.labelIds) && bodyFile(account, it.id).isFile }
    }

    private fun decodeRows(cursor: android.database.Cursor): List<GMessage> = buildList {
        while (cursor.moveToNext()) add(Net.json.decodeFromString(GMessage.serializer(), cursor.getString(0)))
    }

    /** Every saved message of a thread, oldest first, with bodies. */
    @Synchronized
    fun thread(account: String, threadId: String): GThread? {
        checkOwner(account)
        val ids = readableDatabase.query(
            "messages", arrayOf("id"), "thread_id=?", arrayOf(threadId), null, null, "ts ASC",
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        val messages = ids.mapNotNull { message(account, it) }.filter { !OfflinePolicy.excluded(it.labelIds) }
        return if (messages.isEmpty()) null else GThread(threadId, messages)
    }

    /** Offline search: every word must match the start of a word in a saved message. */
    @Synchronized
    fun search(account: String, query: String, limit: Int = 500): List<GMessage> {
        checkOwner(account)
        val match = OfflinePolicy.matchQuery(query) ?: return emptyList()
        return readableDatabase.rawQuery(
            "SELECT m.metadata FROM message_search JOIN messages m ON m.rowid = message_search.docid " +
                "WHERE message_search MATCH ? ORDER BY m.ts DESC LIMIT CAST(? AS INTEGER)",
            arrayOf(match, limit.toString()),
        ).use(::decodeRows).filter { !OfflinePolicy.excluded(it.labelIds) && bodyFile(account, it.id).isFile }
    }

    /**
     * Indexes messages saved before the search index existed. Returns how many it looked at.
     * Bodies are read and their text built outside the lock; only the writes take it.
     */
    fun indexBacklog(account: String, limit: Int): Int {
        val rows = unindexed(account, limit)
        val texts = rows.map { (rowid, id) ->
            Triple(rowid, id, runCatching { message(account, id) }.getOrNull()?.let(OfflinePolicy::searchText))
        }
        writeIndex(account, texts)
        return rows.size
    }

    @Synchronized
    private fun unindexed(account: String, limit: Int): List<Pair<Long, String>> {
        checkOwner(account)
        return readableDatabase.query(
            "messages", arrayOf("rowid", "id"), "in_search=0", null, null, null, "ts DESC", limit.toString(),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) } }
    }

    @Synchronized
    private fun writeIndex(account: String, texts: List<Triple<Long, String, String?>>) {
        checkOwner(account)
        transaction {
            for ((rowid, id, text) in texts) {
                // Saved again (and indexed) or removed since it was read: nothing to do.
                val pending = readableDatabase.rawQuery(
                    "SELECT 1 FROM messages WHERE rowid=? AND id=? AND in_search=0", arrayOf(rowid.toString(), id),
                ).use { it.moveToFirst() }
                if (!pending) continue
                if (text != null) index(writableDatabase, rowid, text)
                writableDatabase.update("messages", ContentValues().apply { put("in_search", 1) }, "id=?", arrayOf(id))
            }
        }
    }

    @Synchronized
    fun attachment(account: String, id: String, aid: String): ByteArray? {
        checkOwner(account)
        val file = attachmentFile(account, id, aid)
        return if (file.exists()) AtomicFile(file).openRead().use { it.readBytes() } else null
    }

    /** False, and nothing written, when the message isn't saved (removed while downloading). */
    @Synchronized
    fun putAttachment(account: String, id: String, aid: String, bytes: ByteArray): Boolean {
        checkOwner(account)
        val saved = readableDatabase.rawQuery("SELECT 1 FROM messages WHERE id=?", arrayOf(id)).use { it.moveToFirst() }
        if (saved) writeAtomic(attachmentFile(account, id, aid), bytes)
        return saved
    }

    @Synchronized
    fun markComplete(account: String, id: String) {
        checkOwner(account)
        writableDatabase.update("messages", ContentValues().apply { put("complete", 1) }, "id=?", arrayOf(id))
    }

    /**
     * Saved messages still missing attachments or body text: newest first, ones tried before
     * behind the rest. [textOnly] (low storage) asks only for those missing text. Trimmed ones,
     * and ones tried [MAX_DOWNLOAD_ATTEMPTS] times, wait to be opened.
     */
    @Synchronized
    fun incomplete(account: String, limit: Int, textOnly: Boolean = false): List<String> {
        checkOwner(account)
        val pending = if (textOnly) "text_ok=0" else "complete=0 AND trimmed=0"
        return readableDatabase.query(
            "messages", arrayOf("id"), "$pending AND attempts<$MAX_DOWNLOAD_ATTEMPTS",
            null, null, null, "attempts ASC, ts DESC", limit.toString(),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    }

    /** Counted before a download starts, so even a crash (out of memory on a huge file) counts. */
    @Synchronized
    fun noteDownloadAttempt(account: String, id: String) {
        checkOwner(account)
        writableDatabase.execSQL("UPDATE messages SET attempts = attempts + 1 WHERE id=?", arrayOf(id))
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
        transaction {
            val db = writableDatabase
            db.execSQL("DELETE FROM message_search WHERE docid IN (SELECT rowid FROM messages WHERE id=?)", arrayOf(id))
            db.delete("messages", "id=?", arrayOf(id))
            db.delete("fetch_queue", "id=?", arrayOf(id))
        }
        val dir = messageDir(account, id)
        if (dir.exists() && !dir.deleteRecursively()) throw IOException("Could not remove downloaded email.")
    }

    // MARK: Retention

    /** Opening a thread, online or off, keeps it for another two months. */
    @Synchronized
    fun markOpened(account: String, threadId: String, at: Long) {
        checkOwner(account)
        writableDatabase.insertWithOnConflict(
            "threads", null, ContentValues().apply { put("thread_id", threadId); put("opened", at) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    @Synchronized
    fun opened(account: String, threadId: String): Long? {
        checkOwner(account)
        return readableDatabase.query("threads", arrayOf("opened"), "thread_id=?", arrayOf(threadId), null, null, null)
            .use { if (it.moveToFirst()) it.getLong(0) else null }
    }

    /**
     * A thread stays while its newest message arrived after [cutoff], it was opened after
     * [cutoff], it is pinned (starred) or snoozed, or a local draft or Outbox item replies to it.
     */
    @Synchronized
    fun threadKept(account: String, threadId: String, cutoff: Long, snoozedLabel: String?): Boolean {
        checkOwner(account)
        if ((opened(account, threadId) ?: 0) >= cutoff) return true
        return readableDatabase.rawQuery(
            "SELECT 1 FROM messages WHERE thread_id=? AND (ts>=? OR labels LIKE '% STARRED %' OR labels LIKE ?) " +
                "UNION SELECT 1 FROM drafts WHERE thread_id=? AND status!=?",
            arrayOf(threadId, cutoff.toString(), labelPattern(snoozedLabel), threadId, DraftStatus.SENT.name),
        ).use { it.moveToFirst() }
    }

    /**
     * Removes up to [limit] threads nobody kept (see [threadKept]), least recently relevant
     * first. Returns how many threads it removed; [limit] means more may be waiting.
     */
    @Synchronized
    fun evict(account: String, cutoff: Long, snoozedLabel: String?, limit: Int): Int {
        checkOwner(account)
        val threads = readableDatabase.rawQuery(
            "SELECT m.thread_id FROM messages m LEFT JOIN threads t ON t.thread_id = m.thread_id " +
                "GROUP BY m.thread_id " +
                // Arguments arrive as text, and SQLite orders any number before any text
                // when neither side is a column, so the aggregates compare against a CAST.
                "HAVING MAX(m.ts) < CAST(?1 AS INTEGER) AND COALESCE(MAX(t.opened), 0) < CAST(?1 AS INTEGER) " +
                "AND SUM(m.labels LIKE '% STARRED %') = 0 AND SUM(m.labels LIKE ?2) = 0 " +
                "AND m.thread_id NOT IN (SELECT thread_id FROM drafts WHERE thread_id IS NOT NULL AND status != ?3) " +
                "ORDER BY MAX(MAX(m.ts), COALESCE(MAX(t.opened), 0)) ASC LIMIT CAST(?4 AS INTEGER)",
            arrayOf(cutoff.toString(), labelPattern(snoozedLabel), DraftStatus.SENT.name, limit.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        for (threadId in threads) {
            val ids = readableDatabase.query("messages", arrayOf("id"), "thread_id=?", arrayOf(threadId), null, null, null)
                .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            ids.forEach { removeMessage(account, it) }
            writableDatabase.delete("threads", "thread_id=?", arrayOf(threadId))
        }
        // An opened thread whose messages all went (deleted in Gmail) needs no record.
        writableDatabase.execSQL(
            "DELETE FROM threads WHERE opened < ? AND thread_id NOT IN (SELECT thread_id FROM messages)",
            arrayOf(cutoff.toString()),
        )
        return threads.size
    }

    /**
     * Low storage: deletes saved files (never text) of the least recently relevant messages
     * until [enough] or [max] messages. A message with no file on disk isn't touched, so its
     * files still download once there is room. Trimmed ones aren't fetched again in the
     * background; opening one while connected does. Returns how many it trimmed.
     */
    @Synchronized
    fun trimAttachments(account: String, enough: () -> Boolean, max: Int): Int {
        checkOwner(account)
        val candidates = readableDatabase.rawQuery(
            "SELECT m.id, m.metadata FROM messages m LEFT JOIN threads t ON t.thread_id = m.thread_id " +
                "WHERE m.files=1 AND m.trimmed=0 ORDER BY MAX(m.ts, COALESCE(t.opened, 0)) ASC LIMIT 1000",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
        var trimmed = 0
        for ((id, json) in candidates) {
            if (trimmed >= max || enough()) break
            val payload = runCatching { Net.json.decodeFromString(GMessage.serializer(), json).payload }.getOrNull()
            val files = OfflinePolicy.fileIds(payload).map { attachmentFile(account, id, it) }.filter { it.exists() }
            if (files.isEmpty()) continue
            files.forEach { if (!it.delete()) throw IOException("Could not free storage.") }
            writableDatabase.update(
                "messages", ContentValues().apply { put("trimmed", 1); put("complete", 0) }, "id=?", arrayOf(id),
            )
            trimmed++
        }
        return trimmed
    }

    /**
     * End of a full sync. Gmail listed every message in the window, so a message in the
     * window it didn't list was deleted or moved to Spam or Trash. Older saved messages are
     * there because someone kept their thread; queue those for a label check instead.
     */
    @Synchronized
    fun finishInventory(account: String, cutoff: Long, generation: String, snoozedLabel: String?) {
        checkOwner(account)
        val unseen = readableDatabase.query(
            "messages", arrayOf("id", "thread_id", "ts"), "generation!=?", arrayOf(generation), null, null, null,
        ).use { c -> buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getLong(2))) } }
        for ((id, threadId, ts) in unseen) {
            when {
                ts >= cutoff -> removeMessage(account, id)
                threadKept(account, threadId, cutoff, snoozedLabel) -> enqueue(account, id, QueueKind.VERIFY)
                else -> removeMessage(account, id) // eviction would take it anyway
            }
        }
    }

    // MARK: Fetch queue

    @Synchronized
    fun enqueue(account: String, id: String, kind: QueueKind) {
        checkOwner(account)
        writableDatabase.insertWithOnConflict(
            "fetch_queue", null, ContentValues().apply { put("id", id); put("kind", kind.name) },
            SQLiteDatabase.CONFLICT_REPLACE,
        ).also { if (it == -1L) throw IOException("Could not save the download queue.") }
    }

    data class Queued(val id: String, val kind: QueueKind, val tries: Int)

    /** Most recently queued first, so the newest mail after a long gap arrives first; retries last. */
    @Synchronized
    fun queued(account: String, limit: Int): List<Queued> {
        checkOwner(account)
        return readableDatabase.query(
            "fetch_queue", arrayOf("id", "kind", "tries"), null, null, null, null, "tries ASC, rowid DESC", limit.toString(),
        ).use { c ->
            buildList { while (c.moveToNext()) add(Queued(c.getString(0), QueueKind.valueOf(c.getString(1)), c.getInt(2))) }
        }
    }

    /** Counted before each try, like [noteDownloadAttempt]. */
    @Synchronized
    fun noteQueueTry(account: String, id: String) {
        checkOwner(account)
        writableDatabase.execSQL("UPDATE fetch_queue SET tries = tries + 1 WHERE id=?", arrayOf(id))
    }

    @Synchronized
    fun dequeue(account: String, id: String) {
        checkOwner(account)
        writableDatabase.delete("fetch_queue", "id=?", arrayOf(id))
    }

    /**
     * Free space as Settings shows it, which is what the 500 MB / 750 MB thresholds mean.
     * StorageManager.getAllocatableBytes would first subtract Android's own low-storage
     * reserve (up to 500 MB), roughly doubling them.
     */
    @SuppressLint("UsableSpace")
    fun freeSpace(): Long = (if (root.exists()) root else root.parentFile ?: root).usableSpace

    // MARK: Drafts and Outbox

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
        const val VERSION = 2
        /** Background attempts at a message's attachments before it waits to be opened. */
        const val MAX_DOWNLOAD_ATTEMPTS = 10
        /** Tries at a queued message id before it is dropped (a later change queues it again). */
        const val MAX_QUEUE_TRIES = 5

        fun normalize(account: String): String = account.trim().lowercase(Locale.ROOT)

        /** " INBOX UNREAD ", so a LIKE '% ID %' test matches whole label ids only. */
        internal fun labelColumn(labels: List<String>): String = labels.joinToString(" ", prefix = " ", postfix = " ")

        /** With no snoozed label yet, a pattern no label column can match (ids never hold \u0001). */
        private fun labelPattern(label: String?): String = if (label.isNullOrBlank()) "%\u0001%" else "% $label %"
    }
}
