package org.evsyukov.brook.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asImageBitmap
import org.evsyukov.brook.data.images.Thumbnails
import org.evsyukov.brook.ui.list.ListImages
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
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
import org.evsyukov.brook.ui.manage.ManageScreen
import org.evsyukov.brook.ui.manage.ManageViewModel
import org.evsyukov.brook.ui.reader.ReaderScreen
import org.evsyukov.brook.ui.reader.ReaderViewModel
import org.evsyukov.brook.ui.settings.SettingsScreen

@Serializable
data object HomeKey : NavKey

@Serializable
data object AddAccountKey : NavKey

@Serializable
data object SettingsKey : NavKey

@Serializable
data object ManageKey : NavKey

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
                    onOpenManage = { backStack.add(ManageKey) },
                )
            }
            entry<ManageKey> { ManageRoute(container, current, onBack = { backStack.removeLastOrNull() }) }
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
    onOpenManage: () -> Unit,
) {
    val session by produceState<AccountSession?>(null, active.id) { value = container.accounts.session(active.id) }
    val current = session?.takeIf { it.account.id == active.id } ?: return
    // Everything below belongs to one account: switching starts it afresh, with its own view models.
    key(active.id) {
        val vm = viewModel(key = "home-${active.id}") { HomeViewModel(current, onFullTextChanged = { container.requestPrefetch(active.id) }) }
        val reader = viewModel(key = "reader-${active.id}") { ReaderViewModel(current) }
        val tree by vm.navTree.collectAsStateWithLifecycle()
        val selection by vm.selection.collectAsStateWithLifecycle()
        val query by vm.query.collectAsStateWithLifecycle()
        val items = vm.list(selection).collectAsLazyPagingItems()
        val searchResults = vm.searchResults.collectAsLazyPagingItems()
        val feedFullText by vm.feedFullText.collectAsStateWithLifecycle()
        val syncing by container.syncing.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val images = remember(current) {
            val thumbnails = Thumbnails(current.images)
            ListImages { url, sizePx -> thumbnails.load(url, sizePx)?.asImageBitmap() }
        }

        // Only while the app is on screen; in the background the periodic work syncs (R4).
        LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { container.watch(active.id) } }

        HomeScreen(
            accounts = accounts,
            active = active,
            tree = tree,
            selection = selection,
            query = query,
            onQueryChange = vm::setQuery,
            syncing = active.id in syncing,
            items = items,
            searchResults = searchResults,
            onSelectScope = vm::select,
            onSelectFilter = vm::select,
            onSelectAccount = { scope.launch { container.accounts.setActive(it.id) } },
            onAddAccount = onAddAccount,
            onRemoveAccount = { scope.launch { container.removeAccount(it.id) } },
            onToggleRead = vm::toggleRead,
            onToggleStar = vm::toggleStar,
            onMarkAllRead = vm::markAllRead,
            onRefresh = { container.requestSync(active.id) },
            feedFullText = feedFullText,
            onFeedFullText = vm::setFeedFullText,
            onOpenSettings = onOpenSettings,
            onOpenManage = onOpenManage,
            images = images,
            detail = { id, onBack ->
                LaunchedEffect(id) { reader.show(id) }
                ReaderScreen(reader, onBack)
            },
        )
    }
}

@Composable
private fun ManageRoute(container: AppContainer, active: Account, onBack: () -> Unit) {
    val session by produceState<AccountSession?>(null, active.id) { value = container.accounts.session(active.id) }
    val current = session?.takeIf { it.account.id == active.id } ?: return
    key(active.id) {
        val vm = viewModel(key = "manage-${active.id}") { ManageViewModel(current, sync = { container.sync(active.id) }) }
        val tree by vm.navTree.collectAsStateWithLifecycle()
        val state by vm.state.collectAsStateWithLifecycle()
        val resolver = LocalContext.current.contentResolver
        // Files come and go through the system picker (SAF); the stream is read off the main thread.
        val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                vm.importOpml {
                    withContext(Dispatchers.IO) {
                        resolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: throw IOException("Cannot open $uri")
                    }
                }
            }
        }
        val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(OPML_TYPE)) { uri ->
            if (uri != null) {
                vm.exportOpml { opml ->
                    withContext(Dispatchers.IO) {
                        resolver.openOutputStream(uri, "wt")?.use { it.write(opml.encodeToByteArray()) } ?: throw IOException("Cannot open $uri")
                    }
                }
            }
        }
        ManageScreen(
            tree = tree,
            state = state,
            onBack = onBack,
            onSubscribe = vm::subscribe,
            onRenameFeed = vm::renameFeed,
            onMoveFeed = vm::moveFeed,
            onUnsubscribe = vm::unsubscribe,
            onRenameCategory = vm::renameCategory,
            onDeleteCategory = vm::deleteCategory,
            onRenameLabel = vm::renameLabel,
            onDeleteLabel = vm::deleteLabel,
            // Files of OPML come under many types, text/xml and text/x-opml among them.
            onImport = { open.launch(arrayOf("text/xml", "application/xml", "text/x-opml", "application/octet-stream", "*/*")) },
            onExport = { create.launch("brook-subscriptions.opml") },
            onMessageShown = vm::messageShown,
        )
    }
}

private const val OPML_TYPE = "text/x-opml"

@Composable
private fun SettingsRoute(container: AppContainer, onBack: () -> Unit) {
    val settings by container.settings.current.collectAsStateWithLifecycle()
    val accounts by container.accounts.accounts.collectAsStateWithLifecycle(initialValue = emptyList())
    val push by container.push.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // A distributor may have been installed or removed since the start.
    LaunchedEffect(Unit) {
        try {
            container.push.reconcile()
        } catch (e: IOException) {
            // The stores could not be read; the next start tries again.
        }
    }
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
        push = push,
        accounts = accounts,
        onPush = { scope.launch { container.settings.setPush(it) } },
        onDistributor = { scope.launch { container.push.choose(it) } },
    )
}
