package org.evsyukov.brook.ui.manage

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.evsyukov.brook.ui.home.CategoryNode
import org.evsyukov.brook.ui.home.FeedNode
import org.evsyukov.brook.ui.home.LabelNode
import org.evsyukov.brook.ui.home.NavTree
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManageScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val feed = FeedNode("feed/1", "One", "", 0)
    private val tree = NavTree(
        categories = listOf(CategoryNode("News", 0, listOf(feed)), CategoryNode("Blogs", 0, emptyList())),
        labels = listOf(LabelNode("later", 0)),
    )
    private val calls = mutableListOf<String>()

    private fun show(state: ManageState = ManageState()) {
        compose.setContent {
            MaterialTheme {
                ManageScreen(
                    tree = tree,
                    state = state,
                    onBack = {},
                    onSubscribe = { calls += "subscribe $it" },
                    onRenameFeed = { id, title -> calls += "rename $id $title" },
                    onMoveFeed = { id, category -> calls += "move $id $category" },
                    onUnsubscribe = { calls += "unsubscribe $it" },
                    onRenameCategory = { name, newName -> calls += "rename-category $name $newName" },
                    onDeleteCategory = { calls += "delete-category $it" },
                    onRenameLabel = { name, newName -> calls += "rename-label $name $newName" },
                    onDeleteLabel = { calls += "delete-label $it" },
                    onImport = { calls += "import" },
                    onExport = { calls += "export" },
                    onMessageShown = {},
                )
            }
        }
    }

    @Test
    fun addsAFeedByAddress() {
        show()
        compose.onNodeWithTag(ManageTags.ADD).performClick()
        compose.onNodeWithTag(ManageTags.FIELD).performTextInput(" https://y.org/rss ")
        compose.onNodeWithTag(ManageTags.CONFIRM).performClick()

        assertThat(calls).containsExactly("subscribe https://y.org/rss")
    }

    @Test
    fun movesAFeedIntoAnotherOrANewCategory() {
        show()
        compose.onNodeWithTag(ManageTags.feed("feed/1") + "-menu").performClick()
        compose.onNodeWithTag(ManageTags.MOVE).performClick()
        compose.onNodeWithTag(ManageTags.option("Blogs")).performClick()
        compose.onNodeWithTag(ManageTags.CONFIRM).performClick()

        compose.onNodeWithTag(ManageTags.feed("feed/1") + "-menu").performClick()
        compose.onNodeWithTag(ManageTags.MOVE).performClick()
        compose.onNodeWithTag(ManageTags.NEW_CATEGORY).performClick()
        compose.onNodeWithTag(ManageTags.FIELD).performTextInput("Tech")
        compose.onNodeWithTag(ManageTags.CONFIRM).performClick()

        assertThat(calls).containsExactly("move feed/1 Blogs", "move feed/1 Tech").inOrder()
    }

    @Test
    fun renamesAndDeletesAfterConfirmation() {
        show()
        compose.onNodeWithTag(ManageTags.label("later") + "-menu").performClick()
        compose.onNodeWithTag(ManageTags.RENAME).performClick()
        compose.onNodeWithTag(ManageTags.FIELD).performTextReplacement("Later on")
        compose.onNodeWithTag(ManageTags.CONFIRM).performClick()

        compose.onNodeWithTag(ManageTags.category("News") + "-menu").performClick()
        compose.onNodeWithTag(ManageTags.DELETE).performClick()
        compose.onNodeWithTag(ManageTags.CONFIRM).performClick()

        compose.onNodeWithTag(ManageTags.feed("feed/1") + "-menu").performClick()
        compose.onNodeWithTag(ManageTags.DELETE).performClick()
        compose.onNodeWithTag(ManageTags.CONFIRM).performClick()

        assertThat(calls).containsExactly("rename-label later Later on", "delete-category News", "unsubscribe feed/1").inOrder()
    }

    @Test
    fun opmlFromTheMenu() {
        show()
        compose.onNodeWithTag(ManageTags.MENU).performClick()
        compose.onNodeWithTag(ManageTags.IMPORT).performClick()
        compose.onNodeWithTag(ManageTags.MENU).performClick()
        compose.onNodeWithTag(ManageTags.EXPORT).performClick()

        assertThat(calls).containsExactly("import", "export").inOrder()
    }
}
