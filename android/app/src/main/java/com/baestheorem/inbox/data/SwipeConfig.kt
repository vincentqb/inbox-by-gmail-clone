package com.baestheorem.inbox.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The four customizable swipe zones: short and full swipe on each edge. Stored
 * per slot; the settings screen edits them live. Where the iPhone build sends a
 * thread to Things 3, this one shares it, since Things is Apple-only and every
 * Android task app takes a share intent.
 */
enum class SwipeActionKind(val label: String, val icon: String, val color: Long, val removesRow: Boolean) {
    DONE("Done", "done", 0xFF0F9D58, true),
    SNOOZE("Snooze", "schedule", 0xFFF4B400, false),
    PIN("Pin / Unpin", "push_pin", 0xFF4285F4, false),
    SHARE("Share", "send", 0xFF3367D6, false),
    SPAM("Report spam", "report", 0xFFD93025, true),
    UNREAD("Toggle read", "markunread", 0xFF5F6368, false),
    TO_INBOX("Move to Inbox", "move_to_inbox", 0xFF4285F4, true),
    NONE("Nothing", "block", 0xFF9AA0A6, false);

    companion object {
        /** The subset a user can assign to a slot. */
        val configurable = listOf(DONE, SNOOZE, PIN, SHARE, SPAM, UNREAD, NONE)
    }
}

enum class SwipeSlot(val key: String, val title: String, val defaultAction: SwipeActionKind) {
    LEADING_SHORT("swipe.leadingShort", "Swipe right", SwipeActionKind.DONE),
    LEADING_LONG("swipe.leadingLong", "Swipe right, full", SwipeActionKind.SHARE),
    TRAILING_SHORT("swipe.trailingShort", "Swipe left", SwipeActionKind.SNOOZE),
    TRAILING_LONG("swipe.trailingLong", "Swipe left, full", SwipeActionKind.PIN),
}

object SwipeConfig {
    private lateinit var prefs: SharedPreferences
    private val _slots = MutableStateFlow<Map<SwipeSlot, SwipeActionKind>>(emptyMap())
    val slots: StateFlow<Map<SwipeSlot, SwipeActionKind>> = _slots

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("inbox-swipes", Context.MODE_PRIVATE)
        _slots.value = SwipeSlot.entries.associateWith { slot ->
            val raw = prefs.getString(slot.key, null)
            runCatching { SwipeActionKind.valueOf(raw ?: "") }.getOrDefault(slot.defaultAction)
        }
    }

    fun kind(slot: SwipeSlot): SwipeActionKind = _slots.value[slot] ?: slot.defaultAction

    fun set(slot: SwipeSlot, kind: SwipeActionKind) {
        prefs.edit().putString(slot.key, kind.name).apply()
        _slots.value = _slots.value + (slot to kind)
    }

    fun reset() {
        for (slot in SwipeSlot.entries) set(slot, slot.defaultAction)
    }
}

/** Small app preferences that are not swipes. */
object Prefs {
    private lateinit var prefs: SharedPreferences
    private const val K_SEEN_UNREAD = "seenUnreadIds"
    private const val K_NOTIFY_NEW_MAIL = "notifyNewMail"

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("inbox-prefs", Context.MODE_PRIVATE)
    }

    var notifyNewMail: Boolean
        get() = prefs.getBoolean(K_NOTIFY_NEW_MAIL, true)
        set(v) = prefs.edit().putBoolean(K_NOTIFY_NEW_MAIL, v).apply()

    fun seenUnread(): Set<String> = prefs.getStringSet(K_SEEN_UNREAD, emptySet()) ?: emptySet()

    fun noteSeenUnread(ids: Collection<String>) {
        val merged = (seenUnread() + ids).toList().takeLast(500).toSet()
        prefs.edit().putStringSet(K_SEEN_UNREAD, merged).apply()
    }

    fun clearSeen() { prefs.edit().remove(K_SEEN_UNREAD).apply() }
}
