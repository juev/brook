package org.evsyukov.brook.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.evsyukov.brook.data.social.Delivery
import org.evsyukov.brook.data.social.SocialPublishing

@Composable
fun SocialSettingsRoute(publishing: SocialPublishing, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val connections by produceState<List<SocialAccount>?>(null, publishing) {
        publishing.store.connections.map { list -> list.map { SocialAccount(it.service, it.displayName, it.server, it.enabled) } }
            .catch { if (it is CancellationException) throw it; error = true; emit(emptyList()) }
            .collect { value = it }
    }
    val history by produceState(emptyList<Delivery>(), publishing) {
        publishing.history().catch { if (it is CancellationException) throw it; error = true; emit(emptyList()) }.collect { value = it }
    }
    fun command(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = false
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // No response bodies, tokens or exception messages are shown or logged.
                error = true
            } finally {
                busy = false
            }
        }
    }
    SocialSettingsScreen(connections.orEmpty(), history, busy || connections == null, error, onBack,
        onConnect = { service, server, user, secret -> command { publishing.connect(service, server, user, secret) } },
        onEnabled = { service, enabled -> command { publishing.setEnabled(service, enabled) } },
        onDisconnect = { service -> command { publishing.disconnect(service) } },
        onRetry = { publishing.retryPending() })
}