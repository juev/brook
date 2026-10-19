package org.evsyukov.brook.ui.list

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import kotlinx.coroutines.launch
import org.evsyukov.brook.R

object ListTags {
    const val LIST = "entry-list"
    const val EMPTY = "entry-list-empty"
    fun row(id: Long) = "entry-$id"
}

/**
 * The entries of the selection. A swipe to the end toggles read, a swipe to the start toggles
 * starred; the row comes back in place either way.
 */
@Composable
fun EntryList(
    items: LazyPagingItems<EntryRow>,
    selectedId: Long?,
    onOpen: (EntryRow) -> Unit,
    onToggleRead: (EntryRow) -> Unit,
    onToggleStar: (EntryRow) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
) {
    if (items.itemCount == 0 && items.loadState.refresh !is LoadState.Loading) {
        Box(modifier.testTag(ListTags.EMPTY), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.list_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(modifier.testTag(ListTags.LIST), state = state) {
        items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
            val row = items[index] ?: return@items
            SwipeRow(row, onToggleRead, onToggleStar) {
                EntryRowContent(row, selected = row.id == selectedId, onClick = { onOpen(row) })
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun SwipeRow(
    row: EntryRow,
    onToggleRead: (EntryRow) -> Unit,
    onToggleStar: (EntryRow) -> Unit,
    content: @Composable () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val toStart = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Row(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 24.dp),
                horizontalArrangement = if (toStart) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val icon = when {
                    toStart -> if (row.isStarred) Icons.Outlined.StarOutline else Icons.Default.Star
                    row.isRead -> Icons.Outlined.MarkEmailUnread
                    else -> Icons.Outlined.MarkEmailRead
                }
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        },
        onDismiss = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onToggleRead(row)
                SwipeToDismissBoxValue.EndToStart -> onToggleStar(row)
                SwipeToDismissBoxValue.Settled -> Unit
            }
            scope.launch { state.reset() }
        },
    ) {
        content()
    }
}

@Composable
private fun EntryRowContent(row: EntryRow, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (selected) colors.secondaryContainer else colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(ListTags.row(row.id)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.feedTitle,
                style = MaterialTheme.typography.labelMedium,
                color = colors.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                DateUtils.getRelativeTimeSpanString(row.time * 1000).toString(),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            if (row.isStarred) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = stringResource(R.string.starred),
                    tint = colors.tertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Text(
            row.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (row.isRead) FontWeight.Normal else FontWeight.SemiBold,
            color = if (row.isRead) colors.onSurfaceVariant else colors.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (row.preview.isNotEmpty()) {
            Text(
                row.preview,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
