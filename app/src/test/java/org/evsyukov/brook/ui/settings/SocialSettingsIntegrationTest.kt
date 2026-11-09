package org.evsyukov.brook.ui.settings

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.brook.data.EntryActions
import org.evsyukov.brook.data.account.TokenCipher
import org.evsyukov.brook.data.db.BrookDatabase
import org.evsyukov.brook.data.db.EntryEntity
import org.evsyukov.brook.data.social.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SocialSettingsIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db = Room.inMemoryDatabaseBuilder(context, BrookDatabase::class.java).allowMainThreadQueries().build()
    private val store = SocialStore(context, scope, object : TokenCipher {
        override fun encrypt(plain: String) = plain.reversed()
        override fun decrypt(sealed: String) = sealed.reversed()
    }, "route-${UUID.randomUUID()}")
    private val posts = mutableListOf<Delivery>()
    private val api = object : SocialPublisher {
        override suspend fun connect(service: SocialService, server: String, user: String, secret: String) = SocialConnection("account", service, server, "42", "alice", secret)
        override suspend fun refresh(connection: SocialConnection) = connection
        override suspend fun publish(connection: SocialConnection, delivery: Delivery): String { posts += delivery; return "77" }
        override suspend fun verify(connection: SocialConnection, delivery: Delivery) = true
    }
    private val publishing = SocialPublishing(store, api, { listOf(db) }, {})
    @After fun close() = runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin(); db.close() }

    @Test
    fun settingsConnectionSelectionAndFavoriteReachTheDeliveryHistory() = runBlocking<Unit> {
        compose.setContent { MaterialTheme { SocialSettingsRoute(publishing, {}) } }
        compose.onNodeWithText("Connect Mastodon").performScrollTo().performClick()
        compose.onNodeWithTag("social-server").performTextInput("https://m.example/")
        compose.onNodeWithTag("social-secret").performTextInput("token")
        compose.onNodeWithText("Connect", substring = false).performClick()
        compose.waitUntil(5_000) { runBlocking { store.connections.first().isNotEmpty() } }
        compose.onNodeWithTag("social-toggle-MASTODON").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { store.connections.first().single().enabled } }
        db.entryDao().insertIgnore(listOf(EntryEntity(1, "feed/1", "Article", "", "https://example.org/story", 1, "", "", false, false, null)))
        EntryActions(db, destinations = publishing::destinations).setStarred(1, true)
        publishing.send()
        assertThat(posts.map { it.url }).containsExactly("https://example.org/story")
        compose.onNodeWithText("Published and verified").performScrollTo().assertIsDisplayed()
    }
}