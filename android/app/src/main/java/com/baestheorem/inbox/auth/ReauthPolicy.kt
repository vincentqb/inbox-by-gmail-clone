package com.baestheorem.inbox.auth

/** When the app puts Google's sign-in in front of the user without being asked. */
object ReauthPolicy {
    /** How long after an app open a dead session still counts as found "on open". */
    const val OPEN_GRACE_MS = 30_000L

    /**
     * When the app opens ([open] counts opens) and Google has signed it out, with a
     * connection that could finish a new sign-in. Finding out can take a token refresh, so
     * the first [OPEN_GRACE_MS] after the open count. Later in the session (a draft may be
     * half written) the banner waits for the next open. Never while sign-in is showing or
     * in flight, and not again in an open where it was already offered.
     */
    fun shouldPrompt(
        expired: Boolean,
        online: Boolean,
        open: Int,
        promptedOpen: Int,
        sinceOpen: Long,
        showing: Boolean,
        inFlight: Boolean,
    ): Boolean = expired && online && open > 0 && open != promptedOpen && sinceOpen in 0..OPEN_GRACE_MS &&
        !showing && !inFlight
}
