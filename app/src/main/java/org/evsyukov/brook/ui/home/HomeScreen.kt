package org.evsyukov.brook.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import kotlinx.coroutines.launch
import org.evsyukov.brook.R
import org.evsyukov.brook.data.Filter
import org.evsyukov.brook.data.Scope
import org.evsyukov.brook.data.account.Account

/**
 * The shell: the tree in a drawer, permanent on wide windows and modal otherwise, and the list of
 * the selection with the filter switcher on top. The list itself comes in [content].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    accounts: List<Account>,
    active: Account,
    tree: NavTree,
    selection: Selection,
    syncing: Boolean,
    onSelectScope: (Scope) -> Unit,
    onSelectFilter: (Filter) -> Unit,
    onSelectAccount: (Account) -> Unit,
    onAddAccount: () -> Unit,
    onRemoveAccount: (Account) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val wide = currentWindowAdaptiveInfo().windowSizeClass
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var removing by remember { mutableStateOf<Account?>(null) }

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
        )
    }

    val main: @Composable () -> Unit = {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(tree.title(selection.scope) ?: stringResource(R.string.scope_all)) },
                    navigationIcon = {
                        if (!wide) {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.open_tree))
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Box(Modifier.fillMaxWidth()) {
                    if (syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                FilterSwitcher(
                    filter = selection.filter,
                    onSelect = onSelectFilter,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                content(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }

    if (wide) {
        PermanentNavigationDrawer(drawerContent = { PermanentDrawerSheet(Modifier.width(320.dp)) { drawer() } }) { main() }
    } else {
        ModalNavigationDrawer(drawerState = drawerState, drawerContent = { ModalDrawerSheet { drawer() } }) { main() }
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
}

/** Until the list of step 5: what is selected. */
@Composable
fun SelectionPlaceholder(tree: NavTree, selection: Selection, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.list_placeholder, tree.title(selection.scope) ?: stringResource(R.string.scope_all)),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
