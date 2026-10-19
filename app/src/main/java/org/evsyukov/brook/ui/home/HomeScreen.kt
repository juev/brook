package org.evsyukov.brook.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.window.core.layout.WindowSizeClass
import kotlinx.coroutines.launch
import org.evsyukov.brook.R
import org.evsyukov.brook.data.Filter
import org.evsyukov.brook.data.Scope
import org.evsyukov.brook.data.account.Account
import org.evsyukov.brook.ui.list.EntryList
import org.evsyukov.brook.ui.list.EntryRow

object HomeTags {
    const val MARK_ALL_READ = "mark-all-read"
    const val FEED_FULL_TEXT = "feed-full-text"
    const val NEW_BANNER = "new-entries-banner"
    const val MARK_ALL_READ_CONFIRM = "mark-all-read-confirm"
}

/**
 * The shell: the tree in a drawer, permanent on large windows and modal otherwise; the list with
 * the filter switcher on top; the entry in [detail], beside the list from expanded widths on and
 * in place of it below.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun HomeScreen(
    accounts: List<Account>,
    active: Account,
    tree: NavTree,
    selection: Selection,
    syncing: Boolean,
    items: LazyPagingItems<EntryRow>,
    onSelectScope: (Scope) -> Unit,
    onSelectFilter: (Filter) -> Unit,
    onSelectAccount: (Account) -> Unit,
    onAddAccount: () -> Unit,
    onRemoveAccount: (Account) -> Unit,
    onToggleRead: (EntryRow) -> Unit,
    onToggleStar: (EntryRow) -> Unit,
    onMarkAllRead: () -> Unit,
    onRefresh: () -> Unit,
    feedFullText: Boolean?,
    onFeedFullText: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    detail: @Composable (entryId: Long, onBack: (() -> Unit)?) -> Unit,
) {
    val permanentDrawer = currentWindowAdaptiveInfoV2().windowSizeClass
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_LARGE_LOWER_BOUND)
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val navigator = rememberListDetailPaneScaffoldNavigator<Long>()
    val openId = navigator.currentDestination?.contentKey
    val listHidden = navigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Hidden
    var removing by remember { mutableStateOf<Account?>(null) }
    var confirmMarkAll by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 } }
    // The newest entry the reader has seen at the top. Entries a sync puts above it keep the rows on
    // screen in place (the list is keyed), and the banner counts them.
    var topSeenId by remember(selection) { mutableStateOf<Long?>(null) }
    LaunchedEffect(atTop, items.itemCount, selection) {
        if (atTop && items.itemCount > 0) topSeenId = items.peek(0)?.id
    }
    val newAbove = topSeenId?.let { id -> items.itemSnapshotList.items.indexOfFirst { it.id == id } }?.coerceAtLeast(0) ?: 0

    val drawer: @Composable () -> Unit = {
        NavDrawerContent(
            tree = tree,
            selected = selection.scope,
            onSelect = {
                onSelectScope(it)
                scope.launch { drawerState.close() }
            },
            header = {
                AccountSwitcher(
                    accounts = accounts,
                    active = active,
                    onSelect = {
                        onSelectAccount(it)
                        scope.launch { drawerState.close() }
                    },
                    onAdd = {
                        onAddAccount()
                        scope.launch { drawerState.close() }
                    },
                    onRemove = { removing = it },
                )
            },
            onOpenSettings = {
                onOpenSettings()
                scope.launch { drawerState.close() }
            },
        )
    }

    val listPane: @Composable () -> Unit = {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(tree.title(selection.scope) ?: stringResource(R.string.scope_all)) },
                    navigationIcon = {
                        if (!permanentDrawer) {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.open_tree))
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { confirmMarkAll = true }, modifier = Modifier.testTag(HomeTags.MARK_ALL_READ)) {
                            Icon(Icons.Default.DoneAll, contentDescription = stringResource(R.string.mark_all_read))
                        }
                        if (feedFullText != null) {
                            Box {
                                IconButton(onClick = { menu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more))
                                }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
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
                FilterSwitcher(
                    filter = selection.filter,
                    onSelect = onSelectFilter,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                PullToRefreshBox(
                    isRefreshing = syncing,
                    onRefresh = onRefresh,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    EntryList(
                        items = items,
                        selectedId = if (listHidden) null else openId,
                        onOpen = { row -> scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, row.id) } },
                        onToggleRead = onToggleRead,
                        onToggleStar = onToggleStar,
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
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
                    val id = openId
                    if (id != null) {
                        detail(id, if (listHidden) ({ scope.launch { navigator.navigateBack() } }) else null)
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
        PermanentNavigationDrawer(drawerContent = { PermanentDrawerSheet(Modifier.width(320.dp)) { drawer() } }) { main() }
    } else {
        // While the entry covers the list, a swipe from the edge belongs to the entry, not to the tree.
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = !listHidden || drawerState.isOpen,
            drawerContent = { ModalDrawerSheet { drawer() } },
        ) { main() }
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

    if (confirmMarkAll) {
        MarkAllReadDialog(
            scopeTitle = tree.title(selection.scope) ?: stringResource(R.string.scope_all),
            onConfirm = {
                confirmMarkAll = false
                onMarkAllRead()
            },
            onDismiss = { confirmMarkAll = false },
        )
    }
}

@Composable
fun MarkAllReadDialog(scopeTitle: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mark_all_read_title)) },
        text = { Text(stringResource(R.string.mark_all_read_text, scopeTitle)) },
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
