package com.baestheorem.inbox.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.baestheorem.inbox.data.MailStore
import com.baestheorem.inbox.data.LocalDraft
import com.baestheorem.inbox.data.OfflineMail
import com.baestheorem.inbox.gmail.ATTACH_HOST
import com.baestheorem.inbox.gmail.ORIGINAL_HOST
import com.baestheorem.inbox.gmail.CID_HOST
import com.baestheorem.inbox.gmail.GmailClient
import com.baestheorem.inbox.gmail.ThreadDetail
import com.baestheorem.inbox.gmail.ThreadSummary
import com.baestheorem.inbox.gmail.UnsubInfo
import com.baestheorem.inbox.gmail.UnsubMethod
import com.baestheorem.inbox.gmail.gmailPermalink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

@Composable
fun ThreadScreen(
    store: MailStore,
    threadId: String,
    onClose: () -> Unit,
    onSnooze: (ThreadSummary) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var detail by remember(threadId) { mutableStateOf<ThreadDetail?>(null) }
    var pinned by remember(threadId) { mutableStateOf(false) }
    var replyDraft by remember(threadId) { mutableStateOf<LocalDraft?>(null) }
    var replyAll by remember(threadId) { mutableStateOf(false) }
    var replyEdited by remember(threadId) { mutableStateOf(false) }
    var saveState by remember(threadId) { mutableStateOf("") }
    var editVersion by remember(threadId) { mutableStateOf(0) }
    var sending by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmUnsub by remember { mutableStateOf(false) }
    var originalId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(threadId) {
        try {
            val d = store.loadThread(threadId)
            detail = d
            pinned = d.pinned
            val saved = store.loadReplyDraft(d)
            replyDraft = saved
            replyAll = saved.cc.isNotBlank()
            replyEdited = saved.body.isNotEmpty()
            if (replyEdited) saveState = "Saved on this phone"
        } catch (e: IOException) {
            store.showSnack(e.message ?: "Couldn't load thread")
            onClose()
        }
    }

    fun saveReply(next: LocalDraft) {
        replyDraft = next
        replyEdited = true
        editVersion++
        val version = editVersion
        saveState = "Saving…"
        val pending = OfflineMail.save(next)
        scope.launch {
            try {
                pending.await()
                if (version == editVersion) saveState = "Saved on this phone"
            } catch (e: IOException) {
                if (version == editVersion) saveState = "Couldn't save. Tap Save draft to retry."
            }
        }
    }

    fun saveAndClose() {
        if (sending) return
        val draft = replyDraft
        if (!replyEdited || draft == null) return onClose()
        sending = true
        val pending = OfflineMail.save(draft)
        scope.launch {
            try {
                pending.await()
                onClose()
            } catch (e: IOException) {
                sending = false
                saveState = "Couldn't save. Tap Save draft to retry."
                store.showSnack("Couldn't save reply: ${e.message}")
            }
        }
    }
    BackHandler(onBack = ::saveAndClose)

    // Reader actions need a list-row shape; build one from what the reader knows.
    fun stub(): ThreadSummary = ThreadSummary(
        id = threadId,
        messageId = detail?.messages?.lastOrNull()?.id ?: "",
        sender = detail?.messages?.firstOrNull()?.sender ?: "",
        senderEmail = detail?.messages?.firstOrNull()?.senderEmail ?: "",
        subject = detail?.subject ?: "(email)",
        snippet = "",
        time = "",
        ts = 0,
        unread = false,
        pinned = pinned,
        important = false,
        permalink = gmailPermalink(threadId),
    )

    Column(Modifier.fillMaxSize().background(Theme.pageBg)) {
        // .reader-bar: white, hairline bottom, gray icon buttons, green Done
        Row(
            Modifier
                .fillMaxWidth()
                .background(Theme.cardBg)
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarIcon("arrow_back") { saveAndClose() }
            Text(
                detail?.subject ?: "",
                style = robotoStyle(17),
                color = Theme.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            BarIcon("push_pin", if (pinned) Theme.pinYellow else Theme.textSecondary) {
                if (store.requireConnection()) {
                    store.togglePin(stub().copy(pinned = pinned))
                    pinned = !pinned
                }
            }
            BarIcon("playlist_add_check", Theme.doneGreen) {
                if (store.requireConnection()) {
                    store.markDone(stub())
                    saveAndClose()
                }
            }
            Box {
                BarIcon("more_vert") { menuOpen = true }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    MenuRow("Snooze", "schedule") {
                        menuOpen = false
                        if (store.requireConnection()) onSnooze(stub())
                    }
                    MenuRow("Share", "send") {
                        menuOpen = false
                        store.share(stub())
                    }
                    MenuRow("Mark unread", "markunread") {
                        menuOpen = false
                        if (store.requireConnection()) {
                            store.toggleUnread(stub().copy(unread = false))
                            saveAndClose()
                        }
                    }
                    MenuRow("Report spam", "report") {
                        menuOpen = false
                        if (store.requireConnection()) {
                            store.spam(stub())
                            saveAndClose()
                        }
                    }
                    MenuRow("Open in Gmail", "open_in_new") {
                        menuOpen = false
                        store.openUrl(gmailPermalink(threadId))
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Theme.divider))

        val d = detail
        if (d == null) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = Theme.blue) }
        } else {
            d.unsub?.let { u -> UnsubBanner(d, u) { confirmUnsub = true } }
            Box(Modifier.weight(1f)) { ThreadWebView(store, d) { originalId = it } }
            ReplyBar(
                store = store,
                detail = d,
                text = replyDraft?.body.orEmpty(),
                onText = { text -> replyDraft?.let { saveReply(it.copy(body = text)) } },
                replyAll = replyAll,
                onToggleReplyAll = {
                    replyAll = !replyAll
                    val template = store.replyTemplate(d, replyAll)
                    replyDraft?.let {
                        val next = it.copy(to = template.to, cc = template.cc)
                        if (replyEdited) saveReply(next) else replyDraft = next
                    }
                },
                ready = replyDraft != null,
                sending = sending,
                saveState = saveState,
                onSave = ::saveAndClose,
                onSend = {
                    val draft = replyDraft ?: return@ReplyBar
                    sending = true
                    val pending = OfflineMail.queue(draft)
                    scope.launch {
                        try {
                            pending.await()
                            editVersion++
                            replyDraft = store.replyTemplate(d, replyAll)
                            replyEdited = false
                            saveState = ""
                            store.showSnack("Queued in Outbox")
                        } catch (e: IOException) {
                            store.showSnack("Couldn't queue reply: ${e.message}")
                        }
                        sending = false
                    }
                },
            )
        }
    }

    originalId?.let { OriginalDialog(store, it) { originalId = null } }

    if (confirmUnsub && detail?.unsub != null) {
        val d = detail!!
        val u = d.unsub!!
        val sender = d.messages.firstOrNull()?.sender ?: "this sender"
        AlertDialog(
            onDismissRequest = { confirmUnsub = false },
            title = { Text("Unsubscribe from $sender?", style = robotoStyle(17, FontWeight.Medium)) },
            text = {
                Text(
                    when (u.method) {
                        UnsubMethod.ONE_CLICK -> "Sends the one-click unsubscribe request straight to the sender."
                        UnsubMethod.MAILTO -> "Sends an unsubscribe email from your address."
                        UnsubMethod.LINK -> "Opens the sender's unsubscribe page in your browser."
                    },
                    style = robotoStyle(14),
                    color = Theme.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnsub = false
                    store.unsubscribe(u, sender)
                }) { Text("Unsubscribe", color = Theme.blue) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnsub = false }) {
                    Text("Cancel", color = Theme.textSecondary)
                }
            },
        )
    }
}

