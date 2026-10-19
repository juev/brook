package org.evsyukov.brook.sync

import org.evsyukov.brook.data.remote.Streams
import org.evsyukov.brook.data.remote.UnreadCountsDto

/** What `unread-count` says about the newest entry; a change means there is something to sync. */
data class NewestMarker(val newestUsec: String, val max: Long, val unread: Long)

/**
 * Decides from `unread-count` whether a sync is due (R4). The answer is cheap: the newest entry of
 * the reading list and `max` change when the server stores entries, the unread count also when
 * the user reads elsewhere.
 */
class NewEntriesDetector {
    private var last: NewestMarker? = null

    /** Forgets the last answer: the next check only records. */
    fun reset() {
        last = null
    }

    /** True when [counts] differ from the last answer; the first answer only records. */
    fun changed(counts: UnreadCountsDto): Boolean {
        val now = marker(counts)
        val before = last
        last = now
        return before != null && before != now
    }

    private fun marker(counts: UnreadCountsDto): NewestMarker {
        val readingList = counts.counts.firstOrNull { it.id == Streams.READING_LIST }
        return NewestMarker(readingList?.newestItemTimestampUsec.orEmpty(), counts.max, readingList?.count ?: 0)
    }
}
