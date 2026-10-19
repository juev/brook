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
import org.evsyukov.brook.data.settings.ReaderFont
import org.evsyukov.brook.data.settings.ReaderTheme
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
    const val MARK_ON_SCROLL = "settings-mark-on-scroll"
    const val THEME = "settings-theme"
    const val FONT = "settings-font"
    const val TEXT_SIZE = "settings-text-size"
    const val LINE_HEIGHT = "settings-line-height"
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
    onMarkReadOnScroll: (Boolean) -> Unit = {},
    onReaderTheme: (ReaderTheme) -> Unit = {},
    onReaderFont: (ReaderFont) -> Unit = {},
    onReaderTextSize: (Int) -> Unit = {},
    onReaderLineHeight: (Int) -> Unit = {},
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
            ListItem(
                checked = settings.markReadOnScroll,
                onCheckedChange = onMarkReadOnScroll,
                supportingContent = { Text(stringResource(R.string.settings_mark_on_scroll_hint)) },
                trailingContent = { Switch(checked = settings.markReadOnScroll, onCheckedChange = null) },
                modifier = Modifier.testTag(SettingsTags.MARK_ON_SCROLL),
            ) { Text(stringResource(R.string.settings_mark_on_scroll)) }
            ListItem(
                onClick = { choosing = Choice.THEME },
                supportingContent = { Text(themeName(settings.readerTheme)) },
                modifier = Modifier.testTag(SettingsTags.THEME),
            ) { Text(stringResource(R.string.settings_theme)) }
            ListItem(
                onClick = { choosing = Choice.FONT },
                supportingContent = { Text(fontName(settings.readerFont)) },
                modifier = Modifier.testTag(SettingsTags.FONT),
            ) { Text(stringResource(R.string.settings_font)) }
            ListItem(
                onClick = { choosing = Choice.TEXT_SIZE },
                supportingContent = { Text(textSize(settings.readerTextSize)) },
                modifier = Modifier.testTag(SettingsTags.TEXT_SIZE),
            ) { Text(stringResource(R.string.settings_text_size)) }
            ListItem(
                onClick = { choosing = Choice.LINE_HEIGHT },
                supportingContent = { Text(lineHeight(settings.readerLineHeight)) },
                modifier = Modifier.testTag(SettingsTags.LINE_HEIGHT),
            ) { Text(stringResource(R.string.settings_line_height)) }
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
        Choice.THEME -> ChoiceDialog(
            title = stringResource(R.string.settings_theme),
            options = ReaderTheme.entries,
            selected = settings.readerTheme,
            label = { themeName(it) },
            key = { it.name },
            onSelect = onReaderTheme,
            onDismiss = { choosing = null },
        )
        Choice.FONT -> ChoiceDialog(
            title = stringResource(R.string.settings_font),
            options = ReaderFont.entries,
            selected = settings.readerFont,
            label = { fontName(it) },
            key = { it.name },
            onSelect = onReaderFont,
            onDismiss = { choosing = null },
        )
        Choice.TEXT_SIZE -> ChoiceDialog(
            title = stringResource(R.string.settings_text_size),
            options = Settings.TEXT_SIZES,
            selected = settings.readerTextSize,
            label = { textSize(it) },
            key = { it },
            onSelect = onReaderTextSize,
            onDismiss = { choosing = null },
        )
        Choice.LINE_HEIGHT -> ChoiceDialog(
            title = stringResource(R.string.settings_line_height),
            options = Settings.LINE_HEIGHTS,
            selected = settings.readerLineHeight,
            label = { lineHeight(it) },
            key = { it },
            onSelect = onReaderLineHeight,
            onDismiss = { choosing = null },
        )
        null -> Unit
    }
}

private enum class Choice { INTERVAL, RETENTION, DISTRIBUTOR, THEME, FONT, TEXT_SIZE, LINE_HEIGHT }

@Composable
private fun themeName(theme: ReaderTheme) = stringResource(
    when (theme) {
        ReaderTheme.SYSTEM -> R.string.theme_system
        ReaderTheme.SEPIA -> R.string.theme_sepia
        ReaderTheme.BLACK -> R.string.theme_black
    },
)

@Composable
private fun fontName(font: ReaderFont) = stringResource(
    when (font) {
        ReaderFont.SANS -> R.string.font_sans
        ReaderFont.SERIF -> R.string.font_serif
    },
)

@Composable
private fun textSize(px: Int) = stringResource(
    when (Settings.TEXT_SIZES.indexOf(px)) {
        0 -> R.string.text_size_smallest
        1 -> R.string.text_size_small
        2 -> R.string.text_size_medium
        3 -> R.string.text_size_large
        else -> R.string.text_size_largest
    },
)

@Composable
private fun lineHeight(percent: Int) = stringResource(
    when (Settings.LINE_HEIGHTS.indexOf(percent)) {
        0 -> R.string.line_height_compact
        1 -> R.string.line_height_normal
        else -> R.string.line_height_relaxed
    },
)

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
