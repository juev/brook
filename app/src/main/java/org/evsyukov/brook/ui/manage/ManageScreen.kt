package org.evsyukov.brook.ui.manage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.evsyukov.brook.R
import org.evsyukov.brook.ui.home.FeedNode
import org.evsyukov.brook.ui.home.NavTree

object ManageTags {
    const val ADD = "manage-add"
    const val MENU = "manage-menu"
    const val IMPORT = "manage-import"
    const val EXPORT = "manage-export"
    const val FIELD = "manage-field"
    const val CONFIRM = "manage-confirm"
    const val NEW_CATEGORY = "manage-new-category"
    fun feed(id: String) = "manage-feed-$id"
    fun category(name: String) = "manage-category-$name"
    fun label(name: String) = "manage-label-$name"
    fun option(name: String) = "manage-option-$name"
    const val RENAME = "manage-rename"
    const val MOVE = "manage-move"
    const val DELETE = "manage-delete"
}

/** What the person is asked about. */
private sealed interface Ask {
    data object Add : Ask
    data class RenameFeed(val feed: FeedNode) : Ask
    data class MoveFeed(val feed: FeedNode, val category: String?) : Ask
    data class Unsubscribe(val feed: FeedNode) : Ask
    data class RenameCategory(val name: String) : Ask
    data class DeleteCategory(val name: String) : Ask
    data class RenameLabel(val name: String) : Ask
    data class DeleteLabel(val name: String) : Ask
}

/**
 * Subscriptions, categories and labels: add a feed by address, rename, move or drop a feed,
 * rename or delete a category or label, import and export OPML (R6).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageScreen(
    tree: NavTree,
    state: ManageState,
    onBack: () -> Unit,
    onSubscribe: (String) -> Unit,
    onRenameFeed: (feedId: String, title: String) -> Unit,
    onMoveFeed: (feedId: String, category: String) -> Unit,
    onUnsubscribe: (feedId: String) -> Unit,
    onRenameCategory: (name: String, newName: String) -> Unit,
    onDeleteCategory: (name: String) -> Unit,
    onRenameLabel: (name: String, newName: String) -> Unit,
    onDeleteLabel: (name: String) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onMessageShown: () -> Unit,
) {
    var ask by remember { mutableStateOf<Ask?>(null) }
    var menu by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val message = state.message?.let { messageText(it) }
    LaunchedEffect(state.message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            onMessageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.manage)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.testTag(ManageTags.MENU)) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.opml_import)) },
                                enabled = !state.busy,
                                onClick = {
                                    menu = false
                                    onImport()
                                },
                                modifier = Modifier.testTag(ManageTags.IMPORT),
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.opml_export)) },
                                enabled = !state.busy,
                                onClick = {
                                    menu = false
                                    onExport()
                                },
                                modifier = Modifier.testTag(ManageTags.EXPORT),
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (!state.busy) ask = Ask.Add },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.feed_add)) },
                modifier = Modifier.testTag(ManageTags.ADD),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else Box(Modifier.padding(top = 4.dp))
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 88.dp)) {
                for (category in tree.categories) {
                    item(key = "category/${category.name}") {
                        Row(
                            title = category.name,
                            icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                            emphasized = true,
                            enabled = !state.busy,
                            tag = ManageTags.category(category.name),
                            actions = listOf(
                                Action(R.string.rename, ManageTags.RENAME) { ask = Ask.RenameCategory(category.name) },
                                Action(R.string.delete, ManageTags.DELETE) { ask = Ask.DeleteCategory(category.name) },
                            ),
                        )
                    }
                    items(category.feeds, key = { "feed/${category.name}/${it.id}" }) { feed ->
                        FeedRow(feed, category.name, enabled = !state.busy, indent = true, onAsk = { ask = it })
                    }
                }
                items(tree.uncategorized, key = { "feed//${it.id}" }) { feed ->
                    FeedRow(feed, null, enabled = !state.busy, indent = false, onAsk = { ask = it })
                }
                if (tree.labels.isNotEmpty()) {
                    item(key = "labels") {
                        Text(
                            stringResource(R.string.section_labels),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                    items(tree.labels, key = { "label/${it.name}" }) { label ->
                        Row(
                            title = label.name,
                            icon = { Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null) },
                            enabled = !state.busy,
                            tag = ManageTags.label(label.name),
                            actions = listOf(
                                Action(R.string.rename, ManageTags.RENAME) { ask = Ask.RenameLabel(label.name) },
                                Action(R.string.delete, ManageTags.DELETE) { ask = Ask.DeleteLabel(label.name) },
                            ),
                        )
                    }
                }
            }
        }
    }

    val close = { ask = null }
    when (val current = ask) {
        Ask.Add -> TextDialog(
            title = stringResource(R.string.feed_add),
            label = stringResource(R.string.feed_address),
            initial = "",
            keyboardType = KeyboardType.Uri,
            confirm = stringResource(R.string.feed_subscribe),
            onConfirm = onSubscribe,
            onDismiss = close,
        )
        is Ask.RenameFeed -> TextDialog(
            title = stringResource(R.string.feed_rename),
            label = stringResource(R.string.name),
            initial = current.feed.title,
            confirm = stringResource(R.string.rename),
            onConfirm = { onRenameFeed(current.feed.id, it) },
            onDismiss = close,
        )
        is Ask.MoveFeed -> MoveDialog(
            categories = tree.categories.map { it.name },
            current = current.category,
            onConfirm = { onMoveFeed(current.feed.id, it) },
            onDismiss = close,
        )
        is Ask.Unsubscribe -> ConfirmDialog(
            title = stringResource(R.string.feed_unsubscribe_title),
            text = stringResource(R.string.feed_unsubscribe_text, current.feed.title),
            confirm = stringResource(R.string.feed_unsubscribe),
            onConfirm = { onUnsubscribe(current.feed.id) },
            onDismiss = close,
        )
        is Ask.RenameCategory -> TextDialog(
            title = stringResource(R.string.category_rename),
            label = stringResource(R.string.name),
            initial = current.name,
            confirm = stringResource(R.string.rename),
            onConfirm = { onRenameCategory(current.name, it) },
            onDismiss = close,
        )
        is Ask.DeleteCategory -> ConfirmDialog(
            title = stringResource(R.string.category_delete_title),
            text = stringResource(R.string.category_delete_text, current.name),
            confirm = stringResource(R.string.delete),
            onConfirm = { onDeleteCategory(current.name) },
            onDismiss = close,
        )
        is Ask.RenameLabel -> TextDialog(
            title = stringResource(R.string.label_rename),
            label = stringResource(R.string.name),
            initial = current.name,
            confirm = stringResource(R.string.rename),
            onConfirm = { onRenameLabel(current.name, it) },
            onDismiss = close,
        )
        is Ask.DeleteLabel -> ConfirmDialog(
            title = stringResource(R.string.label_delete_title),
            text = stringResource(R.string.label_delete_text, current.name),
            confirm = stringResource(R.string.delete),
            onConfirm = { onDeleteLabel(current.name) },
            onDismiss = close,
        )
        null -> Unit
    }
}

private class Action(val text: Int, val tag: String, val onClick: () -> Unit)

@Composable
private fun FeedRow(feed: FeedNode, category: String?, enabled: Boolean, indent: Boolean, onAsk: (Ask) -> Unit) {
    Row(
        title = feed.title,
        icon = { Icon(Icons.Default.RssFeed, contentDescription = null) },
        enabled = enabled,
        tag = ManageTags.feed(feed.id),
        modifier = if (indent) Modifier.padding(start = 24.dp) else Modifier,
        actions = listOf(
            Action(R.string.rename, ManageTags.RENAME) { onAsk(Ask.RenameFeed(feed)) },
            Action(R.string.feed_move, ManageTags.MOVE) { onAsk(Ask.MoveFeed(feed, category)) },
            Action(R.string.feed_unsubscribe, ManageTags.DELETE) { onAsk(Ask.Unsubscribe(feed)) },
        ),
    )
}

/** A line with a menu of [actions], opened by its button. */
@Composable
private fun Row(
    title: String,
    icon: @Composable () -> Unit,
    enabled: Boolean,
    tag: String,
    actions: List<Action>,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = {
            Text(
                title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            )
        },
        leadingContent = icon,
        trailingContent = {
            Box {
                IconButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.testTag("$tag-menu")) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more))
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    for (action in actions) {
                        DropdownMenuItem(
                            text = { Text(stringResource(action.text)) },
                            onClick = {
                                open = false
                                action.onClick()
                            },
                            modifier = Modifier.testTag(action.tag),
                        )
                    }
                }
            }
        },
        modifier = modifier.testTag(tag),
    )
}

