package com.baestheorem.inbox.gmail

import java.util.Base64
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

// MIME walking, inline-image resolution, quoted-history trimming and RFC 2822
// building. Port of app.py's body layer via ios/Sources/Bundling.swift.

data class BodyParts(val text: String, val html: String)

fun decodeBody(payload: GPayload?): BodyParts {
    var text = ""
    var html = ""
    fun walk(part: GPayload?) {
        if (part == null) return
        val mime = part.mimeType ?: ""
        val data = part.body?.data
        val attachment = !part.filename.isNullOrEmpty() ||
            headerValue(part.headers, "Content-Disposition").startsWith("attachment", true)
        if (data != null && !attachment) {
            if (mime == "text/plain" && text.isEmpty()) {
                base64UrlDecode(data)?.let { text = String(it, Charsets.UTF_8) }
            } else if (mime == "text/html") {
                base64UrlDecode(data)?.let { html = String(it, Charsets.UTF_8) }
            }
        }
        for (sub in part.parts) walk(sub)
    }
    walk(payload)
    return BodyParts(text, html)
}

fun isInlinePart(part: GPayload): Boolean {
    var inline = false
    var hasCid = false
    for (h in part.headers) {
        when ((h.name ?: "").lowercase()) {
            "content-disposition" -> inline = (h.value ?: "").lowercase().startsWith("inline")
            "content-id" -> hasCid = true
        }
    }
    return inline || hasCid
}

// Inline images smaller than this are decoration (logos/signatures/spacers).
const val INLINE_IMG_MAX = 30000

fun collectAttachments(
    payload: GPayload?,
    messageId: String,
    hideInlineImages: Boolean,
): List<AttachmentInfo> {
    val out = mutableListOf<AttachmentInfo>()
    val seen = HashSet<String>()
    fun walk(part: GPayload?) {
        if (part == null) return
        val fn = part.filename ?: ""
        val aid = part.body?.attachmentId
        if (fn.isNotEmpty() && aid != null) {
            val mime = part.mimeType ?: "application/octet-stream"
            val inlineImg = mime.startsWith("image/") && isInlinePart(part)
            val decoration = inlineImg && (hideInlineImages || (part.body?.size ?: 0) < INLINE_IMG_MAX)
            if (!decoration && seen.add(fn)) {
                out.add(AttachmentInfo(fn, mime, part.body?.size ?: 0, aid, messageId))
            }
        }
        for (sub in part.parts) walk(sub)
    }
    walk(payload)
    return out
}

data class CidPart(val attachmentId: String, val mime: String)

fun inlineCidMap(payload: GPayload?): Map<String, CidPart> {
    val out = HashMap<String, CidPart>()
    fun walk(part: GPayload?) {
        if (part == null) return
        val aid = part.body?.attachmentId
        if (aid != null) {
            val mime = part.mimeType ?: "application/octet-stream"
            for (h in part.headers) {
                if ((h.name ?: "").lowercase() == "content-id") {
                    val cid = (h.value ?: "").trim().trim('<', '>').trim()
                    if (cid.isNotEmpty()) out[cid.lowercase()] = CidPart(aid, mime)
                }
            }
            // Some senders reference the part by filename rather than Content-ID.
            val fn = (part.filename ?: "").trim()
            if (fn.isNotEmpty() && !out.containsKey(fn.lowercase())) {
                out[fn.lowercase()] = CidPart(aid, mime)
            }
        }
        for (sub in part.parts) walk(sub)
    }
    walk(payload)
    return out
}

// The reader's WebView serves this host out of shouldInterceptRequest, straight
// from the Gmail attachment endpoint. https so WebView actually asks us.
const val CID_HOST = "cid.inboxclone.invalid"
const val ATTACH_HOST = "attach.inboxclone.invalid"
const val ORIGINAL_HOST = "original.inboxclone.invalid"

private val cidSrcRx = Regex(
    """(<img\b[^>]*?\bsrc\s*=\s*)(["']?)cid:([^"'\s>]+)\2""",
    setOf(RegexOption.IGNORE_CASE),
)

