package org.evsyukov.brook.data

import org.evsyukov.brook.data.remote.Streams

/** What part of the subscriptions a list shows. */
sealed interface Scope {
    data object All : Scope
    data class Feed(val feedId: String) : Scope
    data class Category(val name: String) : Scope
    data class Label(val name: String) : Scope

    /** The stream of the Google Reader API that covers the scope. */
    val streamId: String
        get() = when (this) {
            All -> Streams.READING_LIST
            is Feed -> feedId
            is Category -> Streams.label(name)
            is Label -> Streams.label(name)
        }
}

enum class Filter { ALL, UNREAD, STARRED }

/**
 * The mode a short press of the one mode button moves to: All to Unread, Unread back to All, and
 * Starred to Unread, its explicit way out.
 */
fun Filter.nextOnShortPress(): Filter = when (this) {
    Filter.ALL -> Filter.UNREAD
    Filter.UNREAD -> Filter.ALL
    Filter.STARRED -> Filter.UNREAD
}
