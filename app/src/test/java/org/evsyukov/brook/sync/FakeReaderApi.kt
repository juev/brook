package org.evsyukov.brook.sync

import org.evsyukov.brook.data.remote.CategoryRefDto
import org.evsyukov.brook.data.remote.ContentDto
import org.evsyukov.brook.data.remote.ItemDto
import org.evsyukov.brook.data.remote.OriginDto
import org.evsyukov.brook.data.remote.ReaderApi
import org.evsyukov.brook.data.remote.StreamContentsDto
import org.evsyukov.brook.data.remote.Streams
import org.evsyukov.brook.data.remote.SubscriptionDto
import org.evsyukov.brook.data.remote.TagDto
import org.evsyukov.brook.data.remote.UnreadCountDto
import org.evsyukov.brook.data.remote.UnreadCountsDto
import java.io.IOException

/** A Google Reader server in memory that keeps the rules sync depends on (freshgo A13–A23). */
class FakeReaderApi : ReaderApi {
    data class Entry(
        val id: Long,
        val feedId: String,
        var title: String,
        var content: String = "text of $id",
        /** The second the entry was added or last changed by its feed, what `ot` compares with. */
        var changedAt: Long = id / 1_000_000,
        var read: Boolean = false,
        var starred: Boolean = false,
        val labels: MutableSet<String> = mutableSetOf(),
    )

    val feeds = linkedMapOf<String, String>() // feed id → category
    val labels = mutableSetOf<String>()
    val entries = sortedMapOf<Long, Entry>(compareByDescending { it })

    /** Every edit-tag call as (ids, add, remove). */
    val edits = mutableListOf<Triple<List<Long>, List<String>, List<String>>>()

    /** When set, every call fails as if the network were down. */
    var offline = false

    /** Runs inside allItemIds, to act while a sync is between its steps. */
    var duringIds: (suspend () -> Unit)? = null

    private var nextId = 1_700_000_000_000_000L

    fun feed(id: String, category: String) {
        feeds[id] = category
    }

    fun add(feedId: String, title: String = "entry", read: Boolean = false, starred: Boolean = false): Entry {
        nextId += 1_000_000
        return Entry(nextId, feedId, title, read = read, starred = starred).also { entries[it.id] = it }
    }

    private fun check() {
        if (offline) throw IOException("offline")
    }

    override suspend fun tagList(): List<TagDto> {
        check()
        return feeds.values.distinct().map { TagDto(Streams.label(it), "folder") } + labels.map { TagDto(Streams.label(it), "tag") }
    }

    override suspend fun subscriptions(): List<SubscriptionDto> {
        check()
        return feeds.map { (id, category) ->
            SubscriptionDto(id = id, title = "Feed $id", categories = listOf(CategoryRefDto(Streams.label(category), category)), url = "https://x.org/$id")
        }
    }

    override suspend fun unreadCounts(): UnreadCountsDto {
        check()
        val unread = entries.values.filter { !it.read && it.feedId in feeds }
        return UnreadCountsDto(
            max = unread.size.toLong(),
            counts = listOf(UnreadCountDto(Streams.READING_LIST, unread.size.toLong(), (entries.keys.firstOrNull() ?: 0).toString())),
        )
    }

    private fun inStream(e: Entry, streamId: String) = when {
        streamId == Streams.READING_LIST -> e.feedId in feeds
        streamId == Streams.STARRED -> e.starred
        streamId.startsWith(Streams.FEED_PREFIX) -> e.feedId == streamId
        else -> {
            val name = Streams.labelName(streamId)
            feeds[e.feedId] == name || name in e.labels
        }
    }

    override suspend fun streamContents(
        streamId: String,
        count: Int,
        continuation: String?,
        oldestFirst: Boolean,
        exclude: String?,
        include: String?,
        newerThanSeconds: Long?,
    ): StreamContentsDto {
        check()
        var list = entries.values.filter { inStream(it, streamId) }
        if (exclude == Streams.READ) list = list.filter { !it.read }
        if (newerThanSeconds != null) list = list.filter { it.changedAt >= newerThanSeconds }
        continuation?.toLong()?.let { c -> list = list.filter { it.id < c } }
        val page = list.take(count)
        return StreamContentsDto(page.map { it.toItem() }, if (page.size == count) page.last().id.toString() else null)
    }

    override suspend fun allItemIds(streamId: String, exclude: String?, pageSize: Int): List<Long> {
        check()
        duringIds?.invoke()
        return entries.values.filter { inStream(it, streamId) && (exclude != Streams.READ || !it.read) }.map { it.id }
    }

    override suspend fun itemContents(ids: Collection<Long>): List<ItemDto> {
        check()
        return ids.mapNotNull { entries[it]?.toItem() }
    }

    override suspend fun editTag(ids: Collection<Long>, add: List<String>, remove: List<String>) {
        check()
        edits += Triple(ids.toList(), add, remove)
        for (id in ids) {
            val e = entries[id] ?: continue
            for (tag in add) apply(e, tag, true)
            for (tag in remove) apply(e, tag, false)
        }
    }

    private fun apply(e: Entry, tag: String, on: Boolean) {
        when (tag) {
            Streams.READ -> e.read = on
            Streams.STARRED -> e.starred = on
            else -> Streams.labelName(tag)?.let { name ->
                if (on) {
                    labels += name
                    e.labels += name
                } else {
                    e.labels -= name
                }
            }
        }
    }

    private fun Entry.toItem() = ItemDto(
        id = "tag:google.com,2005:reader/item/%016x".format(id),
        timestampUsec = id.toString(),
        crawlTimeMsec = (id / 1000).toString(),
        published = id / 1_000_000,
        title = title,
        categories = buildList {
            add(Streams.READING_LIST)
            add(Streams.label(feeds[feedId] ?: ""))
            if (read) add(Streams.READ)
            if (starred) add(Streams.STARRED)
            labels.forEach { add(Streams.label(it)) }
        },
        origin = OriginDto(streamId = feedId, title = "Feed $feedId"),
        summary = ContentDto(content),
    )
}
