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
import org.evsyukov.brook.data.settings.Settings

object SettingsTags {
    const val INTERVAL = "settings-interval"
    const val UNMETERED = "settings-unmetered"
    const val RETENTION = "settings-retention"
    const val NOTIFY = "settings-notify"
    fun option(value: Int) = "settings-option-$value"
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
        }
    }

    when (choosing) {
        Choice.INTERVAL -> ChoiceDialog(
            title = stringResource(R.string.settings_interval),
            options = Settings.SYNC_INTERVALS,
            selected = settings.syncIntervalMinutes,
            label = { minutes(it) },
            onSelect = onSyncInterval,
            onDismiss = { choosing = null },
        )
        Choice.RETENTION -> ChoiceDialog(
            title = stringResource(R.string.settings_retention),
            options = Settings.RETENTION_DAYS,
            selected = settings.readRetentionDays,
            label = { days(it) },
            onSelect = onReadRetention,
            onDismiss = { choosing = null },
        )
        null -> Unit
    }
}

private enum class Choice { INTERVAL, RETENTION }

@Composable
private fun minutes(n: Int) = pluralStringResource(R.plurals.minutes, n, n)

@Composable
private fun days(n: Int) = pluralStringResource(R.plurals.days, n, n)

@Composable
private fun ChoiceDialog(
    title: String,
    options: List<Int>,
    selected: Int,
    label: @Composable (Int) -> String,
    onSelect: (Int) -> Unit,
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
                        modifier = Modifier.testTag(SettingsTags.option(option)),
                    ) { Text(label(option)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
