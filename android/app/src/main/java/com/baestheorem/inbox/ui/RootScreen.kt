package com.baestheorem.inbox.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baestheorem.inbox.data.MailMode
import com.baestheorem.inbox.data.MailStore
import com.baestheorem.inbox.data.Connectivity
import com.baestheorem.inbox.data.DraftStatus
import com.baestheorem.inbox.data.OfflineMail
import com.baestheorem.inbox.gmail.ThreadSummary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

@Composable
fun RootScreen(store: MailStore, onSignedOut: () -> Unit, onReauthorize: () -> Unit) {
    val scope = rememberCoroutineScope()
    val mode by store.mode.collectAsState()
    val inbox by store.inbox.collectAsState()
    val snack by store.snack.collectAsState()
    val pinnedOnly by store.pinnedOnly.collectAsState()
    val email by store.userEmail.collectAsState()
    val deepLink by store.deepLinkThreadId.collectAsState()
    val offline by OfflineMail.status.collectAsState()
    val drafts by OfflineMail.drafts.collectAsState()
    val online by Connectivity.online.collectAsState()
    val expired by store.authExpired.collectAsState()

    var drawerOpen by remember { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchText by rememberSaveable { mutableStateOf("") }
    var readerThreadId by rememberSaveable { mutableStateOf<String?>(null) }
    var composeDraftId by rememberSaveable { mutableStateOf<String?>(null) }
    var creatingDraft by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var snoozeTarget by remember { mutableStateOf<ThreadSummary?>(null) }
    var unsubTarget by remember { mutableStateOf<ThreadSummary?>(null) }
    var previousAccount by remember { mutableStateOf(email) }

    LaunchedEffect(email) {
        if (previousAccount.isNotBlank() && previousAccount != email) {
            readerThreadId = null
            composeDraftId = null
            snoozeTarget = null
            unsubTarget = null
            settingsOpen = false
        }
        previousAccount = email
    }
    BackHandler(drawerOpen || searchOpen) {
        if (drawerOpen) drawerOpen = false
        else {
            searchOpen = false
            searchText = ""
            if (mode is MailMode.Search) store.switchMode(MailMode.Inbox)
        }
    }

    LaunchedEffect(deepLink) {
        deepLink?.let {
            drawerOpen = false
            readerThreadId = it
            store.deepLinkThreadId.value = null
        }
    }

    // Live sync while the list is on screen, same 60s tick as the iPhone build
    LaunchedEffect(readerThreadId, drawerOpen) {
        while (readerThreadId == null && !drawerOpen) {
            delay(60_000)
            store.refresh()
        }
    }

    Box(Modifier.fillMaxSize().background(Theme.pageBg)) {
        Column(Modifier.fillMaxSize()) {
            AppBar(
                title = mode.title,
                searchOpen = searchOpen,
                searchText = searchText,
                pinnedOnly = pinnedOnly,
                onSearchText = { searchText = it },
                onSubmitSearch = {
                    val q = searchText.trim()
                    if (q.isNotEmpty()) store.switchMode(MailMode.Search(q))
                },
                onOpenSearch = { searchOpen = true },
                onCloseSearch = {
                    searchOpen = false
                    searchText = ""
                    if (mode is MailMode.Search) store.switchMode(MailMode.Inbox)
                },
                onMenu = { drawerOpen = true },
                onTogglePinned = { store.pinnedOnly.value = !pinnedOnly },
            )
            val banner = when {
                expired -> "Sign in again · downloaded mail is available"
                !online -> "Offline · ${offline.available} emails saved on this phone"
                offline.syncing -> "Downloading mail · ${offline.complete} of ${offline.available} complete"
                offline.error != null -> "Mail download paused · open settings to retry"
                else -> null
            }
            banner?.let {
                Text(
                    it,
                    style = robotoStyle(12),
                    color = Theme.bannerText,
                    modifier = Modifier.fillMaxWidth().background(Theme.bannerBg)
                        .clickable { if (expired) onReauthorize() else settingsOpen = true }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            if (mode == MailMode.Drafts || mode == MailMode.Outbox) {
                DraftList(store, outbox = mode == MailMode.Outbox, onEdit = { composeDraftId = it })
            } else {
                MailList(
                    store = store,
                    onOpen = { readerThreadId = it.id },
                    onSnooze = { snoozeTarget = it },
                    onUnsubscribe = { unsubTarget = it },
                )
            }
        }

        // Compose FAB (red, 56dp, Material "add")
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 16.dp, bottom = 24.dp)
                .size(56.dp)
                .shadow(6.dp, CircleShape)
                .background(Theme.fabRed, CircleShape)
                .clickable(enabled = !creatingDraft) {
                    if (email.isBlank()) store.showSnack("Connect once to finish setting up your account")
                    else scope.launch {
                        creatingDraft = true
                        try { composeDraftId = store.newDraft().id }
                        catch (e: IOException) { store.showSnack(e.message ?: "Could not open a draft") }
                        finally { creatingDraft = false }
                    }
                },
            contentAlignment = Alignment.Center,
        ) { MIcon("add", size = 26, color = Color.White) }

        if (drawerOpen) {
            Drawer(
                store = store,
                email = email,
                mode = mode,
                bundles = inbox.bundles,
                inboxTotal = inbox.inboxTotal,
                draftCount = drafts.count { it.status == DraftStatus.DRAFT },
                outboxCount = drafts.count { it.status != DraftStatus.DRAFT },
                onClose = { drawerOpen = false },
                onSettings = {
                    drawerOpen = false
                    settingsOpen = true
                },
            )
        }

        AnimatedVisibility(
            visible = readerThreadId != null,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
        ) {
            readerThreadId?.let { tid ->
                ThreadScreen(
                    store = store,
                    threadId = tid,
                    onClose = { readerThreadId = null },
                    onSnooze = { snoozeTarget = it },
                )
            }
        }

        composeDraftId?.let { id -> ComposeScreen(store, id) { composeDraftId = null } }
        if (settingsOpen) {
            SettingsScreen(
                onClose = { settingsOpen = false },
                onReauthorize = onReauthorize,
                onSignedOut = {
                    settingsOpen = false
                    onSignedOut()
                },
            )
        }

        snoozeTarget?.let { t ->
            SnoozeSheet(onDismiss = { snoozeTarget = null }) { wake, desc ->
                if (store.requireConnection()) {
                    store.snoozeThread(t, wake, desc)
                    if (readerThreadId == t.id) readerThreadId = null
                }
            }
        }

        unsubTarget?.let { t ->
            UnsubDialog(
                target = t,
                onDismiss = { unsubTarget = null },
                onConfirm = {
                    t.unsub?.let { store.unsubscribe(it, t.sender) }
                    unsubTarget = null
                },
            )
        }

        // .snackbar: centered, 48dp, #323232, light-blue action
        snack?.let { s ->
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 24.dp)
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Theme.snackBg, RoundedCornerShape(4.dp))
                    .padding(start = 16.dp, end = if (s.hasUndo) 0.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    s.text,
                    style = robotoStyle(14),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (s.hasUndo) {
                    Text(
                        "UNDO",
                        style = robotoStyle(14, FontWeight.Medium),
                        color = Theme.snackAction,
                        modifier = Modifier
                            .clickable { store.snackUndoTapped() }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AppBar(
    title: String,
    searchOpen: Boolean,
    searchText: String,
    pinnedOnly: Boolean,
    onSearchText: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onMenu: () -> Unit,
    onTogglePinned: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    Row(
        Modifier
            .fillMaxWidth()
            .background(Theme.blue)
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (searchOpen) {
            BarIcon("arrow_back", onClick = onCloseSearch)
            Box(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = searchText,
                    onValueChange = onSearchText,
                    singleLine = true,
                    textStyle = robotoStyle(15).copy(color = Color.White),
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmitSearch() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (searchText.isEmpty()) {
                    Text("Search mail", style = robotoStyle(15), color = Color.White.copy(alpha = 0.7f))
                }
            }
            LaunchedEffect(Unit) { focus.requestFocus() }
        } else {
            BarIcon("menu", onClick = onMenu)
            Text(
                title,
                style = robotoStyle(20),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            // The iconic app-bar switch: blue knob, white pin that turns yellow
            Box(
                Modifier.size(width = 48.dp, height = 40.dp).clickable(onClick = onTogglePinned),
                contentAlignment = if (pinnedOnly) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(width = 44.dp, height = 16.dp)
                        .background(
                            if (pinnedOnly) Color.White.copy(alpha = 0.4f) else Color.Black.copy(alpha = 0.22f),
                            RoundedCornerShape(50),
                        )
                )
                Box(
                    Modifier.size(24.dp).background(Color(0xFF5E97F6), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    MIcon("push_pin", size = 14, color = if (pinnedOnly) Theme.pinYellow else Color.White)
                }
            }
            BarIcon("search", onClick = onOpenSearch)
        }
    }
}

@Composable
private fun BarIcon(name: String, color: Color = Color.White, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clickable(onClick = onClick), Alignment.Center) {
        MIcon(name, size = 22, color = color)
    }
}

/** White, 256dp, red active pill, plain account line. */
@Composable
private fun Drawer(
    store: MailStore,
    email: String,
    mode: MailMode,
    bundles: List<com.baestheorem.inbox.gmail.BundleGroup>,
    inboxTotal: Int?,
    draftCount: Int,
    outboxCount: Int,
    onClose: () -> Unit,
    onSettings: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(onClick = onClose)
        )
        Column(
            Modifier
                .width(256.dp)
                .fillMaxHeight()
                .background(Theme.cardBg)
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                email.ifEmpty { "Inbox" },
                style = robotoStyle(13),
                color = Theme.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 24.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
            )
            NavRow("Inbox", "inbox", inboxTotal, mode is MailMode.Inbox) {
                store.switchMode(MailMode.Inbox)
                onClose()
            }
            NavRow("Snoozed", "schedule", null, mode is MailMode.Snoozed) {
                store.switchMode(MailMode.Snoozed)
                onClose()
            }
            NavRow("Done", "done", null, mode is MailMode.Done) {
                store.switchMode(MailMode.Done)
                onClose()
            }
            NavRow("Sent", "send", null, mode is MailMode.Sent) {
                store.switchMode(MailMode.Sent)
                onClose()
            }
            NavRow("Drafts", "drafts", draftCount, mode is MailMode.Drafts) {
                store.switchMode(MailMode.Drafts)
                onClose()
            }
            NavRow("Outbox", "outbox", outboxCount, mode is MailMode.Outbox) {
                store.switchMode(MailMode.Outbox)
                onClose()
            }
            if (bundles.isNotEmpty()) {
                Text(
                    "BUNDLES",
                    style = robotoStyle(11, FontWeight.Medium),
                    color = Theme.textFaint,
                    modifier = Modifier.padding(start = 24.dp, top = 14.dp, bottom = 6.dp),
                )
                for (b in bundles) {
                    NavRow(
                        b.name, b.icon, b.items.size,
                        mode is MailMode.BundleFilter && mode.name == b.name,
                    ) {
                        store.switchMode(MailMode.BundleFilter(b.name))
                        onClose()
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Theme.divider).padding(vertical = 6.dp))
            NavRow("Settings", "settings", null, false, onSettings)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun NavRow(
    title: String,
    icon: String,
    count: Int?,
    active: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (active) Theme.fabRed else Theme.textSecondary
    Row(
        Modifier
            .fillMaxWidth()
            .padding(end = 10.dp)
            .height(44.dp)
            .background(
                if (active) Theme.navActiveBg else Color.Transparent,
                RoundedCornerShape(topEnd = 22.dp, bottomEnd = 22.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        MIcon(icon, size = 20, color = tint)
        Text(title, style = robotoStyle(14, FontWeight.Medium), color = tint)
        Spacer(Modifier.weight(1f))
        if (count != null) Text("$count", style = robotoStyle(13), color = tint)
    }
}

@Composable
private fun UnsubDialog(
    target: ThreadSummary,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Unsubscribe from ${target.sender}?", style = robotoStyle(17, FontWeight.Medium))
        },
        text = {
            Text(
                when (target.unsub?.method) {
                    com.baestheorem.inbox.gmail.UnsubMethod.ONE_CLICK ->
                        "Sends the one-click unsubscribe request straight to the sender."
                    com.baestheorem.inbox.gmail.UnsubMethod.MAILTO ->
                        "Sends an unsubscribe email from your address."
                    else -> "Opens the sender's unsubscribe page in your browser."
                },
                style = robotoStyle(14),
                color = Theme.textSecondary,
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                Text("Unsubscribe", color = Theme.blue)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = Theme.textSecondary)
            }
        },
    )
}
