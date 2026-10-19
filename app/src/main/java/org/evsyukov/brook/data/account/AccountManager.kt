package org.evsyukov.brook.data.account

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.evsyukov.brook.data.EntryActions
import org.evsyukov.brook.data.TreeEditor
import org.evsyukov.brook.data.db.BrookDatabase
import org.evsyukov.brook.data.fulltext.FullTextExtractor
import org.evsyukov.brook.data.images.ImageCache
import org.evsyukov.brook.data.remote.GReaderClient
import org.evsyukov.brook.sync.SyncEngine
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A signed-in user of a server. [apiBase] is where the API answers, found at sign-in. */
data class Account(val id: String, val apiBase: String, val user: String) {
    /** The host of the server, for the account switcher. */
    val host: String get() = apiBase.toHttpUrl().host
}

/** What `ClientLogin` gave: where the API answers and the token for it. */
data class Credentials(val apiBase: HttpUrl, val auth: String)

/**
 * The account was removed, possibly while a sync or check of it was queued. An [IOException], so
 * that whatever handles a failed sync handles this one too.
 */
class AccountGoneException(id: String) : IOException("No account $id")

/** The objects one account works with; they live until the account is removed. */
class AccountSession(
    val account: Account,
    val db: BrookDatabase,
    val api: GReaderClient,
    val sync: SyncEngine,
    val actions: EntryActions,
    val editor: TreeEditor,
    val images: ImageCache,
    val fullText: FullTextExtractor,
)