@Composable
private fun TextDialog(
    title: String,
    label: String,
    initial: String,
    confirm: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    // The old name selected, so that typing replaces it.
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(ManageTags.FIELD),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onConfirm(text.text.trim())
                },
                enabled = text.text.isNotBlank(),
                modifier = Modifier.testTag(ManageTags.CONFIRM),
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onConfirm()
                },
                modifier = Modifier.testTag(ManageTags.CONFIRM),
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** One of the categories, or a new one by name; the server creates it. */
@Composable
private fun MoveDialog(categories: List<String>, current: String?, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    // null stands for the new category.
    var chosen by remember { mutableStateOf<String?>(current ?: categories.firstOrNull()) }
    var newName by remember { mutableStateOf("") }
    val target = chosen ?: newName.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.feed_move)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (name in categories) {
                    ListItem(
                        selected = chosen == name,
                        onClick = { chosen = name },
                        leadingContent = { RadioButton(selected = chosen == name, onClick = null) },
                        modifier = Modifier.testTag(ManageTags.option(name)),
                    ) { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                ListItem(
                    selected = chosen == null,
                    onClick = { chosen = null },
                    leadingContent = { RadioButton(selected = chosen == null, onClick = null) },
                    modifier = Modifier.testTag(ManageTags.NEW_CATEGORY),
                ) { Text(stringResource(R.string.category_new)) }
                if (chosen == null) {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(stringResource(R.string.name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(ManageTags.FIELD),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onConfirm(target)
                },
                enabled = target.isNotEmpty() && target != current,
                modifier = Modifier.testTag(ManageTags.CONFIRM),
            ) { Text(stringResource(R.string.feed_move_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun messageText(message: ManageMessage): String = when (message) {
    ManageMessage.Subscribed -> stringResource(R.string.manage_subscribed)
    ManageMessage.Imported -> stringResource(R.string.manage_imported)
    ManageMessage.Exported -> stringResource(R.string.manage_exported)
    ManageMessage.NoNetwork -> stringResource(R.string.manage_no_network)
    ManageMessage.FileFailed -> stringResource(R.string.manage_file_failed)
    is ManageMessage.NameTaken -> stringResource(R.string.manage_name_taken, message.name)
    is ManageMessage.NoFeed -> stringResource(R.string.manage_no_feed)
    is ManageMessage.Refused -> stringResource(R.string.manage_refused, message.detail)
}
