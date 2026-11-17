package com.baestheorem.inbox.data

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.baestheorem.inbox.gmail.GMessage
import com.baestheorem.inbox.gmail.Net
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/** Phones that installed the first offline build have a version 1 database with drafts in it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MailDatabaseMigrationTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val name = "migration-test.db"

    @After fun cleanUp() {
        context.deleteDatabase(name)
        File(context.filesDir, "offline-mail").deleteRecursively()
    }

    @Test fun versionOneUpgradesWithoutLosingMailDraftsOrSyncState() {
        val pinned = email("pinned", date = TEST_NOW - 400L * 24 * 60 * 60 * 1000,
            labels = listOf("INBOX", "STARRED"), parts = listOf(attachment("pdf")))
        val draft = LocalDraft(account = ACCOUNT, subject = "Unsent", body = "Keep me")
        createVersion1(pinned, draft)

        val db = MailDatabase(context, name)
        try {
            assertEquals(2, db.readableDatabase.version)
            assertEquals(ACCOUNT, db.owner())
            assertEquals("42", db.syncState(ACCOUNT).historyId)
            assertEquals(5L, db.syncState(ACCOUNT).lastFullSync)
            assertEquals("Keep me", db.drafts(ACCOUNT).single().body)
            assertEquals(listOf("pinned"), db.messages(ACCOUNT).map { it.id })
            assertEquals(listOf("INBOX", "STARRED"), db.message(ACCOUNT, "pinned")!!.labelIds)

            // The label column was filled in: a pinned thread is never evicted.
            assertEquals(0, db.evict(ACCOUNT, TEST_NOW, null, limit = 10))
            // Existing mail joins the search index in the background.
            assertTrue(db.search(ACCOUNT, "pinned").isEmpty())
            assertEquals(1, db.indexBacklog(ACCOUNT, 10))
            assertEquals(0, db.indexBacklog(ACCOUNT, 10))
            assertEquals(listOf("pinned"), db.search(ACCOUNT, "pinned").map { it.id })
            // And the attachment flag: low storage can trim its file.
            assertEquals(1, db.trimAttachments(ACCOUNT, enough = { false }, max = 10))
        } finally {
            db.close()
        }
    }

    private fun createVersion1(message: GMessage, draft: LocalDraft) {
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { v1 ->
            v1.execSQL("CREATE TABLE state (name TEXT PRIMARY KEY, value TEXT NOT NULL)")
            v1.execSQL(
                "CREATE TABLE messages (id TEXT PRIMARY KEY, thread_id TEXT NOT NULL, " +
                    "ts INTEGER NOT NULL, metadata TEXT NOT NULL, complete INTEGER NOT NULL DEFAULT 0, " +
                    "generation TEXT NOT NULL DEFAULT '')"
            )
            v1.execSQL("CREATE INDEX messages_thread ON messages(thread_id)")
            v1.execSQL("CREATE INDEX messages_date ON messages(ts)")
            v1.execSQL(
                "CREATE TABLE drafts (id TEXT PRIMARY KEY, status TEXT NOT NULL, updated INTEGER NOT NULL, " +
                    "thread_id TEXT, data TEXT NOT NULL)"
            )
            v1.execSQL("INSERT INTO state VALUES ('owner', ?)", arrayOf(ACCOUNT))
            v1.execSQL("INSERT INTO state VALUES ('sync', ?)", arrayOf("""{"historyId":"42","lastFullSync":5,"lastSync":6}"""))
            val metadata = message.copy(payload = message.payload!!.copy(
                body = message.payload!!.body?.copy(data = null),
            ))
            v1.insert("messages", null, ContentValues().apply {
                put("id", message.id); put("thread_id", message.threadId); put("ts", message.internalDate!!.toLong())
                put("metadata", Net.json.encodeToString(GMessage.serializer(), metadata))
                put("complete", 1); put("generation", "g1")
            })
            v1.insert("drafts", null, ContentValues().apply {
                put("id", draft.id); put("status", draft.status.name); put("updated", draft.updatedAt)
                put("thread_id", draft.threadId); put("data", Net.json.encodeToString(LocalDraft.serializer(), draft))
            })
            v1.version = 1
        }
        // Version 1 wrote bodies under SHA-256 directory names, as version 2 still does.
        fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val dir = File(File(File(context.filesDir, "offline-mail"), digest(ACCOUNT)), digest(message.id))
        File(dir, "body.json").apply { parentFile!!.mkdirs() }
            .writeText(Net.json.encodeToString(GMessage.serializer(), message))
        File(dir, "attachments").mkdirs()
        File(File(dir, "attachments"), digest("pdf")).writeBytes(byteArrayOf(1))
    }
}
