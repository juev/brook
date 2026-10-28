package org.evsyukov.brook.ui.list

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import org.evsyukov.brook.ui.home.HomeTags
import org.evsyukov.brook.ui.home.MarkAllReadDialog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class EntryListTest {
    @get:Rule
    val compose = createComposeRule()

    private val rows = listOf(
        EntryRow(1, "Feed", "First entry", "Preview of the first", 1_800_000_000, isRead = false, isStarred = false),
        EntryRow(2, "Feed", "Second entry", "", 1_799_000_000, isRead = true, isStarred = true),
    )

    private val opened = mutableListOf<Long>()
    private val read = mutableListOf<Long>()
    private val starred = mutableListOf<Long>()

    private val beside = mutableListOf<Pair<Long, Boolean>>()
    private val scrolledPast = mutableListOf<Long>()

    private fun show(
        list: List<EntryRow>,
        images: ListImages? = null,
        markOnScroll: Boolean = false,
        state: LazyListState? = null,
    ) {
        // Scrolling needs a list taller than the screen it has.
        val modifier = if (state != null) Modifier.height(400.dp) else Modifier
        compose.setContent {
            MaterialTheme {
                // A static page says loading is over; otherwise an empty one stays "loading" forever.
                val done = LoadState.NotLoading(endOfPaginationReached = true)
                val items = flowOf(PagingData.from(list, LoadStates(done, done, done))).collectAsLazyPagingItems()
                EntryList(
                    items = items,
                    selectedId = null,
                    onOpen = { opened += it.id },
                    onToggleRead = { read += it.id },
                    onToggleStar = { starred += it.id },
                    images = images,
                    onMarkBeside = { row, above -> beside += row.id to above },
                    onScrolledPast = if (markOnScroll) ({ rows -> scrolledPast += rows.map { it.id } }) else null,
                    state = state ?: rememberLazyListState(),
                    modifier = modifier,
                )
            }
        }
    }

    @Test
    fun rowsShowTitleAndPreviewAndOpenOnTap() {
        show(rows)
        compose.onNodeWithText("First entry").assertExists()
        compose.onNodeWithText("Preview of the first").assertExists()

        compose.onNodeWithTag(ListTags.row(2)).performClick()

        assertThat(opened).containsExactly(2L)
    }

    @Test
    fun unreadRowsCarryADotThatGoesWhenTheyAreRead() {
        var list by mutableStateOf(rows)
        compose.setContent {
            MaterialTheme {
                val done = LoadState.NotLoading(endOfPaginationReached = true)
                val items = remember(list) { flowOf(PagingData.from(list, LoadStates(done, done, done))) }.collectAsLazyPagingItems()
                EntryList(items = items, selectedId = null, onOpen = {}, onToggleRead = {}, onToggleStar = {})
            }
        }
        compose.onNodeWithTag(ListTags.unread(1), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(ListTags.unread(2), useUnmergedTree = true).assertDoesNotExist()

        list = rows.map { it.copy(isRead = it.id == 1L) }

        compose.onNodeWithTag(ListTags.unread(1), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(ListTags.unread(2), useUnmergedTree = true).assertExists()
    }

    @Test
    fun swipeToTheEndTogglesReadAndToTheStartTogglesStarred() {
        show(rows)

        compose.onNodeWithTag(ListTags.row(1)).performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithTag(ListTags.row(2)).performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertThat(read).containsExactly(1L)
        assertThat(starred).containsExactly(2L)
        assertThat(opened).isEmpty()
        // The rows come back in place for the next swipe.
        compose.onNodeWithTag(ListTags.row(1)).performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertThat(read).containsExactly(1L, 1L)
    }

    @Test
    fun eachDayStartsWithAHeader() {
        val zone = ZoneId.systemDefault()
        fun noon(daysAgo: Long) = LocalDate.now(zone).minusDays(daysAgo).atTime(12, 0).atZone(zone).toEpochSecond()
        show(
            listOf(
                EntryRow(1, "Feed", "One", "", noon(0), isRead = false, isStarred = false),
                EntryRow(2, "Feed", "Two", "", noon(0) - 60, isRead = false, isStarred = false),
                EntryRow(3, "Feed", "Three", "", noon(1), isRead = false, isStarred = false),
                EntryRow(4, "Feed", "Four", "", noon(5), isRead = false, isStarred = false),
            ),
        )

        compose.onAllNodesWithTag(ListTags.DAY).assertCountEquals(3)
        compose.onNodeWithText("Today").assertExists()
        compose.onNodeWithText("Yesterday").assertExists()
    }

    // Legacy graphics of Robolectric cannot make a bitmap.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun aThumbnailShowsWhenThePictureLoadsAndGoesWhenItCannot() {
        val loaded = mutableListOf<String>()
        show(
            listOf(
                EntryRow(1, "Feed", "With", "", 1_800_000_000, isRead = false, isStarred = false, imageUrl = "https://example.org/a.png"),
                EntryRow(2, "Feed", "Broken", "", 1_799_999_000, isRead = false, isStarred = false, imageUrl = "https://example.org/gone.png"),
                EntryRow(3, "Feed", "Without", "", 1_799_998_000, isRead = false, isStarred = false),
            ),
            images = { url, _ ->
                loaded += url
                if (url.endsWith("a.png")) ImageBitmap(4, 4) else null
            },
        )
        compose.waitForIdle()

        compose.onNodeWithTag(ListTags.thumbnail(1), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(ListTags.thumbnail(2), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(ListTags.thumbnail(3), useUnmergedTree = true).assertDoesNotExist()
        assertThat(loaded).containsExactly("https://example.org/a.png", "https://example.org/gone.png")
    }

    @Test
    fun aLongPressOffersToMarkTheEntriesAboveOrBelowRead() {
        show(rows)

        compose.onNodeWithTag(ListTags.row(2)).performTouchInput { longClick() }
        compose.onNodeWithTag(ListTags.MARK_ABOVE).performClick()
        compose.onNodeWithTag(ListTags.row(1)).performTouchInput { longClick() }
        compose.onNodeWithTag(ListTags.MARK_BELOW).performClick()

        assertThat(beside).containsExactly(2L to true, 1L to false).inOrder()
        assertThat(opened).isEmpty()
    }

    private fun many() = (1L..30L).map { EntryRow(it, "Feed", "Entry $it", "", 1_800_000_000 - it * 60, isRead = it == 2L, isStarred = false) }

    @Test
    fun unreadRowsScrolledOffTheTopAreHandedOverOnce() {
        val state = LazyListState()
        show(many(), markOnScroll = true, state = state)

        compose.runOnIdle { runBlocking { state.scrollToItem(5) } }
        compose.runOnIdle { runBlocking { state.scrollToItem(4) } }
        compose.runOnIdle { runBlocking { state.scrollToItem(6) } }
        compose.waitForIdle()

        // Entry 2 is read already; entry 5 left the screen only at the last scroll.
        assertThat(scrolledPast).containsExactly(1L, 3L, 4L, 5L, 6L).inOrder()
    }

    @Test
    fun rowsNeverOnScreenAreLeftAlone() {
        val state = LazyListState(firstVisibleItemIndex = 10)
        show(many(), markOnScroll = true, state = state)

        compose.runOnIdle { runBlocking { state.scrollToItem(12) } }
        compose.waitForIdle()

        assertThat(scrolledPast).containsExactly(11L, 12L).inOrder()
    }

    @Test
    fun withMarkOnScrollOffScrollingChangesNothing() {
        val state = LazyListState()
        show(many(), state = state)

        compose.runOnIdle { runBlocking { state.scrollToItem(10) } }
        compose.waitForIdle()

        assertThat(scrolledPast).isEmpty()
    }

    @Test
    fun anEmptyListSaysSo() {
        show(emptyList())
        compose.onNodeWithTag(ListTags.EMPTY).assertExists()
    }

    @Test
    fun markAllReadAsksFirst() {
        var confirmed = 0
        var dismissed = 0
        compose.setContent {
            MaterialTheme {
                MarkAllReadDialog("News", onConfirm = { confirmed++ }, onDismiss = { dismissed++ })
            }
        }
        compose.onNodeWithText("Every entry of News the list shows now will be marked read.").assertExists()
        compose.onNodeWithTag(HomeTags.MARK_ALL_READ_CONFIRM).performClick()

        assertThat(confirmed).isEqualTo(1)
        assertThat(dismissed).isEqualTo(0)
    }
}
