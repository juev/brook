package org.evsyukov.brook.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import org.evsyukov.brook.R
import org.evsyukov.brook.data.Filter
import org.evsyukov.brook.data.Scope
import org.evsyukov.brook.data.account.Account
import org.evsyukov.brook.ui.list.EntryRow
import org.evsyukov.brook.ui.list.ListTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val scopes = mutableListOf<Scope>()
    private val filters = mutableListOf<Filter>()
    private val fullText = mutableListOf<Boolean>()
    private val marked = mutableListOf<Unit>()

    private val account = Account("a", "http://localhost/api/greader.php", "alice")
    private val otherAccount = Account("b", "http://other/api/greader.php", "bob")
    private val tree = NavTree(
        unreadTotal = 27,
        categories = listOf(CategoryNode("News", 27, emptyList())),
        feeds = listOf(FeedNode("f1", "Feed one", "", unread = 5, starred = 1)),
    )

    private fun show(
        large: Boolean? = false,
        canMarkAllRead: Boolean = true,
        startFilter: Filter = Filter.UNREAD,
        startScope: Scope = Scope.Category("News"),
        hasFeedFullText: Boolean = false,
        navTree: NavTree = tree,
        selectionController: MutableState<Selection>? = null,
        activeController: MutableState<Account>? = null,
        staleBulk: BulkAvailability? = null,
    ) {
        compose.setContent {
            val fallback = remember { mutableStateOf(Selection(scope = startScope, filter = startFilter)) }
            var selection by (selectionController ?: fallback)
            var query by remember { mutableStateOf<String?>(null) }
            val active = activeController?.value ?: account
            MaterialTheme {
                val done = LoadState.NotLoading(endOfPaginationReached = true)
                val items = remember { flowOf(PagingData.from(emptyList<EntryRow>(), LoadStates(done, done, done))) }.collectAsLazyPagingItems()
                HomeScreen(
                    accounts = listOf(account, otherAccount),
                    active = active,
                    tree = navTree,
                    selection = selection,
                    query = query,
                    onQueryChange = { query = it },
                    syncing = false,
                    items = items,
                    searchResults = items,
                    onSelectScope = {
                        scopes += it
                        selection = selection.copy(scope = it)
                    },
                    onSelectFilter = {
                        filters += it
                        selection = selection.copy(filter = it)
                    },
                    bulkAvailability = staleBulk ?: BulkAvailability(selection, canMarkAllRead),
                    onSelectAccount = {},
                    onAddAccount = {},
                    onRemoveAccount = {},
                    onToggleRead = {},
                    onToggleStar = {},
                    onMarkAllRead = { marked += Unit },
                    onMarkBeside = { _, _ -> },
                    onScrolledPast = null,
                    onRefresh = {},
                    feedFullText = if (hasFeedFullText) false else null,
                    onFeedFullText = { fullText += it },
                    onOpenSettings = {},
                    onOpenManage = {},
                    images = null,
                    detail = { _, _, _ -> },
                    largeScreen = large,
                )
            }
        }
    }

    private fun more() {
        val label = ApplicationProvider.getApplicationContext<android.content.Context>().getString(R.string.more)
        compose.onNodeWithContentDescription(label).performClick()
    }

    /** The mode button is a 48 dp control, so its measured height also gives the screen density. */
    private fun pxPerDp(): Float =
        compose.onNodeWithTag(ModeTags.BUTTON).fetchSemanticsNode().boundsInRoot.height / 48f

    /** Copies of the title that share the mode button's row, wherever else the tree puts a copy. */
    private fun titleCopiesInTheToolbarRow(title: String): Int {
        val row = compose.onNodeWithTag(ModeTags.BUTTON).fetchSemanticsNode().boundsInRoot
        return compose.onAllNodesWithText(title, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { it.boundsInRoot }
            .count { it.top >= row.top - 0.5f && it.bottom <= row.bottom + 0.5f }
    }

    /** Distance from the bottom of the toolbar control to the top of the list area. */
    private fun listTopBelowTheToolbar(): Float {
        val row = compose.onNodeWithTag(ModeTags.BUTTON).fetchSemanticsNode().boundsInRoot
        return compose.onNodeWithTag(ListTags.EMPTY).fetchSemanticsNode().boundsInRoot.top - row.bottom
    }

    @Test
    fun theToolbarHasOneTitleAndNoSubtitleBlock() {
        show()

        compose.onNodeWithTag(HomeTags.SUBTITLE).assertDoesNotExist()
        assertThat(titleCopiesInTheToolbarRow("News")).isEqualTo(1)
        // The list starts in the same row's shadow: no expanded title or count block pushes it down.
        assertThat(listTopBelowTheToolbar()).isAtMost(16f * pxPerDp())
    }

    @Test
    fun theWideToolbarHasOneTitleAndNoSubtitleBlock() {
        show(large = true)

        compose.onNodeWithTag(HomeTags.SUBTITLE).assertDoesNotExist()
        assertThat(titleCopiesInTheToolbarRow("News")).isEqualTo(1)
        assertThat(listTopBelowTheToolbar()).isAtMost(16f * pxPerDp())
    }

    @Test
    fun aShortPressOnStarredGoesToUnread() {
        show(startFilter = Filter.STARRED)
        compose.onNodeWithContentDescription("View mode: Starred").assertExists()

        compose.onNodeWithTag(ModeTags.BUTTON).performClick()

        compose.onNodeWithContentDescription("View mode: Unread").assertExists()
        assertThat(filters).containsExactly(Filter.UNREAD)
    }

    @Test
    fun aLongPressOpensTheModeMenuWithoutChangingTheMode() {
        show()
        compose.onNodeWithTag(ModeTags.BUTTON).performTouchInput { longClick() }

        compose.onNodeWithTag(ModeTags.MENU).assertIsDisplayed()
        compose.onNodeWithTag(ModeTags.option(Filter.UNREAD)).assertIsSelected()
        assertThat(filters).isEmpty()
        compose.onNodeWithContentDescription("View mode: Unread").assertExists()

        compose.onNodeWithTag(ModeTags.option(Filter.STARRED)).performClick()

        compose.onNodeWithTag(ModeTags.MENU).assertDoesNotExist()
        compose.onNodeWithContentDescription("View mode: Starred").assertExists()
        assertThat(filters).containsExactly(Filter.STARRED)
    }

    @Test
    fun theOverflowOpensTheSameModeMenu() {
        show()
        more()
        compose.onNodeWithTag(HomeTags.VIEW_MODE).performClick()

        compose.onNodeWithTag(ModeTags.MENU).assertIsDisplayed()
        compose.onNodeWithTag(ModeTags.option(Filter.ALL)).performClick()

        compose.onNodeWithContentDescription("View mode: All").assertExists()
        assertThat(filters).containsExactly(Filter.ALL)
    }

    @Test
    fun theModeButtonNamesItsState() {
        show()
        compose.onNodeWithContentDescription("View mode: Unread").assertExists()
    }

    @Test
    fun theTitleOpensThePickerAndChoosingAFeedKeepsTheMode() {
        show()
        compose.onNodeWithTag(HomeTags.TITLE_PICKER).performClick()

        compose.onNodeWithTag(HomeTags.PANE).assertIsDisplayed()
        compose.onNodeWithTag(FeedPaneTags.scope(Scope.Feed("f1"))).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(HomeTags.PANE).assertDoesNotExist()
        assertThat(scopes).containsExactly(Scope.Feed("f1"))
        assertThat(filters).isEmpty()
        compose.onNodeWithContentDescription("View mode: Unread").assertExists()
        compose.onNodeWithText("Feed one").assertExists()
    }

    @Test
    fun largeWindowsKeepThePaneAndHaveNoPicker() {
        show(large = true)

        compose.onNodeWithTag(FeedPaneTags.ALL).assertIsDisplayed()
        compose.onNodeWithTag(HomeTags.TITLE_PICKER).assertDoesNotExist()
        compose.onNodeWithTag(HomeTags.PANE).assertDoesNotExist()
        compose.onNodeWithTag(ModeTags.BUTTON).assertExists()
    }

    @Test
    fun theBulkIsOffWhenNothingMatches() {
        show(canMarkAllRead = false, startFilter = Filter.STARRED)
        compose.onNodeWithTag(HomeTags.MARK_ALL_READ).assertIsNotEnabled()
    }

    @Test
    fun theBulkConfirmationNamesTheScopeAndTheStarredRestriction() {
        show(canMarkAllRead = true, startFilter = Filter.STARRED)
        compose.onNodeWithTag(HomeTags.MARK_ALL_READ).assertIsEnabled()
        compose.onNodeWithTag(HomeTags.MARK_ALL_READ).performClick()

        compose.onNodeWithText("Only starred entries of News", substring = true).assertExists()
        compose.onNodeWithText("entries without a star stay as they are", substring = true).assertExists()
    }

    @Test
    fun aPendingBulkOfAFeedDoesNotBroadenToAllWhenTheScopeChanges() {
        val selection = mutableStateOf(Selection(scope = Scope.Feed("f1"), filter = Filter.UNREAD))
        show(selectionController = selection)
        compose.onNodeWithTag(HomeTags.MARK_ALL_READ).performClick()
        compose.onNodeWithTag(HomeTags.MARK_ALL_READ_CONFIRM).assertExists()

        // The feed is gone, so the owner of the selection moves to All.
        selection.value = Selection(scope = Scope.All, filter = Filter.UNREAD)
        compose.waitForIdle()

        compose.onNodeWithTag(HomeTags.MARK_ALL_READ_CONFIRM).assertDoesNotExist()
        assertThat(marked).isEmpty()
    }

    @Test
    fun aPendingBulkConfirmationDiesWhenTheModeChanges() {
        val selection = mutableStateOf(Selection(scope = Scope.Category("News"), filter = Filter.UNREAD))
        show(selectionController = selection)
        compose.onNodeWithTag(HomeTags.MARK_ALL_READ).performClick()

        selection.value = selection.value.copy(filter = Filter.STARRED)
        compose.waitForIdle()

        compose.onNodeWithTag(HomeTags.MARK_ALL_READ_CONFIRM).assertDoesNotExist()
        assertThat(marked).isEmpty()
    }

    @Test
    fun aPendingBulkConfirmationDiesWhenTheAccountChanges() {
        val active = mutableStateOf(account)
        show(activeController = active)
        compose.onNodeWithTag(HomeTags.MARK_ALL_READ).performClick()

        active.value = otherAccount
        compose.waitForIdle()

        compose.onNodeWithTag(HomeTags.MARK_ALL_READ_CONFIRM).assertDoesNotExist()
        assertThat(marked).isEmpty()
    }

    @Test
    fun aLongTitleIsTruncatedAndKeepsItsChevron() {
        val longTitle = "A feed with a title so long that it cannot fit beside the chevron on a phone, not even close"
        val longTree = NavTree(
            unreadTotal = 5,
            feeds = listOf(FeedNode("f1", longTitle, "", unread = 5)),
        )
        show(startScope = Scope.Feed("f1"), navTree = longTree)

        // The flexible bar composes its title more than once; take the copy laid out in the picker.
        val picker = compose.onNodeWithTag(HomeTags.TITLE_PICKER).fetchSemanticsNode().boundsInRoot
        fun inside(rect: androidx.compose.ui.geometry.Rect) =
            rect.left >= picker.left - 0.5f && rect.right <= picker.right + 0.5f

        val chevron = compose.onAllNodesWithTag(HomeTags.TITLE_CHEVRON, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { it.boundsInRoot }
            .firstOrNull(::inside)
        assertThat(chevron).isNotNull()
        val text = compose.onAllNodesWithText(longTitle, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { it.boundsInRoot }
            .first(::inside)

        assertThat(text.right).isAtMost(chevron!!.left)
    }

    @Test
    fun aLongTitleIsOneEllipsizedLineOnAWideWindow() {
        val longTitle = "A feed with a title so long that it cannot fit across the permanent pane, not even close"
        val longTree = NavTree(
            unreadTotal = 5,
            feeds = listOf(FeedNode("f1", longTitle, "", unread = 5)),
        )
        show(large = true, startScope = Scope.Feed("f1"), navTree = longTree)

        // The bar may compose the title more than once; every copy must stay on one ellipsized line.
        val layouts = compose.onAllNodesWithText(longTitle, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .mapNotNull { node ->
                val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult) ?: return@mapNotNull null
                val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                action.action?.invoke(results)
                results.firstOrNull()
            }
        // The bar keeps zero-width copies for its expanded/collapsed crossfade; only the laid-out
        // copy proves how the title is measured.
        val visible = layouts.filter { it.size.width > 0 }
        assertThat(visible).isNotEmpty()
        visible.forEach { layout ->
            assertThat(layout.lineCount).isEqualTo(1)
            assertThat(layout.hasVisualOverflow).isTrue()
        }
    }

    @Test
    fun theBulkOfAnotherSelectionDoesNotEnableTheButton() {
        val selection = mutableStateOf(Selection(scope = Scope.Feed("f1"), filter = Filter.UNREAD))
        val stale = BulkAvailability(Selection(scope = Scope.Category("News"), filter = Filter.UNREAD), true)
        show(selectionController = selection, staleBulk = stale)

        compose.onNodeWithTag(HomeTags.MARK_ALL_READ).assertIsNotEnabled()
    }

    @Test
    fun searchSitsInTheBarOnAWideWindow() {
        show(large = true)

        // The Robolectric window is narrow even when the branch is the wide one; the emulator proof
        // checks the real width. Here the direct action must exist without opening the overflow.
        compose.onNodeWithTag(HomeTags.SEARCH).assertExists()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag(HomeTags.SEARCH_FIELD).assertExists()
    }

    @Test
    fun searchAndFullTextStayReachableFromTheOverflow() {
        show(hasFeedFullText = true)
        more()
        compose.onNodeWithTag(HomeTags.FEED_FULL_TEXT).assertExists().performClick()
        assertThat(fullText).containsExactly(true)

        more()
        compose.onNodeWithTag(HomeTags.SEARCH).performClick()
        compose.onNodeWithTag(HomeTags.SEARCH_FIELD).assertExists()
    }
}
