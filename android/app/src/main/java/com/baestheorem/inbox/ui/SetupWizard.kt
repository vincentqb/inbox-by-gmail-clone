package com.baestheorem.inbox.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.baestheorem.inbox.auth.AuthSession
import com.baestheorem.inbox.auth.AuthStore
import com.baestheorem.inbox.auth.ClientCredentials

private enum class Step { WELCOME, BYO, CONSENT, SIGNING_IN, DONE }

private const val URL_PROJECT = "https://console.cloud.google.com/projectcreate"
private const val URL_GMAIL_API = "https://console.cloud.google.com/apis/library/gmail.googleapis.com"
private const val URL_AUDIENCE = "https://console.cloud.google.com/auth/audience"
private const val URL_CLIENTS = "https://console.cloud.google.com/auth/clients"

/**
 * First-run setup. With an OAuth client baked into this build, the path is
 * Welcome, one heads-up about Google's unverified-app warning, and the
 * browser: two taps. Without one (or by choice) the wizard walks through
 * making your own Google Cloud project. Either way the app ends up holding a
 * refresh token for your account and nothing else.
 *
 * The sign-in itself runs in [AuthSession], which outlives this composable;
 * the wizard only mirrors its state, so coming back from the browser into a
 * recreated activity lands on the right screen.
 */
