package org.evsyukov.brook.ui.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.evsyukov.brook.data.db.EntryEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderActionsTest {
    @get:Rule
    val compose = createComposeRule()

    private val entry = EntryEntity(
        id = 1, feedId = "feed/1", title = "Entry", author = "", url = "https://example.org/e/1", sortTime = 1,
        content = "<p>Text</p>", enclosuresJson = "", isRead = true, isStarred = true, readAt = 1,
    )

    private val calls = mutableListOf<String>()

    private fun bar(state: ReaderState) {
        compose.setContent {
            MaterialTheme {
                ReaderTopBar(
                    state = state,
                    onBack = { calls += "back" },
                    onToggleStar = { calls += "star" },
                    onToggleFullText = { calls += "full" },
                    onMarkUnread = { calls += "unread" },
                    onLabels = { calls += "labels" },
                    onOpenOriginal = { calls += "open" },
                    onShare = { calls += "share" },
                )
            }
        }
    }

    @Test
    fun barShowsTheStateAndCallsTheActions() {
        bar(ReaderState(entry = entry, showFullText = false))
        compose.onNodeWithTag(ReaderTags.STAR).assertIsOn()
        compose.onNodeWithTag(ReaderTags.FULL_TEXT).assertIsOff()

        compose.onNodeWithTag(ReaderTags.STAR).performClick()
        compose.onNodeWithTag(ReaderTags.FULL_TEXT).performClick()
        for (tag in listOf(ReaderTags.MARK_UNREAD, ReaderTags.LABELS, ReaderTags.OPEN, ReaderTags.SHARE)) {
            compose.onNodeWithTag(ReaderTags.MORE).performClick()
            compose.onNodeWithTag(tag).performClick()
        }

        assertThat(calls).containsExactly("star", "full", "unread", "labels", "open", "share").inOrder()
    }

    @Test
    fun anEntryWithoutAddressCannotBeOpenedOrShared() {
        bar(ReaderState(entry = entry.copy(url = "")))
        compose.onNodeWithTag(ReaderTags.MORE).performClick()

        compose.onNodeWithTag(ReaderTags.MARK_UNREAD).assertExists()
        compose.onNodeWithTag(ReaderTags.OPEN).assertDoesNotExist()
        compose.onNodeWithTag(ReaderTags.SHARE).assertDoesNotExist()
    }

    @Test
    fun labelsDialogTogglesAndAdds() {
        val changes = mutableListOf<Pair<String, Boolean>>()
        compose.setContent {
            MaterialTheme {
                LabelsDialog(
                    allLabels = listOf("later", "work"),
                    selected = setOf("later"),
                    onToggle = { label, on -> changes += label to on },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag(ReaderTags.label("later")).assertIsOn()
        compose.onNodeWithTag(ReaderTags.label("later")).performClick()
        compose.onNodeWithTag(ReaderTags.label("work")).performClick()
        compose.onNodeWithTag(ReaderTags.NEW_LABEL).performTextInput("  fresh ")
        compose.onNodeWithTag(ReaderTags.ADD_LABEL).performClick()

        assertThat(changes).containsExactly("later" to false, "work" to true, "fresh" to true).inOrder()
    }
}
