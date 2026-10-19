package org.evsyukov.brook.sync

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.evsyukov.brook.data.db.EntryEntity
import org.evsyukov.brook.data.db.FeedEntity
import org.evsyukov.brook.data.remote.EnclosureDto
import org.evsyukov.brook.data.remote.ItemDto
import org.evsyukov.brook.data.remote.ItemIds
import org.evsyukov.brook.data.remote.Streams
import org.evsyukov.brook.data.remote.SubscriptionDto

/**
 * freshgo and FreshRSS replace `& < >` in titles of entries and authors, and also `? \ / , ;` in
 * names of feeds, by their fullwidth forms (freshgo A12); the reader shows the plain characters.
 */
internal fun String.withoutFullwidth(): String = buildString(length) {
    for (c in this@withoutFullwidth) {
        append(
            when (c) {
                '＆' -> '&'
                '＜' -> '<'
                '＞' -> '>'
                '？' -> '?'
                '＼' -> '\\'
                '／' -> '/'
                '，' -> ','
                '；' -> ';'
                else -> c
            },
        )
    }
}

private val enclosuresSerializer = ListSerializer(EnclosureDto.serializer())
private val json = Json { ignoreUnknownKeys = true }

fun encodeEnclosures(enclosures: List<EnclosureDto>): String =
    if (enclosures.isEmpty()) "" else json.encodeToString(enclosuresSerializer, enclosures)

fun decodeEnclosures(value: String): List<EnclosureDto> =
    if (value.isEmpty()) emptyList() else runCatching { json.decodeFromString(enclosuresSerializer, value) }.getOrDefault(emptyList())

internal fun SubscriptionDto.toEntity() = FeedEntity(
    id = id,
    title = title.withoutFullwidth().ifBlank { url },
    url = url,
    htmlUrl = htmlUrl,
    iconUrl = iconUrl,
    categoryName = categories.firstOrNull()?.let { Streams.labelName(it.id) ?: it.label },
)

/** An item as the server sends it: the entry, its user labels and its state. */
internal data class MappedItem(val entry: EntryEntity, val labels: List<String>)

internal fun ItemDto.toMapped(labelNames: Set<String>, now: Long): MappedItem? {
    val id = ItemIds.parse(id) ?: return null
    val isRead = Streams.READ in categories
    val crawledSeconds = (crawlTimeMsec.toLongOrNull() ?: (timestampUsec.toLongOrNull()?.div(1000)) ?: 0) / 1000
    val entry = EntryEntity(
        id = id,
        feedId = origin?.streamId.orEmpty(),
        title = title.withoutFullwidth(),
        author = author.withoutFullwidth(),
        url = (canonical + alternate).firstOrNull { it.href.isNotBlank() }?.href.orEmpty(),
        sortTime = published.takeIf { it > 0 } ?: crawledSeconds,
        content = (content ?: summary)?.content.orEmpty(),
        enclosuresJson = encodeEnclosures(enclosure.filter { it.href.isNotBlank() }),
        isRead = isRead,
        isStarred = Streams.STARRED in categories,
        readAt = if (isRead) now else null,
    )
    val labels = categories.mapNotNull { Streams.labelName(it) }.filter { it in labelNames }
    return MappedItem(entry, labels)
}
