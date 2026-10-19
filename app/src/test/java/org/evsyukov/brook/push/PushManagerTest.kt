package org.evsyukov.brook.push

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.evsyukov.brook.data.account.Account
import org.evsyukov.brook.data.remote.GReaderException
import org.evsyukov.brook.data.remote.PushApi
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class PushManagerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = PushStore(ApplicationProvider.getApplicationContext(), scope, file = "push-test")
    private val distributor = FakeDistributor()
    private val servers = mutableMapOf<String, FakePushApi>()
    private var accounts = listOf(ALICE, BOB)
    private var enabled = true
    private val pushes = mutableListOf<Pair<String, PushKind>>()

    private val manager = PushManager(
        distributor = distributor,
        store = store,
        api = { id -> servers.getValue(id) },
        accounts = { accounts },
        enabled = { enabled },
        onPush = { id, kind -> pushes += id to kind },
    )

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun eachAccountWhoseServerPushesIsRegisteredAndSubscribed() = runBlocking<Unit> {
        servers[ALICE.id] = FakePushApi(key = KEY)
        servers[BOB.id] = FakePushApi(key = null)
        distributor.installed += NTFY

        manager.reconcile()

        assertThat(distributor.savedPackage).isEqualTo(NTFY.packageName)
        assertThat(distributor.registered).containsExactly(ALICE.id, Registration("alice · a.example", KEY))
        assertThat(manager.status.value.accounts).containsExactly(ALICE.id, AccountPush.CONNECTING, BOB.id, AccountPush.UNSUPPORTED)

        manager.onNewEndpoint(ALICE.id, ENDPOINT, "pub", "auth")
        manager.onNewEndpoint(ALICE.id, ENDPOINT, "pub", "auth")

        assertThat(servers.getValue(ALICE.id).subscribed).containsExactly(Triple(ENDPOINT, "pub", "auth"))
        assertThat(manager.status.value.accounts[ALICE.id]).isEqualTo(AccountPush.ON)
        assertThat(store.all().getValue(ALICE.id)).isEqualTo(PushRecord(KEY, ENDPOINT, AccountPush.ON))
    }

    @Test
    fun aStartWithTheSameEndpointDoesNotAskTheServerAgain() = runBlocking<Unit> {
        servers[ALICE.id] = FakePushApi(key = KEY)
        accounts = listOf(ALICE)
        distributor.installed += NTFY
        manager.reconcile()
        manager.onNewEndpoint(ALICE.id, ENDPOINT, "pub", "auth")
        servers.getValue(ALICE.id).keyRequests = 0

        manager.reconcile()
        manager.onNewEndpoint(ALICE.id, ENDPOINT, "pub", "auth")

        assertThat(servers.getValue(ALICE.id).keyRequests).isEqualTo(0)
        assertThat(servers.getValue(ALICE.id).subscribed).hasSize(1)
        assertThat(manager.status.value.accounts[ALICE.id]).isEqualTo(AccountPush.ON)
    }

    @Test
    fun aNewEndpointReplacesTheOldOneOnTheServer() = runBlocking<Unit> {
        servers[ALICE.id] = FakePushApi(key = KEY)
        accounts = listOf(ALICE)
        distributor.installed += NTFY
        manager.reconcile()
        manager.onNewEndpoint(ALICE.id, ENDPOINT, "pub", "auth")

        manager.onNewEndpoint(ALICE.id, "https://ntfy.example/upNew", "pub2", "auth2")

        assertThat(servers.getValue(ALICE.id).subscribed.last()).isEqualTo(Triple("https://ntfy.example/upNew", "pub2", "auth2"))
        assertThat(servers.getValue(ALICE.id).unsubscribed).containsExactly(ENDPOINT)
        assertThat(store.all().getValue(ALICE.id).endpoint).isEqualTo("https://ntfy.example/upNew")
    }

    @Test
    fun refusalsOfTheServerAreTold() = runBlocking<Unit> {
        servers[ALICE.id] = FakePushApi(key = KEY, subscribeError = GReaderException.Conflict())
        servers[BOB.id] = FakePushApi(key = KEY, subscribeError = GReaderException.BadRequest("Bad Request!"))
        distributor.installed += NTFY
        manager.reconcile()

        manager.onNewEndpoint(ALICE.id, ENDPOINT, "pub", "auth")
        manager.onNewEndpoint(BOB.id, "https://ntfy.example/upBob", "pub", "auth")

        assertThat(manager.status.value.accounts).containsExactly(ALICE.id, AccountPush.TOO_MANY, BOB.id, AccountPush.FAILED)
    }

    @Test
    fun anUnreachableServerFailsAndIsAskedAgainAtTheNextStart() = runBlocking<Unit> {
        servers[ALICE.id] = FakePushApi(key = KEY, keyError = IOException("offline"))
        accounts = listOf(ALICE)
        distributor.installed += NTFY

        manager.reconcile()
        assertThat(manager.status.value.accounts[ALICE.id]).isEqualTo(AccountPush.FAILED)
        assertThat(distributor.registered).isEmpty()

        servers.getValue(ALICE.id).keyError = null
        manager.reconcile()
        assertThat(distributor.registered.keys).containsExactly(ALICE.id)
    }

    @Test
    fun withoutADistributorNothingIsRegistered() = runBlocking<Unit> {
        servers[ALICE.id] = FakePushApi(key = KEY)

        manager.reconcile()

        assertThat(distributor.registered).isEmpty()
        assertThat(manager.status.value).isEqualTo(PushStatus())
    }

    @Test
    fun withSeveralDistributorsTheUserChooses() = runBlocking<Unit> {
        servers[ALICE.id] = FakePushApi(key = KEY)
        servers[BOB.id] = FakePushApi(key = KEY)
        distributor.installed += listOf(NTFY, NEXTPUSH)

        manager.reconcile()
        assertThat(distributor.registered).isEmpty()
        assertThat(manager.status.value.distributors).containsExactly(NTFY, NEXTPUSH)
        assertThat(manager.status.value.distributor).isNull()

        manager.choose(NEXTPUSH.packageName)

        assertThat(distributor.savedPackage).isEqualTo(NEXTPUSH.packageName)
        assertThat(distributor.registered.keys).containsExactly(ALICE.id, BOB.id)
        assertThat(manager.status.value.distributor).isEqualTo(NEXTPUSH.packageName)
    }

    @Test
    fun switchingOffTakesEverySubscriptionBack() = runBlocking<Unit> {
        servers[ALICE.id] = FakePushApi(key = KEY)
        servers[BOB.id] = FakePushApi(key = null)
        distributor.installed += NTFY
        manager.reconcile()
        manager.onNewEndpoint(ALICE.id, ENDPOINT, "pub", "auth")

        enabled = false
        manager.reconcile()

        assertThat(servers.getValue(ALICE.id).unsubscribed).containsExactly(ENDPOINT)
        assertThat(distributor.registered).isEmpty()
        assertThat(store.all()).isEmpty()
        assertThat(manager.status.value.accounts).isEmpty()
    }

    @Test
    fun aRemovedAccountIsTakenBack() = runBlocking<Unit> {
        servers[ALICE.id] = FakePushApi(key = KEY)
        servers[BOB.id] = FakePushApi(key = KEY)
        distributor.installed += NTFY
        manager.reconcile()
        manager.onNewEndpoint(ALICE.id, ENDPOINT, "pub", "auth")

        manager.forget(ALICE.id)

        assertThat(servers.getValue(ALICE.id).unsubscribed).containsExactly(ENDPOINT)
        assertThat(distributor.registered.keys).containsExactly(BOB.id)
        assertThat(store.all().keys).containsExactly(BOB.id)
        assertThat(manager.status.value.accounts.keys).containsExactly(BOB.id)
    }

    @Test
    fun anEndpointWhilePushIsOffIsDropped() = runBlocking<Unit> {
        servers[ALICE.id] = FakePushApi(key = KEY)
        enabled = false

        manager.onNewEndpoint(ALICE.id, ENDPOINT, "pub", "auth")

        assertThat(servers.getValue(ALICE.id).subscribed).isEmpty()
        assertThat(distributor.unregistered).containsExactly(ALICE.id)
    }

    @Test
    fun theDistributorDroppingTheInstanceUnsubscribes() = runBlocking<Unit> {
        servers[ALICE.id] = FakePushApi(key = KEY)
        accounts = listOf(ALICE)
        distributor.installed += NTFY
        manager.reconcile()
        manager.onNewEndpoint(ALICE.id, ENDPOINT, "pub", "auth")

        manager.onUnregistered(ALICE.id)

        assertThat(servers.getValue(ALICE.id).unsubscribed).containsExactly(ENDPOINT)
        assertThat(store.all().getValue(ALICE.id).endpoint).isNull()
        assertThat(manager.status.value.accounts[ALICE.id]).isEqualTo(AccountPush.FAILED)
    }

    @Test
    fun aMessageSyncsTheAccountItIsFor() = runBlocking<Unit> {
        manager.onMessage(BOB.id, """{"kind":"entries","count":3,"newest":"1791274998925411","title":"freshgo","body":"3 new"}""".encodeToByteArray())
        manager.onMessage(ALICE.id, """{"kind":"changes"}""".encodeToByteArray())
        manager.onMessage("gone", """{"kind":"entries"}""".encodeToByteArray())

        assertThat(pushes).containsExactly(BOB.id to PushKind.ENTRIES, ALICE.id to PushKind.CHANGES).inOrder()
    }

    @Test
    fun aMessageOfAnotherShapeIsAQuietSync() {
        assertThat(PushKind.of("""{"kind":"entries"}""".encodeToByteArray())).isEqualTo(PushKind.ENTRIES)
        assertThat(PushKind.of("""{"kind":"something"}""".encodeToByteArray())).isEqualTo(PushKind.CHANGES)
        assertThat(PushKind.of("not json".encodeToByteArray())).isEqualTo(PushKind.CHANGES)
    }

    data class Registration(val name: String, val vapid: String)

    private class FakeDistributor : PushDistributor {
        val installed = mutableListOf<Distributor>()
        var savedPackage: String? = null
        val registered = mutableMapOf<String, Registration>()
        val unregistered = mutableListOf<String>()

        override fun installed() = installed.toList()
        override fun saved() = savedPackage
        override fun save(packageName: String) {
            savedPackage = packageName
        }

        override fun register(instance: String, name: String, vapid: String): Boolean {
            registered[instance] = Registration(name, vapid)
            return true
        }

        override fun unregister(instance: String) {
            registered.remove(instance)
            unregistered += instance
        }
    }

    private class FakePushApi(
        val key: String?,
        var keyError: IOException? = null,
        val subscribeError: IOException? = null,
    ) : PushApi {
        var keyRequests = 0
        val subscribed = mutableListOf<Triple<String, String, String>>()
        val unsubscribed = mutableListOf<String>()

        override suspend fun pushKey(): String? {
            keyRequests++
            keyError?.let { throw it }
            return key
        }

        override suspend fun pushSubscribe(endpoint: String, p256dh: String, auth: String) {
            subscribeError?.let { throw it }
            subscribed += Triple(endpoint, p256dh, auth)
        }

        override suspend fun pushUnsubscribe(endpoint: String) {
            unsubscribed += endpoint
        }
    }

    private companion object {
        val ALICE = Account("a1", "https://a.example/api/greader.php", "alice")
        val BOB = Account("b2", "https://b.example", "bob")
        val NTFY = Distributor("io.heckel.ntfy", "ntfy")
        val NEXTPUSH = Distributor("org.unifiedpush.distributor.nextpush", "NextPush")
        const val KEY = "BDd3_hVL9fZi9Ybo2UUzA284WG5FZR30_95YeZJsiApwXKpNcF1rRPF3foIiBHXRdJI2Qhumhf6_LFTeZaNndIo"
        const val ENDPOINT = "https://ntfy.example/upAlice"
    }
}
