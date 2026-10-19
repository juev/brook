package org.evsyukov.brook.ui.reader

import org.evsyukov.brook.data.remote.EnclosureDto

/** Colors of the theme for the page, as CSS values. */
data class ReaderColors(
    val background: String,
    val text: String,
    val secondary: String,
    val link: String,
    val surface: String,
)

/**
 * The document the WebView shows: a header with title, feed, author and date, the text, and
 * enclosures the text does not link to yet (freshgo puts them into the text; other servers may not).
 */
fun readerHtml(
    title: String,
    feedTitle: String?,
    author: String,
    date: String,
    url: String,
    body: String,
    enclosures: List<EnclosureDto>,
    colors: ReaderColors,
): String {
    val missing = enclosures.filter { it.href.isNotBlank() && !body.contains(it.href) }
    val meta = listOfNotNull(feedTitle?.takeIf { it.isNotBlank() }, author.takeIf { it.isNotBlank() }, date.takeIf { it.isNotBlank() })
    return buildString {
        append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        append("<style>").append(css(colors)).append("</style></head><body>")
        append("<header>")
        if (url.isNotBlank()) {
            append("<h1><a href=\"").append(escape(url)).append("\">").append(escape(title)).append("</a></h1>")
        } else {
            append("<h1>").append(escape(title)).append("</h1>")
        }
        if (meta.isNotEmpty()) append("<p class=\"meta\">").append(meta.joinToString(" · ") { escape(it) }).append("</p>")
        append("</header><article>").append(body).append("</article>")
        if (missing.isNotEmpty()) {
            append("<ul class=\"enclosures\">")
            for (e in missing) {
                append("<li>")
                when {
                    e.type.startsWith("audio/") -> append("<audio controls preload=\"none\" src=\"").append(escape(e.href)).append("\"></audio><br>")
                    e.type.startsWith("video/") -> append("<video controls preload=\"none\" src=\"").append(escape(e.href)).append("\"></video><br>")
                    e.type.startsWith("image/") -> append("<img src=\"").append(escape(e.href)).append("\"><br>")
                }
                append("<a href=\"").append(escape(e.href)).append("\">").append(escape(e.href.substringAfterLast('/').ifBlank { e.href })).append("</a>")
                append("</li>")
            }
            append("</ul>")
        }
        append("</body></html>")
    }
}

private fun css(c: ReaderColors) = """
    html { -webkit-text-size-adjust: 100%; }
    body { margin: 0; padding: 16px 20px 48px; background: ${c.background}; color: ${c.text};
           font-family: sans-serif; font-size: 17px; line-height: 1.55; word-wrap: break-word; }
    a { color: ${c.link}; }
    header h1 { font-size: 1.45em; line-height: 1.25; margin: 0 0 8px; }
    header h1 a { color: ${c.text}; text-decoration: none; }
    .meta { color: ${c.secondary}; font-size: 0.85em; margin: 0 0 20px; }
    img, video, iframe, embed, object { max-width: 100%; height: auto; }
    figure { margin: 1em 0; }
    pre, code { background: ${c.surface}; border-radius: 6px; }
    pre { padding: 12px; overflow-x: auto; }
    blockquote { margin: 1em 0; padding-left: 12px; border-left: 3px solid ${c.secondary}; color: ${c.secondary}; }
    table { display: block; overflow-x: auto; border-collapse: collapse; }
    .enclosures { list-style: none; padding: 0; margin-top: 24px; }
    .enclosures li { margin-bottom: 12px; }
    audio { width: 100%; }
""".trimIndent()

internal fun escape(text: String): String = buildString(text.length) {
    for (c in text) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }
}
