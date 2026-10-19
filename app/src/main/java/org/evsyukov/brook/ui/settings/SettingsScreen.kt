package org.evsyukov.brook.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.evsyukov.brook.R
import org.evsyukov.brook.data.account.Account
import org.evsyukov.brook.data.settings.Settings
import org.evsyukov.brook.push.AccountPush
import org.evsyukov.brook.push.PushStatus

object SettingsTags {
    const val INTERVAL = "settings-interval"
    const val UNMETERED = "settings-unmetered"
    const val RETENTION = "settings-retention"
    const val NOTIFY = "settings-notify"
    const val PUSH = "settings-push"
    const val DISTRIBUTOR = "settings-distributor"
    fun option(value: Any) = "settings-option-$value"
    fun account(id: String) = "settings-push-$id"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    onBack: () -> Unit,
    onSyncInterval: (Int) -> Unit,
    onPrefetchOnlyUnmetered: (Boolean) -> Unit,
    onReadRetention: (Int) -> Unit,
    onNotify: (Boolean) -> Unit,
    push: PushStatus = PushStatus(),
    accounts: List<Account> = emptyList(),
    onPush: (Boolean) -> Unit = {},
    onDistributor: (String) -> Unit = {},
) {
    var choosing by remember { mutableStateOf<Choice?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            ListItem(
                onClick = { choosing = Choice.INTERVAL },
                supportingContent = { Text(minutes(settings.syncIntervalMinutes)) },
                modifier = Modifier.testTag(SettingsTags.INTERVAL),
            ) { Text(stringResource(R.string.settings_interval)) }
            ListItem(
                checked = settings.prefetchOnlyUnmetered,
                onCheckedChange = onPrefetchOnlyUnmetered,
                supportingContent = { Text(stringResource(R.string.settings_unmetered_hint)) },
                trailingContent = { Switch(checked = settings.prefetchOnlyUnmetered, onCheckedChange = null) },
                modifier = Modifier.testTag(SettingsTags.UNMETERED),
            ) { Text(stringResource(R.string.settings_unmetered)) }
            ListItem(
                onClick = { choosing = Choice.RETENTION },
                supportingContent = { Text(days(settings.readRetentionDays)) },
                modifier = Modifier.testTag(SettingsTags.RETENTION),
            ) { Text(stringResource(R.string.settings_retention)) }
            ListItem(
                checked = settings.notifyNewEntries,
                onCheckedChange = onNotify,
                supportingContent = { Text(stringResource(R.string.settings_notify_hint)) },
                trailingContent = { Switch(checked = settings.notifyNewEntries, onCheckedChange = null) },
                modifier = Modifier.testTag(SettingsTags.NOTIFY),
            ) { Text(stringResource(R.string.settings_notify)) }
            ListItem(
                checked = settings.push,
                onCheckedChange = onPush,
                supportingContent = { Text(pushHint(settings.push, push)) },
                trailingContent = { Switch(checked = settings.push, onCheckedChange = null) },
                modifier = Modifier.testTag(SettingsTags.PUSH),
            ) { Text(stringResource(R.string.settings_push)) }
            if (settings.push && push.distributors.size > 1) {
                ListItem(
                    onClick = { choosing = Choice.DISTRIBUTOR },
                    supportingContent = {
                        Text(push.distributors.firstOrNull { it.packageName == push.distributor }?.label ?: stringResource(R.string.settings_distributor_none))
                    },
                    modifier = Modifier.testTag(SettingsTags.DISTRIBUTOR),
                ) { Text(stringResource(R.string.settings_distributor)) }
            }
            if (settings.push && push.distributor != null) {
                for (account in accounts) {
                    val state = push.accounts[account.id] ?: continue
                    ListItem(
                        supportingContent = { Text(accountPush(state)) },
                        modifier = Modifier.testTag(SettingsTags.account(account.id)),
                    ) { Text("${account.user} · ${account.host}") }
                }
            }
        }
    }

    when (choosing) {
        Choice.INTERVAL -> ChoiceDialog(
            title = stringResource(R.string.settings_interval),
            options = Settings.SYNC_INTERVALS,
            selected = settings.syncIntervalMinutes,
            label = { minutes(it) },
            key = { it },
            onSelect = onSyncInterval,
            onDismiss = { choosing = null },
        )
        Choice.RETENTION -> ChoiceDialog(
            title = stringResource(R.string.settings_retention),
            options = Settings.RETENTION_DAYS,
            selected = settings.readRetentionDays,
            label = { days(it) },
            key = { it },
            onSelect = onReadRetention,
            onDismiss = { choosing = null },
        )
        Choice.DISTRIBUTOR -> ChoiceDialog(
            title = stringResource(R.string.settings_distributor),
            options = push.distributors,
            selected = push.distributors.firstOrNull { it.packageName == push.distributor },
            label = { it.label },
            key = { it.packageName },
            onSelect = { onDistributor(it.packageName) },
            onDismiss = { choosing = null },
        )
        null -> Unit
    }
}

private enum class Choice { INTERVAL, RETENTION, DISTRIBUTOR }

@Composable
private fun pushHint(on: Boolean, push: PushStatus) = stringResource(
    when {
        on && push.distributors.isEmpty() -> R.string.settings_push_no_distributor
        on && push.distributor == null -> R.string.settings_push_choose
        else -> R.string.settings_push_hint
    },
)

@Composable
private fun accountPush(state: AccountPush) = stringResource(
    when (state) {
        AccountPush.CONNECTING -> R.string.push_connecting
        AccountPush.ON -> R.string.push_on
        AccountPush.UNSUPPORTED -> R.string.push_unsupported
        AccountPush.TOO_MANY -> R.string.push_too_many
        AccountPush.FAILED -> R.string.push_failed
    },
)

@Composable
private fun minutes(n: Int) = pluralStringResource(R.plurals.minutes, n, n)

@Composable
private fun days(n: Int) = pluralStringResource(R.plurals.days, n, n)

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    key: (T) -> Any,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                for (option in options) {
                    ListItem(
                        selected = option == selected,
                        onClick = {
                            onSelect(option)
                            onDismiss()
                        },
                        leadingContent = { RadioButton(selected = option == selected, onClick = null) },
                        modifier = Modifier.testTag(SettingsTags.option(key(option))),
                    ) { Text(label(option)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
