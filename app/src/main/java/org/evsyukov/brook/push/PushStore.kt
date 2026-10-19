package org.evsyukov.brook.push

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Where push of an account stands. */
enum class AccountPush {
    /** Waiting for the endpoint of the distributor, or for the server to take it. */
    CONNECTING,

    /** The server has the endpoint and pushes to it. */
    ON,

    /** The server answered that it does not push. */
    UNSUPPORTED,

    /** The user already has as many browsers and apps as the server allows. */
    TOO_MANY,

    /** The key, the distributor or the server failed; the next start tries again. */
    FAILED,
}

/** Push of one account: the VAPID key of its server, the endpoint the server has, the state. */
data class PushRecord(val key: String? = null, val endpoint: String? = null, val state: AccountPush = AccountPush.CONNECTING)

/** Push records of the accounts, kept across restarts so that a start does not subscribe again. */
class PushStore(context: Context, scope: CoroutineScope, file: String = "push") {
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { context.preferencesDataStoreFile(file) },
    )

    val records: Flow<Map<String, PushRecord>> = store.data.map(::records)

    suspend fun all(): Map<String, PushRecord> = records.first()

    suspend fun update(id: String, change: (PushRecord) -> PushRecord) {
        store.edit { prefs ->
            val record = change(records(prefs)[id] ?: PushRecord())
            prefs.put(key(id, KEY), record.key)
            prefs.put(key(id, ENDPOINT), record.endpoint)
            prefs[key(id, STATE)] = record.state.name
        }
    }

    suspend fun remove(id: String) {
        store.edit { prefs -> listOf(KEY, ENDPOINT, STATE).forEach { prefs.remove(key(id, it)) } }
    }

    private fun records(prefs: Preferences): Map<String, PushRecord> =
        prefs.asMap().keys.map { it.name.substringBeforeLast(SEPARATOR) }.distinct().associateWith { id ->
            PushRecord(
                key = prefs[key(id, KEY)],
                endpoint = prefs[key(id, ENDPOINT)],
                state = prefs[key(id, STATE)]?.let { name -> AccountPush.entries.firstOrNull { it.name == name } } ?: AccountPush.CONNECTING,
            )
        }

    private fun MutablePreferences.put(key: Preferences.Key<String>, value: String?) {
        if (value == null) remove(key) else this[key] = value
    }

    private companion object {
        const val SEPARATOR = "/"
        const val KEY = "key"
        const val ENDPOINT = "endpoint"
        const val STATE = "state"

        fun key(id: String, field: String) = stringPreferencesKey(id + SEPARATOR + field)
    }
}
