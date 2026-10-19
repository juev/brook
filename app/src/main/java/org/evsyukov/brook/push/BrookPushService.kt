package org.evsyukov.brook.push

import kotlinx.coroutines.launch
import org.evsyukov.brook.BrookApplication
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/** Events of the UnifiedPush distributor; the instance is the account. */
class BrookPushService : PushService() {
    private val container get() = (application as BrookApplication).container

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        // Without keys the server could not encrypt to Brook: such an endpoint is of no use.
        val keys = endpoint.pubKeySet ?: return
        container.scope.launch { container.push.onNewEndpoint(instance, endpoint.url, keys.pubKey, keys.auth) }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        if (!message.decrypted) return
        container.scope.launch { container.push.onMessage(instance, message.content) }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        container.scope.launch { container.push.onRegistrationFailed(instance) }
    }

    override fun onUnregistered(instance: String) {
        container.scope.launch { container.push.onUnregistered(instance) }
    }
}
