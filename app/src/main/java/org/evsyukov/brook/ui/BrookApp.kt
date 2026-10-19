package org.evsyukov.brook.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.evsyukov.brook.AppContainer
import org.evsyukov.brook.data.account.Account
import org.evsyukov.brook.data.account.AccountSession
import org.evsyukov.brook.ui.home.HomeScreen
import org.evsyukov.brook.ui.home.HomeViewModel
import org.evsyukov.brook.ui.login.LoginScreen
import org.evsyukov.brook.ui.login.LoginViewModel
import org.evsyukov.brook.ui.reader.ReaderScreen
import org.evsyukov.brook.ui.reader.ReaderViewModel
import org.evsyukov.brook.ui.settings.SettingsScreen

@Serializable
data object HomeKey : NavKey

@Serializable
data object AddAccountKey : NavKey

@Serializable
data object SettingsKey : NavKey

/** Sign-in while there are no accounts; the shell of the active account otherwise. */
@Composable
fun BrookApp(container: AppContainer) {
    val accounts by container.accounts.accounts.collectAsStateWithLifecycle(initialValue = null)
    val active by container.accounts.active.collectAsStateWithLifecycle(initialValue = null)
    val list = accounts ?: return
    val current = active
    if (list.isEmpty() || current == null) {
        LoginRoute(container, key = "login-first", onBack = null, onDone = {})
        return
    }

    val backStack = rememberNavBackStack(HomeKey)
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<HomeKey> {
                HomeRoute(
                    container,
                    list,
                    current,
                    onAddAccount = { backStack.add(AddAccountKey) },
                    onOpenSettings = { backStack.add(SettingsKey) },
                )
            }
            entry<SettingsKey> { SettingsRoute(container, onBack = { backStack.removeLastOrNull() }) }
            entry<AddAccountKey> {
                LoginRoute(
                    container,
                    key = "login-add",
                    onBack = { backStack.removeLastOrNull() },
                    onDone = { backStack.removeLastOrNull() },
                )
            }
        },
    )
}

@Composable
private fun LoginRoute(container: AppContainer, key: String, onBack: (() -> Unit)?, onDone: () -> Unit) {
    // Entries of NavDisplay share the store of the activity, so each sign-in has a key of its own.
    val vm = viewModel(key = key) {
        LoginViewModel(
            signIn = container.accounts::add,
            onSignedIn = { _: Account -> onDone() },
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    LoginScreen(
        state = state,
        onServerChange = vm::setServer,
        onUserChange = vm::setUser,
        onPasswordChange = vm::setPassword,
        onSubmit = vm::submit,
        onBack = onBack,
    )
}

@Composable
private fun HomeRoute(
    container: AppContainer,
    accounts: List<Account>,
    active: Account,
    onAddAccount: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val session by produceState<AccountSession?>(null, active.id) { value = container.accounts.session(active.id) }
    val current = session?.takeIf { it.account.id == active.id } ?: return
    // Everything below belongs to one account: switching starts it afresh, with its own view models.
    key(active.id) {
        val vm = viewModel(key = "home-${active.id}") { HomeViewModel(current, onFullTextChanged = { container.requestPrefetch(active.id) }) }
        val reader = viewModel(key = "reader-${active.id}") { ReaderViewModel(current) }
        val tree by vm.navTree.collectAsStateWithLifecycle()
        val selection by vm.selection.collectAsStateWithLifecycle()
        val items = vm.list.collectAsLazyPagingItems()
        val feedFullText by vm.feedFullText.collectAsStateWithLifecycle()
        val syncing by container.syncing.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        val lifecycle = LocalLifecycleOwner.current.lifecycle

        // Only while the app is on screen; in the background the periodic work syncs (R4).
        LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { container.watch(active.id) } }

        HomeScreen(
            accounts = accounts,
            active = active,
            tree = tree,
            selection = selection,
            syncing = active.id in syncing,
            items = items,
            onSelectScope = vm::select,
            onSelectFilter = vm::select,
            onSelectAccount = { scope.launch { container.accounts.setActive(it.id) } },
            onAddAccount = onAddAccount,
            onRemoveAccount = { scope.launch { container.accounts.remove(it.id) } },
            onToggleRead = vm::toggleRead,
            onToggleStar = vm::toggleStar,
            onMarkAllRead = vm::markAllRead,
            onRefresh = { container.requestSync(active.id) },
            feedFullText = feedFullText,
            onFeedFullText = vm::setFeedFullText,
            onOpenSettings = onOpenSettings,
            detail = { id, onBack ->
                LaunchedEffect(id) { reader.show(id) }
                ReaderScreen(reader, onBack)
            },
        )
    }
}

@Composable
private fun SettingsRoute(container: AppContainer, onBack: () -> Unit) {
    val settings by container.settings.current.collectAsStateWithLifecycle()
    val accounts by container.accounts.accounts.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    // Notifications need the permission of Android 13; the switch turns on only once it is granted.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scope.launch { container.settings.setNotifyNewEntries(true) }
    }
    SettingsScreen(
        settings = settings,
        onBack = onBack,
        onSyncInterval = { scope.launch { container.settings.setSyncInterval(it) } },
        onPrefetchOnlyUnmetered = { value ->
            scope.launch {
                container.settings.setPrefetchOnlyUnmetered(value)
                accounts.forEach { container.requestPrefetch(it.id) }
            }
        },
        onReadRetention = { scope.launch { container.settings.setReadRetentionDays(it) } },
        onNotify = { on ->
            if (on) {
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                scope.launch { container.settings.setNotifyNewEntries(false) }
            }
        },
    )
}
