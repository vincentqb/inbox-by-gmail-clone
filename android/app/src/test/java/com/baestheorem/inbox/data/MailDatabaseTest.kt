package com.baestheorem.inbox.data

import com.baestheorem.inbox.gmail.GLabel
import com.baestheorem.inbox.gmail.decodeBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MailDatabaseTest : MailDatabaseTestBase() {
    @Test fun bodiesLargerThanCursorWindowAndAttachmentsSurviveRestart() {
        val text = "Offline ünicode body\n".repeat(150_000)
        db.putMessage(ACCOUNT, email("large", body = text))
        db.putAttachment(ACCOUNT, "large", "pdf", byteArrayOf(0, 1, 2, 127, -1))
        db.markComplete(ACCOUNT, "large")
        db.saveLabels(ACCOUNT, listOf(GLabel("INBOX", "Inbox")))
        db.saveDisplayName(ACCOUNT, "Reader")
        db.saveDraft(LocalDraft(account = ACCOUNT, subject = "Draft", body = "Not sent"))

        reopenDatabase()

        assertEquals(1 to 1, db.counts(ACCOUNT))
        assertEquals(text, decodeBody(db.thread(ACCOUNT, "thread-large", 0)!!.messages.single().payload).text)
        assertNull(db.messages(ACCOUNT, 0).single().payload?.body?.data)
        assertArrayEquals(byteArrayOf(0, 1, 2, 127, -1), db.attachment(ACCOUNT, "large", "pdf"))
        assertEquals("Reader", db.displayName(ACCOUNT))
        assertEquals("Inbox", db.labels(ACCOUNT).single().name)
        assertEquals("Not sent", db.drafts(ACCOUNT).single().body)
    }

    @Test fun switchingAccountsClearsOldDataAndRejectsLateWorkerWrites() {
        db.putMessage(ACCOUNT, email("mail"))
        db.putAttachment(ACCOUNT, "mail", "pdf", byteArrayOf(1))
        val draft = LocalDraft(account = ACCOUNT, body = "Private draft")
        db.saveDraft(draft)
        assertFalse(db.activate(" READER@EXAMPLE.COM "))
        assertTrue(db.activate("other@example.com"))

        assertEquals(0 to 0, db.counts("other@example.com"))
        assertTrue(db.drafts("other@example.com").isEmpty())
        assertNull(db.attachment("other@example.com", "mail", "pdf"))
        assertThrows(AccountChangedException::class.java) { db.putMessage(ACCOUNT, email("late")) }
        assertThrows(AccountChangedException::class.java) { db.putAttachment(ACCOUNT, "mail", "pdf", byteArrayOf(2)) }
        assertThrows(AccountChangedException::class.java) { db.saveDraft(draft) }
    }

    @Test fun queuedContentIsFrozenAndLateAutosaveCannotResurrectIt() {
        val original = LocalDraft(account = ACCOUNT, to = "to@example.com", subject = "Ready", body = "Final body")
        db.saveDraft(original)
        db.queue(ACCOUNT, original.id, "frozen MIME", TEST_NOW)
        db.saveDraft(original.copy(body = "A late UI save"))
        reopenDatabase()

        val queued = db.draft(ACCOUNT, original.id)!!
        assertEquals(DraftStatus.QUEUED, queued.status)
        assertEquals("Final body", queued.body)
        assertEquals("frozen MIME", queued.raw)
        assertEquals(original.rfcMessageId, queued.rfcMessageId)
        assertNotNull(db.claim(ACCOUNT, original.id, TEST_NOW))
        assertNull(db.claim(ACCOUNT, original.id, TEST_NOW))
        db.returnToDraft(ACCOUNT, original.id)
        db.deleteDraft(ACCOUNT, original.id)
        assertEquals(DraftStatus.SENDING, db.draft(ACCOUNT, original.id)!!.status)
        db.setDeliveryState(ACCOUNT, original.id, DraftStatus.SENT)
        db.saveDraft(original)
        assertEquals("", db.draft(ACCOUNT, original.id)!!.body)
        assertNull(db.draft(ACCOUNT, original.id)!!.raw)
    }

    @Test fun discardedDraftCannotBeRecreatedByPendingAutosave() {
        val draft = LocalDraft(account = ACCOUNT, body = "Delete this")
        db.saveDraft(draft)
        db.deleteDraft(ACCOUNT, draft.id)
        db.saveDraft(draft)
        assertEquals(DraftStatus.SENT, db.draft(ACCOUNT, draft.id)!!.status)
        assertEquals("", db.draft(ACCOUNT, draft.id)!!.body)
    }

    @Test fun returningQueuedMailToDraftsWinsAgainstLatePreparationFailure() {
        val draft = LocalDraft(account = ACCOUNT, body = "Edit me")
        db.saveDraft(draft)
        db.queue(ACCOUNT, draft.id, "raw", TEST_NOW)
        db.returnToDraft(ACCOUNT, draft.id)
        db.setDeliveryState(ACCOUNT, draft.id, DraftStatus.RETRY, expectedStatus = DraftStatus.QUEUED)
        assertEquals(DraftStatus.DRAFT, db.draft(ACCOUNT, draft.id)!!.status)
        assertNull(db.claim(ACCOUNT, draft.id, TEST_NOW))
    }

    @Test fun labelChangesAndBodyHydrationPreserveInventoryGeneration() {
        val original = email("keep")
        db.putMessage(ACCOUNT, original, "inventory")
        db.updateMetadata(ACCOUNT, original.copy(labelIds = listOf("SENT"), payload = null))
        assertEquals(listOf("SENT"), db.message(ACCOUNT, "keep")!!.labelIds)
        assertEquals("Saved body for keep", decodeBody(db.message(ACCOUNT, "keep")!!.payload).text)
        db.putMessage(ACCOUNT, db.message(ACCOUNT, "keep")!!)
        db.putMessage(ACCOUNT, email("unseen"), "old")
        db.putMessage(ACCOUNT, email("too-old", date = TEST_NOW - 1), "inventory")
        db.prune(ACCOUNT, TEST_NOW, "inventory")
        assertEquals(listOf("keep"), db.messages(ACCOUNT, 0).map { it.id })
    }
}
