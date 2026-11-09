package org.evsyukov.brook.data.social

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.evsyukov.brook.data.account.TokenCipher
import java.io.File

@Serializable
enum class SocialService { MASTODON, BLUESKY, LINKDING }

/** Credentials are never printed, including by exception/UI debugging. */
@Serializable
class SocialConnection(
    val id: String,
    val service: SocialService,
    val server: String,
    val userId: String,
    val displayName: String,
    val accessToken: String,
    val refreshToken: String = "",
    val enabled: Boolean = false,
) {
    fun withEnabled(value: Boolean) = SocialConnection(id, service, server, userId, displayName, accessToken, refreshToken, value)
    fun withId(value: String) = SocialConnection(value, service, server, userId, displayName, accessToken, refreshToken, enabled)
    override fun toString() = "SocialConnection($service, $displayName, enabled=$enabled)"
}

class SocialStore(context: Context, scope: CoroutineScope, private val cipher: TokenCipher, file: String = "social") {
    private val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(context.noBackupFilesDir, "$file.preferences_pb") })
    val connections: Flow<List<SocialConnection>> = store.data.map { prefs ->
        SocialService.entries.mapNotNull { service ->
            prefs[key(service)]?.let { Json.decodeFromString<SocialConnection>(cipher.decrypt(it)) }
        }
    }
    suspend fun save(connection: SocialConnection) {
        val sealed = cipher.encrypt(Json.encodeToString(connection))
        store.edit { it[key(connection.service)] = sealed }
    }
    suspend fun remove(service: SocialService) {
        store.edit { it.remove(key(service)) }
    }
    private fun key(service: SocialService) = stringPreferencesKey(service.name)
}