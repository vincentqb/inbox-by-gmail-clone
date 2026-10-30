package com.baestheorem.inbox.gmail

import java.util.Base64
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.random.Random

// Direct port of app.py's parsing + classification layer by way of the iOS app
// (ios/Sources/Bundling.swift): header helpers, the bundle keyword classifier,
// highlight chips, time formatting, and the snooze presets. Keep behavior in
// lockstep with the Python originals; they are the reference implementation.

// MARK: Small text utilities

fun headerValue(headers: List<GHeader>, name: String): String =
    headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value ?: ""

private val addrRx = Regex("""\s*"?([^"<]*)"?\s*<([^>]+)>""")

data class Addr(val name: String, val email: String)

fun parseAddr(value: String): Addr {
    val m = addrRx.find(value)
    if (m != null) {
        val email = m.groupValues[2].trim()
        val name = m.groupValues[1].trim().ifEmpty { email }
        return Addr(name, email)
    }
    val t = value.trim()
    return Addr(t, t)
}

fun initialsFor(name: String): String {
    val parts = name.split(" ").filter { it.isNotEmpty() }
    val a = parts.firstOrNull()?.firstOrNull()?.toString() ?: "?"
    val b = if (parts.size > 1) parts[1].firstOrNull()?.toString() ?: "" else ""
    return (a + b).uppercase()
}

private val numericEntityRx = Regex("""&#(\d+);""")

fun decodeEntities(s: String): String {
    if (!s.contains("&")) return s
    var t = s
    val map = mapOf(
        "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"",
        "&#39;" to "'", "&apos;" to "'", "&nbsp;" to " ",
    )
    for ((k, v) in map) t = t.replace(k, v)
    return numericEntityRx.replace(t) { m ->
        val code = m.groupValues[1].toIntOrNull()
        if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else ""
    }
}

fun htmlEscape(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

// Percent-encode everything outside RFC 3986 unreserved, so the result is safe
// in a query value, a form body, or a custom-scheme URL alike.
fun pct(s: String): String {
    val out = StringBuilder()
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt().toChar()
        if (UNRESERVED.indexOf(c) >= 0) out.append(c)
        else out.append('%').append("%02X".format(b.toInt() and 0xFF))
    }
    return out.toString()
}

fun base64UrlDecode(s: String): ByteArray? = try {
    Base64.getUrlDecoder().decode(s)
} catch (e: IllegalArgumentException) {
    null
}

fun fmtBytes(n: Int): String = when {
    n < 1024 -> "$n B"
    n < 1_048_576 -> "${n / 1024} KB"
    else -> "%.1f MB".format(n / 1_048_576.0)
}

// MARK: Time formatting (port of _fmt_time, off internalDate)

private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
private val mdFmt = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
private val mdyFmt = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
private val wakeFmt = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.getDefault())
private val monthFmt = DateTimeFormatter.ofPattern("MMMM", Locale.getDefault())

private fun zdt(ms: Long): ZonedDateTime =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())

fun formatTime(ms: Long): String {
    val d = zdt(ms)
    val today = LocalDate.now()
    return when {
        d.toLocalDate() == today -> timeFmt.format(d)
        d.toLocalDate() == today.minusDays(1) -> "Yesterday"
        d.year == today.year -> mdFmt.format(d)
        else -> mdyFmt.format(d)
    }
}

fun formatWake(ms: Long): String = wakeFmt.format(zdt(ms))

// MARK: Gmail's "show details" rows (port of message_details)

private val fullDateFmt = DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a", Locale.getDefault())
private val mailfromRx = Regex("""smtp\.mailfrom=([^\s;]+)""")
private val dkimRx = Regex("""dkim=pass[^;]*?header\.(?:i|d)=@?([^\s;]+)""")
private val tlsRx = Regex("""version=TLS|Google Transport Security|\bE?SMTPS\b""")

private fun addrDomain(a: String): String =
    if ('@' in a) a.substringAfterLast('@').trim(' ', '<', '>').lowercase() else ""

