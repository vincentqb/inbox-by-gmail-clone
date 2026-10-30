package com.baestheorem.inbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baestheorem.inbox.data.Connectivity
import com.baestheorem.inbox.data.DraftStatus
import com.baestheorem.inbox.data.LocalDraft
import com.baestheorem.inbox.data.MailMode
import com.baestheorem.inbox.data.MailStore
import com.baestheorem.inbox.data.OfflineMail
import kotlinx.coroutines.launch
import java.io.IOException

@Composable
fun DraftList(store: MailStore, outbox: Boolean, onEdit: (String) -> Unit) {
    val all by OfflineMail.drafts.collectAsState()
    val online by Connectivity.online.collectAsState()
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf<String?>(null) }
    var retryConfirmation by remember { mutableStateOf<LocalDraft?>(null) }
    val drafts = all.filter { (it.status != DraftStatus.DRAFT) == outbox }

    fun retry(draft: LocalDraft) {
        working = draft.id
        scope.launch {
            try {
                OfflineMail.retry(draft)
                store.showSnack("Checked Sent; any unsent message is queued")
            } catch (e: IOException) {
                store.showSnack("Couldn't check Sent: ${e.message}")
            } finally {
                working = null
            }
        }
    }

    if (drafts.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
            Text(
                if (outbox) "Outbox is empty. Tap Send in a draft to queue a message."
                else "No saved drafts. Tap + to write an email, even offline.",
                style = robotoStyle(15), color = Theme.textSecondary,
            )
        }
    } else {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            items(drafts, key = { it.id }) { draft ->
                Column(
                    Modifier.fillMaxWidth().background(Theme.cardBg)
                        .clickable(enabled = !outbox) { onEdit(draft.id) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(draft.subject.ifBlank { "(no subject)" }, style = robotoStyle(15, FontWeight.Medium),
                        color = Theme.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("To: ${draft.to.ifBlank { "(no recipient)" }}", style = robotoStyle(13),
                        color = Theme.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (draft.body.isNotBlank()) Text(draft.body, style = robotoStyle(13),
                        color = Theme.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (outbox) {
                        val label = when (draft.status) {
                            DraftStatus.QUEUED -> if (online) "Queued for send" else "Queued · waiting for connection"
                            DraftStatus.SENDING -> "Sending…"
                            DraftStatus.RETRY -> "Waiting to retry"
                            DraftStatus.FAILED -> "Gmail rejected this message"
                            DraftStatus.UNKNOWN -> "Delivery unconfirmed"
                            else -> ""
                        }
                        Text(label, style = robotoStyle(13, FontWeight.Medium),
                            color = if (draft.status in setOf(DraftStatus.FAILED, DraftStatus.UNKNOWN))
                                Theme.fabRed else Theme.blue, modifier = Modifier.padding(top = 8.dp))
                        draft.error?.let { Text(it, style = robotoStyle(12), color = Theme.textSecondary) }
                        Row {
                            if (draft.status in setOf(DraftStatus.RETRY, DraftStatus.FAILED, DraftStatus.UNKNOWN)) {
                                TextButton(enabled = online && working == null, onClick = {
                                    if (draft.status == DraftStatus.UNKNOWN) retryConfirmation = draft else retry(draft)
                                }) { Text(if (working == draft.id) "Checking…" else "Retry", color = Theme.blue) }
                            }
                            if (draft.status == DraftStatus.UNKNOWN) {
                                TextButton(enabled = online && working == null, onClick = {
                                    store.switchMode(MailMode.Search("in:sent rfc822msgid:${draft.rfcMessageId.trim('<', '>')}"))
                                }) { Text("Check Sent", color = Theme.blue) }
                            } else if (draft.status != DraftStatus.SENDING) {
                                TextButton(enabled = working == null, onClick = {
                                    working = draft.id
                                    val pending = OfflineMail.editQueued(draft)
                                    scope.launch {
                                        try {
                                            pending.await()
                                            if (OfflineMail.load(draft.id).await()?.status == DraftStatus.DRAFT) onEdit(draft.id)
                                            else store.showSnack("Sending already started")
                                        } catch (e: IOException) {
                                            store.showSnack("Couldn't return message to Drafts")
                                        } finally { working = null }
                                    }
                                }) { Text("Return to Drafts", color = Theme.textSecondary) }
                            }
                        }
                    }
                }
            }
        }
    }
    retryConfirmation?.let { draft ->
        AlertDialog(
            onDismissRequest = { retryConfirmation = null },
            title = { Text("Retry an unconfirmed delivery?") },
            text = { Text("Check Sent first. Gmail may already have delivered this email. " +
                "The app will check again before retrying, but a duplicate is still possible.") },
            confirmButton = {
                TextButton(onClick = { retryConfirmation = null; retry(draft) }) { Text("Check and retry") }
            },
            dismissButton = { TextButton(onClick = { retryConfirmation = null }) { Text("Cancel") } },
        )
    }
}
