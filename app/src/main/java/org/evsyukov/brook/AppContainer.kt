package org.evsyukov.brook

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.evsyukov.brook.data.account.AccountManager
import org.evsyukov.brook.data.account.KeystoreTokenCipher
import org.evsyukov.brook.data.settings.AppSettings
import org.evsyukov.brook.push.PushManager
import org.evsyukov.brook.push.PushStore
import org.evsyukov.brook.push.UnifiedPushDistributor
import org.evsyukov.brook.sync.NewEntriesDetector
import org.evsyukov.brook.sync.SyncResult
import org.evsyukov.brook.work.BackgroundWork
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Objects shared by the whole app; the ones of an account come from [accounts]. */
class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val workManager by lazy { WorkManager.getInstance(context) }

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val settings = AppSettings(context, scope)

    val accounts = AccountManager(
        context = context,
        http = http,
        cipher = KeystoreTokenCipher(),
        cancelWork = { tag -> workManager.cancelAllWorkByTag(tag) },
        retentionMillis = { TimeUnit.DAYS.toMillis(settings.current.value.readRetentionDays.toLong()) },
    )

    private val _syncing = MutableStateFlow<Set<String>>(emptySet())

    /** Accounts with a sync running. */
    val syncing: StateFlow<Set<String>> = _syncing.asStateFlow()

    private val detectors = ConcurrentHashMap<String, NewEntriesDetector>()

    val push = PushManager(
        distributor = UnifiedPushDistributor(context),
        store = PushStore(context, scope),
        api = { id -> accounts.session(id).api },
        accounts = { accounts.accounts.first() },
        enabled = { settings.stored.first().push },
        onPush = { id, kind -> BackgroundWork.enqueuePushSync(workManager, id, kind) },
    )

    init {
        // The interval comes from the settings; a change reschedules the periodic sync.
        scope.launch {
            settings.current.map { it.syncIntervalMinutes }.distinctUntilChanged().collect { minutes ->
                BackgroundWork.schedulePeriodicSync(workManager, minutes)
            }
        }
        // Registering again at every start also repairs a registration the distributor lost.
        scope.launch {
            combine(settings.stored.map { it.push }, accounts.accounts.map { list -> list.map { it.id } }) { on, ids -> on to ids }
                .distinctUntilChanged()
                .collect {
                    try {
                        push.reconcile()
                    } catch (e: IOException) {
                        Log.w(TAG, "Push setup failed", e)
                    }
                }
        }
    }

    /** Removes the account after its server and the distributor forget its push subscription. */
    suspend fun removeAccount(id: String) {
        try {
            push.forget(id)
        } catch (e: IOException) {
            Log.w(TAG, "Push of $id was not taken back", e)
        }
        accounts.remove(id)
    }

    /** Syncs the account and then queues the download of its pictures and full texts. */
    suspend fun sync(accountId: String): SyncResult {
        _syncing.update { it + accountId }
        try {
            val result = accounts.session(accountId).sync.sync()
            BackgroundWork.enqueuePrefetch(workManager, accountId, settings.current.value.prefetchOnlyUnmetered)
            return result
        } finally {
            _syncing.update { it - accountId }
        }
    }

    /** Syncs the account in the background of the app; a failure is logged and waits for the next sync. */
    fun requestSync(accountId: String) {
        scope.launch {
            try {
                sync(accountId)
            } catch (e: IOException) {
                Log.w(TAG, "Sync of $accountId failed", e)
            }
        }
    }

    /**
     * Asks the server whether anything changed since the last check and syncs when it did (R4).
     * The first check after [watch] starts only records the answer.
     */
    suspend fun poll(accountId: String) {
        val counts = try {
            accounts.session(accountId).api.unreadCounts()
        } catch (e: IOException) {
            return
        }
        if (detectors.getOrPut(accountId, ::NewEntriesDetector).changed(counts)) requestSync(accountId)
    }

    /**
     * Keeps the account fresh while it is on screen: a sync at once, then a check of `unread-count`
     * every [interval]; returns only when cancelled.
     */
    suspend fun watch(accountId: String, interval: Long = TimeUnit.SECONDS.toMillis(60)) = withContext(Dispatchers.Default) {
        detectors.getOrPut(accountId, ::NewEntriesDetector).reset()
        try {
            sync(accountId)
        } catch (e: IOException) {
            Log.w(TAG, "Sync of $accountId failed", e)
        }
        while (true) {
            poll(accountId)
            delay(interval)
        }
    }

    /** Queues the download again, after the settings changed or a feed was set to full text. */
    fun requestPrefetch(accountId: String) {
        BackgroundWork.enqueuePrefetch(workManager, accountId, settings.current.value.prefetchOnlyUnmetered)
    }

    private companion object {
        const val TAG = "Brook"
    }
}