@Composable
private fun BarIcon(name: String, color: Color = Theme.textSecondary, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { MIcon(name, size = 22, color = color) }
}

/** The yellow mailing-list banner with the blue filled button. */
@Composable
private fun UnsubBanner(d: ThreadDetail, u: UnsubInfo, onAct: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .background(Theme.bannerBg, RoundedCornerShape(8.dp))
            .border(1.dp, Theme.bannerBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MIcon("unsubscribe", size = 18, color = Theme.bannerIcon)
        Spacer(Modifier.width(10.dp))
        Text(
            "You're subscribed to this mailing list.",
            style = robotoStyle(13),
            color = Theme.bannerText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .background(Theme.blue, RoundedCornerShape(6.dp))
                .clickable(onClick = onAct)
                .padding(horizontal = 14.dp, vertical = 7.dp)
        ) {
            Text("Unsubscribe", style = robotoStyle(13, FontWeight.Medium), color = Color.White)
        }
    }
}

/** Bordered editor plus the 44dp blue send square. */
@Composable
private fun ReplyBar(
    store: MailStore,
    detail: ThreadDetail,
    text: String,
    onText: (String) -> Unit,
    replyAll: Boolean,
    onToggleReplyAll: () -> Unit,
    ready: Boolean,
    sending: Boolean,
    saveState: String,
    onSave: () -> Unit,
    onSend: () -> Unit,
) {
    val canSend = ready && text.isNotBlank() && !sending
    Column(Modifier.background(Theme.cardBg).imePadding().navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Theme.divider))
        if (saveState.isNotBlank()) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(saveState, style = robotoStyle(12), color = Theme.textSecondary, modifier = Modifier.weight(1f))
                TextButton(enabled = !sending, onClick = onSave) { Text("Save draft", color = Theme.blue) }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (store.replyAllAvailable(detail)) {
                Box(
                    Modifier.size(width = 36.dp, height = 44.dp)
                        .clickable(enabled = ready && !sending, onClick = onToggleReplyAll),
                    contentAlignment = Alignment.Center,
                ) {
                    MIcon("people", size = 20, color = if (replyAll) Theme.blue else Theme.textSecondary)
                }
                Spacer(Modifier.width(8.dp))
            }
            Box(
                Modifier
                    .weight(1f)
                    .background(Theme.cardBg, RoundedCornerShape(8.dp))
                    .border(1.dp, Theme.divider, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = onText,
                    enabled = ready && !sending,
                    textStyle = robotoStyle(14).copy(color = Theme.textPrimary),
                    cursorBrush = SolidColor(Theme.blue),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (text.isEmpty()) {
                    Text(
                        if (replyAll) "Reply all…" else "Reply…",
                        style = robotoStyle(14),
                        color = Theme.textFaint,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(44.dp)
                    .background(
                        if (canSend) Theme.blue else Theme.textSecondary.copy(alpha = 0.5f),
                        RoundedCornerShape(8.dp),
                    )
                    .clickable(enabled = canSend, onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                if (sending) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                } else {
                    MIcon("send", size = 20, color = Color.White)
                }
            }
        }
    }
}

