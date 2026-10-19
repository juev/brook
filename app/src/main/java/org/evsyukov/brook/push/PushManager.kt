package org.evsyukov.brook.push

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.evsyukov.brook.data.account.Account
import org.evsyukov.brook.data.remote.GReaderException
import org.evsyukov.brook.data.remote.PushApi
import java.io.IOException

/** What the settings show about push. */
data class PushStatus(
    /** Distributors installed on the device. */
    val distributors: List<Distributor> = emptyList(),
    /** The package of the distributor in use; null while there is none or the user has to choose. */
    val distributor: String? = null,
    val accounts: Map<String, AccountPush> = emptyMap(),
)

/**
 * Push through UnifiedPush (freshgo `docs/specs/push.md`, P11, P12). Every account is an instance
 * of the distributor; its endpoint and keys go to the server of the account, which then pushes
 * when entries come or change. Accounts whose server does not push stay on the periodic sync.
 */
class PushManager(
    private val distributor: PushDistributor,
    private val store: PushStore,
    private val api: suspend (accountId: String) -> PushApi,
    private val accounts: suspend () -> List<Account>,
    /** The push switch of the settings, as stored rather than the defaults before the file is read. */
    private val enabled: suspend () -> Boolean,
    /** A message came for the account: sync it, and tell about new entries for [PushKind.ENTRIES]. */
    private val onPush: (accountId: String, kind: PushKind) -> Unit,
) {
    private val lock = Mutex()
    private val _status = MutableStateFlow(PushStatus())
    val status: StateFlow<PushStatus> = _status.asStateFlow()

    /**
     * Brings the registrations in line with the switch and the accounts: registers every account
     * with the distributor while push is on, takes every subscription back while it is off. Run at
     * start and whenever either changes; registering again also repairs a lost registration.
     */
    suspend fun reconcile() = lock.withLock {
        val list = accounts()
        val ids = list.map { it.id }.toSet()
        // Accounts removed without forget(), e.g. while push was being set up.
        for (id in store.all().keys - ids) {
            distributor.unregister(id)
            store.remove(id)
        }
        if (!enabled()) {
            for ((id, record) in store.all()) takeBack(id, record)
            publish(null)
            return@withLock
        }
        val installed = distributor.installed()
        val current = distributor.saved() ?: installed.singleOrNull()?.packageName?.also(distributor::save)
        if (current == null) {
            publish(null, installed)
            return@withLock
        }
        val records = store.all()
        for (account in list) register(account, records[account.id] ?: PushRecord())
        publish(current, installed)
    }

    /** The user chose [packageName] among several distributors: every account registers with it. */
    suspend fun choose(packageName: String) {
        lock.withLock {
            distributor.save(packageName)
            for (id in store.all().keys) store.update(id) { it.copy(state = AccountPush.CONNECTING) }
        }
        reconcile()
    }

    /** Before the account is removed: the server forgets the endpoint, the distributor the instance. */
    suspend fun forget(accountId: String) = lock.withLock {
        store.all()[accountId]?.let { takeBack(accountId, it) }
        _status.value = _status.value.copy(accounts = _status.value.accounts - accountId)
    }

    /** The distributor gave an endpoint for the account; the server gets it unless it has it already. */
    suspend fun onNewEndpoint(accountId: String, endpoint: String, p256dh: String, auth: String) = lock.withLock {
        if (!enabled() || accounts().none { it.id == accountId }) {
            distributor.unregister(accountId)
            return@withLock
        }
        val record = store.all()[accountId] ?: PushRecord()
        if (record.endpoint == endpoint && record.state == AccountPush.ON) return@withLock
        val state = try {
            api(accountId).pushSubscribe(endpoint, p256dh, auth)
            AccountPush.ON
        } catch (e: GReaderException.Conflict) {
            AccountPush.TOO_MANY
        } catch (e: IOException) {
            Log.w(TAG, "Push subscription of $accountId failed", e)
            AccountPush.FAILED
        }
        if (state == AccountPush.ON && record.endpoint != null && record.endpoint != endpoint) {
            unsubscribe(accountId, record.endpoint)
        }
        store.update(accountId) { it.copy(endpoint = endpoint.takeIf { state == AccountPush.ON } ?: it.endpoint, state = state) }
        setState(accountId, state)
    }

    suspend fun onRegistrationFailed(accountId: String) = lock.withLock {
        if (store.all().containsKey(accountId)) {
            store.update(accountId) { it.copy(state = AccountPush.FAILED) }
            setState(accountId, AccountPush.FAILED)
        }
    }

    /** The distributor dropped the instance; the server stops pushing to it. */
    suspend fun onUnregistered(accountId: String) = lock.withLock {
        val record = store.all()[accountId] ?: return@withLock
        record.endpoint?.let { unsubscribe(accountId, it) }
        store.update(accountId) { it.copy(endpoint = null, state = AccountPush.FAILED) }
        setState(accountId, AccountPush.FAILED)
    }

    /** A decrypted message for the account. */
    suspend fun onMessage(accountId: String, content: ByteArray) {
        if (accounts().any { it.id == accountId }) onPush(accountId, PushKind.of(content))
    }

    private suspend fun register(account: Account, record: PushRecord) {
        val key = record.key ?: try {
            api(account.id).pushKey()
        } catch (e: IOException) {
            Log.w(TAG, "Push key of ${account.id} failed", e)
            store.update(account.id) { it.copy(state = AccountPush.FAILED) }
            return
        }
        if (key == null) {
            store.update(account.id) { it.copy(state = AccountPush.UNSUPPORTED) }
            return
        }
        val asked = distributor.register(account.id, "${account.user} · ${account.host}", key)
        store.update(account.id) {
            when {
                !asked -> it.copy(key = null, state = AccountPush.FAILED)
                it.state == AccountPush.ON -> it.copy(key = key)
                else -> it.copy(key = key, state = AccountPush.CONNECTING)
            }
        }
    }

    private suspend fun takeBack(accountId: String, record: PushRecord) {
        record.endpoint?.let { unsubscribe(accountId, it) }
        distributor.unregister(accountId)
        store.remove(accountId)
    }

    /** Best effort: a server that is down keeps the subscription until its push service forgets it. */
    private suspend fun unsubscribe(accountId: String, endpoint: String) {
        try {
            api(accountId).pushUnsubscribe(endpoint)
        } catch (e: IOException) {
            Log.w(TAG, "Push unsubscription of $accountId failed", e)
        }
    }

    private suspend fun publish(current: String?, installed: List<Distributor> = emptyList()) {
        val states = if (current == null) emptyMap() else store.all().mapValues { it.value.state }
        _status.value = PushStatus(installed, current, states)
    }

    private fun setState(accountId: String, state: AccountPush) {
        _status.value = _status.value.copy(accounts = _status.value.accounts + (accountId to state))
    }

    private companion object {
        const val TAG = "Brook"
    }
}
