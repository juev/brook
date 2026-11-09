package org.evsyukov.brook.data.social

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.evsyukov.brook.data.account.TokenCipher
import org.evsyukov.brook.data.db.BrookDatabase
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SocialPublishingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db = Room.inMemoryDatabaseBuilder(context, BrookDatabase::class.java).allowMainThreadQueries().build()
    private val store = SocialStore(context, scope, object : TokenCipher {
        override fun encrypt(plain: String) = plain.reversed()
        override fun decrypt(sealed: String) = sealed.reversed()
    }, "social-${UUID.randomUUID()}")
    private val sent = mutableListOf<String>()
    private val verified = mutableListOf<String>()
    private var failing: String? = "b"
    private var failureReason: String? = null
    private var refreshed = 0
    private val api = object : SocialPublisher {
        override suspend fun connect(service: SocialService, server: String, user: String, secret: String) = connection(service.name, service)
        override suspend fun refresh(connection: SocialConnection): SocialConnection {
            refreshed++
            failureReason = null
            return connection
        }
        override suspend fun publish(connection: SocialConnection, delivery: Delivery): String {
            sent += delivery.destination
            failureReason?.let { throw SocialFailure(it) }
            if (delivery.destination == failing) throw IOException("offline")
            return "remote-${delivery.id}"
        }
        override suspend fun verify(connection: SocialConnection, delivery: Delivery): Boolean {
            verified += delivery.destination
            return true
        }
    }
    private var clock = 10_000L
    private val publishing = SocialPublishing(store, api, { listOf(db) }, {}, { clock })
    private fun connection(id: String, service: SocialService) = SocialConnection(id, service, "https://example.org/", "user", "alice", "token", enabled = true)
    @After fun close() = runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin(); db.close() }

    @Test
    fun unreadableSocialCredentialsDoNotPreventLocalFavoriting() = runBlocking<Unit> {
        val broken = SocialStore(context, scope, object : TokenCipher {
            override fun encrypt(plain: String) = "sealed"
            override fun decrypt(sealed: String): String = throw java.security.GeneralSecurityException("key invalidated")
        }, "broken-${UUID.randomUUID()}")
        broken.save(connection("m", SocialService.MASTODON))
        val isolated = SocialPublishing(broken, api, { listOf(db) }, {})
        val result = runCatching { isolated.destinations() }
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow()).isEmpty()
    }

    @Test
    fun aDestinationSnapshotTakenBeforeDisconnectCannotPostThroughANewConnection() = runBlocking<Unit> {
        publishing.connect(SocialService.MASTODON, "https://example.org/", "alice", "token")
        publishing.setEnabled(SocialService.MASTODON, true)
        val old = publishing.destinations().single()
        publishing.disconnect(SocialService.MASTODON)
        publishing.connect(SocialService.MASTODON, "https://example.org/", "alice", "new-token")
        publishing.setEnabled(SocialService.MASTODON, true)
        db.deliveryDao().insert(Delivery(entryId = 1, destination = old, title = "Title", url = "https://example.org/story", createdAt = 1))
        publishing.send()
        assertThat(sent).isEmpty()
        assertThat(db.deliveryDao().all().single().state).isEqualTo(Delivery.CANCELED)
    }

    @Test
    fun connectingRequiresSelectionAndDisconnectingErasesCredentialsAndCancelsPendingPosts() = runBlocking<Unit> {
        publishing.connect(SocialService.MASTODON, "https://example.org/", "alice", "token")
        assertThat(store.connections.first().map { it.service }).containsExactly(SocialService.MASTODON)
        assertThat(publishing.destinations()).isEmpty()
        publishing.setEnabled(SocialService.MASTODON, true)
        val id = publishing.destinations().single()
        db.deliveryDao().insert(Delivery(entryId = 1, destination = id, title = "Title", url = "https://example.org/story", createdAt = 1))
        publishing.disconnect(SocialService.MASTODON)
        assertThat(store.connections.first()).isEmpty()
        assertThat(db.deliveryDao().all().single().state).isEqualTo(Delivery.CANCELED)
        publishing.send()
        assertThat(sent).isEmpty()
    }

    @Test
    fun blueskyRefreshesAnExpiredSessionBeforeRequiringReconnect() = runBlocking<Unit> {
        store.save(connection("b", SocialService.BLUESKY))
        failing = null
        failureReason = Delivery.AUTH
        db.deliveryDao().insert(Delivery(entryId = 1, destination = "b", title = "Title", url = "https://example.org/story", createdAt = 1))
        publishing.send()
        assertThat(db.deliveryDao().all().single().state).isEqualTo(Delivery.SENT)
        assertThat(refreshed).isEqualTo(1)
    }

    @Test
    fun anAuthenticationFailureWaitsForReconnectWithoutEndlessBackgroundRetries() = runBlocking<Unit> {
        store.save(connection("m", SocialService.MASTODON))
        failureReason = Delivery.AUTH
        db.deliveryDao().insert(Delivery(entryId = 1, destination = "m", title = "Title", url = "https://example.org/story", createdAt = 1))
        publishing.send()
        assertThat(db.deliveryDao().all().single().state).isEqualTo(Delivery.AUTH)
        publishing.send()
        assertThat(sent).containsExactly("m")
    }

    @Test
    fun anOldAmbiguousMastodonAttemptStopsRatherThanRiskADuplicate() = runBlocking<Unit> {
        store.save(connection("m", SocialService.MASTODON))
        clock = 3_600_001
        db.deliveryDao().insert(Delivery(entryId = 1, destination = "m", title = "Title", url = "https://example.org/story", createdAt = 1, firstAttemptAt = 1))
        publishing.send()
        assertThat(sent).isEmpty()
        assertThat(db.deliveryDao().all().single().state).isEqualTo(Delivery.UNKNOWN)
    }

    @Test
    fun anUnreadableCredentialStoreKeepsQueuedDeliveriesInsteadOfCancellingThem() = runBlocking<Unit> {
        val file = "broken-send-${UUID.randomUUID()}"
        // DataStore allows one active instance per file, so the writer's scope is finished first.
        val writerScope = CoroutineScope(kotlinx.coroutines.Job() + Dispatchers.IO)
        SocialStore(context, writerScope, object : TokenCipher {
            override fun encrypt(plain: String) = plain.reversed()
            override fun decrypt(sealed: String) = sealed.reversed()
        }, file).save(connection("m#old", SocialService.MASTODON))
        writerScope.coroutineContext[Job]!!.cancelAndJoin()
        val broken = SocialStore(context, scope, object : TokenCipher {
            override fun encrypt(plain: String) = "sealed"
            override fun decrypt(sealed: String): String = throw java.security.GeneralSecurityException("key invalidated")
        }, file)
        val isolated = SocialPublishing(broken, api, { listOf(db) }, {})
        db.deliveryDao().insert(Delivery(entryId = 1, destination = "m#old", title = "Title", url = "https://example.org/story", createdAt = 1))
        assertThat(isolated.send()).isFalse()
        assertThat(db.deliveryDao().all().single().state).isEqualTo(Delivery.PENDING)
        assertThat(sent).isEmpty()
    }

    @Test
    fun linkdingRetriesAfterALongOfflineGapBecauseSavingTheSameUrlIsSafe() = runBlocking<Unit> {
        store.save(connection("l", SocialService.LINKDING))
        failing = "l"
        db.deliveryDao().insert(Delivery(entryId = 1, destination = "l", title = "Title", url = "https://example.org/story", createdAt = 1))
        assertThat(publishing.send()).isTrue()
        assertThat(db.deliveryDao().all().single().state).isEqualTo(Delivery.PENDING)
        clock = 3 * 3_600_001
        failing = null
        assertThat(publishing.send()).isFalse()
        assertThat(db.deliveryDao().all().single().state).isEqualTo(Delivery.SENT)
        assertThat(verified).containsExactly("l")
    }

    @Test
    fun aRejectedLinkdingTokenWaitsForReconnectWithoutATokenRefreshAttempt() = runBlocking<Unit> {
        store.save(connection("l", SocialService.LINKDING))
        failureReason = Delivery.AUTH
        db.deliveryDao().insert(Delivery(entryId = 1, destination = "l", title = "Title", url = "https://example.org/story", createdAt = 1))
        publishing.send()
        assertThat(db.deliveryDao().all().single().state).isEqualTo(Delivery.AUTH)
        assertThat(refreshed).isEqualTo(0)
        assertThat(sent).containsExactly("l")
    }

    @Test
    fun aFailedDestinationRetriesWithoutSendingTheSuccessfulOneAgain() = runBlocking<Unit> {
        store.save(connection("m", SocialService.MASTODON))
        store.save(connection("b", SocialService.BLUESKY))
        for (id in listOf("m", "b")) db.deliveryDao().insert(Delivery(entryId = 1, destination = id, title = "Title", url = "https://example.org/story", createdAt = 1))
        assertThat(publishing.send()).isTrue()
        assertThat(db.deliveryDao().all().first { it.destination == "m" }.state).isEqualTo(Delivery.SENT)
        failing = null
        assertThat(publishing.send()).isFalse()
        assertThat(sent).containsExactly("m", "b", "b").inOrder()
        assertThat(verified).containsExactly("m", "b")
        assertThat(db.deliveryDao().all().map { it.state }.toSet()).containsExactly(Delivery.SENT)
    }
}