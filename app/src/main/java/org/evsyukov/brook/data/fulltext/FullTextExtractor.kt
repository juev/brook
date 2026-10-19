package org.evsyukov.brook.data.fulltext

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.dankito.readability4j.extended.Readability4JExtended
import okhttp3.OkHttpClient
import okhttp3.Request
import org.evsyukov.brook.data.remote.await
import org.jsoup.Jsoup
import java.io.IOException

/**
 * The article of a page, found on the device by Readability4J (R9): the server gives only what the
 * feed carries.
 */
class FullTextExtractor(private val http: OkHttpClient) {
    /** The HTML of the article at [url]; throws [IOException] when the page cannot be had or has no article. */
    suspend fun extract(url: String): String {
        val request = try {
            Request.Builder().url(url).get().build()
        } catch (e: IllegalArgumentException) {
            throw IOException("Not a page address: $url")
        }
        // The body is read on the IO dispatcher: the reader screen calls from the main thread.
        val page = withContext(Dispatchers.IO) {
            http.newCall(request).await().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
                response.body.string()
            }
        }
        return withContext(Dispatchers.Default) { article(url, page) }
    }

    companion object {
        /** Shorter text than this is taken for a failed extraction, not an article. */
        private const val MIN_TEXT = 200

        internal fun article(url: String, page: String): String {
            val document = Jsoup.parse(page, url)
            // Readability4J resolves relative addresses itself and drops a port that is not the default.
            for (attribute in listOf("src", "href")) {
                for (element in document.select("[$attribute]")) {
                    element.absUrl(attribute).takeIf { it.isNotEmpty() }?.let { element.attr(attribute, it) }
                }
            }
            val article = try {
                Readability4JExtended(url, document).parse()
            } catch (e: RuntimeException) {
                throw IOException("Cannot read the page at $url", e)
            }
            val html = article.content
            if (html.isNullOrBlank() || article.textContent.orEmpty().trim().length < MIN_TEXT) {
                throw IOException("No article at $url")
            }
            return html
        }
    }
}
