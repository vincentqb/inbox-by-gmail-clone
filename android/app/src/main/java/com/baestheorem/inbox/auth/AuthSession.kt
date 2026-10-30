package com.baestheorem.inbox.auth

import android.content.Context
import android.content.Intent
import com.baestheorem.inbox.gmail.GmailClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one sign-in attempt the app can have in flight, held for the life of the
 * process rather than the wizard's composition. The consent page opens in a
 * Custom Tab, which stops the activity behind it; if that activity is then
 * recreated (rotation, a theme change, the system trimming it), a listener
 * owned by the composable would be closed with it and Google's redirect would
 * land on a dead port. This object keeps the port open across all of that and
 * the wizard just watches [state].
 */
object AuthSession {
    sealed class State {
        object Idle : State()
        data class Waiting(val authUrl: String) : State()
        data class Done(val account: String) : State()
        data class Failed(val message: String) : State()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var session: OAuthFlow.Session? = null
    private var job: Job? = null

    val inFlight: Boolean get() = job?.isActive == true

    /**
     * Binds the listener, opens Google's page through [open], and finishes
     * the exchange whenever the redirect comes back. The client in [AuthStore]
     * is the one used; [open] returns false when no browser could take the URL.
     */
    fun begin(context: Context, open: (String) -> Boolean) {
        if (inFlight) return
        val app = context.applicationContext
        job = scope.launch {
            val s = try {
                withContext(Dispatchers.IO) {
                    OAuthFlow.start(AuthStore.clientId, AuthStore.clientSecret, AuthStore.account)
                }
            } catch (e: Exception) {
                _state.value = State.Failed("Could not open a local listener for the sign-in: ${e.message}")
                return@launch
            }
            session = s
            if (!open(s.authUrl)) {
                s.close()
                session = null
                _state.value = State.Failed("No browser is installed, so Google's sign-in page cannot open.")
                return@launch
            }
            _state.value = State.Waiting(s.authUrl)
            try {
                val tokens = s.awaitTokens()
                // Until profile lookup identifies this grant, background work
                // must not pair the new token with the previous account.
                AuthStore.account = ""
                AuthStore.refreshToken = tokens.refreshToken
                GmailClient.resetForNewAccount()
                // The grant is already stored, so a flaky profile lookup must
                // not read as a failed sign-in; the inbox fills the name in later.
                val email = try {
                    withContext(Dispatchers.IO) { GmailClient.profileEmail() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ""
                }
                AuthStore.account = email
                _state.value = State.Done(email)
                bringAppForward(app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isActive) _state.value = State.Failed(e.message ?: "Sign-in failed.")
            } finally {
                session = null
            }
        }
    }

    /** Stops listening; the browser tab, if still open, just leads nowhere. */
    fun cancel() {
        session?.close()
        session = null
        job?.cancel()
        job = null
        _state.value = State.Idle
    }

    /** The wizard has shown a Done or Failed result; forget it. */
    fun consume() {
        if (!inFlight) _state.value = State.Idle
    }

    /**
     * The Custom Tab was opened inside this app's task, so relaunching the
     * main activity with CLEAR_TOP pops the tab and the user is back in Inbox
     * without hunting for it. Android permits the start because the app has
     * an activity in the foreground task's back stack. If a device refuses,
     * the tab's own "you can close this" page still covers it.
     */
    private fun bringAppForward(app: Context) {
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return
        launch.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        try {
            app.startActivity(launch)
        } catch (e: Exception) {
            // background-start policy on this build; nothing to do
        }
    }
}
