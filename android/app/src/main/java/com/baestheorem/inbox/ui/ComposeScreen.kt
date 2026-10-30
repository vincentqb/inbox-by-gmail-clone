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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.baestheorem.inbox.data.DraftStatus
import com.baestheorem.inbox.data.LocalDraft
import com.baestheorem.inbox.data.MailStore
import com.baestheorem.inbox.data.OfflineMail
import kotlinx.coroutines.launch
import java.io.IOException

@Composable
fun ComposeScreen(store: MailStore, draftId: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var draft by remember(draftId) { mutableStateOf<LocalDraft?>(null) }
    var busy by remember(draftId) { mutableStateOf(false) }
    var saveState by remember(draftId) { mutableStateOf("Saved on this phone") }
    var editVersion by remember(draftId) { mutableStateOf(0) }
    var confirmDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(draftId) {
        try {
            val loaded = OfflineMail.load(draftId).await()
            if (loaded == null || loaded.status != DraftStatus.DRAFT) {
                store.showSnack("This message is already in Outbox or was removed")
                onClose()
            } else draft = loaded
        } catch (e: IOException) {
            store.showSnack("Couldn't open draft: ${e.message}")
            onClose()
        }
    }

    fun update(next: LocalDraft) {
        draft = next
        editVersion++
        val version = editVersion
        saveState = "Saving…"
        // The application owns the write, so leaving/rotating the screen
        // does not cancel it. Only the saved indicator belongs to the UI.
        val pending = OfflineMail.save(next)
        scope.launch {
            try {
                pending.await()
                if (version == editVersion) saveState = "Saved on this phone"
            } catch (e: IOException) {
                if (version == editVersion) saveState = "Couldn't save. Tap Save to retry."
            }
        }
    }

    fun saveAndClose() {
        if (busy) return
        val current = draft ?: return onClose()
        busy = true
        val pending = if (current.isEmpty) OfflineMail.discard(current) else OfflineMail.save(current)
        scope.launch {
            try {
                pending.await()
                if (!current.isEmpty) store.showSnack("Draft saved on this phone")
                onClose()
            } catch (e: IOException) {
                saveState = "Couldn't save. Tap Save to retry."
                store.showSnack("Couldn't save draft: ${e.message}")
                busy = false
            }
        }
    }

    BackHandler(onBack = ::saveAndClose)
    val current = draft
    val canSend = current?.to?.contains("@") == true && !busy
    Column(
        Modifier.fillMaxSize().background(Theme.cardBg)
            .statusBarsPadding().navigationBarsPadding().imePadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clickable(enabled = !busy, onClick = ::saveAndClose), Alignment.Center) {
                MIcon("close", size = 20)
            }
            Text(if (current?.threadId == null) "New message" else "Reply draft",
                style = robotoStyle(16), color = Theme.textPrimary)
            Spacer(Modifier.weight(1f))
            TextButton(enabled = current != null && !busy, onClick = ::saveAndClose) {
                Text("Save", color = Theme.blue)
            }
            Box(
                Modifier.size(40.dp).clickable(enabled = canSend) {
                    val sending = draft ?: return@clickable
                    busy = true
                    val pending = OfflineMail.queue(sending)
                    scope.launch {
                        try {
                            pending.await()
                            store.showSnack("Queued in Outbox")
                            onClose()
                        } catch (e: IOException) {
                            store.showSnack("Couldn't queue message: ${e.message}")
                            busy = false
                        }
                    }
                },
                Alignment.Center,
            ) {
                if (busy) CircularProgressIndicator(color = Theme.blue, modifier = Modifier.size(20.dp))
                else MIcon("send", size = 20, color = if (canSend) Theme.blue else Theme.textFaint)
            }
        }
        Divider()
        if (current == null) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = Theme.blue) }
        } else {
            Field("To", current.to, { update(current.copy(to = it)) }, KeyboardType.Email, !busy)
            Divider()
            Field("Cc", current.cc, { update(current.copy(cc = it)) }, KeyboardType.Email, !busy)
            Divider()
            Field("Subject", current.subject, { update(current.copy(subject = it)) }, KeyboardType.Text, !busy)
            Divider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(saveState, style = robotoStyle(12), color = Theme.textSecondary, modifier = Modifier.weight(1f))
                TextButton(enabled = !busy, onClick = { confirmDiscard = true }) {
                    Text("Discard", color = Theme.fabRed)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                BasicTextField(
                    value = current.body, onValueChange = { update(current.copy(body = it)) },
                    enabled = !busy,
                    textStyle = robotoStyle(15).copy(color = Theme.textPrimary),
                    cursorBrush = SolidColor(Theme.blue), modifier = Modifier.fillMaxSize(),
                )
                if (current.body.isEmpty()) Text("Compose email…", style = robotoStyle(15), color = Theme.textFaint)
            }
        }
    }
    if (confirmDiscard && current != null) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this draft?") },
            text = { Text("This removes the draft saved on your phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    busy = true
                    val pending = OfflineMail.discard(current)
                    scope.launch {
                        try {
                            pending.await()
                            onClose()
                        } catch (e: IOException) {
                            store.showSnack("Couldn't discard draft")
                            busy = false
                        }
                    }
                }) { Text("Discard", color = Theme.fabRed) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Field(label: String, value: String, onValue: (String) -> Unit, type: KeyboardType, enabled: Boolean) {
    Box(Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 16.dp), Alignment.CenterStart) {
        BasicTextField(
            value = value,
            onValueChange = onValue,
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = type),
            textStyle = robotoStyle(15).copy(color = Theme.textPrimary),
            cursorBrush = SolidColor(Theme.blue),
            modifier = Modifier.fillMaxWidth(),
        )
        if (value.isEmpty()) Text(label, style = robotoStyle(15), color = Theme.textFaint)
    }
}

@Composable
fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Theme.divider))
}
