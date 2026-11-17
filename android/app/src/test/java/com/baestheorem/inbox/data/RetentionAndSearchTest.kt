package com.baestheorem.inbox.data

import com.baestheorem.inbox.gmail.GBody
import com.baestheorem.inbox.gmail.GPayload
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
class RetentionAndSearchTest : MailDatabaseTestBase() {
    private val cutoff = OfflinePolicy.cutoff(TEST_NOW)
    private val day = 24 * 60 * 60 * 1000L

    @Test fun evictsOnlyThreadsNobodyKeptOldestFirstInBatches() {
        val old = cutoff - day
        db.putMessage(ACCOUNT, email("recent"))
        db.putMessage(ACCOUNT, email("stale-oldest", date = old - 10 * day))
        db.putMessage(ACCOUNT, email("stale", date = old))
        db.putMessage(ACCOUNT, email("opened", date = old - 90 * day))
        db.markOpened(ACCOUNT, "thread-opened", TEST_NOW - 50 * day)
        db.putMessage(ACCOUNT, email("opened-long-ago", date = old - 90 * day))
        db.markOpened(ACCOUNT, "thread-opened-long-ago", old - 5 * day)
        db.putMessage(ACCOUNT, email("pinned", date = old, labels = listOf("INBOX", "STARRED")))
        db.putMessage(ACCOUNT, email("snoozed", date = old, labels = listOf("Label_9")))
        db.putMessage(ACCOUNT, email("replying", date = old))
        db.saveDraft(LocalDraft(account = ACCOUNT, threadId = "thread-replying", body = "Unsent reply"))
        // A recent reply keeps the whole conversation, including its first message.
        db.putMessage(ACCOUNT, email("conversation-start", date = old).copy(threadId = "thread-conversation"))
        db.putMessage(ACCOUNT, email("conversation-reply").copy(threadId = "thread-conversation"))

        assertEquals(1, db.evict(ACCOUNT, cutoff, "Label_9", limit = 1))
        assertNull(db.message(ACCOUNT, "stale-oldest"))
        assertNotNull(db.message(ACCOUNT, "stale"))
        assertEquals(2, db.evict(ACCOUNT, cutoff, "Label_9", limit = 10))
        assertEquals(0, db.evict(ACCOUNT, cutoff, "Label_9", limit = 10))
        assertEquals(
            setOf("recent", "opened", "pinned", "snoozed", "replying", "conversation-start", "conversation-reply"),
            db.messages(ACCOUNT).map { it.id }.toSet(),
        )
        assertNull(db.opened(ACCOUNT, "thread-opened-long-ago"))
        assertTrue(db.threadKept(ACCOUNT, "thread-opened", cutoff, null))
        assertFalse(db.threadKept(ACCOUNT, "thread-never-seen", cutoff, null))
    }

    @Test fun aSentDraftNoLongerKeepsItsThread() {
        db.putMessage(ACCOUNT, email("answered", date = cutoff - day))
        val draft = db.saveDraft(LocalDraft(account = ACCOUNT, threadId = "thread-answered", body = "Reply"))
        assertEquals(0, db.evict(ACCOUNT, cutoff, null, limit = 10))
        db.deleteDraft(ACCOUNT, draft.id)
        assertEquals(1, db.evict(ACCOUNT, cutoff, null, limit = 10))
    }