/**
 * Accounts and the one in use (R10). Every account has its own Room file, its own DataStore with
 * the server, the user name and the token sealed by [cipher], and its own cache and files directories.
 * The list of accounts and the active one live in a DataStore of their own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountManager(
    private val context: Context,
    private val http: OkHttpClient,
    private val cipher: TokenCipher,
    private val cancelWork: (tag: String) -> Unit,
    /** The DataStore file of the list of accounts; tests running inside the app take another one. */
    private val registryFile: String = "accounts",
    /** How long read entries stay, from the settings. */
    private val retentionMillis: () -> Long = { TimeUnit.DAYS.toMillis(30) },
    private val signIn: suspend (server: HttpUrl, user: String, password: String) -> Credentials = { server, user, password ->
        GReaderClient.login(http, server, user, password).let { Credentials(it.apiBase, it.auth) }
    },
) {
    private val storesLock = Mutex()
    private val sessionsLock = Mutex()
    private val stores = HashMap<String, Store>()
    private val sessions = HashMap<String, AccountSession>()

    private class Store(val data: DataStore<Preferences>, val job: Job)

    private val registry: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        produceFile = { context.preferencesDataStoreFile(registryFile) },
    )

    /** Accounts in the order they were added. */
    val accounts: Flow<List<Account>> = registry.data
        .map { ids(it) }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(ids.map { id -> storeFlow(id) }) { it.filterNotNull() }
            }
        }

    /** The account in use; null only when there are none. */
    val active: Flow<Account?> = combine(accounts, registry.data.map { it[ACTIVE] }) { list, activeId ->
        list.firstOrNull { it.id == activeId } ?: list.firstOrNull()
    }.distinctUntilChanged()

    /**
     * Signs in and makes the account active. Signing in again as a user already added replaces the
     * token of that account and keeps its data.
     */
    suspend fun add(server: HttpUrl, user: String, password: String): Account {
        val credentials = signIn(server, user, password)
        val apiBase = credentials.apiBase.toString()
        val existing = accounts.first().firstOrNull { it.apiBase == apiBase && it.user == user }
        val id = existing?.id ?: UUID.randomUUID().toString()
        store(id).edit {
            it[SERVER] = apiBase
            it[USER] = user
            it[TOKEN] = cipher.encrypt(credentials.auth)
        }
        if (existing != null) {
            // The database stays open for whoever holds it; the client and sync get the new token.
            sessionsLock.withLock {
                sessions[id]?.let { old -> sessions[id] = newSession(old.account, old.db, credentials.auth) }
            }
        }
        registry.edit {
            val ids = ids(it)
            if (id !in ids) it[IDS] = (ids + id).joinToString(SEPARATOR)
            it[ACTIVE] = id
        }
        return Account(id, apiBase, user)
    }

    suspend fun setActive(id: String) {
        registry.edit { if (id in ids(it)) it[ACTIVE] = id }
    }

    /** The session of [id], opened on first use. */
    suspend fun session(id: String): AccountSession = sessionsLock.withLock {
        sessions[id]?.let { return it }
        // Not store(id) first: that would open the DataStore of a removed account again.
        if (id !in ids(registry.data.first())) throw AccountGoneException(id)
        val prefs = store(id).data.first()
        val account = account(id, prefs) ?: throw AccountGoneException(id)
        val token = cipher.decrypt(checkNotNull(prefs[TOKEN]))
        newSession(account, BrookDatabase.open(context, id), token).also { sessions[id] = it }
    }

    private fun newSession(account: Account, db: BrookDatabase, token: String): AccountSession {
        val api = GReaderClient(http, account.apiBase.toHttpUrl(), token)
        return AccountSession(
            account = account,
            db = db,
            api = api,
            sync = SyncEngine(db, api, retentionMillis),
            actions = EntryActions(db),
            editor = TreeEditor(db, api),
            images = ImageCache(File(cacheDir(account.id), "images"), http),
            fullText = FullTextExtractor(http),
        )
    }

    /** Removes the account with its database, settings, cache and background work. */
    suspend fun remove(id: String) {
        registry.edit {
            val ids = ids(it) - id
            it[IDS] = ids.joinToString(SEPARATOR)
            if (it[ACTIVE] == id) {
                ids.firstOrNull()?.let { next -> it[ACTIVE] = next } ?: it.remove(ACTIVE)
            }
        }
        cancelWork(workTag(id))
        sessionsLock.withLock { sessions.remove(id)?.db?.close() }
        storesLock.withLock { stores.remove(id) }?.job?.cancelAndJoin()
        withContext(Dispatchers.IO) {
            context.deleteDatabase(BrookDatabase.fileName(id))
            context.preferencesDataStoreFile(storeFile(id)).delete()
            cacheDir(id).deleteRecursively()
            filesDir(id).deleteRecursively()
        }
    }

    /** Pictures and other things of the account that can be downloaded again. */
    fun cacheDir(id: String) = File(context.cacheDir, "accounts/$id")

    /** Files of the account that cannot be downloaded again. */
    fun filesDir(id: String) = File(context.filesDir, "accounts/$id")

    // A store being removed stops with an error; the list has already left it out by then.
    private fun storeFlow(id: String): Flow<Account?> =
        flow { emitAll(store(id).data.map { account(id, it) }) }.catch { emit(null) }

    private suspend fun store(id: String): DataStore<Preferences> = storesLock.withLock {
        stores.getOrPut(id) {
            val job = SupervisorJob()
            Store(
                PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(Dispatchers.IO + job),
                    produceFile = { context.preferencesDataStoreFile(storeFile(id)) },
                ),
                job,
            )
        }.data
    }

    private fun account(id: String, prefs: Preferences): Account? {
        val server = prefs[SERVER] ?: return null
        val user = prefs[USER] ?: return null
        return Account(id, server, user)
    }

    companion object {
        private const val SEPARATOR = ","
        private val IDS = stringPreferencesKey("ids")
        private val ACTIVE = stringPreferencesKey("active")
        private val SERVER = stringPreferencesKey("server")
        private val USER = stringPreferencesKey("user")
        private val TOKEN = stringPreferencesKey("token")

        fun storeFile(id: String) = "account-$id"

        /** The tag of every WorkManager request of the account, so removal can cancel them. */
        fun workTag(id: String) = "account-$id"

        private fun ids(prefs: Preferences): List<String> =
            prefs[IDS]?.split(SEPARATOR)?.filter { it.isNotEmpty() }.orEmpty()
    }
}
