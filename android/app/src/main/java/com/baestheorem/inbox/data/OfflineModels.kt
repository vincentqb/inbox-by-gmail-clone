package com.baestheorem.inbox.data

import com.baestheorem.inbox.gmail.GMessage
import com.baestheorem.inbox.gmail.GPayload
import com.baestheorem.inbox.gmail.decodeBody
import com.baestheorem.inbox.gmail.headerValue
import com.baestheorem.inbox.gmail.stripTags
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

/**
 * What the phone keeps. The background sync downloads the last two calendar months
 * (not an approximation of 60 days; Gmail uses epoch seconds). A thread then stays while
 * it is recent, was opened in the last two months, is pinned or snoozed, or has a local
 * draft; see [MailDatabase.threadKept].
 */
object OfflinePolicy {
    val excludedLabels = setOf("SPAM", "TRASH", "DRAFT", "CHAT")

    /** Longest stretch of a message's text the search index keeps. */
    private const val SEARCH_CHARS = 50_000
    private val searchHeaders = setOf("subject", "from", "to", "cc", "bcc")
    private val scriptOrStyle = Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>")
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")

    fun excluded(labelIds: List<String>): Boolean = labelIds.any { it in excludedLabels }

    fun cutoff(now: Long): Long =
        Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).minusMonths(2).toInstant().toEpochMilli()

    fun query(cutoff: Long): String =
        "after:${cutoff / 1000 - 1} -in:spam -in:trash -in:draft -in:chats"

    fun includes(message: GMessage, cutoff: Long): Boolean =
        (message.internalDate?.toLongOrNull() ?: 0) >= cutoff && !excluded(message.labelIds)

    /** Subject, people, preview and readable body text: what offline search matches. */
    fun searchText(message: GMessage): String {
        val headers = message.payload?.headers.orEmpty()
            .filter { (it.name ?: "").lowercase(Locale.ROOT) in searchHeaders }
            .mapNotNull { it.value }
        // Cut before the regexes: a multi-megabyte newsletter needs no more than its start.
        val body = decodeBody(message.payload).let { parts ->
            parts.text.take(SEARCH_CHARS).ifBlank { stripTags(scriptOrStyle.replace(parts.html.take(4 * SEARCH_CHARS), " ")) }
        }
        return (headers + listOfNotNull(message.snippet) + body).joinToString("\n").take(SEARCH_CHARS)
    }

    /**
     * Ordinary words become prefix terms that must all match ("invo" finds "invoice").
     * Anything else, including FTS operators and quotes, only separates words.
     */
    fun matchQuery(query: String): String? = query.lowercase(Locale.ROOT).split(nonWord)
        .filter { it.isNotBlank() }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" ") { "$it*" }

    fun attachmentIds(payload: GPayload?): Set<String> = buildSet {
        fun visit(part: GPayload?) {
            if (part == null) return
            part.body?.attachmentId?.let { add(it) }
            part.parts.forEach(::visit)
        }
        visit(payload)
    }

    /** A message's own text, as opposed to a file that happens to be text. */
    private fun isBodyText(part: GPayload): Boolean =
        part.mimeType in setOf("text/plain", "text/html") && part.filename.isNullOrEmpty() &&
            !headerValue(part.headers, "Content-Disposition").startsWith("attachment", true)

    /** Body text Gmail delivers through the attachment endpoint: what [hydrateTextParts] fills in. */
    fun textPartIds(payload: GPayload?): Set<String> = buildSet {
        fun visit(part: GPayload?) {
            if (part == null) return
            if (isBodyText(part)) part.body?.attachmentId?.let { add(it) }
            part.parts.forEach(::visit)
        }
        visit(payload)
    }

    /** The [textPartIds] whose text isn't in the saved body yet. */
    fun missingTextIds(payload: GPayload?): Set<String> = buildSet {
        fun visit(part: GPayload?) {
            if (part == null) return
            if (isBodyText(part) && part.body?.data == null) part.body?.attachmentId?.let { add(it) }
            part.parts.forEach(::visit)
        }
        visit(payload)
    }

    /** Real files: every attachment except body text delivered through the attachment endpoint. */
    fun fileIds(payload: GPayload?): Set<String> = attachmentIds(payload) - textPartIds(payload)

    /** Large text parts can also be returned by Gmail through the attachment endpoint. */
    fun hydrateTextParts(payload: GPayload?, attachments: Map<String, ByteArray>): GPayload? {
        if (payload == null) return null
        val body = payload.body
        val text = isBodyText(payload)
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

/** A history.list pass interrupted between pages resumes at [nextPage] instead of [start]. */
@Serializable
data class HistoryPage(val start: String, val nextPage: String, val newest: String)

@Serializable
data class OfflineSyncState(
    val checkpoint: FullSyncCheckpoint? = null,
    val historyId: String? = null,
    val historyPage: HistoryPage? = null,
    val lastFullSync: Long = 0,
    val lastSync: Long = 0,
    val lastEviction: Long = 0,
)

data class OfflineStatus(
    val available: Int = 0,
    val complete: Int = 0,
    val syncing: Boolean = false,
    val lastSync: Long = 0,
    val error: String? = null,
)

class AccountChangedException : java.io.IOException("The signed-in account changed. Please try again.")
