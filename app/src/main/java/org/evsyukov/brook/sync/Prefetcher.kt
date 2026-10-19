package org.evsyukov.brook.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.evsyukov.brook.data.db.BrookDatabase
import org.evsyukov.brook.data.db.EntryEntity
import org.evsyukov.brook.data.fulltext.FullTextExtractor
import org.evsyukov.brook.data.images.ImageCache
import org.jsoup.Jsoup
import java.io.IOException

/** Pictures of an entry: `img` of its texts, resolved against its address, and picture enclosures. */
fun imageUrls(entry: EntryEntity): Set<String> {
    val urls = LinkedHashSet<String>()
    for (html in listOfNotNull(entry.content, entry.fullContent)) {
        if (!html.contains("<img", ignoreCase = true)) continue
        Jsoup.parseBodyFragment(html, entry.url).select("img[src]").mapNotNullTo(urls) { img ->
            img.absUrl("src").takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }
    }
    decodeEnclosures(entry.enclosuresJson).filter { it.type.startsWith("image/") }.mapTo(urls) { it.href }
    return urls
}

/**
 * Makes unread and starred entries readable offline (R3, R9): downloads their pictures and, for
 * feeds set to always show full text, extracts the full text of unread ones. Afterwards pictures of
 * entries no longer here are deleted. Runs from a background worker on the networks the settings allow.
 */
class Prefetcher(
    private val db: BrookDatabase,
    private val images: ImageCache,
    private val extractor: FullTextExtractor,
) {
    data class Result(val pictures: Int, val fullTexts: Int)

    /** [stopped] is asked between entries, so a worker told to stop leaves soon. */
    suspend fun run(stopped: () -> Boolean = { false }): Result {
        val entries = db.entryDao()
        val fullTextFeeds = db.treeDao().fullTextFeeds().toSet()
        var pictures = 0
        var fullTexts = 0
        var after = 0L
        while (!stopped()) {
            val page = entries.keptForOffline(after, PAGE)
            if (page.isEmpty()) break
            for (entry in page) {
                if (stopped()) break
                var current = entry
                if (!entry.isRead && entry.feedId in fullTextFeeds && entry.fullContent == null && entry.url.isNotBlank()) {
                    try {
                        val html = extractor.extract(entry.url)
                        entries.setFullContent(entry.id, html)
                        current = entry.copy(fullContent = html)
                        fullTexts++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IOException) {
                        // The page is away or has no article; the text of the server stays.
                    }
                }
                pictures += withContext(Dispatchers.IO) {
                    imageUrls(current).count { url -> images.cached(url) == null && images.download(url) != null }
                }
            }
            after = page.last().id
        }
        if (!stopped()) sweep()
        return Result(pictures, fullTexts)
    }

    /** Deletes pictures no entry here shows any more (R3: they go with their entries). */
    private suspend fun sweep() {
        val needed = HashSet<String>()
        var after = 0L
        while (true) {
            val page = db.entryDao().after(after, PAGE)
            if (page.isEmpty()) break
            page.flatMapTo(needed) { imageUrls(it) }
            after = page.last().id
        }
        withContext(Dispatchers.IO) { images.retainOnly(needed) }
    }

    private companion object {
        const val PAGE = 100
    }
}
