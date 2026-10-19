package org.evsyukov.brook.ui.list

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
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

    private fun show(list: List<EntryRow>) {
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
