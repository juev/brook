package org.evsyukov.brook.ui.home

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
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
 * The feed navigation on a real device: the compact title picker, the permanent pane of large
 * windows, the mode button and its menu, and screenshots of what the screen actually drew.
 *
 * Screenshots land in the app's external files directory, `screenshots/`, which the proof workflow
 * pulls off the emulator; nothing here draws a picture the screen did not render.
 */
@RunWith(AndroidJUnit4::class)
class FeedNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val account = Account("a", "http://localhost/api/greader.php", "alice")

    private fun feed(id: String, title: String, unread: Int = 0, starred: Int = 0) = FeedNode(id, title, "", unread, starred)

    private fun tree(feeds: List<FeedNode>) = NavTree(
        unreadTotal = feeds.sumOf { it.unread },
        categories = listOf(CategoryNode("News", feeds.sumOf { it.unread }, emptyList())),
        feeds = feeds,
    )

    private val smallTree = tree(
        listOf(
            feed("f1", "Alpha", unread = 3, starred = 1),
            feed("f2", "Beta", unread = 0),
            feed("f3", "Gamma", unread = 1),
        ),
    )

    private fun show(
        dark: Boolean = false,
        fontScale: Float = 1f,
        tree: NavTree = smallTree,
    ) {
        compose.setContent {
            var selection by remember { mutableStateOf(Selection(scope = Scope.All, filter = Filter.ALL)) }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    val done = LoadState.NotLoading(endOfPaginationReached = true)
                    val items = remember { flowOf(PagingData.from(emptyList<EntryRow>(), LoadStates(done, done, done))) }.collectAsLazyPagingItems()
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
        }
        // The compact captures must come from a genuinely compact window, not a forced branch.
        assertThat(compose.activity.resources.configuration.screenWidthDp)
            .isLessThan(WindowSizeClass.WIDTH_DP_LARGE_LOWER_BOUND)
    }

    private fun openPicker() {
        compose.onNodeWithTag(HomeTags.TITLE_PICKER).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(HomeTags.PANE).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun back() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        compose.waitForIdle()
    }

    private fun capture(name: String, pickerOnly: Boolean = false) {
        compose.waitForIdle()
        val image = if (pickerOnly) {
            compose.onNodeWithTag(HomeTags.PANE).captureToImage()
        } else {
            compose.onRoot().captureToImage()
        }
        write(name, image.asAndroidBitmap())
    }

    /** The whole display, for a popup that is a window of its own and has no single Compose root. */
    private fun captureDisplay(name: String) {
        compose.waitForIdle()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: error("the display produced no screenshot")
        write(name, image)
    }

    private fun write(name: String, image: Bitmap) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("screenshots")
            ?: error("no external files directory")
        dir.mkdirs()
        FileOutputStream(File(dir, "$name.png")).use { out ->
            image.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    @Test
    fun compactToolbarAndPickerInLightAndDark() {
        show(dark = false)
        capture("compact-toolbar-light")
        openPicker()
        capture("compact-picker-light", pickerOnly = true)
        back()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(HomeTags.PANE).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun darkToolbarAndPicker() {
        show(dark = true)
        capture("compact-toolbar-dark")
        openPicker()
        capture("compact-picker-dark", pickerOnly = true)
    }

    @Test
    fun choosingAFeedKeepsTheModeAndTheChoiceIsStillSelected() {
        show()
        openPicker()
        compose.onNodeWithTag(FeedPaneTags.scope(Scope.Feed("f2"))).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(HomeTags.PANE).fetchSemanticsNodes().isEmpty() }

        // The mode stays All; the title names the feed now.
        compose.onNodeWithTag(ModeTags.BUTTON).assertExists()
        openPicker()
        compose.onNodeWithTag(FeedPaneTags.scope(Scope.Feed("f2"))).assertIsSelected()
    }

    @Test
    fun aShortPressMovesTheModeAndALongPressOpensTheMenuWithoutMovingIt() {
        show()
        compose.onNodeWithTag(ModeTags.BUTTON).performClick()
        compose.onNodeWithContentDescription("View mode: Unread").assertExists()

        compose.onNodeWithTag(ModeTags.BUTTON).performTouchInput { longClick() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(ModeTags.MENU).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(ModeTags.option(Filter.STARRED)).assertExists()
        // A popup menu is a window of its own; the full display shows the bar and the menu together.
        captureDisplay("mode-menu")

        back()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(ModeTags.MENU).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun aLargeFontLongListAndDuplicateTitles() {
        val feeds = buildList {
            add(feed("dup-1", "A duplicate", unread = 1))
            add(feed("dup-2", "A duplicate"))
            for (i in 1..40) add(feed("long-$i", "Feed %02d".format(i), unread = i % 3))
        }
        show(fontScale = 2f, tree = tree(feeds))
        openPicker()

        // Do not assume both rows are composed at once: scroll each into view by tag.
        val list = compose.onNodeWithTag(FeedPaneTags.LIST)
        for (duplicate in listOf("dup-1", "dup-2")) {
            list.performScrollToNode(hasTestTag(FeedPaneTags.scope(Scope.Feed(duplicate))))
            compose.onNodeWithTag(FeedPaneTags.scope(Scope.Feed(duplicate))).assertExists()
        }
        capture("large-font-duplicate-titles", pickerOnly = true)

        list.performScrollToNode(hasTestTag(FeedPaneTags.scope(Scope.Feed("long-40"))))
        compose.waitForIdle()
        capture("long-list", pickerOnly = true)
    }
}
