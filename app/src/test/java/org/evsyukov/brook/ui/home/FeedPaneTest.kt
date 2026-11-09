package org.evsyukov.brook.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.evsyukov.brook.data.Filter
import org.evsyukov.brook.data.Scope
import org.evsyukov.brook.data.account.Account
import org.evsyukov.brook.data.db.CategoryEntity
import org.evsyukov.brook.data.db.FeedEntity
import org.evsyukov.brook.data.db.LabelEntity
import org.evsyukov.brook.data.db.UnreadCount
import org.evsyukov.brook.ui.home.FeedPaneTags.EMPTY
import org.evsyukov.brook.ui.home.FeedPaneTags.CONTEXT
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeedPaneTest {
    @get:Rule
    val compose = createComposeRule()

    private val account = Account("a", "https://one.example/api/greader.php", "alice")

    private fun feed(id: String, title: String, category: String? = null) = FeedEntity(id, title, "https://$id", "", "", category)

    private fun tree(
        feeds: List<FeedEntity>,
        unread: Map<String, Int> = emptyMap(),
        starred: Map<String, Int> = emptyMap(),
        categories: List<CategoryEntity> = emptyList(),
        labels: List<LabelEntity> = emptyList(),
    ) = buildNavTree(
        categories = categories,
        feeds = feeds,
        labels = labels,
        unreadByFeed = unread.map { UnreadCount(it.key, it.value) },
        unreadByLabel = emptyList(),
        unreadTotal = unread.values.sum(),
        starredByFeed = starred.map { UnreadCount(it.key, it.value) },
    )

    private fun pane(tree: NavTree, selected: Scope = Scope.All, filter: Filter = Filter.ALL, onSelect: (Scope) -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                FeedPaneContent(
                    tree = tree,
                    selected = selected,
                    filter = filter,
                    onSelect = onSelect,
                    accounts = listOf(account),
                    active = account,
                    onSelectAccount = {},
                    onAddAccount = {},
                    onRemoveAccount = {},
                )
            }
        }
    }

    @Test
    fun `every feed stands once in one flat list and selects its scope`() {
        val picked = mutableListOf<Scope>()
        pane(
            tree(
                feeds = listOf(feed("a", "Alpha", "news"), feed("b", "Beta", "news"), feed("c", "Gamma")),
                categories = listOf(CategoryEntity("news")),
            ),
            onSelect = { picked += it },
        )

        compose.onNodeWithTag(FeedPaneTags.scope(Scope.Feed("a"))).assertExists()
        compose.onNodeWithTag(FeedPaneTags.scope(Scope.Feed("b"))).assertExists()
        compose.onNodeWithTag(FeedPaneTags.scope(Scope.Feed("c"))).assertExists()
        compose.onNodeWithTag(FeedPaneTags.scope(Scope.Feed("a"))).performClick()

        assertThat(picked).containsExactly(Scope.Feed("a"))
    }

    @Test
    fun `a category is a scope of its own and not a folder around feeds`() {
        pane(
            tree(
                feeds = listOf(feed("a", "Alpha", "news")),
                categories = listOf(CategoryEntity("news"), CategoryEntity("empty")),
                labels = listOf(LabelEntity("later")),
            ),
        )

        // The feed is a top-level row whether or not its category is expanded; the category and the
        // label are separate scopes with their own rows.
        compose.onNodeWithTag(FeedPaneTags.scope(Scope.Feed("a"))).assertExists()
        compose.onNodeWithTag(FeedPaneTags.scope(Scope.Category("news"))).assertExists()
        compose.onNodeWithTag(FeedPaneTags.scope(Scope.Category("empty"))).assertExists()
        // Labels sit below the feeds and the categories; the pane is a lazy list.
        val label = FeedPaneTags.scope(Scope.Label("later"))
        compose.onNodeWithTag(FeedPaneTags.LIST).performScrollToNode(hasTestTag(label))
        compose.onNodeWithTag(label).assertExists()
    }

    @Test
    fun `all feeds stays reachable beside a feed scope`() {
        val picked = mutableListOf<Scope>()
        pane(
            tree(feeds = listOf(feed("a", "Alpha"))),
            selected = Scope.Feed("a"),
            onSelect = { picked += it },
        )

        compose.onNodeWithTag(FeedPaneTags.ALL).assertExists().performClick()
        assertThat(picked).containsExactly(Scope.All)
    }

    @Test
    fun `a selected feed filtered out by the mode is kept as context not as a match`() {
        pane(
            tree(
                feeds = listOf(feed("a", "Alpha"), feed("b", "Beta")),
                unread = mapOf("b" to 1),
            ),
            selected = Scope.Feed("a"),
            filter = Filter.UNREAD,
        )

        compose.onNodeWithTag(CONTEXT).assertExists()
        compose.onNodeWithText("Alpha").assertExists()
        compose.onNodeWithText("Nothing unread here").assertExists()
        // The filtered feed is not in the matching list; the context row is the only row naming it.
        compose.onNodeWithTag(FeedPaneTags.scope(Scope.Feed("a"))).assertDoesNotExist()
        compose.onNodeWithTag(FeedPaneTags.scope(Scope.Feed("b"))).assertExists()
    }

    @Test
    fun `a mode with no feeds says so and keeps all feeds`() {
        pane(
            tree(feeds = listOf(feed("a", "Alpha"))),
            filter = Filter.UNREAD,
        )

        compose.onNodeWithTag(EMPTY).assertExists()
        compose.onNodeWithText("No feeds with unread entries").assertExists()
        compose.onNodeWithTag(FeedPaneTags.ALL).assertExists()
    }

    @Test
    fun `the pane names the mode and keeps the account switcher but no longer ends in app actions`() {
        pane(
            tree(feeds = listOf(feed("a", "Alpha")), unread = mapOf("a" to 2)),
            filter = Filter.UNREAD,
        )

        compose.onNodeWithTag(FeedPaneTags.MODE).assertExists()
        compose.onNodeWithText("Feeds with unread entries").assertExists()
        compose.onNodeWithTag(SwitcherTags.ACCOUNT).assertExists()
        // Subscriptions and Settings are app-level actions and belong to the toolbar overflow; the
        // pane lists accounts and feeds only.
        compose.onNodeWithText("Subscriptions").assertDoesNotExist()
        compose.onNodeWithText("Settings").assertDoesNotExist()
    }
}
