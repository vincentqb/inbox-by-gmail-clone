package com.baestheorem.inbox.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.baestheorem.inbox.auth.AuthStore
import com.baestheorem.inbox.auth.OAuthFlow
import com.baestheorem.inbox.data.Prefs
import com.baestheorem.inbox.data.OfflineMail
import com.baestheorem.inbox.data.DraftStatus
import com.baestheorem.inbox.data.Connectivity
import com.baestheorem.inbox.gmail.GmailClient
import com.baestheorem.inbox.data.SwipeActionKind
import com.baestheorem.inbox.data.SwipeConfig
import com.baestheorem.inbox.data.SwipeSlot
import com.baestheorem.inbox.work.NewMailWorker
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(onClose: () -> Unit, onSignedOut: () -> Unit, onReauthorize: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val slots by SwipeConfig.slots.collectAsState()
    val offline by OfflineMail.status.collectAsState()
    val drafts by OfflineMail.drafts.collectAsState()
    val online by Connectivity.online.collectAsState()
    val expired by GmailClient.authExpired.collectAsState()
    var notify by remember { mutableStateOf(Prefs.notifyNewMail) }
    var resetRequested by remember { mutableStateOf<Boolean?>(null) }
    var clearing by remember { mutableStateOf(false) }
    BackHandler { if (!clearing) onClose() }

    resetRequested?.let { reset ->
        AlertDialog(
            onDismissRequest = { resetRequested = null },
            title = { Text(if (reset) "Reset setup?" else "Sign out?") },
            text = {
                val unsent = drafts.count { it.status != DraftStatus.SENT }
                Text(
                    "This removes downloaded mail and $unsent local drafts or queued messages from this phone. " +
                        "Mail already in Gmail stays there.",
                    style = robotoStyle(14),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    resetRequested = null
                    clearing = true
                    val token = AuthStore.refreshToken
                    if (reset) AuthStore.forgetEverything() else AuthStore.signOut()
                    NewMailWorker.cancel(context)
                    scope.launch {
                        withContext(NonCancellable) {
                            try {
                                OfflineMail.clear()
                                GmailClient.resetForNewAccount()
                            } finally {
                                onSignedOut()
                            }
                        }
                    }
                    OfflineMail.scope.launch { OAuthFlow.revoke(token) }
                }) { Text(if (reset) "Reset" else "Sign out", color = Theme.fabRed) }
            },
            dismissButton = {
                TextButton(onClick = { resetRequested = null }) { Text("Keep local mail") }
            },
        )
    }

    Column(Modifier.fillMaxSize().background(Theme.cardBg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Settings", style = robotoStyle(16, FontWeight.Medium), color = Theme.textPrimary)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(40.dp).clickable(enabled = !clearing, onClick = onClose), Alignment.Center) {
                MIcon("close", size = 20)
            }
        }
        Divider()
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SectionLabel("Account")
            Text(
                AuthStore.account.ifEmpty { "Signed in" },
                style = robotoStyle(15),
                color = Theme.textPrimary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (AuthStore.ownProject) {
                    "Connected through your own Google Cloud project. If Google signs you " +
                        "out every 7 days, the project is still in Testing: open the Audience " +
                        "page in the console and press Publish app."
                } else {
                    "Connected through the Google Cloud project built into this app."
                },
                style = robotoStyle(12),
                color = Theme.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    "Sign out",
                    style = robotoStyle(14, FontWeight.Medium),
                    color = Theme.fabRed,
                    modifier = Modifier.clickable(enabled = !clearing) { resetRequested = false },
                )
                Spacer(Modifier.width(24.dp))
                Text(
                    "Reset setup",
                    style = robotoStyle(14, FontWeight.Medium),
                    color = Theme.textSecondary,
                    modifier = Modifier.clickable(enabled = !clearing) { resetRequested = true },
                )
            }

            SectionLabel("Offline mail")
            Text(
                "${offline.available} emails saved · ${offline.complete} fully downloaded",
                style = robotoStyle(15), color = Theme.textPrimary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                "Automatically downloads the last two calendar months, including attachments. " +
                    "Downloads and queued sends continue in the background when connected, using Wi-Fi or mobile data. " +
                    "Drafts are saved on this phone.",
                style = robotoStyle(12), color = Theme.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                when {
                    expired -> "Google sign-in expired. Downloaded mail and local drafts are still available."
                    !online -> "Offline. Downloads and queued sends resume after reconnecting."
                    offline.syncing -> "Downloading…"
                    offline.error != null -> offline.error ?: ""
                    offline.lastSync > 0 -> "Last completed sync: " +
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(offline.lastSync))
                    else -> "Waiting for the first download."
                },
                style = robotoStyle(12), color = Theme.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                if (expired) "Sign in again" else "Sync now",
                style = robotoStyle(14, FontWeight.Medium), color = Theme.blue,
                modifier = Modifier.clickable(enabled = !clearing) {
                    if (expired) onReauthorize() else OfflineMail.syncNow()
                }.padding(horizontal = 16.dp, vertical = 14.dp),
            )

            SectionLabel("Notifications")
            Row(
                Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("New mail banners", style = robotoStyle(15), color = Theme.textPrimary)
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = notify,
                    onCheckedChange = {
                        notify = it
                        Prefs.notifyNewMail = it
                        if (it) NewMailWorker.schedule(context) else NewMailWorker.cancel(context)
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = Theme.blue),
                )
            }
            Text(
                "Android wakes background work about every 15 minutes, so a banner can " +
                    "land a few minutes after the mail does. Android battery restrictions " +
                    "can delay background downloads, sends, and snoozed threads.",
                style = robotoStyle(12),
                color = Theme.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            SectionLabel("Swipes")
            for (slot in SwipeSlot.entries) {
                SlotRow(slot, slots[slot] ?: slot.defaultAction)
            }
            Text(
                "A short drag fires the first action; dragging most of the way across fires " +
                    "the full-swipe action (the color flips and the phone taps when you cross " +
                    "over). Swipes apply in Inbox, bundles, and search. Snoozed and Done keep " +
                    "swipe right as Move to Inbox.",
                style = robotoStyle(12),
                color = Theme.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
            Text(
                "Reset swipes to defaults",
                style = robotoStyle(14, FontWeight.Medium),
                color = Theme.blue,
                modifier = Modifier
                    .clickable { SwipeConfig.reset() }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = robotoStyle(12, FontWeight.Medium),
        color = Theme.textSecondary,
        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun SlotRow(slot: SwipeSlot, current: SwipeActionKind) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MIcon(
            if (slot.key.contains("leading")) "arrow_forward" else "arrow_back",
            size = 18,
        )
        Spacer(Modifier.width(10.dp))
        Text(slot.title, style = robotoStyle(15), color = Theme.textPrimary)
        Spacer(Modifier.weight(1f))
        Box {
            Row(
                Modifier
                    .background(Theme.pageBg, RoundedCornerShape(8.dp))
                    .clickable { open = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(26.dp).background(Color(current.color), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { MIcon(current.icon, size = 14, color = Color.White) }
                Spacer(Modifier.width(8.dp))
                Text(current.label, style = robotoStyle(14), color = Theme.textPrimary)
                Spacer(Modifier.width(6.dp))
                MIcon("unfold_more", size = 16)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (k in SwipeActionKind.configurable) {
                    DropdownMenuItem(
                        text = { Text(k.label, style = robotoStyle(14), color = Theme.textPrimary) },
                        leadingIcon = { MIcon(k.icon, size = 18, color = Color(k.color)) },
                        onClick = {
                            open = false
                            SwipeConfig.set(slot, k)
                        },
                    )
                }
            }
        }
    }
}
