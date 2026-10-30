package com.baestheorem.inbox.data

import com.baestheorem.inbox.gmail.GMessage
import com.baestheorem.inbox.gmail.GPayload
import com.baestheorem.inbox.gmail.headerValue
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Calendar months, rather than an approximation of 60 days. Gmail uses epoch seconds. */
object OfflinePolicy {
    val excludedLabels = setOf("SPAM", "TRASH", "DRAFT", "CHAT")

    fun cutoff(now: Long): Long =
        Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).minusMonths(2).toInstant().toEpochMilli()

    fun query(cutoff: Long): String =
        "after:${cutoff / 1000 - 1} -in:spam -in:trash -in:draft -in:chats"

    fun includes(message: GMessage, cutoff: Long): Boolean =
        (message.internalDate?.toLongOrNull() ?: 0) >= cutoff &&
            message.labelIds.none { it in excludedLabels }

    fun attachmentIds(payload: GPayload?): Set<String> = buildSet {
        fun visit(part: GPayload?) {
            if (part == null) return
            part.body?.attachmentId?.let { add(it) }
            part.parts.forEach(::visit)
        }
        visit(payload)
    }

    /** Large text parts can also be returned by Gmail through the attachment endpoint. */
    fun hydrateTextParts(payload: GPayload?, attachments: Map<String, ByteArray>): GPayload? {
        if (payload == null) return null
        val body = payload.body
        val disposition = headerValue(payload.headers, "Content-Disposition")
        val text = payload.mimeType in setOf("text/plain", "text/html") &&
            payload.filename.isNullOrEmpty() && !disposition.startsWith("attachment", true)
        val bytes = body?.attachmentId?.let { attachments[it] }
        return payload.copy(
            body = if (text && body?.data == null && bytes != null) {
                body?.copy(data = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
            } else body,
            parts = payload.parts.map { hydrateTextParts(it, attachments)!! },
        )
    }
}

enum class DraftStatus { DRAFT, QUEUED, SENDING, RETRY, FAILED, UNKNOWN, SENT }

@Serializable
data class LocalDraft(
    val id: String = UUID.randomUUID().toString(),
    val account: String,
    val to: String = "",
    val cc: String = "",
    val subject: String = "",
    val body: String = "",
    val fromName: String = "",
    val threadId: String? = null,
    val inReplyTo: String? = null,
    val references: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val status: DraftStatus = DraftStatus.DRAFT,
    // Stable across retries. Search Sent by this identifier after an interrupted send.
    val rfcMessageId: String = "<${UUID.randomUUID()}@inboxclone.invalid>",
    val raw: String? = null,
    val error: String? = null,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0,
) {
    val isEmpty: Boolean get() = to.isBlank() && cc.isBlank() && subject.isBlank() && body.isBlank()
}

@Serializable
data class FullSyncCheckpoint(
    val cutoff: Long,
    val startHistoryId: String?,
    val generation: String = UUID.randomUUID().toString(),
    val nextPage: String? = null,
    val remaining: List<String> = emptyList(),
    val pageLoaded: Boolean = false,
)

@Serializable
data class OfflineSyncState(
    val checkpoint: FullSyncCheckpoint? = null,
    val historyId: String? = null,
    val lastFullSync: Long = 0,
    val lastSync: Long = 0,
)

data class OfflineStatus(
    val available: Int = 0,
    val complete: Int = 0,
    val syncing: Boolean = false,
    val lastSync: Long = 0,
    val error: String? = null,
)

class AccountChangedException : java.io.IOException("The signed-in account changed. Please try again.")
