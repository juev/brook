package org.evsyukov.brook.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.evsyukov.brook.R
import org.evsyukov.brook.data.Filter
import org.evsyukov.brook.data.Scope
import org.evsyukov.brook.data.account.Account

object FeedPaneTags {
    const val LIST = "feed-pane-list"
    const val ALL = "feed-pane-all"
    const val MODE = "feed-pane-mode"
    const val EMPTY = "feed-pane-empty"
    const val CONTEXT = "feed-pane-context"
    fun scope(scope: Scope): String = when (scope) {
        Scope.All -> ALL
        is Scope.Feed -> "feed-pane-feed-" + scope.feedId
        is Scope.Category -> "feed-pane-category-" + scope.name
        is Scope.Label -> "feed-pane-label-" + scope.name
    }
}

/**
 * The flat, mode-aware feed pane: the account switcher, All feeds, the subscriptions of [filter], and
 * categories and labels as separate scopes. No feed is nested under a category, and the current feed
 * stays visible as a zero-match context row when the mode filters it out.
 *
 * The same content backs the permanent pane of large windows and the modal sheet of compact ones.
 * The app's own destinations (subscriptions management, settings) are not part of this list: they
 * live in the entry-list toolbar's overflow.
 */
@Composable
fun FeedPaneContent(
    tree: NavTree,
    selected: Scope,
    filter: Filter,
    onSelect: (Scope) -> Unit,
    accounts: List<Account>,
    active: Account,
    onSelectAccount: (Account) -> Unit,
    onAddAccount: () -> Unit,
    onRemoveAccount: (Account) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = tree.feedsFor(filter)
    val selectedFeed = selected as? Scope.Feed
    val selectedTitle = selectedFeed?.let { tree.title(it) }
    val selectedInList = selectedFeed != null && visible.any { it.id == selectedFeed.feedId }
    val itemModifier = Modifier.padding(horizontal = 12.dp)
    LazyColumn(modifier.testTag(FeedPaneTags.LIST)) {
        item { AccountSwitcher(accounts, active, onSelectAccount, onAddAccount, onRemoveAccount) }
        item { HorizontalDivider(Modifier.padding(bottom = 8.dp)) }
        item {
            Text(
                stringResource(filter.paneTitle()),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp).testTag(FeedPaneTags.MODE),
            )
        }
        item {
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.scope_all_feeds)) },
                icon = { Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = null) },
                badge = { if (filter != Filter.STARRED) Count(tree.unreadTotal) },
                selected = selected == Scope.All,
                onClick = { onSelect(Scope.All) },
                modifier = itemModifier.testTag(FeedPaneTags.ALL),
            )
        }
        item { Section(stringResource(R.string.section_feeds)) }
        if (!selectedInList && selectedFeed != null && selectedTitle != null && tree.feeds.isNotEmpty()) {
            item(key = "context") {
                CurrentFeedContext(selectedTitle, filter, selected, onSelect, itemModifier.testTag(FeedPaneTags.CONTEXT))
            }
        }
        if (visible.isEmpty()) {
            item { EmptyFeeds(filter) }
        } else {
            items(visible, key = { "feed/${it.id}" }) { feed ->
                FeedItem(feed, filter, selected, onSelect, itemModifier)
            }
        }
        if (tree.categories.isNotEmpty()) {
            item { Section(stringResource(R.string.section_categories)) }
            items(tree.categories, key = { "category/${it.name}" }) { category ->
                val scope = Scope.Category(category.name)
                NavigationDrawerItem(
                    label = { Label(category.name) },
                    icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    badge = { if (filter != Filter.STARRED) Count(category.unread) },
                    selected = selected == scope,
                    onClick = { onSelect(scope) },
                    modifier = itemModifier.testTag(FeedPaneTags.scope(scope)),
                )
            }
        }
        if (tree.labels.isNotEmpty()) {
            item { Section(stringResource(R.string.section_labels)) }
            items(tree.labels, key = { "label/${it.name}" }) { label ->
                val scope = Scope.Label(label.name)
                NavigationDrawerItem(
                    label = { Label(label.name) },
                    icon = { Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null) },
                    badge = { if (filter != Filter.STARRED) Count(label.unread) },
                    selected = selected == scope,
                    onClick = { onSelect(scope) },
                    modifier = itemModifier.testTag(FeedPaneTags.scope(scope)),
                )
            }
        }
    }
}

@Composable
private fun FeedItem(
    feed: FeedNode,
    filter: Filter,
    selected: Scope,
    onSelect: (Scope) -> Unit,
    modifier: Modifier,
) {
    val scope = Scope.Feed(feed.id)
    NavigationDrawerItem(
        label = { Label(feed.title) },
        icon = { Icon(Icons.Default.RssFeed, contentDescription = null) },
        // A count in Starred mode would read as a starred count; the picker shows none there.
        badge = { if (filter != Filter.STARRED) Count(feed.unread) },
        selected = selected == scope,
        onClick = { onSelect(scope) },
        modifier = modifier.testTag(FeedPaneTags.scope(scope)),
    )
}

/** The selected feed that the current mode filters out: kept as context, not as a matching row. */
@Composable
private fun CurrentFeedContext(
    title: String,
    filter: Filter,
    scope: Scope,
    onSelect: (Scope) -> Unit,
    modifier: Modifier,
) {
    val reason = when (filter) {
        Filter.UNREAD -> stringResource(R.string.feed_no_unread)
        Filter.STARRED -> stringResource(R.string.feed_no_starred)
        Filter.ALL -> stringResource(R.string.feed_no_entries)
    }
    NavigationDrawerItem(
        label = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        icon = { Icon(Icons.Default.RssFeed, contentDescription = null) },
        selected = true,
        onClick = { onSelect(scope) },
        modifier = modifier,
    )
}

@Composable
private fun EmptyFeeds(filter: Filter) {
    val text = when (filter) {
        Filter.ALL -> stringResource(R.string.no_feeds)
        Filter.UNREAD -> stringResource(R.string.no_unread_feeds)
        Filter.STARRED -> stringResource(R.string.no_starred_feeds)
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp).testTag(FeedPaneTags.EMPTY),
    )
}

@Composable
private fun Label(text: String) = Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)

@Composable
private fun Count(count: Int) {
    if (count > 0) {
        val description = pluralStringResource(R.plurals.unread_count, count, count)
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.semantics { contentDescription = description },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
    )
}

/** The title of a scope for the top bar, resolved from the full tree even when the mode hides it. */
fun NavTree.title(scope: Scope): String? = when (scope) {
    Scope.All -> null
    is Scope.Category -> scope.name
    is Scope.Label -> scope.name
    is Scope.Feed -> feeds.firstOrNull { it.id == scope.feedId }?.title
        ?: (categories.flatMap { it.feeds } + uncategorized).firstOrNull { it.id == scope.feedId }?.title
}
