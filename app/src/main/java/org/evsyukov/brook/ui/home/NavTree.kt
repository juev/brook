package org.evsyukov.brook.ui.home

import org.evsyukov.brook.data.db.CategoryEntity
import org.evsyukov.brook.data.db.FeedEntity
import org.evsyukov.brook.data.db.LabelEntity
import org.evsyukov.brook.data.db.UnreadCount

/** The navigation tree: all subscriptions, categories with their feeds, feeds outside any, labels. */
data class NavTree(
    val unreadTotal: Int = 0,
    val categories: List<CategoryNode> = emptyList(),
    val uncategorized: List<FeedNode> = emptyList(),
    val labels: List<LabelNode> = emptyList(),
)

data class CategoryNode(val name: String, val unread: Int, val feeds: List<FeedNode>)

data class FeedNode(val id: String, val title: String, val iconUrl: String, val unread: Int)

data class LabelNode(val name: String, val unread: Int)

/**
 * Builds the tree from the database. The unread count of a category is the sum of its feeds.
 * A feed whose category is missing from the list of categories still gets one.
 */
fun buildNavTree(
    categories: List<CategoryEntity>,
    feeds: List<FeedEntity>,
    labels: List<LabelEntity>,
    unreadByFeed: List<UnreadCount>,
    unreadByLabel: List<UnreadCount>,
    unreadTotal: Int,
): NavTree {
    val feedUnread = unreadByFeed.associate { it.key to it.count }
    val labelUnread = unreadByLabel.associate { it.key to it.count }
    val nodes = feeds.map { it to FeedNode(it.id, it.title.ifBlank { it.url }, it.iconUrl, feedUnread[it.id] ?: 0) }
    val byCategory = nodes.filter { it.first.categoryName != null }.groupBy({ it.first.categoryName!! }, { it.second })
    val names = (categories.map { it.name } + byCategory.keys).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
    return NavTree(
        unreadTotal = unreadTotal,
        categories = names.map { name ->
            val children = byCategory[name].orEmpty()
            CategoryNode(name, children.sumOf { it.unread }, children)
        },
        uncategorized = nodes.filter { it.first.categoryName == null }.map { it.second },
        labels = labels.map { LabelNode(it.name, labelUnread[it.name] ?: 0) },
    )
}