// mailed-by is the SPF envelope domain (smtp.mailfrom, else Return-Path),
// signed-by the passing DKIM domain aligned with From, security is TLS when
// Google's own Received hop says so. Rows that don't apply are left out.
fun messageDetails(headers: List<GHeader>, ms: Long): List<DetailRow> {
    val fromDom = addrDomain(parseAddr(headerValue(headers, "From")).email)
    val auth = headers
        .filter { it.name.equals("authentication-results", ignoreCase = true) && (it.value ?: "").contains("mx.google.com") }
        .joinToString(" ") { it.value ?: "" }
    val mailedBy = mailfromRx.find(auth)?.groupValues?.get(1)?.let { v -> addrDomain(v).ifEmpty { v.lowercase() } }
        ?: if (auth.isNotEmpty()) addrDomain(headerValue(headers, "Return-Path")) else ""
    val signers = dkimRx.findAll(auth).map { it.groupValues[1].lowercase().substringAfterLast('@') }.toList()
    val signedBy = signers.firstOrNull { it == fromDom }
        ?: signers.firstOrNull { fromDom.endsWith(".$it") || it.endsWith(".$fromDom") }
        ?: signers.firstOrNull() ?: ""
    val hop = headers.firstOrNull {
        it.name.equals("received", ignoreCase = true) && (it.value ?: "").contains("by mx.google.com")
    }?.value
    val security = if (hop != null && tlsRx.containsMatchIn(hop)) "Standard encryption (TLS)" else ""
    val date = if (ms > 0) fullDateFmt.format(zdt(ms)) else ""
    return listOf(
        "from" to headerValue(headers, "From"), "reply-to" to headerValue(headers, "Reply-To"),
        "to" to headerValue(headers, "To"), "cc" to headerValue(headers, "Cc"),
        "bcc" to headerValue(headers, "Bcc"), "date" to date,
        "subject" to headerValue(headers, "Subject"), "mailed-by" to mailedBy,
        "signed-by" to signedBy, "security" to security,
    ).filter { it.second.isNotEmpty() }.map { DetailRow(it.first, it.second) }
}

fun dateBucket(ms: Long): String {
    val d = zdt(ms)
    val now = ZonedDateTime.now()
    val today = now.toLocalDate()
    val week = WeekFields.of(Locale.getDefault())
    return when {
        d.toLocalDate() == today -> "Today"
        d.toLocalDate() == today.minusDays(1) -> "Yesterday"
        d.year == now.year && d.get(week.weekOfWeekBasedYear()) == now.get(week.weekOfWeekBasedYear())
            && d.get(week.weekBasedYear()) == now.get(week.weekBasedYear()) -> "This week"
        d.year == now.year && d.month == now.month -> "This month"
        d.year == now.year -> monthFmt.format(d)
        else -> d.year.toString()
    }
}

// MARK: Bundles (native categories + keyword classifier + Bundle/* overrides)

val categoryBundles = mapOf(
    "CATEGORY_PROMOTIONS" to "Promos",
    "CATEGORY_SOCIAL" to "Social",
    "CATEGORY_UPDATES" to "Updates",
    "CATEGORY_FORUMS" to "Forums",
)

data class BundleMeta(val icon: String, val color: Long)

// Same Material icon names + colors as app.py's BUNDLE_META
object Bundles {
    val order = listOf("Travel", "Purchases", "Finance", "Promos", "Social", "Updates", "Forums")
    val meta = mapOf(
        "Travel" to BundleMeta("flight", 0xFF9C27B0),
        "Purchases" to BundleMeta("local_offer", 0xFF795548),
        "Finance" to BundleMeta("attach_money", 0xFF679F38),
        "Social" to BundleMeta("people", 0xFFDB4437),
        "Updates" to BundleMeta("notifications", 0xFFFF6839),
        "Promos" to BundleMeta("loyalty", 0xFF00BCD4),
        "Forums" to BundleMeta("forum", 0xFF3F51B5),
    )
}

private val ci = setOf(RegexOption.IGNORE_CASE)
private val travelRx = Regex("""\b(flight|itinerary|boarding|reservation|hotel|booking|check-?in|airline|trip|departure|gate)\b""", ci)
private val purchaseRx = Regex("""\b(order|shipped|delivery|tracking|receipt|your package|dispatched|invoice|out for delivery)\b""", ci)
private val financeRx = Regex("""\b(statement|payment|invoice|balance|transaction|deposit|bank|credit card|autopay|bill is)\b""", ci)
private val priceRx = Regex("""\$\s?\d[\d,]*(?:\.\d{2})?""")
private val trackRx = Regex("""\b(out for delivery|arriving today|arriving|delivered|has shipped|shipped|on its way|tracking)\b""", ci)
private val flightHlRx = Regex("""\b(flight|boarding|departs?|gate|check[- ]?in)\b""", ci)
private val orderRx = Regex("""\border\s*#?\s*[\w-]{3,}""", ci)

fun bundleFor(
    labels: List<String>,
    subject: String,
    sender: String,
    snippet: String,
    overrides: Map<String, String>,
): String? {
    // 1. User re-label override (Bundle/<name> Gmail label) wins over everything
    for (l in labels) {
        val b = overrides[l]
        if (b != null) return if (b == "Primary") null else b
    }
    // 2. Keyword heuristic
    val blob = "$subject $sender $snippet"
    if (travelRx.containsMatchIn(blob)) return "Travel"
    if (purchaseRx.containsMatchIn(blob)) return "Purchases"
    if (financeRx.containsMatchIn(blob)) return "Finance"
    // 3. Native Gmail category
    for (l in labels) categoryBundles[l]?.let { return it }
    return null
}

private fun capitalizeWords(s: String): String =
    s.split(" ").joinToString(" ") { w ->
        if (w.isEmpty()) w else w[0].uppercase() + w.substring(1).lowercase()
    }

