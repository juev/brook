package org.evsyukov.brook.ui.list

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.evsyukov.brook.R
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

object ListTags {
    const val LIST = "entry-list"
    const val EMPTY = "entry-list-empty"
    const val DAY = "entry-list-day"
    const val MARK_ABOVE = "entry-list-mark-above"
    const val MARK_BELOW = "entry-list-mark-below"
    fun row(id: Long) = "entry-$id"
    fun thumbnail(id: Long) = "entry-thumbnail-$id"
}

/** Pictures of the list: feed icons and thumbnails, at least [sizePx] on the shorter side; null when there is none. */
fun interface ListImages {
    suspend fun load(url: String, sizePx: Int): ImageBitmap?
}

/**
 * The entries of the selection under a header for each day. A swipe to the end toggles read, a
 * swipe to the start toggles starred; the row comes back in place either way. A long press offers
 * [onMarkBeside], marking the entries above or below a row read. With [onScrolledPast], unread rows
 * the reader saw that scroll off the top are handed to it.
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
    images: ListImages? = null,
    onMarkBeside: ((row: EntryRow, above: Boolean) -> Unit)? = null,
    onScrolledPast: ((List<EntryRow>) -> Unit)? = null,
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
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val locale = LocalConfiguration.current.locales[0]
    val todayText = stringResource(R.string.day_today)
    val yesterdayText = stringResource(R.string.day_yesterday)
    if (onScrolledPast != null) ScrolledPast(items, state, onScrolledPast)
    LazyColumn(modifier.testTag(ListTags.LIST), state = state) {
        items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
            val row = items[index] ?: return@items
            val day = dayOf(row.time, zone)
            val previous = if (index > 0) items.peek(index - 1) else null
            Column {
                if (previous == null || dayOf(previous.time, zone) != day) {
                    DayHeader(dayLabel(day, today, locale, todayText, yesterdayText))
                }
                SwipeRow(row, onToggleRead, onToggleStar) {
                    EntryRowContent(row, selected = row.id == selectedId, images = images, onClick = { onOpen(row) }, onMarkBeside = onMarkBeside)
                }
            }
        }
    }
}

/**
 * Hands unread rows that were on screen and are now above it to [onScrolledPast], each once. Rows
 * never seen, above a list that opens scrolled down, stay as they are.
 */
@Composable
private fun ScrolledPast(items: LazyPagingItems<EntryRow>, state: LazyListState, onScrolledPast: (List<EntryRow>) -> Unit) {
    val current by rememberUpdatedState(onScrolledPast)
    LaunchedEffect(items, state) {
        val seen = HashSet<Any>()
        val handed = HashSet<Long>()
        snapshotFlow { state.firstVisibleItemIndex to state.layoutInfo.visibleItemsInfo.map { it.key } }.collect { (first, visible) ->
            seen.addAll(visible)
            val past = (0 until minOf(first, items.itemCount)).mapNotNull { items.peek(it) }
                .filter { !it.isRead && it.id in seen && it.id !in handed }
            if (past.isNotEmpty()) {
                past.mapTo(handed) { it.id }
                current(past)
            }
        }
    }
}

@Composable
private fun DayHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = TEXT_START, end = 16.dp, top = 20.dp, bottom = 4.dp)
            .testTag(ListTags.DAY),
    )
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
    val haptics = LocalHapticFeedback.current
    // A tick when the swipe passes the point where letting go acts.
    LaunchedEffect(state) {
        snapshotFlow { state.targetValue }.drop(1).collect { target ->
            if (target != SwipeToDismissBoxValue.Settled) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        }
    }
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

/** Where the text of a row starts: after the gutter and the feed icon. */
private val ICON_SIZE = 20.dp
private val TEXT_START = 16.dp + ICON_SIZE + 12.dp
private val THUMBNAIL_SIZE = 64.dp

@Composable
private fun EntryRowContent(
    row: EntryRow,
    selected: Boolean,
    images: ListImages?,
    onClick: () -> Unit,
    onMarkBeside: ((row: EntryRow, above: Boolean) -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val timeFormat = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (selected) colors.secondaryContainer else colors.surface)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = if (onMarkBeside == null) null else ({
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    }),
                )
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .testTag(ListTags.row(row.id)),
        ) {
            FeedIcon(row.iconUrl, images, Modifier.padding(top = 1.dp).size(ICON_SIZE))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.feedTitle.uppercase(locale),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.weight(1f))
                    if (row.isStarred) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = stringResource(R.string.starred),
                            tint = colors.tertiary,
                            modifier = Modifier.padding(start = 8.dp).size(14.dp),
                        )
                    }
                    Text(
                        timeFormat.format(Date(row.time * 1000)),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Row {
                    Column(Modifier.weight(1f)) {
                        Text(
                            row.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (row.isRead) FontWeight.Normal else FontWeight.SemiBold,
                            color = if (row.isRead) colors.onSurfaceVariant else colors.onSurface,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
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
                    if (row.imageUrl != null) Thumbnail(row, images)
                }
            }
        }
        if (onMarkBeside != null) {
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.mark_above_read)) },
                    leadingIcon = { Icon(Icons.Default.VerticalAlignTop, contentDescription = null) },
                    onClick = { menu = false; onMarkBeside(row, true) },
                    modifier = Modifier.testTag(ListTags.MARK_ABOVE),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.mark_below_read)) },
                    leadingIcon = { Icon(Icons.Default.VerticalAlignBottom, contentDescription = null) },
                    onClick = { menu = false; onMarkBeside(row, false) },
                    modifier = Modifier.testTag(ListTags.MARK_BELOW),
                )
            }
        }
    }
}

/** The icon of the feed, or a generic one while it loads or when the feed has none. */
@Composable
private fun FeedIcon(url: String, images: ListImages?, modifier: Modifier) {
    val bitmap = rememberListImage(url.takeIf { it.isNotEmpty() }, ICON_SIZE, images).value
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.clip(RoundedCornerShape(4.dp)))
    } else {
        Icon(Icons.Default.RssFeed, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
    }
}

/** The thumbnail on the right; a blank square while it loads, nothing when it cannot be had. */
@Composable
private fun Thumbnail(row: EntryRow, images: ListImages?) {
    val image = rememberListImage(row.imageUrl, THUMBNAIL_SIZE, images)
    if (image.failed) return
    val modifier = Modifier
        .padding(start = 12.dp, top = 4.dp)
        .size(THUMBNAIL_SIZE)
        .clip(RoundedCornerShape(8.dp))
        .testTag(ListTags.thumbnail(row.id))
    val bitmap = image.value
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh))
    }
}

private class ListImage(val value: ImageBitmap?, val failed: Boolean)

@Composable
private fun rememberListImage(url: String?, size: Dp, images: ListImages?): ListImage {
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val image by produceState(ListImage(null, failed = url == null || images == null), url, sizePx, images) {
        if (url == null || images == null) return@produceState
        value = images.load(url, sizePx)?.let { ListImage(it, failed = false) } ?: ListImage(null, failed = true)
    }
    return image
}
