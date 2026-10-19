package org.evsyukov.brook.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

@Serializable
data object HomeKey : NavKey

@Serializable
data object AddAccountKey : NavKey

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
            entry<HomeKey> { HomeRoute(container, list, current, onAddAccount = { backStack.add(AddAccountKey) }) }
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
private fun HomeRoute(container: AppContainer, accounts: List<Account>, active: Account, onAddAccount: () -> Unit) {
    val session by produceState<AccountSession?>(null, active.id) { value = container.accounts.session(active.id) }
    val current = session?.takeIf { it.account.id == active.id } ?: return
    // Everything below belongs to one account: switching starts it afresh, with its own view models.
    key(active.id) {
        val vm = viewModel(key = "home-${active.id}") { HomeViewModel(current) }
        val reader = viewModel(key = "reader-${active.id}") { ReaderViewModel(current) }
        val tree by vm.navTree.collectAsStateWithLifecycle()
        val selection by vm.selection.collectAsStateWithLifecycle()
        val items = vm.list.collectAsLazyPagingItems()
        val syncing by container.syncing.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) { container.requestSync(active.id) }

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
            detail = { id, onBack ->
                LaunchedEffect(id) { reader.show(id) }
                ReaderScreen(reader, onBack)
            },
        )
    }
}
