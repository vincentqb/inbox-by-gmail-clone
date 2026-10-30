package com.baestheorem.inbox.data

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * Port of the Mac's unsub_scan table: list rows arrive without bodies, so
 * senders that omit List-Unsubscribe but bury the opt-out in the body get their
 * body scanned once in the background, and the verdict is cached ("" = scanned,
 * nothing found) so the card link shows without re-downloading bodies.
 */
object UnsubScanCache {
    private const val CAP = 1500
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var file: File

    @Serializable
    private data class Store(val map: MutableMap<String, String> = LinkedHashMap())

    private var store = Store()
    private val queued = HashSet<String>()

    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.applicationContext.filesDir, "unsub-scan.json")
        store = try {
            if (file.exists()) json.decodeFromString(Store.serializer(), file.readText()) else Store()
        } catch (e: Exception) {
            Store()
        }
    }

    private fun save() {
        try {
            file.writeText(json.encodeToString(Store.serializer(), store))
        } catch (e: IOException) {
            Log.w("UnsubScanCache", "could not persist scan cache", e)
        }
    }

    /** null = never scanned; "" = scanned, no opt-out; else the https URL. */
    @Synchronized
    fun lookup(messageId: String): String? = store.map[messageId]

    @Synchronized
    fun put(messageId: String, url: String) {
        store.map[messageId] = url
        while (store.map.size > CAP) {
            val oldest = store.map.keys.firstOrNull() ?: break
            store.map.remove(oldest)
        }
        save()
    }

    /** Returns only ids not cached and not already queued this run. */
    @Synchronized
    fun claimForScan(ids: List<String>): List<String> {
        val fresh = ids.filter { it.isNotEmpty() && store.map[it] == null && !queued.contains(it) }
        queued.addAll(fresh)
        return fresh
    }

    @Synchronized
    fun clear() {
        store = Store()
        queued.clear()
        file.delete()
    }

    @Synchronized
    fun release(messageId: String) { queued.remove(messageId) }
}