fun computeHighlights(subject: String, snippet: String, bundle: String?): List<HighlightChip> {
    val blob = "$subject $snippet"
    val chips = mutableListOf<HighlightChip>()
    if (bundle == "Travel" || flightHlRx.containsMatchIn(blob)) {
        chips.add(HighlightChip("flight", "Travel"))
    }
    trackRx.find(blob)?.let { chips.add(HighlightChip("local_shipping", capitalizeWords(it.value))) }
    orderRx.find(blob)?.let { chips.add(HighlightChip("receipt_long", it.value.trim().take(22))) }
    priceRx.find(blob)?.let { chips.add(HighlightChip("payments", it.value.replace(" ", ""))) }
    return chips.distinctBy { it.icon }.take(2)
}

// MARK: Thread summaries (port of thread_summary / summarize_ids)

fun gmailPermalink(threadId: String): String =
    "https://mail.google.com/mail/u/0/#all/$threadId"

fun threadSummary(
    msg: GMessage,
    overrides: Map<String, String>,
    outgoing: Boolean,
): ThreadSummary {
    val headers = msg.payload?.headers ?: emptyList()
    val subject = headerValue(headers, "Subject").ifEmpty { "(no subject)" }
    val labels = msg.labelIds
    val snippet = decodeEntities(msg.snippet ?: "")
    val sender: String
    val senderEmail: String
    var bundle: String? = null
    var unsub: UnsubInfo? = null
    if (outgoing) {
        val a = parseAddr(headerValue(headers, "To"))
        sender = "To: " + a.name.ifEmpty { "(no recipient)" }
        senderEmail = a.email
    } else {
        val a = parseAddr(headerValue(headers, "From"))
        sender = a.name
        senderEmail = a.email
        bundle = bundleFor(labels, subject, a.name, snippet, overrides)
        unsub = parseUnsubscribe(headers)
    }
    // Bulk/promo mail treats inline images as decoration (hidden as attachments)
    val bulk = unsub != null || labels.any { categoryBundles.containsKey(it) }
    val ts = msg.internalDate?.toLongOrNull() ?: 0L
    val tid = msg.threadId ?: msg.id
    return ThreadSummary(
        id = tid,
        messageId = msg.id,
        sender = sender,
        senderEmail = senderEmail,
        subject = subject,
        snippet = snippet,
        time = formatTime(ts),
        ts = ts,
        unread = labels.contains("UNREAD"),
        pinned = labels.contains("STARRED"),
        important = labels.contains("IMPORTANT"),
        bundle = bundle,
        highlights = if (outgoing) emptyList() else computeHighlights(subject, snippet, bundle),
        attachments = collectAttachments(msg.payload, msg.id, hideInlineImages = bulk),
        unsub = unsub,
        permalink = gmailPermalink(tid),
    )
}

fun summarize(
    msgs: List<GMessage>,
    overrides: Map<String, String>,
    outgoing: Boolean,
): List<ThreadSummary> {
    // newest message per thread wins the row
    val byThread = HashMap<String, GMessage>()
    for (m in msgs) {
        val tid = m.threadId ?: m.id
        val prev = byThread[tid]
        if (prev == null ||
            (m.internalDate?.toLongOrNull() ?: 0L) > (prev.internalDate?.toLongOrNull() ?: 0L)
        ) {
            byThread[tid] = m
        }
    }
    return byThread.values
        .map { threadSummary(it, overrides, outgoing) }
        .sortedByDescending { it.ts }
}

// MARK: Snooze presets (port of resolve_snooze)

enum class SnoozePreset(val label: String, val icon: String) {
    LATER_TODAY("Later today", "wb_twilight"),
    TOMORROW("Tomorrow", "today"),
    THIS_WEEKEND("This weekend", "weekend"),
    NEXT_WEEK("Next week", "event"),
    SOMEDAY("Someday", "all_inclusive");

    fun resolve(now: ZonedDateTime = ZonedDateTime.now()): Long {
        // Python weekday (Mon=0..Sun=6)
        val wd = now.dayOfWeek.value - 1
        fun at8(daysAhead: Long): Long =
            LocalDateTime.of(now.toLocalDate().plusDays(daysAhead), LocalTime.of(8, 0))
                .atZone(now.zone).toInstant().toEpochMilli()
        return when (this) {
            LATER_TODAY -> now.toInstant().toEpochMilli() + 3 * 3600_000L
            TOMORROW -> at8(1)
            THIS_WEEKEND -> {
                var days = Math.floorMod(5 - wd, 7)
                if (days == 0) days = 7
                at8(days.toLong())
            }
            NEXT_WEEK -> {
                var days = Math.floorMod(7 - wd, 7)
                if (days == 0) days = 7
                at8(days.toLong())
            }
            SOMEDAY -> at8(Random.nextInt(30, 91).toLong())
        }
    }
}
