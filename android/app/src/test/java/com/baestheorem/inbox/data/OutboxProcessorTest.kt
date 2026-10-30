package com.baestheorem.inbox.data

import com.baestheorem.inbox.gmail.AuthExpiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class OutboxProcessorTest : MailDatabaseTestBase() {
    private class Gateway : OutboxGateway {
        var prepareAction: suspend () -> Unit = {}
        var sendAction: suspend (LocalDraft) -> Unit = {}
        var inSent = false
        val sent = mutableListOf<LocalDraft>()
        val reconciled = mutableListOf<String>()
        override suspend fun prepare(account: String): PreparedSender {
            prepareAction()
            return PreparedSender { sent.add(it); sendAction(it) }
        }
        override suspend fun isInSent(account: String, rfcMessageId: String): Boolean {
            reconciled.add(rfcMessageId)
            return inSent
        }
    }

    private fun queued(): LocalDraft {
        val draft = LocalDraft(account = ACCOUNT, to = "to@example.com", body = "Saved mail", threadId = "reply")
        db.saveDraft(draft)
        return db.queue(ACCOUNT, draft.id, "immutable MIME", TEST_NOW)
    }

    @Test fun claimsDurablyBeforePostAndSendsTheSavedSnapshotOnce() = runBlocking {
        val draft = queued()
        reopenDatabase()
        val gateway = Gateway()
        gateway.sendAction = {
            assertEquals(DraftStatus.SENDING, db.draft(ACCOUNT, it.id)!!.status)
            assertEquals("immutable MIME", it.raw)
            assertEquals("reply", it.threadId)
        }
        val processor = OutboxProcessor(db, gateway, now = { TEST_NOW })
        assertFalse(processor.drain(ACCOUNT))
        assertFalse(processor.drain(ACCOUNT))
        assertEquals(1, gateway.sent.size)
        assertEquals(draft.rfcMessageId, gateway.sent.single().rfcMessageId)
        assertEquals(DraftStatus.SENT, db.draft(ACCOUNT, draft.id)!!.status)
        assertNull(db.draft(ACCOUNT, draft.id)!!.raw)
    }

    @Test fun authenticationFailureBeforePostIsSafeToRetry() = runBlocking {
        val draft = queued()
        val gateway = Gateway()
        gateway.prepareAction = { throw AuthExpiredException("Expired") }
        assertTrue(OutboxProcessor(db, gateway, now = { TEST_NOW }).drain(ACCOUNT))
        assertEquals(DraftStatus.RETRY, db.draft(ACCOUNT, draft.id)!!.status)
        assertEquals(0, db.draft(ACCOUNT, draft.id)!!.attempts)
        assertEquals(TEST_NOW + 30_000, db.draft(ACCOUNT, draft.id)!!.nextAttemptAt)
        assertTrue(gateway.sent.isEmpty())
    }

    @Test fun cancelledQueueCannotBeResurrectedByPreparationFailure() = runBlocking {
        val draft = queued()
        val gateway = Gateway()
        gateway.prepareAction = {
            db.returnToDraft(ACCOUNT, draft.id)
            throw IOException("Token refresh failed")
        }
        assertFalse(OutboxProcessor(db, gateway, now = { TEST_NOW }).drain(ACCOUNT))
        assertEquals(DraftStatus.DRAFT, db.draft(ACCOUNT, draft.id)!!.status)
        assertTrue(gateway.sent.isEmpty())
    }

    @Test fun explicitRateLimitRejectionRetriesAfterBackoff() = runBlocking {
        var clock = TEST_NOW
        val draft = queued()
        val gateway = Gateway()
        gateway.sendAction = { throw SendRejectedException(true, "Rate limited") }
        val processor = OutboxProcessor(db, gateway, now = { clock })
        assertTrue(processor.drain(ACCOUNT))
        assertEquals(DraftStatus.RETRY, db.draft(ACCOUNT, draft.id)!!.status)
        assertTrue(processor.drain(ACCOUNT))
        assertEquals(1, gateway.sent.size)
        clock += 30_000
        gateway.sendAction = {}
        assertFalse(processor.drain(ACCOUNT))
        assertEquals(2, gateway.sent.size)
        assertEquals(gateway.sent[0].raw, gateway.sent[1].raw)
        assertEquals(gateway.sent[0].rfcMessageId, gateway.sent[1].rfcMessageId)
    }

    @Test fun permanentRejectionKeepsTheDraftForEditing() = runBlocking {
        val draft = queued()
        val gateway = Gateway()
        gateway.sendAction = { throw SendRejectedException(false, "Invalid recipient") }
        assertFalse(OutboxProcessor(db, gateway, now = { TEST_NOW }).drain(ACCOUNT))
        assertEquals(DraftStatus.FAILED, db.draft(ACCOUNT, draft.id)!!.status)
        assertEquals("Saved mail", db.draft(ACCOUNT, draft.id)!!.body)
        db.returnToDraft(ACCOUNT, draft.id)
        assertEquals(DraftStatus.DRAFT, db.draft(ACCOUNT, draft.id)!!.status)
    }

    @Test fun lostPostResponseBecomesUnknownAndNeverAutomaticallyResends() = runBlocking {
        val draft = queued()
        val gateway = Gateway()
        gateway.sendAction = { throw IOException("Response lost after delivery") }
        assertFalse(OutboxProcessor(db, gateway, now = { TEST_NOW }).drain(ACCOUNT))
        assertEquals(DraftStatus.UNKNOWN, db.draft(ACCOUNT, draft.id)!!.status)

        reopenDatabase()
        val restarted = OutboxProcessor(db, gateway, now = { TEST_NOW + 60_000 })
        assertFalse(restarted.drain(ACCOUNT))
        assertEquals(1, gateway.sent.size)
        assertEquals(listOf(draft.rfcMessageId), gateway.reconciled)
        gateway.inSent = true
        assertFalse(restarted.drain(ACCOUNT))
        assertEquals(DraftStatus.SENT, db.draft(ACCOUNT, draft.id)!!.status)
        assertEquals(1, gateway.sent.size)
    }

    @Test fun processDeathWhileSendingIsReconciledAfterRestart() = runBlocking {
        val draft = queued()
        db.claim(ACCOUNT, draft.id, TEST_NOW)
        reopenDatabase()
        val gateway = Gateway().apply { inSent = true }
        assertFalse(OutboxProcessor(db, gateway, now = { TEST_NOW }).drain(ACCOUNT))
        assertEquals(DraftStatus.SENT, db.draft(ACCOUNT, draft.id)!!.status)
        assertTrue(gateway.sent.isEmpty())
    }

    @Test fun coroutineCancellationLeavesDurableSendingStateForReconciliation() {
        val draft = queued()
        val gateway = Gateway()
        gateway.sendAction = { throw CancellationException("Worker stopped") }
        assertThrows(CancellationException::class.java) {
            runBlocking { OutboxProcessor(db, gateway, now = { TEST_NOW }).drain(ACCOUNT) }
        }
        assertEquals(DraftStatus.SENDING, db.draft(ACCOUNT, draft.id)!!.status)
    }

    @Test fun userRetryChecksSentBeforeQueueingAndPreservesMessageId() = runBlocking {
        val draft = queued()
        db.setDeliveryState(ACCOUNT, draft.id, DraftStatus.UNKNOWN)
        val gateway = Gateway()
        val processor = OutboxProcessor(db, gateway, now = { TEST_NOW })
        processor.retry(ACCOUNT, draft.id)
        assertEquals(DraftStatus.QUEUED, db.draft(ACCOUNT, draft.id)!!.status)
        assertEquals(draft.rfcMessageId, db.draft(ACCOUNT, draft.id)!!.rfcMessageId)
        db.setDeliveryState(ACCOUNT, draft.id, DraftStatus.UNKNOWN)
        gateway.inSent = true
        processor.retry(ACCOUNT, draft.id)
        assertEquals(DraftStatus.SENT, db.draft(ACCOUNT, draft.id)!!.status)
        assertEquals(listOf(draft.rfcMessageId, draft.rfcMessageId), gateway.reconciled)
        assertTrue(gateway.sent.isEmpty())
    }

    @Test fun accountSwitchDuringPreparationPreventsThePost() {
        val draft = queued()
        val gateway = Gateway()
        gateway.prepareAction = { db.activate("other@example.com") }
        assertThrows(AccountChangedException::class.java) {
            runBlocking { OutboxProcessor(db, gateway, now = { TEST_NOW }).drain(ACCOUNT) }
        }
        assertTrue(gateway.sent.isEmpty())
        assertNull(db.draft("other@example.com", draft.id))
    }
}
