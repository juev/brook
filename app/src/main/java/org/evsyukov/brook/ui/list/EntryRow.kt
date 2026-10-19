package org.evsyukov.brook.ui.list

import org.evsyukov.brook.data.db.EntryListItem
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
)

fun EntryListItem.toRow() = EntryRow(
    id = id,
    feedTitle = feedTitle.orEmpty(),
    title = title.ifBlank { url },
    preview = plainText(excerpt),
    time = sortTime,
    isRead = isRead,
    isStarred = isStarred,
)

/** The text of an HTML fragment, spaces collapsed; the excerpt may end inside a tag. */
internal fun plainText(html: String): String = Jsoup.parse(html).text().trim()
