package com.baestheorem.inbox.gmail

import kotlinx.serialization.Serializable

// MARK: Gmail REST resources, decoded straight off the wire. Same shapes the
// iOS port decodes (ios/Sources/Models.swift); every field is optional because
// Gmail omits empties and a fields mask trims more.

@Serializable
data class GMessageRef(val id: String, val threadId: String? = null)

@Serializable
data class GMessageList(
    val messages: List<GMessageRef> = emptyList(),
    val nextPageToken: String? = null,
    val resultSizeEstimate: Int? = null,
)

@Serializable
data class GHeader(val name: String? = null, val value: String? = null)

@Serializable
data class GBody(val data: String? = null, val attachmentId: String? = null, val size: Int? = null)

@Serializable
data class GPayload(
    val partId: String? = null,
    val mimeType: String? = null,
    val filename: String? = null,
    val headers: List<GHeader> = emptyList(),
    val body: GBody? = null,
    val parts: List<GPayload> = emptyList(),
)

@Serializable
data class GMessage(
    val id: String,
    val threadId: String? = null,
    val labelIds: List<String> = emptyList(),
    val snippet: String? = null,
    val internalDate: String? = null,
    val payload: GPayload? = null,
)

@Serializable
data class GThread(val id: String? = null, val messages: List<GMessage> = emptyList())

@Serializable
data class GLabel(
    val id: String,
    val name: String? = null,
    val threadsTotal: Int? = null,
    val threadsUnread: Int? = null,
)

@Serializable
data class GLabelList(val labels: List<GLabel> = emptyList())

@Serializable
data class GProfile(val emailAddress: String? = null, val historyId: String? = null)

@Serializable
data class GSendAs(val displayName: String? = null, val isPrimary: Boolean? = null)

@Serializable
data class GSendAsList(val sendAs: List<GSendAs> = emptyList())

@Serializable
data class GAttachmentBody(val data: String? = null, val size: Int? = null)

@Serializable
data class GRawMessage(val raw: String? = null)

// One row of Gmail's "show details" popup
data class DetailRow(val label: String, val value: String)

// history.list: one record per mailbox change; `messages` is the union of every
// message the record touched, which is all the cache invalidation needs. The typed
// lists say what happened, so the offline copy can apply label changes without
// fetching the message again. Their `message` carries id, threadId and labelIds.
@Serializable
data class GHistoryRecord(
    val id: String? = null,
    val messages: List<GMessageRef> = emptyList(),
    val messagesAdded: List<GHistoryMessage> = emptyList(),
    val messagesDeleted: List<GHistoryMessage> = emptyList(),
    val labelsAdded: List<GHistoryLabels> = emptyList(),
    val labelsRemoved: List<GHistoryLabels> = emptyList(),
)

@Serializable
data class GHistoryMessage(val message: GMessage)

@Serializable
data class GHistoryLabels(val message: GMessage, val labelIds: List<String> = emptyList())

@Serializable
data class GHistoryList(
    val history: List<GHistoryRecord> = emptyList(),
    val nextPageToken: String? = null,
    val historyId: String? = null,
)

// MARK: App models (mirror the shapes app.py serves to the web client)

data class HighlightChip(val icon: String, val text: String)

// RFC 2369 / RFC 8058 unsubscribe routes (port of parse_unsubscribe's shape)
enum class UnsubMethod { ONE_CLICK, MAILTO, LINK }

data class UnsubInfo(
    val method: UnsubMethod,
    val httpsUrl: String? = null,
    val mailto: String? = null,
    val mailtoSubject: String = "unsubscribe",
    val fromBody: Boolean = false,
)

data class AttachmentInfo(
    val filename: String,
    val mimeType: String,
    val size: Int,
    val attachmentId: String,
    val messageId: String,
)

data class ThreadSummary(
    val id: String,
    val messageId: String,
    val sender: String,
    val senderEmail: String,
    val subject: String,
    val snippet: String,
    val time: String,
    val ts: Long,
    val unread: Boolean,
    val pinned: Boolean,
    val important: Boolean,
    val bundle: String? = null,
    val highlights: List<HighlightChip> = emptyList(),
    val attachments: List<AttachmentInfo> = emptyList(),
    val unsub: UnsubInfo? = null,
    val permalink: String,
    val wake: Long? = null,
)

data class BundleGroup(
    val name: String,
    val icon: String,
    val color: Long,
    val items: List<ThreadSummary>,
)

data class InboxData(
    val pinned: List<ThreadSummary> = emptyList(),
    val primary: List<ThreadSummary> = emptyList(),
    val bundles: List<BundleGroup> = emptyList(),
    val inboxTotal: Int? = null,
    val inboxUnread: Int? = null,
)

data class MessageDetail(
    val id: String,
    val sender: String,
    val senderEmail: String,
    val to: String,
    val cc: String,
    val bcc: String,
    val date: String,
    val details: List<DetailRow>,
    val text: String,
    val html: String,
    val quotedHtml: String?,
    val attachments: List<AttachmentInfo>,
    val unread: Boolean,
)

data class ThreadDetail(
    val id: String,
    val subject: String,
    val messages: List<MessageDetail>,
    val rfcMessageId: String,
    val allRfcIds: List<String>,
    val permalink: String,
    val pinned: Boolean,
    val unsub: UnsubInfo?,
)
