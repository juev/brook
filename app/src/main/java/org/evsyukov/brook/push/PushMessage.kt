package org.evsyukov.brook.push

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What a push message of freshgo asks for (`docs/specs/push.md`, P7, P12). */
enum class PushKind {
    /** A refresh brought unread entries: sync and tell about them. */
    ENTRIES,

    /** Entries were read, starred or otherwise changed: sync and show nothing. */
    CHANGES;

    companion object {
        /** The kind of a decrypted message; anything else is taken as [CHANGES], a sync that shows nothing. */
        fun of(content: ByteArray): PushKind {
            val kind = try {
                json.decodeFromString<Body>(content.decodeToString()).kind
            } catch (e: IllegalArgumentException) {
                null
            }
            return if (kind == "entries") ENTRIES else CHANGES
        }

        @Serializable
        private class Body(val kind: String? = null)

        private val json = Json { ignoreUnknownKeys = true }
    }
}
