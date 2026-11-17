package com.baestheorem.inbox.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SyncPacerTest {
    private var clock = 0L
    private var limitedAt = 0L
    private var foreground = true
    private val pacer = SyncPacer(
        clock = { clock }, sleep = { clock += it }, rateLimitedAt = { limitedAt }, foreground = { foreground },
    )

    private fun fetchFull(count: Int) = runBlocking { repeat(count) { pacer.acquire(GmailCost.FULL) } }

    @Test fun startsAtHalfOfGmailsPerUserQuota() {
        fetchFull(8) // ten seconds of budget is available at once
        assertEquals(0L, clock)
        fetchFull(50) // then 3,000 units a minute: 50 full messages
        assertTrue("took $clock ms", clock in 58_000L..62_000L)
        assertEquals(SyncPacer.START, pacer.unitsPerMinute, 0.0)
    }

    @Test fun halvesOnARateLimitAndRecoversWhileQuiet() {
        fetchFull(10)
        limitedAt = clock
        fetchFull(1)
        assertEquals(SyncPacer.START / 2, pacer.unitsPerMinute, 0.0)
        fetchFull(40) // over a minute without a limit
        assertTrue(pacer.unitsPerMinute > SyncPacer.START / 2)
        fetchFull(200)
        assertEquals("never above the start while the app is open", SyncPacer.START, pacer.unitsPerMinute, 0.0)
    }

    @Test fun goesFasterOnlyInTheBackground() {
        foreground = false
        fetchFull(300)
        assertTrue(pacer.unitsPerMinute > SyncPacer.START)
        foreground = true
        fetchFull(1)
        assertEquals(SyncPacer.START, pacer.unitsPerMinute, 0.0)
    }

    @Test fun neverDropsBelowTheFloor() {
        repeat(20) {
            limitedAt = clock + 1
            clock += 1
            fetchFull(1)
        }
        assertEquals(SyncPacer.MIN, pacer.unitsPerMinute, 0.0)
    }
}
