package org.evsyukov.brook.ui.reader

import org.evsyukov.brook.data.remote.EnclosureDto
import org.evsyukov.brook.data.settings.ReaderFont
import org.jsoup.Jsoup

/** Colors of the theme for the page, as CSS values. */
data class ReaderColors(
    val background: String,
    val text: String,
    val secondary: String,
    val link: String,
    val surface: String,
)

/** Typography of the page: the settings of the reader. */
data class ReaderStyle(
    val font: ReaderFont = ReaderFont.SANS,
    /** CSS pixels. */
    val textSize: Int = 17,
    /** Percent of the text size. */
    val lineHeight: Int = 155,
)

/** Where the page asks for bundled fonts; the WebView client serves them from the assets. */
const val READER_ASSETS = "https://brook.invalid/"

/**
 * The document the WebView shows: a header with the date, the title, the author and the feed, the
 * text in a column of readable width, and enclosures the text does not link to yet (freshgo puts
 * them into the text; other servers may not). A picture that opens the text spans the whole width.
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
    style: ReaderStyle = ReaderStyle(),
): String {
    val missing = enclosures.filter { it.href.isNotBlank() && !body.contains(it.href) }
    val byline = listOfNotNull(author.takeIf { it.isNotBlank() }, feedTitle?.takeIf { it.isNotBlank() })
    return buildString {
        append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        append("<style>").append(css(colors, style)).append("</style></head><body><div class=\"page\">")
        append("<header>")
        if (date.isNotBlank()) append("<p class=\"date\">").append(escape(date)).append("</p>")
        if (url.isNotBlank()) {
            append("<h1><a href=\"").append(escape(url)).append("\">").append(escape(title)).append("</a></h1>")
        } else {
            append("<h1>").append(escape(title)).append("</h1>")
        }
        if (byline.isNotEmpty()) append("<p class=\"byline\">").append(byline.joinToString("<br>") { escape(it) }).append("</p>")
        append("</header><article>").append(withLeadPicture(body)).append("</article>")
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
        append("</div></body></html>")
    }
}

/**
 * [body] with the class `lead` on its first element when that is a picture: an `img`, or an element
 * holding one picture and no text, such as a figure with a caption-less image or a link around it.
 * The text is returned as it came when it does not start with a picture.
 */
internal fun withLeadPicture(body: String): String {
    if (!body.contains("<img", ignoreCase = true)) return body
    val document = Jsoup.parseBodyFragment(body)
    // Pretty printing would put spaces between inline elements of the text.
    document.outputSettings().prettyPrint(false)
    val root = document.body()
    val first = root.children().firstOrNull() ?: return body
    // Text before the first element means the entry does not open with it.
    if (root.textNodes().any { it.text().isNotBlank() && it.siblingIndex() < first.siblingIndex() }) return body
    val lead = when {
        first.normalName() == "img" -> first
        first.select("img").size == 1 && first.text().isBlank() -> first
        else -> return body
    }
    lead.addClass("lead")
    return root.html()
}

private fun fontFamily(font: ReaderFont) = when (font) {
    ReaderFont.SANS -> "sans-serif"
    ReaderFont.SERIF -> "Literata, serif"
}

private fun css(c: ReaderColors, s: ReaderStyle) = """
    @font-face { font-family: Literata; font-style: normal; font-weight: 200 900; src: url("${READER_ASSETS}fonts/literata.woff2") format("woff2"); }
    @font-face { font-family: Literata; font-style: italic; font-weight: 200 900; src: url("${READER_ASSETS}fonts/literata-italic.woff2") format("woff2"); }
    html { -webkit-text-size-adjust: 100%; }
    body { margin: 0; padding: 24px 0 48px; background: ${c.background}; color: ${c.text};
           font-family: ${fontFamily(s.font)}; font-size: ${s.textSize}px; line-height: ${s.lineHeight / 100.0}; word-wrap: break-word; }
    .page { max-width: 38em; margin: 0 auto; padding: 0 20px; }
    a { color: ${c.link}; }
    header { margin: 0 0 24px; }
    .date, .byline { color: ${c.secondary}; font-family: sans-serif; font-size: 0.72em; line-height: 1.4;
                     text-transform: uppercase; letter-spacing: 0.04em; }
    .date { margin: 0 0 8px; }
    .byline { margin: 10px 0 0; }
    header h1 { font-size: 1.6em; line-height: 1.2; margin: 0; }
    header h1 a { color: ${c.text}; text-decoration: none; }
    img, video, iframe, embed, object { max-width: 100%; height: auto; }
    figure { margin: 1em 0; }
    .lead { display: block; margin: 0 -20px 1.2em; width: calc(100% + 40px); max-width: none; }
    .lead img { display: block; width: 100%; max-width: none; }
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
