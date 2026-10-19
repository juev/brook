package org.evsyukov.brook.ui.home

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.evsyukov.brook.R
import org.evsyukov.brook.data.Scope

object TreeTags {
    const val ALL = "tree-all"
    const val SETTINGS = "tree-settings"
    fun scope(scope: Scope) = "tree-" + scope.streamId
}

/** The tree of the drawer below [header]; a tap selects a scope, the arrow of a category opens its feeds. */
@Composable
fun NavDrawerContent(
    tree: NavTree,
    selected: Scope,
    onSelect: (Scope) -> Unit,
    header: @Composable () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val itemModifier = Modifier.padding(horizontal = 12.dp)
    LazyColumn(modifier) {
        item { header() }
        item { HorizontalDivider(Modifier.padding(bottom = 8.dp)) }
        item {
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.scope_all)) },
                icon = { Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = null) },
                badge = { Count(tree.unreadTotal) },
                selected = selected == Scope.All,
                onClick = { onSelect(Scope.All) },
                modifier = itemModifier.testTag(TreeTags.ALL),
            )
        }
        if (tree.categories.isNotEmpty() || tree.uncategorized.isNotEmpty()) {
            item { Section(stringResource(R.string.section_feeds)) }
        }
        for (category in tree.categories) {
            val scope = Scope.Category(category.name)
            val open = expanded[category.name] == true
            item(key = scope.streamId) {
                NavigationDrawerItem(
                    label = { Label(category.name) },
                    icon = {
                        IconButton(onClick = { expanded[category.name] = !open }) {
                            Icon(
                                if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = stringResource(if (open) R.string.collapse else R.string.expand),
                            )
                        }
                    },
                    badge = { Count(category.unread) },
                    selected = selected == scope,
                    onClick = { onSelect(scope) },
                    modifier = itemModifier.testTag(TreeTags.scope(scope)),
                )
            }
            if (open) {
                items(category.feeds, key = { "${category.name}/${it.id}" }) { feed ->
                    FeedItem(feed, selected, onSelect, Modifier.padding(start = 24.dp, end = 12.dp))
                }
            }
        }
        items(tree.uncategorized, key = { it.id }) { feed -> FeedItem(feed, selected, onSelect, itemModifier) }
        if (tree.labels.isNotEmpty()) {
            item { Section(stringResource(R.string.section_labels)) }
            items(tree.labels, key = { "label/${it.name}" }) { label ->
                val scope = Scope.Label(label.name)
                NavigationDrawerItem(
                    label = { Label(label.name) },
                    icon = { Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null) },
                    badge = { Count(label.unread) },
                    selected = selected == scope,
                    onClick = { onSelect(scope) },
                    modifier = itemModifier.testTag(TreeTags.scope(scope)),
                )
            }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item {
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.settings)) },
                icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                selected = false,
                onClick = onOpenSettings,
                modifier = itemModifier.testTag(TreeTags.SETTINGS),
            )
        }
    }
}

@Composable
private fun FeedItem(feed: FeedNode, selected: Scope, onSelect: (Scope) -> Unit, modifier: Modifier) {
    val scope = Scope.Feed(feed.id)
    NavigationDrawerItem(
        label = { Label(feed.title) },
        icon = { Icon(Icons.Default.RssFeed, contentDescription = null) },
        badge = { Count(feed.unread) },
        selected = selected == scope,
        onClick = { onSelect(scope) },
        modifier = modifier.testTag(TreeTags.scope(scope)),
    )
}

@Composable
private fun Label(text: String) = Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)

@Composable
private fun Count(count: Int) {
    if (count > 0) Text(count.toString(), style = MaterialTheme.typography.labelLarge)
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

/** The title of a scope for the top bar. */
fun NavTree.title(scope: Scope): String? = when (scope) {
    Scope.All -> null
    is Scope.Category -> scope.name
    is Scope.Label -> scope.name
    is Scope.Feed -> (categories.flatMap { it.feeds } + uncategorized).firstOrNull { it.id == scope.feedId }?.title
}
