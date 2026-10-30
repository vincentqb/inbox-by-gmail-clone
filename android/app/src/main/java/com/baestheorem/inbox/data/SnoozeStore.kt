package com.baestheorem.inbox.data

import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.baestheorem.inbox.gmail.GmailClient
import com.baestheorem.inbox.work.SnoozeWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class SnoozeRecord(val threadId: String, val wake: Long, val subject: String)

/**
 * Phone-local half of the snooze scheduler. Label ops (remove INBOX, add
 * Snoozed) live in Gmail like the Mac server's; the wake time lives here plus a
 * WorkManager job that does the flip and posts the banner. Snoozes made on the
 * Mac stay the Mac scheduler's job (they are in its snooze.db); the two never
 * fight because each side only wakes its own records.
 */
object SnoozeStore {
    private const val TAG = "SnoozeStore"
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var appContext: Context
    private lateinit var file: File

    private val _records = MutableStateFlow<List<SnoozeRecord>>(emptyList())
    val records: StateFlow<List<SnoozeRecord>> = _records

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        file = File(appContext.filesDir, "snoozes.json")
        _records.value = try {
            if (file.exists()) json.decodeFromString(ListSerializer(SnoozeRecord.serializer()), file.readText())
            else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save() {
        try {
            file.writeText(json.encodeToString(ListSerializer(SnoozeRecord.serializer()), _records.value))
        } catch (e: IOException) {
            Log.w(TAG, "could not persist snoozes", e)
        }
    }

    fun wake(threadId: String): Long? = _records.value.firstOrNull { it.threadId == threadId }?.wake

    fun add(threadId: String, wake: Long, subject: String) {
        // re-snooze supersedes
        _records.value = _records.value.filterNot { it.threadId == threadId } +
            SnoozeRecord(threadId, wake, subject)
        save()
        val delay = (wake - System.currentTimeMillis()).coerceAtLeast(0)
        val work = OneTimeWorkRequestBuilder<SnoozeWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(
                Data.Builder()
                    .putString(SnoozeWorker.KEY_THREAD_ID, threadId)
                    .putString(SnoozeWorker.KEY_SUBJECT, subject)
                    .build()
            )
            .build()
        WorkManager.getInstance(appContext)
            .enqueueUniqueWork(workName(threadId), ExistingWorkPolicy.REPLACE, work)
    }

    fun remove(threadId: String) {
        _records.value = _records.value.filterNot { it.threadId == threadId }
        save()
        WorkManager.getInstance(appContext).cancelUniqueWork(workName(threadId))
    }

    fun clear() {
        _records.value.forEach { WorkManager.getInstance(appContext).cancelUniqueWork(workName(it.threadId)) }
        _records.value = emptyList()
        file.delete()
    }

    private fun workName(threadId: String) = "snooze-$threadId"

    /**
     * Re-file anything due: add INBOX back, drop the Snoozed label. Failed wakes
     * stay recorded and get retried on the next pass. Runs on foreground and from
     * the periodic worker, so a missed WorkManager job is not a lost snooze.
     */
    suspend fun processDue(): Int {
        val due = _records.value.filter { it.wake <= System.currentTimeMillis() }
        if (due.isEmpty()) return 0
        var woken = 0
        for (rec in due) {
            if (wakeThread(rec.threadId)) woken++
        }
        return woken
    }

    /** The label flip itself, shared with SnoozeWorker. */
    suspend fun wakeThread(threadId: String): Boolean = try {
        val sid = GmailClient.ensureLabel("Snoozed")
        GmailClient.modifyThread(threadId, add = listOf("INBOX"), remove = listOf(sid))
        remove(threadId)
        true
    } catch (e: IOException) {
        Log.w(TAG, "wake failed for $threadId", e)
        false
    }
}
