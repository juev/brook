package org.evsyukov.brook.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.window.core.layout.WindowSizeClass
import kotlinx.coroutines.launch
import org.evsyukov.brook.R
import org.evsyukov.brook.data.Filter
import org.evsyukov.brook.data.Scope
import org.evsyukov.brook.data.account.Account
import org.evsyukov.brook.data.nextOnShortPress
import org.evsyukov.brook.ui.list.EntryList
import org.evsyukov.brook.ui.list.EntryRow
import org.evsyukov.brook.ui.list.ListImages

object HomeTags {
    const val MARK_ALL_READ = "mark-all-read"
    const val FEED_FULL_TEXT = "feed-full-text"
    const val NEW_BANNER = "new-entries-banner"
    const val MARK_ALL_READ_CONFIRM = "mark-all-read-confirm"
    const val SEARCH = "search"
    const val SEARCH_FIELD = "search-field"
    const val SEARCH_CLOSE = "search-close"
    const val SUBTITLE = "list-subtitle"
    const val TITLE_PICKER = "feed-title-picker"
    const val TITLE_CHEVRON = "title-chevron"
    const val PANE = "feed-pane"
    const val VIEW_MODE = "view-mode"
}

/** The bulk dialog and the target it was opened for; a changed target dismisses it. */
private data class PendingBulk(val selection: Selection, val account: Account)

