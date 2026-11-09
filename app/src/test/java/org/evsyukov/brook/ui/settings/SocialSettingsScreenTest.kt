package org.evsyukov.brook.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.evsyukov.brook.data.social.SocialService
import org.evsyukov.brook.data.social.Delivery
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SocialSettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun selectedDestinationsAndDisconnectConfirmationAreIndependent() {
        val enabled = mutableListOf<Pair<SocialService, Boolean>>()
        val disconnected = mutableListOf<SocialService>()
        var retried = false
        compose.setContent {
            MaterialTheme {
                SocialSettingsScreen(
                    listOf(SocialAccount(SocialService.MASTODON, "alice", "https://m.example/", false), SocialAccount(SocialService.BLUESKY, "bob.test", "https://bsky.social/", true)),
                    listOf(Delivery(entryId = 1, destination = "m", title = "Article", url = "https://example.org", createdAt = 1, state = Delivery.UNKNOWN)),
                    false, false, {}, { _, _, _, _ -> }, { s, on -> enabled += s to on }, { disconnected += it }, { retried = true })
            }
        }
        compose.onNodeWithTag("social-toggle-MASTODON").performScrollTo().performClick()
        compose.onNodeWithTag("social-toggle-BLUESKY").performScrollTo().performClick()
        assertThat(enabled).containsExactly(SocialService.MASTODON to true, SocialService.BLUESKY to false)
        compose.onNodeWithTag("social-disconnect-MASTODON").performScrollTo().performClick()
        assertThat(disconnected).isEmpty()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("social-disconnect-MASTODON").performScrollTo().performClick()
        compose.onNodeWithTag("social-disconnect-confirm").performClick()
        assertThat(disconnected).containsExactly(SocialService.MASTODON)
        compose.onNodeWithText("Retry pending deliveries").performScrollTo().performClick()
        assertThat(retried).isTrue()
        compose.onNodeWithText("Outcome uncertain.", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun linkdingConnectsWithAnInstanceUrlAndApiTokenWithoutAHandle() {
        val calls = mutableListOf<List<Any>>()
        compose.setContent {
            MaterialTheme {
                SocialSettingsScreen(emptyList(), emptyList(), false, false, {},
                    onConnect = { service, server, user, secret -> calls += listOf(service, server, user, secret) },
                    onEnabled = { _, _ -> error("Must not enable implicitly") }, onDisconnect = {}, onRetry = {})
            }
        }
        compose.onNodeWithText("Connect linkding").performScrollTo().performClick()
        compose.onNodeWithTag("social-handle").assertDoesNotExist()
        compose.onNodeWithText("API token", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Bookmarks stay private", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("social-server").performTextInput("https://links.example.org/")
        compose.onNodeWithTag("social-secret").performTextInput("api-token")
        compose.onNodeWithText("Connect", substring = false).performClick()
        assertThat(calls).containsExactly(listOf(SocialService.LINKDING, "https://links.example.org/", "", "api-token"))
    }

    @Test
    fun userCanConnectMastodonAndBlueskyWithoutSelectingThemImplicitly() {
        val calls = mutableListOf<List<Any>>()
        compose.setContent {
            MaterialTheme {
                SocialSettingsScreen(emptyList(), emptyList(), false, false, {},
                    onConnect = { service, server, user, secret -> calls += listOf(service, server, user, secret) },
                    onEnabled = { _, _ -> error("Must not enable implicitly") }, onDisconnect = {}, onRetry = {})
            }
        }
        compose.onNodeWithText("Connect Mastodon").performScrollTo().performClick()
        compose.onNodeWithTag("social-server").performTextInput("https://social.example/")
        compose.onNodeWithTag("social-secret").performTextInput("token")
        compose.onNodeWithText("Connect", substring = false).performClick()
        assertThat(calls).containsExactly(listOf(SocialService.MASTODON, "https://social.example/", "", "token"))
        compose.onNodeWithText("Connect Bluesky").performScrollTo().performClick()
        compose.onNodeWithTag("social-handle").performTextInput("alice.test")
        compose.onNodeWithTag("social-secret").performTextInput("app-password")
        compose.onNodeWithText("Connect", substring = false).performClick()
        assertThat(calls.last()).isEqualTo(listOf(SocialService.BLUESKY, "https://bsky.social", "alice.test", "app-password"))
    }
}