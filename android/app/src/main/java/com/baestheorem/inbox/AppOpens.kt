package com.baestheorem.inbox

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Counts app opens: a cold start or a return from the background, never a rotation.
 * MainActivity reports its start and stop. Process-wide, so the sign-in prompt is offered
 * at most once per open even when the activity is recreated.
 */
object AppOpens {
    private val _opens = MutableStateFlow(0)
    val opens: StateFlow<Int> = _opens

    @Volatile var foreground = false
        private set

    /** When the current open began. */
    @Volatile var openedAt = 0L
        private set

    /** The open in which sign-in was last offered, automatically or by the user. */
    @Volatile var promptedOpen = 0

    private var backgrounded = true

    @Synchronized
    fun onStart() {
        foreground = true
        if (backgrounded) {
            backgrounded = false
            openedAt = System.currentTimeMillis()
            _opens.value++
        }
    }

    @Synchronized
    fun onStop(changingConfigurations: Boolean) {
        foreground = false
        if (!changingConfigurations) backgrounded = true
    }
}
