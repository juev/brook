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
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.evsyukov.brook.data.EntryActions
import org.evsyukov.brook.data.account.TokenCipher
import org.evsyukov.brook.data.db.BrookDatabase
import org.evsyukov.brook.data.db.EntryEntity
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

/** The whole path for one destination: connect, select, favorite locally, deliver, verify. */
@RunWith(AndroidJUnit4::class)
class SocialDeliveryIntegrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db = Room.inMemoryDatabaseBuilder(context, BrookDatabase::class.java).allowMainThreadQueries().build()
    private val store = SocialStore(context, scope, object : TokenCipher {
        override fun encrypt(plain: String) = plain.reversed()
        override fun decrypt(sealed: String) = sealed.reversed()
    }, "delivery-${UUID.randomUUID()}")
    private val server = MockWebServer().apply { start() }
    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder().url(chain.request().url.newBuilder().scheme("http").build()).build())
    }.build()
    private var wakes = 0
    private val publishing = SocialPublishing(store, SocialApi(http), { listOf(db) }, { wakes++ })
    private fun endpoint() = server.url("/").toString().replace("http:", "https:")

    @After fun close() = runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin(); db.close(); server.close() }

    @Test
    fun aFavoritedArticleReachesLinkdingAndIsVerifiedThroughTheStoredConnection() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().body("""{"count":0,"next":null,"previous":null,"results":[]}""").build())
        publishing.connect(SocialService.LINKDING, endpoint(), "", "api-token")
        publishing.setEnabled(SocialService.LINKDING, true)
        val destination = publishing.destinations().single()
        assertThat(destination).startsWith("LINKDING|")

        db.entryDao().insertIgnore(listOf(EntryEntity(1, "feed/1", "Article &amp; note", "", "https://example.org/story", 1, "", "", false, false, null)))
        wakes = 0
        EntryActions(db, destinations = publishing::destinations, onSocialQueued = publishing::retryPending).setStarred(1, true)
        assertThat(db.deliveryDao().all().map { it.destination }).containsExactly(destination)
        assertThat(wakes).isEqualTo(1)

        server.enqueue(MockResponse.Builder().body("""{"bookmark":null,"metadata":{},"auto_tags":[]}""").build())
        server.enqueue(MockResponse.Builder().code(201).body("""{"id":88,"url":"https://example.org/story","title":"Article & note"}""").build())
        server.enqueue(MockResponse.Builder().body("""{"id":88,"url":"https://example.org/story","title":"Article & note","shared":false}""").build())
        assertThat(publishing.send()).isFalse()

        val stored = db.deliveryDao().all().single()
        assertThat(stored.state).isEqualTo(Delivery.SENT)
        assertThat(stored.remoteId).isEqualTo("88")
        assertThat(server.takeRequest(1, TimeUnit.SECONDS)!!.headers["Authorization"]).isEqualTo("Token api-token")
        assertThat(server.takeRequest(1, TimeUnit.SECONDS)!!.url.encodedPath).isEqualTo("/api/bookmarks/check/")
        assertThat(server.takeRequest(1, TimeUnit.SECONDS)!!.url.encodedPath).isEqualTo("/api/bookmarks/")
        assertThat(server.takeRequest(1, TimeUnit.SECONDS)!!.url.encodedPath).isEqualTo("/api/bookmarks/88/")
    }
}