/**
 * The reader WebView. Two hosts ride inside the document: CID_HOST is answered
 * from the Gmail attachment endpoint so embedded images render, and ATTACH_HOST
 * links are intercepted and handed to whatever app opens that file type.
 */
@Composable
private fun ThreadWebView(store: MailStore, detail: ThreadDetail, onOriginal: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loadedId = remember { mutableStateOf<String?>(null) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(Theme.pageBg.toArgb())
                settings.javaScriptEnabled = true // only our own quote-toggle script
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.blockNetworkLoads = false
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val url = request.url
                        if (url.host != CID_HOST) return null
                        val parts = url.pathSegments
                        if (parts.size < 2) return null
                        val mime = url.getQueryParameter("mime") ?: "image/png"
                        // These bytes arrived with the mail, so rendering them
                        // tells the sender nothing.
                        val data = runBlocking {
                            runCatching { GmailClient.attachmentData(parts[0], parts[1]) }.getOrNull()
                        } ?: byteArrayOf()
                        return WebResourceResponse(mime, null, ByteArrayInputStream(data))
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val url = request.url
                        if (url.host == ORIGINAL_HOST) {
                            url.pathSegments.firstOrNull()?.let(onOriginal)
                            return true
                        }
                        if (url.host == ATTACH_HOST) {
                            val parts = url.pathSegments
                            if (parts.size >= 2) {
                                openAttachment(
                                    store, scope, context,
                                    messageId = parts[0],
                                    attachmentId = parts[1],
                                    name = url.getQueryParameter("name") ?: "attachment",
                                    mime = url.getQueryParameter("mime") ?: "application/octet-stream",
                                )
                            }
                            return true
                        }
                        // Email links open in the browser, never inside the reader
                        store.openUrl(url.toString())
                        return true
                    }
                }
            }
        },
        update = { web ->
            // Track what is loaded in Compose state: View.setTag(key, _) only
            // accepts an application-specific resource id and throws otherwise.
            if (loadedId.value != detail.id) {
                loadedId.value = detail.id
                web.loadDataWithBaseURL(
                    "https://mail.google.com/",
                    buildThreadHtml(detail),
                    "text/html",
                    "utf-8",
                    null,
                )
            }
        },
    )
}

private fun openAttachment(
    store: MailStore,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    messageId: String,
    attachmentId: String,
    name: String,
    mime: String,
) {
    store.showSnack("Downloading $name")
    scope.launch {
        try {
            val bytes = GmailClient.attachmentData(messageId, attachmentId)
            val file = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
                File(dir, name.replace('/', '-')).apply { writeBytes(bytes) }
            }
            val uri: Uri = FileProvider.getUriForFile(
                context, "${context.packageName}.files", file,
            )
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            store.dismissSnack()
            try {
                context.startActivity(view)
            } catch (e: ActivityNotFoundException) {
                store.showSnack("No app can open $name")
            }
        } catch (e: IOException) {
            store.showSnack("Couldn't download attachment")
        }
    }
}

// Gmail's "Show original": the raw RFC 822 source, monospaced and selectable.
// A message with big attachments is megabytes of base64, so the view stops at
// the cap and Share carries the whole thing.
@Composable
private fun OriginalDialog(store: MailStore, messageId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var source by remember(messageId) { mutableStateOf<String?>(null) }
    var failed by remember(messageId) { mutableStateOf(false) }
    LaunchedEffect(messageId) {
        try {
            source = GmailClient.rawMessage(messageId)
        } catch (e: IOException) {
            failed = true
        }
    }
    val cap = 120_000
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Original message", style = robotoStyle(17, FontWeight.Medium)) },
        text = {
            val src = source
            when {
                src != null -> SelectionContainer {
                    Text(
                        if (src.length > cap) src.take(cap) + "\n\n[… ${src.length - cap} more characters, use Share for the full source]" else src,
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        modifier = Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
                    )
                }
                failed -> Text("Couldn't load the original message", color = Theme.textSecondary)
                else -> CircularProgressIndicator(color = Theme.blue)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = Theme.blue) } },
        dismissButton = {
            source?.let { src ->
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, src)
                    context.startActivity(Intent.createChooser(send, "Share original"))
                }) { Text("Share", color = Theme.blue) }
            }
        },
    )
}
