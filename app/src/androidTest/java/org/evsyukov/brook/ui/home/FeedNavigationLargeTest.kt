package org.evsyukov.brook.ui.home

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.window.core.layout.WindowSizeClass
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import org.evsyukov.brook.data.Filter
import org.evsyukov.brook.data.Scope
import org.evsyukov.brook.data.account.Account
import org.evsyukov.brook.ui.list.EntryRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * The wide-window half of the feed navigation. The proof workflow resizes the emulator before this
 * class runs, so the production width breakpoint chooses the permanent pane; nothing here sets a
 * large-screen flag by hand.
 */
@RunWith(AndroidJUnit4::class)
class FeedNavigationLargeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val account = Account("a", "http://localhost/api/greader.php", "alice")

    @Test
    fun aRealWideWindowSelectsThePermanentPane() {
        val feeds = listOf(
            FeedNode("f1", "Alpha", "", unread = 3, starred = 1),
            FeedNode("f2", "Beta", "", unread = 0),
            FeedNode("f3", "Gamma", "", unread = 1),
        )
        val tree = NavTree(unreadTotal = 4, feeds = feeds)

        compose.setContent {
            var selection by remember { mutableStateOf(Selection(scope = Scope.All, filter = Filter.ALL)) }
            MaterialTheme {
                val done = LoadState.NotLoading(endOfPaginationReached = true)
                val items = remember {
                    flowOf(PagingData.from(emptyList<EntryRow>(), LoadStates(done, done, done)))
                }.collectAsLazyPagingItems()
                HomeScreen(
                    accounts = listOf(account),
                    active = account,
                    tree = tree,
                    selection = selection,
                    query = null,
                    onQueryChange = {},
                    syncing = false,
                    items = items,
                    searchResults = items,
                    onSelectScope = { selection = selection.copy(scope = it) },
                    onSelectFilter = { selection = selection.copy(filter = it) },
                    bulkAvailability = BulkAvailability(selection, true),
                    onSelectAccount = {},
                    onAddAccount = {},
                    onRemoveAccount = {},
                    onToggleRead = {},
                    onToggleStar = {},
                    onMarkAllRead = {},
                    onMarkBeside = { _, _ -> },
                    onScrolledPast = null,
                    onRefresh = {},
                    feedFullText = null,
                    onFeedFullText = {},
                    onOpenSettings = {},
                    onOpenManage = {},
                    images = null,
                    detail = { _, _, _ -> },
                )
            }
        }

        val width = compose.activity.resources.configuration.screenWidthDp
        assertThat(width).isAtLeast(WindowSizeClass.WIDTH_DP_LARGE_LOWER_BOUND)
        // The production breakpoint did it: the pane is permanent and the compact picker is absent.
        compose.onNodeWithTag(FeedPaneTags.LIST).assertExists()
        compose.onNodeWithTag(HomeTags.TITLE_PICKER).assertDoesNotExist()
        compose.onNodeWithTag(HomeTags.SEARCH).assertExists()
        assertThat(compose.onNodeWithTag(FeedPaneTags.MODE).fetchSemanticsNode().boundsInRoot.width)
            .isGreaterThan(0f)

        compose.waitForIdle()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("screenshots")
            ?: error("no external files directory")
        dir.mkdirs()
        FileOutputStream(File(dir, "large-layout.png")).use { out ->
            image.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }
}
