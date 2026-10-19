package org.evsyukov.brook.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.evsyukov.brook.data.settings.AppSettings
import org.evsyukov.brook.data.settings.Settings
import org.evsyukov.brook.ui.home.HomeTags
import org.evsyukov.brook.ui.home.NewEntriesBanner
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun settingsAreKeptAndReadBack() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val settings = AppSettings(ApplicationProvider.getApplicationContext(), scope, file = "settings-test")
            assertThat(withTimeout(5_000) { settings.current.first() }).isEqualTo(Settings())

            settings.setSyncInterval(60)
            settings.setPrefetchOnlyUnmetered(false)
            settings.setReadRetentionDays(7)
            settings.setNotifyNewEntries(true)

            val changed = withTimeout(5_000) { settings.current.first { it.notifyNewEntries } }
            assertThat(changed).isEqualTo(Settings(syncIntervalMinutes = 60, prefetchOnlyUnmetered = false, readRetentionDays = 7, notifyNewEntries = true))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun screenChangesEachSetting() {
        val calls = mutableListOf<Any>()
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    settings = Settings(),
                    onBack = {},
                    onSyncInterval = { calls += "interval" to it },
                    onPrefetchOnlyUnmetered = { calls += "unmetered" to it },
                    onReadRetention = { calls += "retention" to it },
                    onNotify = { calls += "notify" to it },
                )
            }
        }
        compose.onNodeWithText("Every 30 minutes").assertIsDisplayed()
        compose.onNodeWithTag(SettingsTags.INTERVAL).performClick()
        compose.onNodeWithTag(SettingsTags.option(60)).performClick()
        compose.onNodeWithTag(SettingsTags.UNMETERED).performClick()
        compose.onNodeWithTag(SettingsTags.RETENTION).performClick()
        compose.onNodeWithTag(SettingsTags.option(90)).performClick()
        compose.onNodeWithTag(SettingsTags.NOTIFY).performClick()

        assertThat(calls).containsExactly("interval" to 60, "unmetered" to false, "retention" to 90, "notify" to true).inOrder()
    }

    @Test
    fun bannerShowsOnlyWithNewEntries() {
        var count by mutableStateOf(0)
        var clicks = 0
        compose.setContent {
            MaterialTheme { NewEntriesBanner(count, onClick = { clicks++ }) }
        }
        compose.onNodeWithTag(HomeTags.NEW_BANNER).assertDoesNotExist()

        count = 3
        compose.onNodeWithText("3 new").assertIsDisplayed().performClick()

        assertThat(clicks).isEqualTo(1)
    }
}