// Repoint <img src="cid:..."> at the host the reader intercepts, so embedded
// images render instead of showing as broken.
fun resolveInlineImages(html: String, messageId: String, payload: GPayload?): String {
    if (html.isEmpty() || !html.contains("cid:", ignoreCase = true)) return html
    val cids = inlineCidMap(payload)
    if (cids.isEmpty()) return html
    return cidSrcRx.replace(html) { m ->
        val key = decodeEntities(m.groupValues[3]).trim().lowercase()
        val hit = cids[key]
        if (hit == null) m.value
        else m.groupValues[1] + "\"https://" + CID_HOST + "/" + messageId + "/" +
            hit.attachmentId + "?mime=" + pct(hit.mime) + "\""
    }
}

// MARK: Quoted reply history (port of split_quoted_html)

private val quoteMarkers = listOf(
    """<div[^>]+class="[^"]*gmail_quote""",
    """<blockquote[^>]+type="cite"""",
    """<blockquote[^>]+class="[^"]*gmail_quote""",
    """<div[^>]+id="appendonsend"""",
    """<div[^>]+id="divRplyFwdMsg"""",
    """<div[^>]+id="mail-editor-reference-message-container"""",
    """<div[^>]+id="ymail_android_signature"""",
).map { Regex(it, setOf(RegexOption.IGNORE_CASE)) }

private val tagStripRx = Regex("""<[^>]+>""")

data class SplitBody(val visible: String, val quoted: String?)

fun splitQuotedHtml(html: String): SplitBody {
    if (html.isEmpty()) return SplitBody(html, null)
    var cut: Int? = null
    for (pat in quoteMarkers) {
        val m = pat.find(html) ?: continue
        if (cut == null || m.range.first < cut!!) cut = m.range.first
    }
    val at = cut ?: return SplitBody(html, null)
    val visible = html.substring(0, at)
    val quoted = html.substring(at)
    // Never hide everything: a quote-only body (pure forward) stays untrimmed.
    val textOnly = tagStripRx.replace(visible, " ")
    if (textOnly.isBlank() && !visible.contains("<img", ignoreCase = true)) {
        return SplitBody(html, null)
    }
    return SplitBody(visible, quoted)
}

internal fun stripTags(s: String): String = decodeEntities(tagStripRx.replace(s, " "))

// MARK: RFC 2822 building (port of _build_raw_message, plain-text bodies)

fun encodeHeaderText(s: String): String {
    if (s.all { it.code < 128 }) return s
    val b64 = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
    return "=?UTF-8?B?$b64?="
}

fun formatFromAddr(name: String, email: String): String {
    if (name.isEmpty()) return email
    if (!name.all { it.code < 128 }) return "${encodeHeaderText(name)} <$email>"
    if (name.any { it in "\",:;<>@[]()" }) {
        return "\"${name.replace("\"", "")}\" <$email>"
    }
    return "$name <$email>"
}

fun buildRawMessage(
    to: String,
    cc: String?,
    subject: String,
    body: String,
    fromName: String,
    fromEmail: String,
    inReplyTo: String?,
    references: String?,
    messageId: String = "<${UUID.randomUUID()}@inboxclone.invalid>",
    date: Long = System.currentTimeMillis(),
): String {
    for (header in listOf(to, cc.orEmpty(), subject, fromName, fromEmail, inReplyTo.orEmpty(), references.orEmpty(), messageId)) {
        require(!header.contains('\r') && !header.contains('\n')) { "Email headers cannot contain line breaks." }
    }
    val lines = mutableListOf<String>()
    lines.add("To: $to")
    if (!cc.isNullOrEmpty()) lines.add("Cc: $cc")
    if (fromEmail.isNotEmpty()) lines.add("From: ${formatFromAddr(fromName, fromEmail)}")
    lines.add("Subject: ${encodeHeaderText(subject)}")
    lines.add("Message-ID: $messageId")
    lines.add("Date: ${DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(date).atZone(ZoneOffset.UTC))}")
    if (!inReplyTo.isNullOrEmpty()) {
        lines.add("In-Reply-To: $inReplyTo")
        // RFC 2822 wants the full ancestry; fall back to just the parent id
        lines.add("References: ${if (!references.isNullOrEmpty()) references else inReplyTo}")
    }
    lines.add("MIME-Version: 1.0")
    lines.add("Content-Type: text/plain; charset=\"utf-8\"")
    lines.add("Content-Transfer-Encoding: base64")
    lines.add("")
    val b64 = Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(body.toByteArray(Charsets.UTF_8))
    val raw = lines.joinToString("\r\n") + "\r\n" + b64 + "\r\n"
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
}
