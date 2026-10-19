package org.evsyukov.brook.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Settings of the app, the same for every account. */
data class Settings(
    /** Minutes between background syncs. */
    val syncIntervalMinutes: Int = 30,
    /** Pictures and full texts are downloaded ahead only on Wi-Fi and other unmetered networks. */
    val prefetchOnlyUnmetered: Boolean = true,
    /** Read entries are kept this long after they were read; starred ones always. */
    val readRetentionDays: Int = 30,
    /** A notification tells about new entries a background sync brought. */
    val notifyNewEntries: Boolean = false,
) {
    companion object {
        val SYNC_INTERVALS = listOf(15, 30, 60)
        val RETENTION_DAYS = listOf(7, 30, 90)
    }
}

class AppSettings(context: Context, scope: CoroutineScope, file: String = "settings") {
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { context.preferencesDataStoreFile(file) },
    )

    /** The settings now; defaults until the file is read. */
    val current: StateFlow<Settings> = store.data
        .map { prefs ->
            val defaults = Settings()
            Settings(
                syncIntervalMinutes = prefs[SYNC_INTERVAL] ?: defaults.syncIntervalMinutes,
                prefetchOnlyUnmetered = prefs[PREFETCH_UNMETERED] ?: defaults.prefetchOnlyUnmetered,
                readRetentionDays = prefs[RETENTION_DAYS] ?: defaults.readRetentionDays,
                notifyNewEntries = prefs[NOTIFY] ?: defaults.notifyNewEntries,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, Settings())

    suspend fun setSyncInterval(minutes: Int) = store.edit { it[SYNC_INTERVAL] = minutes }
    suspend fun setPrefetchOnlyUnmetered(value: Boolean) = store.edit { it[PREFETCH_UNMETERED] = value }
    suspend fun setReadRetentionDays(days: Int) = store.edit { it[RETENTION_DAYS] = days }
    suspend fun setNotifyNewEntries(value: Boolean) = store.edit { it[NOTIFY] = value }

    private companion object {
        val SYNC_INTERVAL = intPreferencesKey("sync_interval_minutes")
        val PREFETCH_UNMETERED = booleanPreferencesKey("prefetch_only_unmetered")
        val RETENTION_DAYS = intPreferencesKey("read_retention_days")
        val NOTIFY = booleanPreferencesKey("notify_new_entries")
    }
}
