package org.evsyukov.brook.ui.home

import org.evsyukov.brook.data.Filter
import org.evsyukov.brook.data.Scope
import org.evsyukov.brook.data.db.CategoryEntity
import org.evsyukov.brook.data.db.FeedEntity
import org.evsyukov.brook.data.db.LabelEntity
import org.evsyukov.brook.data.db.UnreadCount

/**
 * The navigation model: all subscriptions flat, the categories and labels that stay reachable as
 * scopes of their own, and the counts of the active account. Categories never gate a feed in
 * [feeds]: a feed of a category, an uncategorized feed and a feed whose category is missing all
 * appear once in the same list.
 */
data class NavTree(
    val unreadTotal: Int = 0,
    val categories: List<CategoryNode> = emptyList(),
    val uncategorized: List<FeedNode> = emptyList(),
    val labels: List<LabelNode> = emptyList(),
    /** Every subscription, once, in the order of the flat feed picker. */
    val feeds: List<FeedNode> = emptyList(),
)

/** Whether [scope] is still in the tree; a list of a scope that is gone would stay empty. */
fun NavTree.has(scope: Scope): Boolean = when (scope) {
    Scope.All -> true
    is Scope.Category -> categories.any { it.name == scope.name }
    is Scope.Label -> labels.any { it.name == scope.name }
    is Scope.Feed -> feeds.any { it.id == scope.feedId } || (categories.flatMap { it.feeds } + uncategorized).any { it.id == scope.feedId }
}

/** Unread entries of [scope], as the tree counts them; 0 for a scope no longer in it. */
fun NavTree.unread(scope: Scope): Int = when (scope) {
    Scope.All -> unreadTotal
    is Scope.Category -> categories.firstOrNull { it.name == scope.name }?.unread ?: 0
    is Scope.Label -> labels.firstOrNull { it.name == scope.name }?.unread ?: 0
    is Scope.Feed -> feeds.firstOrNull { it.id == scope.feedId }?.unread
        ?: (categories.flatMap { it.feeds } + uncategorized).firstOrNull { it.id == scope.feedId }?.unread
        ?: 0
}

/**
 * The feeds the flat picker shows in [filter]. All keeps feeds without cached entries, Unread takes
 * feeds with at least one actually unread entry, Starred takes feeds with a starred entry even when
 * that entry is already read.
 */
fun NavTree.feedsFor(filter: Filter): List<FeedNode> = when (filter) {
    Filter.ALL -> feeds
    Filter.UNREAD -> feeds.filter { it.unread > 0 }
    Filter.STARRED -> feeds.filter { it.starred > 0 }
}

data class CategoryNode(val name: String, val unread: Int, val feeds: List<FeedNode>)

data class FeedNode(val id: String, val title: String, val iconUrl: String, val unread: Int, val starred: Int = 0)

data class LabelNode(val name: String, val unread: Int)

/**
 * Builds the tree from the database. The unread count of a category is the sum of its feeds.
 * A feed whose category is missing from the list of categories still gets one. [feeds] is flat:
 * case-insensitive title order, and the feed id breaks ties between equal titles.
 */
fun buildNavTree(
    categories: List<CategoryEntity>,
    feeds: List<FeedEntity>,
    labels: List<LabelEntity>,
    unreadByFeed: List<UnreadCount>,
    unreadByLabel: List<UnreadCount>,
    unreadTotal: Int,
    starredByFeed: List<UnreadCount> = emptyList(),
): NavTree {
    val feedUnread = unreadByFeed.associate { it.key to it.count }
    val feedStarred = starredByFeed.associate { it.key to it.count }
    val labelUnread = unreadByLabel.associate { it.key to it.count }
    val nodes = feeds.map {
        it to FeedNode(
            id = it.id,
            title = it.title.ifBlank { it.url },
            iconUrl = it.iconUrl,
            unread = feedUnread[it.id] ?: 0,
            starred = feedStarred[it.id] ?: 0,
        )
    }
    val byCategory = nodes.filter { it.first.categoryName != null }.groupBy({ it.first.categoryName!! }, { it.second })
    val names = (categories.map { it.name } + byCategory.keys).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
    val flat = nodes.map { it.second }.sortedWith(
        compareBy(String.CASE_INSENSITIVE_ORDER) { node: FeedNode -> node.title }.thenBy { it.id },
    )
    return NavTree(
        unreadTotal = unreadTotal,
        categories = names.map { name ->
            val children = byCategory[name].orEmpty()
            CategoryNode(name, children.sumOf { it.unread }, children)
        },
        uncategorized = nodes.filter { it.first.categoryName == null }.map { it.second },
        labels = labels.map { LabelNode(it.name, labelUnread[it.name] ?: 0) },
        feeds = flat,
    )
}