@Composable
fun SetupWizard(onDone: () -> Unit, reauth: Boolean = false, onCancel: () -> Unit = {}) {
    val context = LocalContext.current
    // Signing in again skips the explainer: Google's page opens at once, for the saved account.
    val direct = reauth && AuthStore.hasClient
    var step by rememberSaveable {
        mutableStateOf(if (direct) Step.SIGNING_IN else if (AuthStore.hasClient) Step.CONSENT else Step.WELCOME)
    }
    var started by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var pasted by rememberSaveable { mutableStateOf("") }
    var account by remember { mutableStateOf(AuthStore.account) }
    val auth by AuthSession.state.collectAsState()

    fun signIn() {
        error = null
        step = Step.SIGNING_IN
        AuthSession.begin(context) { openInBrowser(context, it) }
    }

    LaunchedEffect(auth) {
        when (val a = auth) {
            AuthSession.State.Idle -> when {
                direct && !started -> {
                    started = true
                    signIn()
                }
                step == Step.SIGNING_IN -> step = Step.CONSENT
            }
            is AuthSession.State.Waiting -> step = Step.SIGNING_IN
            is AuthSession.State.Done -> {
                account = a.account
                error = null
                AuthSession.consume()
                // Back to the mail the user was reading, the way other apps return after sign-in.
                if (reauth) onDone() else step = Step.DONE
            }
            is AuthSession.State.Failed -> {
                error = a.message
                step = Step.CONSENT
                AuthSession.consume()
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Theme.pageBg)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Spacer(Modifier.height(28.dp))
            when (step) {
                Step.WELCOME -> Welcome(
                    embedded = AuthStore.hasEmbeddedClient,
                    owner = AuthStore.embeddedOwner,
                    onSignIn = {
                        AuthStore.useEmbeddedClient()
                        error = null
                        step = Step.CONSENT
                    },
                    onOwn = { step = Step.BYO },
                )
                Step.BYO -> BringYourOwn(
                    pasted = pasted,
                    onPasted = { pasted = it },
                    error = error,
                    onOpen = { openInBrowser(context, it) },
                    onSubmit = {
                        try {
                            val parsed = ClientCredentials.parse(pasted)
                            AuthStore.setOwnClient(parsed.clientId, parsed.clientSecret)
                            error = null
                            step = Step.CONSENT
                        } catch (e: Exception) {
                            error = e.message
                        }
                    },
                )
                Step.CONSENT -> Consent(
                    ownProject = AuthStore.ownProject,
                    owner = AuthStore.embeddedOwner,
                    embeddedAvailable = AuthStore.hasEmbeddedClient,
                    error = error,
                    onSignIn = { signIn() },
                    onOwn = {
                        error = null
                        step = Step.BYO
                    },
                    onEmbedded = {
                        AuthStore.useEmbeddedClient()
                        error = null
                    },
                )
                Step.SIGNING_IN -> SigningIn(
                    onCancel = {
                        AuthSession.cancel()
                        if (reauth) onCancel() else step = Step.CONSENT
                    }
                )
                Step.DONE -> Done(account = account, onOpen = onDone)
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun Welcome(embedded: Boolean, owner: String, onSignIn: () -> Unit, onOwn: () -> Unit) {
    Column {
        MIcon("inbox", size = 56, color = Theme.blue)
        Spacer(Modifier.height(16.dp))
        Text("Inbox", style = robotoStyle(30, FontWeight.Medium), color = Theme.textPrimary)
        Spacer(Modifier.height(8.dp))
        Text(
            "A rebuild of Google Inbox on top of your own Gmail account: bundles, " +
                "pin, snooze, and done.",
            style = robotoStyle(15),
            color = Theme.textSecondary,
        )
        Spacer(Modifier.height(24.dp))
        Card {
            Text("What it does with your account", style = robotoStyle(14, FontWeight.Medium), color = Theme.textPrimary)
            Spacer(Modifier.height(10.dp))
            Bullet("Reads your mail and its labels, to show the inbox")
            Bullet("Moves labels around: archive, star, mark read, snooze, spam")
            Bullet("Sends mail you write, including replies and unsubscribe requests")
            Spacer(Modifier.height(10.dp))
            Text(
                "It never deletes anything: the delete scope is not requested. " +
                    "Your mail goes between your phone and Google, and nowhere else. " +
                    "There is no server in the middle.",
                style = robotoStyle(13),
                color = Theme.textSecondary,
            )
        }
        Spacer(Modifier.height(24.dp))
        if (embedded) {
            PrimaryButton("Sign in with Google", onSignIn)
            Spacer(Modifier.height(8.dp))
            Text(
                "Through " + owner.ifEmpty { "the app owner" } + "'s Google Cloud project. " +
                    "If that project is still in testing, your address has to be on its allowed list.",
                style = robotoStyle(12),
                color = Theme.textFaint,
            )
            Spacer(Modifier.height(6.dp))
            TextButton("Use my own Google Cloud project instead", onOwn)
        } else {
            PrimaryButton("Get started", onOwn)
        }
    }
}

@Composable
private fun BringYourOwn(
    pasted: String,
    onPasted: (String) -> Unit,
    error: String?,
    onOpen: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column {
        Heading("Make your own connection")
        Text(
            "Google only hands out Gmail access to an app registered in a Google Cloud " +
                "project. This makes one that belongs to you. It is free.",
            style = robotoStyle(15),
            color = Theme.textSecondary,
        )
        Spacer(Modifier.height(18.dp))
        WizardStep(1, "Create a project", "Any name. Takes a few seconds to provision.", "Open console", URL_PROJECT, onOpen)
        WizardStep(2, "Turn on the Gmail API", "Press Enable on the Gmail API page.", "Open Gmail API", URL_GMAIL_API, onOpen)
        WizardStep(
            3, "Set up the sign-in screen",
            "Under Audience, pick External, fill in the app name and your email, then add " +
                "your own Gmail address under Test users. While the app sits in Testing, " +
                "Google expires the sign-in every 7 days, so press Publish app when you are " +
                "done and it stops asking.",
            "Open Audience", URL_AUDIENCE, onOpen,
        )
        WizardStep(
            4, "Create the client",
            "Create client, application type Desktop app. Copy the client ID and secret, " +
                "or download the JSON.",
            "Open Clients", URL_CLIENTS, onOpen,
        )
        Spacer(Modifier.height(8.dp))
        Text("5. Paste it here", style = robotoStyle(15, FontWeight.Medium), color = Theme.textPrimary)
        Spacer(Modifier.height(4.dp))
        Text(
            "The whole downloaded JSON works, or the client ID on one line and the secret " +
                "on the next.",
            style = robotoStyle(13),
            color = Theme.textSecondary,
        )
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(Theme.cardBg, RoundedCornerShape(8.dp))
                .border(1.dp, Theme.divider, RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            BasicTextField(
                value = pasted,
                onValueChange = onPasted,
                textStyle = robotoStyle(13).copy(color = Theme.textPrimary),
                cursorBrush = SolidColor(Theme.blue),
                modifier = Modifier.fillMaxSize(),
            )
            if (pasted.isEmpty()) {
                Text(
                    "{\"installed\":{\"client_id\":\"…\"}}",
                    style = robotoStyle(13),
                    color = Theme.textFaint,
                )
            }
        }
        if (error != null) {
            Spacer(Modifier.height(10.dp))
            ErrorNote(error)
        }
        Spacer(Modifier.height(18.dp))
        PrimaryButton("Continue", onSubmit)
    }
}

@Composable
private fun Consent(
    ownProject: Boolean,
    owner: String,
    embeddedAvailable: Boolean,
    error: String?,
    onSignIn: () -> Unit,
    onOwn: () -> Unit,
    onEmbedded: () -> Unit,
) {
    Column {
        Heading("Sign in to Google")
        Text(
            "The next screen is Google's own, in your browser. Inbox never sees your password.",
            style = robotoStyle(15),
            color = Theme.textSecondary,
        )
        Spacer(Modifier.height(18.dp))
        Card {
            Text("Expect a scary-looking warning", style = robotoStyle(14, FontWeight.Medium), color = Theme.textPrimary)
            Spacer(Modifier.height(8.dp))
            Text(
                "Google shows \"Google hasn't verified this app\" for any app it has not " +
                    "reviewed, and this one is not going through review for two people. " +
                    "Press Advanced, then \"Go to Inbox (unsafe)\". " +
                    if (ownProject) "The app in that warning is the one you just created yourself."
                    else "The app in that warning is ${owner.ifEmpty { "the one" }}'s project.",
                style = robotoStyle(13),
                color = Theme.textSecondary,
            )
        }
        if (!ownProject) {
            Spacer(Modifier.height(12.dp))
            Card {
                Text(
                    "If Google says the app is blocked or your account is not allowed, " +
                        "your address still needs adding as a test user on that project. " +
                        "Ask for it, or make your own project below.",
                    style = robotoStyle(13),
                    color = Theme.textSecondary,
                )
            }
        }
        if (error != null) {
            Spacer(Modifier.height(12.dp))
            ErrorNote(error)
        }
        Spacer(Modifier.height(20.dp))
        PrimaryButton("Sign in with Google", onSignIn)
        Spacer(Modifier.height(10.dp))
        if (ownProject) {
            TextButton("Paste different credentials", onOwn)
            if (embeddedAvailable) TextButton("Use the built-in connection instead", onEmbedded)
        } else {
            TextButton("Use my own Google Cloud project instead", onOwn)
        }
    }
}

@Composable
private fun SigningIn(onCancel: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(60.dp))
        CircularProgressIndicator(color = Theme.blue)
        Spacer(Modifier.height(20.dp))
        Text("Waiting for Google", style = robotoStyle(16, FontWeight.Medium), color = Theme.textPrimary)
        Spacer(Modifier.height(8.dp))
        Text(
            "Finish in the browser tab that just opened. Inbox comes back on its own.",
            style = robotoStyle(14),
            color = Theme.textSecondary,
        )
        Spacer(Modifier.height(28.dp))
        TextButton("Cancel", onCancel)
    }
}

@Composable
private fun Done(account: String, onOpen: () -> Unit) {
    Column {
        MIcon("done", size = 48, color = Theme.doneGreen)
        Spacer(Modifier.height(14.dp))
        Heading("You're connected")
        Text(
            account.ifEmpty { "Your Gmail account is linked." },
            style = robotoStyle(15),
            color = Theme.textSecondary,
        )
        Spacer(Modifier.height(20.dp))
        Card {
            Text("Worth knowing", style = robotoStyle(14, FontWeight.Medium), color = Theme.textPrimary)
            Spacer(Modifier.height(8.dp))
            Bullet("Snooze creates a Gmail label called Snoozed and moves mail into it")
            Bullet("Done is Gmail's archive, Pin is Gmail's star, so other mail apps agree")
            Bullet("Revoke access any time at myaccount.google.com, Data and privacy, Third-party access")
        }
        Spacer(Modifier.height(22.dp))
        PrimaryButton("Open Inbox", onOpen)
    }
}

// MARK: Pieces

@Composable
private fun Heading(text: String) {
    Text(text, style = robotoStyle(26, FontWeight.Medium), color = Theme.textPrimary)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme.cardBg, RoundedCornerShape(8.dp))
            .border(1.dp, Theme.divider, RoundedCornerShape(8.dp))
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(bottom = 6.dp)) {
        Text("•", style = robotoStyle(13), color = Theme.textSecondary)
        Spacer(Modifier.width(8.dp))
        Text(text, style = robotoStyle(13), color = Theme.textSecondary)
    }
}

