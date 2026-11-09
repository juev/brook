package org.evsyukov.brook.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.evsyukov.brook.R
import org.evsyukov.brook.data.social.Delivery
import org.evsyukov.brook.data.social.SocialService

/** UI projection contains no secrets. */
data class SocialAccount(val service: SocialService, val name: String, val server: String, val enabled: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialSettingsScreen(
    connections: List<SocialAccount>,
    deliveries: List<Delivery>,
    busy: Boolean,
    error: Boolean,
    onBack: () -> Unit,
    onConnect: (SocialService, String, String, String) -> Unit,
    onEnabled: (SocialService, Boolean) -> Unit,
    onDisconnect: (SocialService) -> Unit,
    onRetry: () -> Unit,
) {
    var connecting by remember { mutableStateOf<SocialService?>(null) }
    var disconnecting by remember { mutableStateOf<SocialService?>(null) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.social_title)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(stringResource(R.string.social_warning))
            if (busy) Text(stringResource(R.string.social_busy))
            if (error) Text(stringResource(R.string.social_error))
            for (service in SocialService.entries) {
                val account = connections.firstOrNull { it.service == service }
                Text(service.displayName(), modifier = Modifier.padding(top = 16.dp))
                if (account != null) {
                    Text("${account.name} · ${account.server}")
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.social_enable)) },
                        trailingContent = {
                            Switch(account.enabled, { onEnabled(service, it) }, enabled = !busy,
                                modifier = Modifier.testTag("social-toggle-${service.name}"))
                        },
                    )
                    TextButton(onClick = { disconnecting = service }, enabled = !busy, modifier = Modifier.testTag("social-disconnect-${service.name}")) {
                        Text(stringResource(R.string.social_disconnect))
                    }
                }
                Button(onClick = { connecting = service }, enabled = !busy) {
                    Text(if (account == null) stringResource(R.string.social_connect, service.displayName()) else stringResource(R.string.social_reconnect))
                }
            }
            TextButton(onClick = onRetry, enabled = !busy) { Text(stringResource(R.string.social_retry)) }
            HorizontalDivider()
            Text(stringResource(R.string.social_history), modifier = Modifier.padding(vertical = 16.dp))
            if (deliveries.isEmpty()) Text(stringResource(R.string.social_empty))
            for (delivery in deliveries.take(30)) {
                val destination = delivery.destinationKey.substringBefore('|')
                Text("$destination · ${delivery.title}")
                Text(stringResource(when (delivery.state) {
                    Delivery.SENT -> R.string.social_sent
                    Delivery.AUTH -> R.string.social_auth
                    Delivery.INVALID -> R.string.social_invalid
                    Delivery.UNKNOWN -> R.string.social_unknown
                    Delivery.CANCELED -> R.string.social_canceled
                    else -> R.string.social_pending
                }), modifier = Modifier.padding(bottom = 16.dp))
            }
        }
    }
    connecting?.let { service ->
        ConnectionDialog(service, connections.firstOrNull { it.service == service }, onDismiss = { connecting = null }) { server, user, secret ->
            connecting = null
            onConnect(service, server, user, secret)
        }
    }
    disconnecting?.let { service ->
        AlertDialog(onDismissRequest = { disconnecting = null },
            title = { Text(service.displayName()) }, text = { Text(stringResource(R.string.social_disconnect_hint)) },
            confirmButton = { TextButton(onClick = { disconnecting = null; onDisconnect(service) }, modifier = Modifier.testTag("social-disconnect-confirm")) { Text(stringResource(R.string.social_disconnect)) } },
            dismissButton = { TextButton(onClick = { disconnecting = null }) { Text(stringResource(R.string.social_cancel)) } })
    }
}

private fun SocialService.displayName() = when (this) {
    SocialService.MASTODON -> "Mastodon"
    SocialService.BLUESKY -> "Bluesky"
    SocialService.LINKDING -> "linkding"
}

@Composable
private fun ConnectionDialog(service: SocialService, account: SocialAccount?, onDismiss: () -> Unit, onConnect: (String, String, String) -> Unit) {
    // Never save credentials to instance state; dismissing drops them rather than restoring a secret.
    var server by remember { mutableStateOf(account?.server ?: if (service == SocialService.BLUESKY) "https://bsky.social" else "") }
    var user by remember { mutableStateOf(account?.name ?: "") }
    var secret by remember { mutableStateOf("") }
    val needsHandle = service == SocialService.BLUESKY
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.social_connect, service.displayName())) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(when (service) {
                    SocialService.MASTODON -> R.string.social_mastodon_help
                    SocialService.BLUESKY -> R.string.social_bluesky_help
                    SocialService.LINKDING -> R.string.social_linkding_help
                }))
                OutlinedTextField(server, { server = it }, label = { Text(stringResource(R.string.social_server)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("social-server"))
                if (needsHandle) {
                    OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.social_handle)) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("social-handle"))
                }
                OutlinedTextField(secret, { secret = it }, label = { Text(stringResource(when (service) {
                    SocialService.MASTODON -> R.string.social_token
                    SocialService.BLUESKY -> R.string.social_password
                    SocialService.LINKDING -> R.string.social_api_token
                })) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("social-secret"))
            }
        },
        confirmButton = { TextButton(onClick = { val value = secret; secret = ""; onConnect(server, user, value) },
            enabled = server.isNotBlank() && secret.isNotBlank() && (!needsHandle || user.isNotBlank())) { Text(stringResource(R.string.social_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.social_cancel)) } },
    )
}