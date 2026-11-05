package org.evsyukov.brook.ui.home

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.evsyukov.brook.data.Filter
import org.evsyukov.brook.data.Scope
import org.evsyukov.brook.data.db.CategoryEntity
import org.evsyukov.brook.data.db.FeedEntity
import org.evsyukov.brook.data.db.LabelEntity
import org.evsyukov.brook.data.db.UnreadCount
import org.junit.Test

class NavTreeTest {
    private fun feed(id: String, category: String?, title: String = id) = FeedEntity(id, title, "https://$id", "", "", category)

    private val tree = buildNavTree(
        categories = listOf(CategoryEntity("news"), CategoryEntity("Blogs"), CategoryEntity("Empty")),
        feeds = listOf(
            feed("feed/1", "news"),
            feed("feed/2", "news"),
            feed("feed/3", "Blogs"),
            feed("feed/4", "Missing"),
            feed("feed/5", null, title = ""),
        ),
        labels = listOf(LabelEntity("later"), LabelEntity("work")),
        unreadByFeed = listOf(UnreadCount("feed/1", 3), UnreadCount("feed/2", 4), UnreadCount("feed/4", 1)),
        unreadByLabel = listOf(UnreadCount("later", 2)),
        unreadTotal = 9,
        starredByFeed = listOf(UnreadCount("feed/2", 1), UnreadCount("feed/5", 2)),
    )

    @Test
    fun `a category counts the unread of its feeds`() {
        val byName = tree.categories.associateBy { it.name }
        assertThat(byName.getValue("news").unread).isEqualTo(7)
        assertThat(byName.getValue("news").feeds.map { it.id }).containsExactly("feed/1", "feed/2")
        assertThat(byName.getValue("Blogs").unread).isEqualTo(0)
        assertThat(byName.getValue("Empty").feeds).isEmpty()
    }

    @Test
    fun `categories are sorted ignoring case and include ones only feeds name`() {
        assertThat(tree.categories.map { it.name }).containsExactly("Blogs", "Empty", "Missing", "news").inOrder()
        assertThat(tree.categories.first { it.name == "Missing" }.unread).isEqualTo(1)
    }

    @Test
    fun `feeds without a category stand alone and a feed without a title shows its address`() {
        assertThat(tree.uncategorized.map { it.title }).containsExactly("https://feed/5")
    }

    @Test
    fun `labels carry their unread counts and the total is kept`() {
        assertThat(tree.labels).containsExactly(LabelNode("later", 2), LabelNode("work", 0)).inOrder()
        assertThat(tree.unreadTotal).isEqualTo(9)
    }

    @Test
    fun `the unread count of a scope comes from the tree`() {
        assertThat(tree.unread(Scope.All)).isEqualTo(9)
        assertThat(tree.unread(Scope.Category("news"))).isEqualTo(7)
        assertThat(tree.unread(Scope.Feed("feed/4"))).isEqualTo(1)
        assertThat(tree.unread(Scope.Label("later"))).isEqualTo(2)
        assertThat(tree.unread(Scope.Feed("feed/gone"))).isEqualTo(0)
    }

    @Test
    fun `the title of a scope comes from the tree`() {
        assertThat(tree.title(Scope.All)).isNull()
        assertThat(tree.title(Scope.Feed("feed/3"))).isEqualTo("feed/3")
        assertThat(tree.title(Scope.Feed("feed/4"))).isEqualTo("feed/4")
        assertThat(tree.title(Scope.Category("news"))).isEqualTo("news")
        assertThat(tree.title(Scope.Label("later"))).isEqualTo("later")
    }

    @Test
    fun `every subscription is in the flat list once even under different categories`() {
        assertThat(tree.feeds.map { it.id }).containsExactly("feed/1", "feed/2", "feed/3", "feed/4", "feed/5")
        assertThat(tree.feeds.map { it.id }).containsNoDuplicates()
    }

    @Test
    fun `all mode keeps feeds without local entries`() {
        // feed/3 and feed/5 have no unread count and no entries at all.
        assertThat(tree.feedsFor(Filter.ALL).map { it.id }).containsExactly("feed/1", "feed/2", "feed/3", "feed/4", "feed/5")
    }

    @Test
    fun unreadModeListsOnlyFeedsWithUnreadEntries() {
        // The proof mutation makes Unread include every feed, the old no-filter picker: this
        // assertion, and only it, must go red with this message.
        assertWithMessage("FLAT_UNREAD_MUST_EXCLUDE_FEEDS_WITHOUT_UNREAD")
            .that(tree.feedsFor(Filter.UNREAD).map { it.id })
            .containsExactly("feed/1", "feed/2", "feed/4")
    }

    @Test
    fun `starred mode lists a feed whose starred entry is already read`() {
        // The counts come from the same Room row as the unread ones: a read but starred entry still
        // makes the feed a Starred feed, exactly as the entry list keeps it.
        assertThat(tree.feedsFor(Filter.STARRED).map { it.id }).containsExactly("feed/2", "feed/5")
    }

    @Test
    fun `flat feeds are ordered by title ignoring case with the id breaking ties`() {
        val flat = buildNavTree(
            categories = emptyList(),
            feeds = listOf(
                feed("z", null, title = "alpha"),
                feed("a", null, title = "Beta"),
                feed("b", null, title = "alpha"),
                feed("c", null, title = "gamma"),
            ),
            labels = emptyList(),
            unreadByFeed = emptyList(),
            unreadByLabel = emptyList(),
            unreadTotal = 0,
        )
        // alpha (z, b) before Beta (a), ignoring case; equal titles fall back to the id.
        assertThat(flat.feeds.map { it.id }).containsExactly("b", "z", "a", "c").inOrder()
    }

    @Test
    fun `a scope is still in the tree while the mode hides it`() {
        assertThat(tree.has(Scope.Feed("feed/3"))).isTrue()
        assertThat(tree.has(Scope.Feed("feed/gone"))).isFalse()
    }
}
