package com.baestheorem.inbox.auth

import org.junit.Assert.*
import org.junit.Test

class ReauthPolicyTest {
    private fun prompt(
        expired: Boolean = true, online: Boolean = true, open: Int = 1, promptedOpen: Int = 0,
        sinceOpen: Long = 2_000, showing: Boolean = false, inFlight: Boolean = false,
    ) = ReauthPolicy.shouldPrompt(expired, online, open, promptedOpen, sinceOpen, showing, inFlight)

    @Test fun promptsOncePerOpenWhenSignedOutAndOnline() {
        assertTrue(prompt())
        assertFalse("already offered in this open", prompt(promptedOpen = 1))
        assertTrue("the next open asks again", prompt(open = 2, promptedOpen = 1))
    }

    @Test fun onlyRightAfterTheAppOpens() {
        assertTrue("a token refresh can take a few seconds", prompt(sinceOpen = ReauthPolicy.OPEN_GRACE_MS))
        assertFalse("found out mid-session: the banner waits", prompt(sinceOpen = ReauthPolicy.OPEN_GRACE_MS + 1))
    }

    @Test fun staysQuietWhenItCannotHelp() {
        assertFalse(prompt(expired = false))
        assertFalse("sign-in needs a connection", prompt(online = false))
        assertFalse("before the first open", prompt(open = 0))
        assertFalse(prompt(showing = true))
        assertFalse(prompt(inFlight = true))
    }
}
