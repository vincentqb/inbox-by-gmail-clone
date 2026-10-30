package com.baestheorem.inbox.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.baestheorem.inbox.auth.AuthStore
import com.baestheorem.inbox.data.AccountChangedException
import com.baestheorem.inbox.data.OfflineMail
import com.baestheorem.inbox.gmail.AuthExpiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Mail downloads and delivery continue independently of notification settings. */
object MailWork {
    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

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

    fun sync(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "offline-mail-now", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<OfflineMailWorker>().setConstraints(connected).build(),
        )
    }

    fun send(context: Context) {
        // A send queued while a worker is exiting must still get its own wakeup.
        WorkManager.getInstance(context).enqueueUniqueWork(
            "outbox-now", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<OutboxWorker>().setConstraints(connected).build(),
        )
    }

    fun cancel(context: Context) {
        val manager = WorkManager.getInstance(context)
        listOf("offline-mail-periodic", "outbox-periodic", "offline-mail-now", "outbox-now")
            .forEach(manager::cancelUniqueWork)
    }
}

class OfflineMailWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!AuthStore.isSignedIn || AuthStore.account.isBlank()) return@withContext Result.success()
        try {
            if (OfflineMail.sync(System.currentTimeMillis() + 8 * 60_000)) Result.success() else Result.retry()
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
