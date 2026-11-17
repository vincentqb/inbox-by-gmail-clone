package com.baestheorem.inbox.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.baestheorem.inbox.auth.AuthStore
import com.baestheorem.inbox.data.AccountChangedException
import com.baestheorem.inbox.data.OfflineMail
import com.baestheorem.inbox.gmail.AuthExpiredException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Mail downloads and delivery continue independently of notification settings. */
object MailWork {
    private const val SYNC_NOW = "offline-mail-now"
    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun schedule(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.enqueueUniquePeriodicWork(
            "offline-mail-periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<OfflineMailWorker>(15, TimeUnit.MINUTES)
                .setConstraints(connected).build(),
        )
        manager.enqueueUniquePeriodicWork(
            "outbox-periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<OutboxWorker>(15, TimeUnit.MINUTES)
                .setConstraints(connected).build(),
        )
        sync(context)
        send(context)
    }

    /**
     * Opening the app, reconnecting or opening a thread asks for a pass now. A pass that is
     * running or merely waiting for a network is left alone; one held back by retry backoff
     * (after a failed run) starts over, so it doesn't sit out the backoff.
     */
    fun sync(context: Context) {
        val app = context.applicationContext
        scope.launch { enqueueSync(app, ExistingWorkPolicy.KEEP) }
    }

    /** The time budget ran out mid-pass: queue the rest right away, with no retry backoff. */
    suspend fun continueSync(context: Context) = enqueueSync(context.applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)

    /** [policy] applies unless the queued pass is waiting out retry backoff; that one is replaced. */
    private suspend fun enqueueSync(app: Context, policy: ExistingWorkPolicy) = withContext(Dispatchers.IO) {
        val manager = WorkManager.getInstance(app)
        val backingOff = try {
            manager.getWorkInfosForUniqueWork(SYNC_NOW).get()
                .any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 }
        } catch (e: Exception) {
            false
        }
        manager.enqueueUniqueWork(SYNC_NOW, if (backingOff) ExistingWorkPolicy.REPLACE else policy, syncRequest())
    }

    private fun syncRequest() = OneTimeWorkRequestBuilder<OfflineMailWorker>().setConstraints(connected).build()

    fun send(context: Context) {
        // A send queued while a worker is exiting must still get its own wakeup.
        WorkManager.getInstance(context).enqueueUniqueWork(
            "outbox-now", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<OutboxWorker>().setConstraints(connected).build(),
        )
    }

    fun cancel(context: Context) {
        val manager = WorkManager.getInstance(context)
        listOf("offline-mail-periodic", "outbox-periodic", SYNC_NOW, "outbox-now")
            .forEach(manager::cancelUniqueWork)
    }
}

class OfflineMailWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!AuthStore.isSignedIn || AuthStore.account.isBlank()) return@withContext Result.success()
        try {
            // WorkManager stops a worker after 10 minutes. Running out of time isn't a
            // failure, so it gets a follow-up run instead of a retry with backoff.
            if (!OfflineMail.sync(System.currentTimeMillis() + 8 * 60_000)) MailWork.continueSync(applicationContext)
            Result.success()
        } catch (e: AccountChangedException) {
            Result.success()
        } catch (e: AuthExpiredException) {
            Result.success() // preserve downloaded mail; sign-in/reconnect starts a new pass
        } catch (e: IOException) {
            Result.retry()
        }
    }
}

class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!AuthStore.isSignedIn || AuthStore.account.isBlank()) return@withContext Result.success()
        try {
            if (OfflineMail.sendPending()) Result.retry() else Result.success()
        } catch (e: AccountChangedException) {
            Result.success()
        } catch (e: IOException) {
            Result.retry()
        }
    }
}
