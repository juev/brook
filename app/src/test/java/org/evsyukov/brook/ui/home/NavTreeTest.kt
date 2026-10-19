package org.evsyukov.brook.ui.home

import com.google.common.truth.Truth.assertThat
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
    fun `the title of a scope comes from the tree`() {
        assertThat(tree.title(Scope.All)).isNull()
        assertThat(tree.title(Scope.Feed("feed/3"))).isEqualTo("feed/3")
        assertThat(tree.title(Scope.Category("news"))).isEqualTo("news")
        assertThat(tree.title(Scope.Label("later"))).isEqualTo("later")
    }
}
