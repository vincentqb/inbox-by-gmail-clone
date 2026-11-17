package com.baestheorem.inbox.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max
import kotlin.math.min

/** Gmail quota units per call, from Gmail's usage limits as revised on May 1, 2026. */
object GmailCost {
    const val PROFILE = 1
    const val LABELS = 1
    const val LIST = 5
    const val HISTORY = 2
    const val MINIMAL = 20
    const val FULL = 60
    const val ATTACHMENT = 20
}

/**
 * Background sync spends Gmail quota at a pace the app's own screens can live with.
 * Every request a user makes counts against one per-user budget: 6,000 units a minute for
 * projects on Gmail's May 2026 limits, where one full message costs 60. The sync starts at
 * half of that, halves on any rate limit (the screens' own included), and recovers by half
 * again per quiet minute. It goes above the starting pace only while the app is in the
 * background, which lets projects still on Gmail's older, larger quota download faster.
 * Interactive requests are never paced.
 */
class SyncPacer(
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val rateLimitedAt: () -> Long = { 0L },
    private val foreground: () -> Boolean = { false },
) {
    private val lock = Mutex()
    var unitsPerMinute = START
        private set
    private var tokens = capacity()
    private var refilledAt = clock()
    private var seenLimit = rateLimitedAt()
    private var steadySince = clock()

    suspend fun acquire(units: Int) = lock.withLock {
        while (true) {
            adapt()
            refill()
            if (tokens >= units) {
                tokens -= units
                return@withLock
            }
            sleep(max(10L, ((units - tokens) / unitsPerMinute * 60_000).toLong()))
        }
    }

    private fun capacity(): Double = max(unitsPerMinute / 6, 2.0 * GmailCost.FULL) // ten seconds' worth

    private fun adapt() {
        val now = clock()
        val limit = rateLimitedAt()
        val ceiling = if (foreground()) START else MAX
        if (limit > seenLimit) {
            seenLimit = limit
            unitsPerMinute = max(MIN, unitsPerMinute / 2)
            tokens = min(tokens, 0.0) // let the minute's budget recover first
            steadySince = now
        } else if (now - steadySince >= 60_000) {
            unitsPerMinute = min(ceiling, unitsPerMinute * 1.5)
            steadySince = now
        }
        unitsPerMinute = min(unitsPerMinute, ceiling)
    }

    private fun refill() {
        val now = clock()
        tokens = min(capacity(), tokens + (now - refilledAt) / 60_000.0 * unitsPerMinute)
        refilledAt = now
    }

    companion object {
        const val START = 3_000.0
        const val MIN = 600.0
        const val MAX = 60_000.0
    }
}
