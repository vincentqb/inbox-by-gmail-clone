package com.baestheorem.inbox.data

import com.baestheorem.inbox.gmail.AuthExpiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import kotlin.math.min

/** Google explicitly rejected this request; retrying it cannot duplicate a delivery. */
class SendRejectedException(val retryable: Boolean, message: String) : IOException(message)

/** A prepared sender captures the account's access token BEFORE a durable send claim. */
fun interface PreparedSender {
    suspend fun send(draft: LocalDraft)
}

interface OutboxGateway {
    suspend fun prepare(account: String): PreparedSender
    suspend fun isInSent(account: String, rfcMessageId: String): Boolean
}

/**
 * Gmail has no idempotency key. Never replay an interrupted POST automatically:
 * search Sent for its stable Message-ID; if delivery cannot be established, leave
 * it in UNKNOWN for the user to inspect. An explicit rejection can safely retry.
 */
class OutboxProcessor(
    private val db: MailDatabase,
    private val gateway: OutboxGateway,
    private val now: () -> Long = System::currentTimeMillis,
    private val changed: suspend () -> Unit = {},
) {
    private val lock = Mutex()

    /** True when a safely retryable item is still waiting. */
    suspend fun drain(account: String, deadline: Long = Long.MAX_VALUE): Boolean = lock.withLock {
        for (draft in db.drafts(account)) {
            currentCoroutineContext().ensureActive()
            if (now() >= deadline) break
            if (draft.status in setOf(DraftStatus.SENDING, DraftStatus.UNKNOWN)) {
                try {
                    if (gateway.isInSent(account, draft.rfcMessageId)) {
                        db.setDeliveryState(account, draft.id, DraftStatus.SENT, expectedStatus = draft.status)
                    } else {
                        db.setDeliveryState(account, draft.id, DraftStatus.UNKNOWN,
                            "Delivery could not be confirmed. Check Sent before retrying.", expectedStatus = draft.status)
                    }
                } catch (e: IOException) {
                    if (draft.status == DraftStatus.SENDING) {
                        db.setDeliveryState(account, draft.id, DraftStatus.UNKNOWN,
                            "Sending was interrupted. Connect and check Sent before retrying.",
                            expectedStatus = DraftStatus.SENDING)
                    }
                }
                changed()
                continue
            }
            if (draft.status !in setOf(DraftStatus.QUEUED, DraftStatus.RETRY) || draft.nextAttemptAt > now()) continue
            val prepared = try {
                gateway.prepare(account)
            } catch (e: IOException) {
                // Nothing was sent: failures to refresh auth or validate the account are safe.
                db.setDeliveryState(account, draft.id, DraftStatus.RETRY,
                    if (e is AuthExpiredException) "Sign in again to send this message."
                    else "Waiting for a connection to Gmail.", now() + 30_000, expectedStatus = draft.status)
                changed()
                continue
            }
            val claimed = db.claim(account, draft.id, now()) ?: continue
            changed()
            try {
                prepared.send(claimed)
                db.setDeliveryState(account, claimed.id, DraftStatus.SENT, expectedStatus = DraftStatus.SENDING)
            } catch (e: SendRejectedException) {
                val retryAt = now() + min(15 * 60_000L, 30_000L * claimed.attempts.coerceAtMost(30))
                db.setDeliveryState(account, claimed.id,
                    if (e.retryable) DraftStatus.RETRY else DraftStatus.FAILED,
                    e.message, if (e.retryable) retryAt else 0, expectedStatus = DraftStatus.SENDING)
            } catch (e: CancellationException) {
                // The durable SENDING state will be reconciled on the next run.
                throw e
            } catch (e: IOException) {
                db.setDeliveryState(account, claimed.id, DraftStatus.UNKNOWN,
                    "Connection lost during send. Check Sent before retrying.", expectedStatus = DraftStatus.SENDING)
            }
            changed()
        }
        db.drafts(account).any { it.status in setOf(DraftStatus.QUEUED, DraftStatus.RETRY) }
    }

    /** Reconcile first even for a user-requested retry. */
    suspend fun retry(account: String, id: String) = lock.withLock {
        val draft = db.draft(account, id) ?: return@withLock
        if (draft.status !in setOf(DraftStatus.RETRY, DraftStatus.FAILED, DraftStatus.UNKNOWN)) return@withLock
        if (gateway.isInSent(account, draft.rfcMessageId)) {
            db.setDeliveryState(account, id, DraftStatus.SENT, expectedStatus = draft.status)
        } else {
            db.retry(account, id)
        }
        changed()
    }
}