@Composable
private fun ChoiceCard(title: String, body: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme.cardBg, RoundedCornerShape(8.dp))
            .border(1.dp, Theme.divider, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = robotoStyle(16, FontWeight.Medium), color = Theme.textPrimary, modifier = Modifier.weight(1f))
            MIcon("arrow_forward", size = 20, color = Theme.blue)
        }
        Spacer(Modifier.height(6.dp))
        Text(body, style = robotoStyle(13), color = Theme.textSecondary)
    }
}

@Composable
private fun WizardStep(
    number: Int,
    title: String,
    body: String,
    action: String,
    url: String,
    onOpen: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Box(
            Modifier.size(24.dp).background(Theme.blue, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", style = robotoStyle(13, FontWeight.Medium), color = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = robotoStyle(15, FontWeight.Medium), color = Theme.textPrimary)
            Spacer(Modifier.height(3.dp))
            Text(body, style = robotoStyle(13), color = Theme.textSecondary)
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.clickable { onOpen(url) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(action, style = robotoStyle(13, FontWeight.Medium), color = Theme.blue)
                Spacer(Modifier.width(4.dp))
                MIcon("open_in_new", size = 14, color = Theme.blue)
            }
        }
    }
}

@Composable
private fun ErrorNote(text: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme.bannerBg, RoundedCornerShape(8.dp))
            .border(1.dp, Theme.bannerBorder, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Text(text, style = robotoStyle(13), color = Theme.bannerText)
    }
}

@Composable
fun PrimaryButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Theme.blue, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = robotoStyle(15, FontWeight.Medium), color = Color.White)
    }
}

@Composable
fun TextButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = robotoStyle(14, FontWeight.Medium), color = Theme.blue)
    }
}

/**
 * Custom Tab when a browser supports it, plain VIEW intent otherwise. From an
 * Activity the tab opens inside this app's task, which is what lets the
 * finished sign-in pop it and land back here; only a non-Activity context
 * needs a new task.
 */
fun openInBrowser(context: Context, url: String): Boolean {
    val uri = Uri.parse(url)
    val activity = context.findActivity()
    val extraFlags = if (activity == null) Intent.FLAG_ACTIVITY_NEW_TASK else 0
    return try {
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
            .also { it.intent.addFlags(extraFlags) }
            .launchUrl(context, uri)
        true
    } catch (e: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(extraFlags))
            true
        } catch (e2: ActivityNotFoundException) {
            false
        }
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