/**
 * The shell. Compact windows show the feed picker in a title-triggered modal sheet; large ones keep
 * the same flat pane permanently. The mode button leads, the bulk read and the overflow trail, and
 * the list sits under a large title with the unread count of the scope.
 *
 * [largeScreen] is a seam for previews and tests; null reads the window.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(
    accounts: List<Account>,
    active: Account,
    tree: NavTree,
    selection: Selection,
    query: String?,
    onQueryChange: (String?) -> Unit,
    syncing: Boolean,
    /** The list of [selection]; a new selection comes with new items, not with the rows of the old one. */
    items: LazyPagingItems<EntryRow>,
    searchResults: LazyPagingItems<EntryRow>,
    onSelectScope: (Scope) -> Unit,
    onSelectFilter: (Filter) -> Unit,
    /** The bulk read of [selection] and whether it has anything to do; tagged with its selection. */
    bulkAvailability: BulkAvailability?,
    onSelectAccount: (Account) -> Unit,
    onAddAccount: () -> Unit,
    onRemoveAccount: (Account) -> Unit,
    onToggleRead: (EntryRow) -> Unit,
    onToggleStar: (EntryRow) -> Unit,
    onMarkAllRead: (Selection) -> Unit,
    /** Marks read the entries above or below a row of the list. */
    onMarkBeside: (row: EntryRow, above: Boolean) -> Unit,
    /** With mark-on-scroll on, takes rows that scrolled off the top; null when it is off. */
    onScrolledPast: ((List<EntryRow>) -> Unit)?,
    onRefresh: () -> Unit,
    feedFullText: Boolean?,
    onFeedFullText: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenManage: () -> Unit,
    images: ListImages?,
    /** The entry [entryId]; [onOpen] shows another one in its place, back still leading to the list. */
    detail: @Composable (entryId: Long, onBack: (() -> Unit)?, onOpen: (Long) -> Unit) -> Unit,
    largeScreen: Boolean? = null,
) {
    val permanentDrawer = largeScreen ?: currentWindowAdaptiveInfoV2().windowSizeClass
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_LARGE_LOWER_BOUND)
    val navigator = rememberListDetailPaneScaffoldNavigator<Long>()
    val openId = navigator.currentDestination?.contentKey
    // The reader moves to the next entry in place: the back stack keeps the one opened from the list.
    var shownId by rememberSaveable(openId) { mutableStateOf(openId) }
    val listHidden = navigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Hidden
    var removing by remember { mutableStateOf<Account?>(null) }
    var confirmMarkAll by remember { mutableStateOf<PendingBulk?>(null) }
    var menu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    var paneOpen by rememberSaveable { mutableStateOf(false) }
    // A list of its own per selection, as [items] are: a shared one would keep the row of the old list
    // on top, the list being keyed, and hide the newer entries of the new one above it.
    val listState = rememberSaveable(selection, saver = LazyListState.Saver) { LazyListState() }
    // Results of a search scroll on their own: the list keeps its place and its count of new entries.
    val searchState = rememberLazyListState()
    val atTop by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 } }
    // The newest entry the reader has seen at the top. Entries a sync puts above it keep the rows on
    // screen in place (the list is keyed), and the banner counts them.
    val searching = query != null
    var topSeenId by remember(selection) { mutableStateOf<Long?>(null) }
    LaunchedEffect(atTop, items.itemCount, selection, searching) {
        if (atTop && items.itemCount > 0 && !searching) topSeenId = items.peek(0)?.id
    }
    BackHandler(enabled = searching && !listHidden) { onQueryChange(null) }
    val newAbove = if (searching) 0 else topSeenId?.let { id -> items.itemSnapshotList.items.indexOfFirst { it.id == id } }?.coerceAtLeast(0) ?: 0
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val canMarkAllRead = bulkAvailability?.takeIf { it.selection == selection }?.canMark == true
    // A dialog names a target; when the target moves, the confirmation no longer applies.
    LaunchedEffect(selection, active) { confirmMarkAll = null }

    val title = tree.title(selection.scope) ?: stringResource(R.string.scope_all)
    val pane: @Composable (Modifier) -> Unit = { modifier ->
        FeedPaneContent(
            tree = tree,
            selected = selection.scope,
            filter = selection.filter,
            onSelect = {
                onSelectScope(it)
                paneOpen = false
            },
            accounts = accounts,
            active = active,
            onSelectAccount = { account ->
                onSelectAccount(account)
                paneOpen = false
            },
            onAddAccount = {
                onAddAccount()
                paneOpen = false
            },
            onRemoveAccount = {
                removing = it
                paneOpen = false
            },
            onOpenSettings = {
                onOpenSettings()
                paneOpen = false
            },
            onOpenManage = {
                onOpenManage()
                paneOpen = false
            },
            modifier = modifier,
        )
    }

    val listPane: @Composable () -> Unit = {
        val titleScroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        Scaffold(
            modifier = Modifier.nestedScroll(titleScroll.nestedScrollConnection),
            topBar = {
                if (query != null) {
                    SearchBar(query = query, onQueryChange = onQueryChange)
                } else LargeFlexibleTopAppBar(
                    title = {
                        if (permanentDrawer) {
                            // The permanent pane leaves the bar its own width; a long title keeps to
                            // one line there too instead of crowding the actions.
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        } else {
                            val choose = stringResource(R.string.choose_feed, title)
                            Row(
                                Modifier
                                    .testTag(HomeTags.TITLE_PICKER)
                                    .heightIn(min = 48.dp)
                                    .clickable(role = Role.Button, onClickLabel = choose) { paneOpen = true }
                                    .semantics(mergeDescendants = true) { contentDescription = choose },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // The text takes what the chevron leaves and ellipsizes there; the
                                // chevron keeps its place however long the title is.
                                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp).testTag(HomeTags.TITLE_CHEVRON),
                                )
                            }
                        }
                    },
                    subtitle = {
                        // The title names the scope; the subtitle says which filter is on.
                        val unread = tree.unread(selection.scope)
                        val count = pluralStringResource(R.plurals.unread_count, unread, unread)
                        val text = when (selection.filter) {
                            Filter.UNREAD -> stringResource(R.string.list_subtitle, stringResource(R.string.filter_unread), unread.toString())
                            Filter.STARRED -> stringResource(R.string.list_subtitle, stringResource(R.string.filter_starred), count)
                            Filter.ALL -> stringResource(R.string.list_subtitle, stringResource(R.string.filter_all_entries), count)
                        }
                        Text(text, modifier = Modifier.testTag(HomeTags.SUBTITLE))
                    },
                    scrollBehavior = titleScroll,
                    navigationIcon = {
                        ModeButton(
                            filter = selection.filter,
                            menuOpen = modeMenu,
                            onShortPress = { onSelectFilter(selection.filter.nextOnShortPress()) },
                            onMenuOpen = { modeMenu = true },
                            onMenuDismiss = { modeMenu = false },
                            onSelect = {
                                modeMenu = false
                                onSelectFilter(it)
                            },
                        )
                    },
                    actions = {
                        IconButton(
                            onClick = { confirmMarkAll = PendingBulk(selection, active) },
                            enabled = canMarkAllRead,
                            modifier = Modifier.testTag(HomeTags.MARK_ALL_READ),
                        ) {
                            Icon(Icons.Default.DoneAll, contentDescription = stringResource(R.string.mark_all_read))
                        }
                        if (permanentDrawer) {
                            IconButton(
                                onClick = { onQueryChange("") },
                                modifier = Modifier.testTag(HomeTags.SEARCH),
                            ) {
                                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search))
                            }
                        }
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.view_mode)) },
                                    leadingIcon = { Icon(selection.filter.icon(), contentDescription = null) },
                                    onClick = {
                                        menu = false
                                        modeMenu = true
                                    },
                                    modifier = Modifier.testTag(HomeTags.VIEW_MODE),
                                )
                                // On a wide window the search sits in the bar itself.
                                if (!permanentDrawer) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.search)) },
                                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                        onClick = {
                                            menu = false
                                            onQueryChange("")
                                        },
                                        modifier = Modifier.testTag(HomeTags.SEARCH),
                                    )
                                }
                                if (feedFullText != null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.feed_always_full_text)) },
                                        leadingIcon = { Checkbox(checked = feedFullText, onCheckedChange = null) },
                                        onClick = {
                                            menu = false
                                            onFeedFullText(!feedFullText)
                                        },
                                        modifier = Modifier.testTag(HomeTags.FEED_FULL_TEXT),
                                    )
                                }
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                PullToRefreshBox(
                    isRefreshing = syncing,
                    onRefresh = onRefresh,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    EntryList(
                        items = if (searching) searchResults else items,
                        selectedId = if (listHidden) null else shownId,
                        onOpen = { row ->
                            // The same entry opened again after moving on from it keeps the key of the destination.
                            shownId = row.id
                            scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, row.id) }
                        },
                        onToggleRead = onToggleRead,
                        onToggleStar = onToggleStar,
                        modifier = Modifier.fillMaxSize(),
                        state = if (searching) searchState else listState,
                        images = images,
                        // Both act on the list of the selection, not on search results.
                        onMarkBeside = if (searching) null else onMarkBeside,
                        onScrolledPast = if (searching) null else onScrolledPast,
                    )
                    NewEntriesBanner(
                        count = newAbove,
                        onClick = { scope.launch { listState.animateScrollToItem(0) } },
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                    )
                }
            }
        }
    }

    val main: @Composable () -> Unit = {
        NavigableListDetailPaneScaffold(
            navigator = navigator,
            listPane = { AnimatedPane { listPane() } },
            detailPane = {
                AnimatedPane {
                    val id = shownId
                    if (id != null) {
                        detail(id, if (listHidden) ({ scope.launch { navigator.navigateBack() } }) else null) { shownId = it }
                    } else {
                        Surface(Modifier.fillMaxSize()) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    stringResource(R.string.reader_none),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
        )
    }

    if (permanentDrawer) {
        PermanentNavigationDrawer(
            drawerContent = {
                PermanentDrawerSheet(Modifier.width(320.dp)) {
                    pane(Modifier.fillMaxHeight())
                }
            },
        ) { main() }
    } else {
        main()
        if (paneOpen) {
            ModalBottomSheet(
                onDismissRequest = { paneOpen = false },
                sheetState = sheetState,
                modifier = Modifier.testTag(HomeTags.PANE),
            ) {
                pane(Modifier.fillMaxHeight(0.9f))
            }
        }
    }

    removing?.let { account ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.account_remove_title)) },
            text = { Text(stringResource(R.string.account_remove_text, account.user, account.host)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    onRemoveAccount(account)
                }) { Text(stringResource(R.string.account_remove_confirm)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    confirmMarkAll?.let { pending ->
        MarkAllReadDialog(
            scopeTitle = tree.title(pending.selection.scope) ?: stringResource(R.string.scope_all),
            filter = pending.selection.filter,
            onConfirm = {
                val current = confirmMarkAll
                confirmMarkAll = null
                // The target moved between the tap and the confirmation: never widen it silently.
                if (current == pending && selection == pending.selection && active == pending.account) {
                    // The view model checks the same target again before it writes anything.
                    onMarkAllRead(pending.selection)
                }
            },
            onDismiss = { confirmMarkAll = null },
        )
    }
}

/** The top bar while searching: the field, focused at once, and a way out. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(query: String, onQueryChange: (String?) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = { onQueryChange(null) }, modifier = Modifier.testTag(HomeTags.SEARCH_CLOSE)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.search_close))
            }
        },
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(HomeTags.SEARCH_FIELD),
            )
        },
        actions = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.search_clear))
                }
            }
        },
    )
}

/** [filter] names what the bulk will touch, so a Starred scope never reads as a whole-feed action. */
@Composable
fun MarkAllReadDialog(scopeTitle: String, filter: Filter, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mark_all_read_title)) },
        text = {
            Text(
                if (filter == Filter.STARRED) {
                    stringResource(R.string.mark_all_read_text_starred, scopeTitle)
                } else {
                    stringResource(R.string.mark_all_read_text, scopeTitle)
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(HomeTags.MARK_ALL_READ_CONFIRM)) {
                Text(stringResource(R.string.mark_all_read_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** "N new" over the list while new entries are above what the reader sees. */
@Composable
fun NewEntriesBanner(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = count > 0, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        Button(onClick = onClick, modifier = Modifier.testTag(HomeTags.NEW_BANNER)) {
            Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(pluralStringResource(R.plurals.new_entries, count, count))
        }
    }
}
