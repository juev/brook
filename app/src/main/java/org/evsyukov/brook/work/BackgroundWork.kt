package org.evsyukov.brook.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.evsyukov.brook.BrookApplication
import org.evsyukov.brook.MainActivity
import org.evsyukov.brook.R
import org.evsyukov.brook.data.account.Account
import org.evsyukov.brook.data.account.AccountManager
import org.evsyukov.brook.push.PushKind
import org.evsyukov.brook.sync.Prefetcher
import org.evsyukov.brook.sync.SyncResult
import java.io.IOException
import java.util.concurrent.TimeUnit

/** WorkManager requests of the app. */
object BackgroundWork {
    const val SYNC = "sync"
    private const val ACCOUNT = "account"
    private const val KIND = "kind"

    /** The periodic sync of every account, needing a network; a new interval replaces the old one. */
    fun schedulePeriodicSync(workManager: WorkManager, minutes: Int) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(minutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniquePeriodicWork(SYNC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /**
     * Downloads pictures and full texts of the account (R3, R9) when the network allows:
     * an unmetered one, or any with [onlyUnmetered] off. A new request replaces a waiting or running one.
     */
    fun enqueuePrefetch(workManager: WorkManager, accountId: String, onlyUnmetered: Boolean) {
        val network = if (onlyUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = OneTimeWorkRequestBuilder<PrefetchWorker>()
            .setInputData(workDataOf(ACCOUNT to accountId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .addTag(AccountManager.workTag(accountId))
            .build()
        workManager.enqueueUniqueWork(prefetchName(accountId), ExistingWorkPolicy.REPLACE, request)
    }

    fun prefetchName(accountId: String) = "prefetch-$accountId"

    /**
     * Syncs the account a push message was for, at once if the system allows. Messages that come
     * while a sync runs queue one after it, so that what they announced is fetched.
     */
    fun enqueuePushSync(workManager: WorkManager, accountId: String, kind: PushKind) {
        val request = OneTimeWorkRequestBuilder<PushSyncWorker>()
            .setInputData(workDataOf(ACCOUNT to accountId, KIND to kind.name))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(AccountManager.workTag(accountId))
            .build()
        workManager.enqueueUniqueWork(pushSyncName(accountId), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun pushSyncName(accountId: String) = "push-$accountId"

    internal fun accountOf(params: WorkerParameters): String? = params.inputData.getString(ACCOUNT)

    internal fun kindOf(params: WorkerParameters): PushKind =
        PushKind.entries.firstOrNull { it.name == params.inputData.getString(KIND) } ?: PushKind.CHANGES
}

/** The number of new entries to tell about after the sync a push asked for; 0 tells nothing. */
internal suspend fun syncForPush(kind: PushKind, notify: Boolean, sync: suspend () -> SyncResult): Int {
    val result = sync()
    return if (kind == PushKind.ENTRIES && notify) result.newEntries else 0
}

/** Syncs every account; a failure of one does not stop the others. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as BrookApplication).container
        var failed = false
        for (account in container.accounts.accounts.first()) {
            try {
                val result = container.sync(account.id)
                if (result.newEntries > 0 && container.settings.current.value.notifyNewEntries) {
                    NewEntriesNotification.show(applicationContext, account, result.newEntries)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                failed = true
            }
        }
        return if (failed) Result.retry() else Result.success()
    }
}

/** Syncs the account a push message was for; a message of new entries may notify. */
class PushSyncWorker(context: Context, private val params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val accountId = BackgroundWork.accountOf(params) ?: return Result.failure()
        val container = (applicationContext as BrookApplication).container
        val account = container.accounts.accounts.first().firstOrNull { it.id == accountId } ?: return Result.success()
        return try {
            // The process often starts for the message: the stored settings, not the defaults.
            val count = syncForPush(BackgroundWork.kindOf(params), container.settings.stored.first().notifyNewEntries) {
                container.sync(accountId)
            }
            if (count > 0) NewEntriesNotification.show(applicationContext, account, count)
            Result.success()
        } catch (e: IOException) {
            Result.retry()
        }
    }
}

class PrefetchWorker(context: Context, private val params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val accountId = BackgroundWork.accountOf(params) ?: return Result.failure()
        val container = (applicationContext as BrookApplication).container
        if (container.accounts.accounts.first().none { it.id == accountId }) return Result.success()
        val session = container.accounts.session(accountId)
        Prefetcher(session.db, session.images, session.fullText).run(stopped = { isStopped })
        return Result.success()
    }
}

object NewEntriesNotification {
    private const val CHANNEL = "new_entries"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_DEFAULT)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun show(context: Context, account: Account, count: Int) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.resources.getQuantityString(R.plurals.notification_new_entries, count, count))
            .setContentText("${account.user} · ${account.host}")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(account.id.hashCode(), notification)
    }
}
