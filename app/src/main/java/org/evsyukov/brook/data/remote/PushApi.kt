package org.evsyukov.brook.data.remote

/** Push subscriptions of an app at freshgo (`docs/specs/push.md`, P11); [GReaderClient] in the app. */
interface PushApi {
    /** The public VAPID key of the server, or null when the server does not push. */
    suspend fun pushKey(): String?

    /**
     * Subscribes the endpoint of a UnifiedPush distributor with its RFC 8291 keys.
     * [GReaderException.BadRequest] when the server refuses it, [GReaderException.Conflict] when
     * the user already has as many browsers and apps as the server allows.
     */
    suspend fun pushSubscribe(endpoint: String, p256dh: String, auth: String)

    suspend fun pushUnsubscribe(endpoint: String)
}
