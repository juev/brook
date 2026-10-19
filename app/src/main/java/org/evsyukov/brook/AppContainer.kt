package org.evsyukov.brook

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.evsyukov.brook.data.account.AccountManager
import org.evsyukov.brook.data.account.KeystoreTokenCipher
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Objects shared by the whole app; the ones of an account come from [accounts]. */
class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val accounts = AccountManager(
        context = context,
        http = http,
        cipher = KeystoreTokenCipher(),
        cancelWork = { tag -> WorkManager.getInstance(context).cancelAllWorkByTag(tag) },
    )

    private val _syncing = MutableStateFlow<Set<String>>(emptySet())

    /** Accounts with a sync running. */
    val syncing: StateFlow<Set<String>> = _syncing.asStateFlow()

    /** Syncs the account in the background of the app; a failure is logged and waits for the next sync. */
    fun requestSync(accountId: String) {
        scope.launch {
            _syncing.update { it + accountId }
            try {
                accounts.session(accountId).sync.sync()
            } catch (e: IOException) {
                Log.w(TAG, "Sync of $accountId failed", e)
            } finally {
                _syncing.update { it - accountId }
            }
        }
    }

    private companion object {
        const val TAG = "Brook"
    }
}
