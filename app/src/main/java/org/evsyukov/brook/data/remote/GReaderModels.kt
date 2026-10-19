package org.evsyukov.brook.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Stream identifiers of the Google Reader API. */
object Streams {
    const val READING_LIST = "user/-/state/com.google/reading-list"
    const val STARRED = "user/-/state/com.google/starred"
    const val READ = "user/-/state/com.google/read"
    const val LABEL_PREFIX = "user/-/label/"
    const val FEED_PREFIX = "feed/"

    fun label(name: String) = LABEL_PREFIX + name
    fun feed(id: String) = FEED_PREFIX + id

    /** The label or category name of `user/-/label/<name>`, or null for another stream. */
    fun labelName(streamId: String): String? = streamId.removePrefix(LABEL_PREFIX).takeIf { streamId.startsWith(LABEL_PREFIX) }
}

/** Item identifiers come as decimal in lists and as `tag:google.com,2005:reader/item/<16 hex>` in items. */
object ItemIds {
    private const val PREFIX = "tag:google.com,2005:reader/item/"

    fun parse(id: String): Long? = when {
        id.startsWith(PREFIX) -> id.removePrefix(PREFIX).toULongOrNull(16)?.toLong()
        // A string of digits not starting with zero is decimal (freshgo A19).
        id.length == 16 && id.all { it.isDigit() || it in 'a'..'f' } && (id.startsWith("0") || id.any { it in 'a'..'f' }) ->
            id.toULongOrNull(16)?.toLong()
        else -> id.toLongOrNull()
    }
}

@Serializable
data class TagListDto(val tags: List<TagDto> = emptyList())

@Serializable
data class TagDto(
    val id: String,
    val type: String? = null,
    @SerialName("unread_count") val unreadCount: Int? = null,
)

@Serializable
data class SubscriptionListDto(val subscriptions: List<SubscriptionDto> = emptyList())

@Serializable
data class SubscriptionDto(
    val id: String,
    val title: String = "",
    val categories: List<CategoryRefDto> = emptyList(),
    val url: String = "",
    val htmlUrl: String = "",
    val iconUrl: String = "",
)

@Serializable
data class CategoryRefDto(val id: String, val label: String = "")

@Serializable
data class UnreadCountsDto(
    val max: Long = 0,
    @SerialName("unreadcounts") val counts: List<UnreadCountDto> = emptyList(),
)

@Serializable
data class UnreadCountDto(
    val id: String,
    val count: Long = 0,
    val newestItemTimestampUsec: String = "",
)

@Serializable
data class StreamContentsDto(
    val items: List<ItemDto> = emptyList(),
    val continuation: String? = null,
)

@Serializable
data class ItemDto(
    val id: String,
    val crawlTimeMsec: String = "",
    val timestampUsec: String = "",
    val published: Long = 0,
    val updated: Long = 0,
    val title: String = "",
    val canonical: List<LinkDto> = emptyList(),
    val alternate: List<LinkDto> = emptyList(),
    val categories: List<String> = emptyList(),
    val origin: OriginDto? = null,
    val summary: ContentDto? = null,
    val content: ContentDto? = null,
    val enclosure: List<EnclosureDto> = emptyList(),
    val author: String = "",
)

@Serializable
data class LinkDto(val href: String = "", val type: String? = null)

@Serializable
data class OriginDto(val streamId: String = "", val htmlUrl: String = "", val title: String = "")

@Serializable
data class ContentDto(val content: String = "")

@Serializable
data class EnclosureDto(val href: String = "", val type: String = "", val length: Long? = null)

@Serializable
data class ItemIdsDto(
    val itemRefs: List<ItemRefDto> = emptyList(),
    val continuation: String? = null,
)

@Serializable
data class ItemRefDto(val id: String)

@Serializable
data class UserInfoDto(val userId: String = "", val userName: String = "", val userEmail: String = "")

@Serializable
data class QuickAddDto(
    val numResults: Int = 0,
    val query: String = "",
    val streamId: String = "",
    val streamName: String = "",
    val error: String? = null,
)
