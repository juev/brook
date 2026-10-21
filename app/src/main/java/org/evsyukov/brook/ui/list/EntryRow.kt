package org.evsyukov.brook.ui.list

import org.evsyukov.brook.data.db.EntryListItem
import org.evsyukov.brook.sync.decodeEnclosures
import org.evsyukov.brook.ui.reader.PAGE_CHROME
import org.jsoup.Jsoup

/** A row of the list as the screen draws it. */
data class EntryRow(
    val id: Long,
    val feedTitle: String,
    val title: String,
    /** Plain text of the start of the entry. */
    val preview: String,
    /** Seconds. */
    val time: Long,
    val isRead: Boolean,
    val isStarred: Boolean,
    /** The icon of the feed, empty when there is none. */
    val iconUrl: String = "",
    /** The picture for the thumbnail, null when the entry has none. */
    val imageUrl: String? = null,
)

fun EntryListItem.toRow() = EntryRow(
    id = id,
    feedTitle = feedTitle.orEmpty(),
    title = title.ifBlank { url },
    preview = plainText(excerpt),
    time = sortTime,
    isRead = isRead,
    isStarred = isStarred,
    iconUrl = feedIconUrl.orEmpty(),
    imageUrl = thumbnailUrl(imageTag, url, enclosuresJson),
)

/** The text of an HTML fragment without [PAGE_CHROME], spaces collapsed; the excerpt may end inside a tag. */
internal fun plainText(html: String): String = Jsoup.parse(html).apply { select(PAGE_CHROME).remove() }.text().trim()

/**
 * The first picture of an entry worth a thumbnail: the first `img` of [imageTag], resolved against
 * the address of the entry, else the first picture enclosure. Pictures a side or two pixels wide are
 * counters, not content.
 */
internal fun thumbnailUrl(imageTag: String?, entryUrl: String, enclosuresJson: String): String? {
    if (imageTag != null) {
        Jsoup.parseBodyFragment(imageTag, entryUrl).select("img[src]").firstOrNull { img ->
            val tiny = listOf("width", "height").any { img.attr(it).trim().toIntOrNull()?.let { size -> size <= 2 } == true }
            !tiny && img.absUrl("src").isWeb()
        }?.let { return it.absUrl("src") }
    }
    return decodeEnclosures(enclosuresJson).firstOrNull { it.type.startsWith("image/") && it.href.isWeb() }?.href
}

private fun String.isWeb() = startsWith("http://") || startsWith("https://")