    @Test fun searchMatchesWordPrefixesInSubjectPeopleAndText() {
        db.putMessage(ACCOUNT, email("invoice", body = "Your Résumé and the QUARTERLY invoices"))
        val html = "<style>.promo { color: red }</style><p>Dinner at <b>Luigi&#39;s</b> on Friday</p>"
        db.putMessage(ACCOUNT, email("html").copy(payload = email("html").payload!!.copy(
            mimeType = "text/html",
            body = GBody(data = Base64.getUrlEncoder().withoutPadding().encodeToString(html.toByteArray())),
        )))
        db.putMessage(ACCOUNT, email("parts", parts = listOf(GPayload(mimeType = "text/plain",
            body = GBody(data = Base64.getUrlEncoder().withoutPadding().encodeToString("Nested words".toByteArray()))))))

        fun ids(q: String) = db.search(ACCOUNT, q).map { it.id }.toSet()
        assertEquals(setOf("invoice"), ids("invo"))
        assertEquals(setOf("invoice"), ids("resume quarterly"))
        assertEquals(setOf("html"), ids("luigi friday"))
        assertEquals(setOf("parts"), ids("nested"))
        assertTrue(ids("color").isEmpty()) // style sheets aren't text
        assertEquals(setOf("invoice", "html", "parts"), ids("sender@example"))
        assertEquals(setOf("invoice"), ids("subject invoice"))
        assertTrue(ids("\"* OR NEAR( -").isEmpty())
        assertTrue(ids("  ").isEmpty())
        db.removeMessage(ACCOUNT, "invoice")
        assertTrue(ids("invo").isEmpty())
        db.putMessage(ACCOUNT, email("html", body = "Replaced body"))
        assertTrue(ids("luigi").isEmpty())
        assertEquals(setOf("html"), ids("replaced"))
    }

    @Test fun trimmingDropsFilesOnDiskButKeepsText() {
        db.putMessage(ACCOUNT, email("with-file", date = cutoff - day, parts = listOf(
            attachment("body", "", "text/plain"), attachment("pdf"),
        )))
        db.putAttachment(ACCOUNT, "with-file", "body", "Text".toByteArray())
        db.putAttachment(ACCOUNT, "with-file", "pdf", byteArrayOf(1, 2))
        db.markComplete(ACCOUNT, "with-file")
        db.putMessage(ACCOUNT, email("not-downloaded", date = cutoff - 2 * day, parts = listOf(attachment("zip"))))
        db.putMessage(ACCOUNT, email("text-only"))
        db.markComplete(ACCOUNT, "text-only")

        assertEquals(1, db.trimAttachments(ACCOUNT, enough = { false }, max = 10))
        assertNull(db.attachment(ACCOUNT, "with-file", "pdf"))
        assertNotNull(db.attachment(ACCOUNT, "with-file", "body")) // text stays
        assertNotNull(db.message(ACCOUNT, "with-file"))
        assertEquals(3 to 1, db.counts(ACCOUNT))
        // Trimmed files aren't fetched again in the background; a file never downloaded still is.
        assertEquals(listOf("not-downloaded"), db.incomplete(ACCOUNT, 10))
        assertEquals(0, db.trimAttachments(ACCOUNT, enough = { false }, max = 10))
    }

    @Test fun trimmingStopsOnceThereIsRoom() {
        for (i in 1..3) {
            db.putMessage(ACCOUNT, email("m$i", date = cutoff - i * day, parts = listOf(attachment("pdf"))))
            db.putAttachment(ACCOUNT, "m$i", "pdf", byteArrayOf(1))
        }
        var trimmed = 0
        assertEquals(2, db.trimAttachments(ACCOUNT, enough = { trimmed++ >= 2 }, max = 10))
        assertNotNull(db.attachment(ACCOUNT, "m1", "pdf")) // the most recent survives
        assertNull(db.attachment(ACCOUNT, "m3", "pdf"))
    }

    @Test fun laterBodyTextNeverUndoesSyncChanges() {
        val fetched = email("kept", labels = listOf("INBOX"))
        db.putMessage(ACCOUNT, fetched)
        db.applyLabels(ACCOUNT, "kept", listOf("INBOX", "STARRED")) // the sync, meanwhile
        assertTrue(db.updateBody(ACCOUNT, fetched.copy(snippet = "Fuller")))
        assertEquals(listOf("INBOX", "STARRED"), db.message(ACCOUNT, "kept")!!.labelIds)
        db.removeMessage(ACCOUNT, "kept")
        assertFalse(db.updateBody(ACCOUNT, fetched))
        assertNull(db.message(ACCOUNT, "kept"))
        assertFalse(db.putAttachment(ACCOUNT, "kept", "pdf", byteArrayOf(1)))
    }
}
