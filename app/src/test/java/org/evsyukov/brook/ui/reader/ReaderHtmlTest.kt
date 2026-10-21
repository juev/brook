package org.evsyukov.brook.ui.reader

import com.google.common.truth.Truth.assertThat
import org.evsyukov.brook.data.remote.EnclosureDto
import org.evsyukov.brook.data.settings.ReaderFont
import org.jsoup.Jsoup
import org.junit.Test

class ReaderHtmlTest {
    private val colors = ReaderColors("#FFFFFF", "#111111", "#555555", "#0055AA", "#EEEEEE")

    private fun html(
        title: String = "Title",
        body: String = "<p>Body</p>",
        url: String = "https://example.org/e/1",
        enclosures: List<EnclosureDto> = emptyList(),
        feedTitle: String? = "Feed",
        author: String = "",
        style: ReaderStyle = ReaderStyle(),
    ) = readerHtml(title, feedTitle, author, "1 Jan 2026", url, body, enclosures, colors, style)

    @Test
    fun `the title is escaped and links to the entry while the body stays HTML`() {
        val page = html(title = "<b>A & B</b>", body = "<p>Text <em>here</em></p>")
        assertThat(page).contains("<h1><a href=\"https://example.org/e/1\">&lt;b&gt;A &amp; B&lt;/b&gt;</a></h1>")
        assertThat(page).contains("<article><p>Text <em>here</em></p></article>")
    }

    @Test
    fun `the date goes above the title and the author and feed under it`() {
        val page = html(author = "Ann")
        assertThat(page).contains("<header><p class=\"date\">1 Jan 2026</p><h1>")
        assertThat(page).contains("</h1><p class=\"byline\">Ann<br>Feed</p></header>")
        assertThat(html(feedTitle = null)).doesNotContain("class=\"byline\"")
    }

    @Test
    fun `a picture that opens the text spans the width`() {
        assertThat(withLeadPicture("<img src=\"a.png\"><p>Text</p>")).isEqualTo("<img src=\"a.png\" class=\"lead\"><p>Text</p>")
        assertThat(withLeadPicture("<figure><a href=\"x\"><img src=\"a.png\"></a></figure><p>Text</p>"))
            .isEqualTo("<figure class=\"lead\"><a href=\"x\"><img src=\"a.png\"></a></figure><p>Text</p>")
        assertThat(withLeadPicture("<p><img src=\"a.png\"></p><p>Text <b>bold</b> <i>it</i></p>"))
            .isEqualTo("<p class=\"lead\"><img src=\"a.png\"></p><p>Text <b>bold</b> <i>it</i></p>")
    }

    @Test
    fun `a picture inside the text or beside words stays in the column`() {
        for (body in listOf(
            "<p>Text</p><img src=\"a.png\">",
            "Lead text<img src=\"a.png\">",
            "<p>Words <img src=\"a.png\"></p>",
            "<figure><img src=\"a.png\"><figcaption>Caption</figcaption></figure>",
            "<div><img src=\"a.png\"><img src=\"b.png\"></div>",
            "<p>No pictures</p>",
        )) {
            assertThat(withLeadPicture(body)).isEqualTo(body)
        }
    }

    @Test
    fun `the style of the reader reaches the page`() {
        val serif = html(style = ReaderStyle(ReaderFont.SERIF, textSize = 21, lineHeight = 170))
        assertThat(serif).contains("font-family: Literata, serif; font-size: 21px; line-height: 1.7;")
        assertThat(serif).contains("src: url(\"https://brook.invalid/fonts/literata.woff2\")")
        assertThat(html()).contains("font-family: sans-serif; font-size: 17px; line-height: 1.55;")
    }

    @Test
    fun `an entry without an address has a plain title`() {
        assertThat(html(url = "")).contains("<h1>Title</h1>")
    }

    @Test
    fun `enclosures the text already links to are not repeated`() {
        val inText = EnclosureDto("https://cdn.example.org/ep1.mp3", "audio/mpeg")
        val notInText = EnclosureDto("https://cdn.example.org/ep1.mp4", "video/mp4")
        val page = html(body = "<p><a href=\"https://cdn.example.org/ep1.mp3\">listen</a></p>", enclosures = listOf(inText, notInText))

        assertThat(page).contains("<video controls preload=\"none\" src=\"https://cdn.example.org/ep1.mp4\"></video>")
        assertThat(page).contains(">ep1.mp4</a>")
        assertThat(page).doesNotContain("<audio")
    }

    @Test
    fun `colors of the theme reach the style`() {
        val page = html()
        assertThat(page).contains("background: #FFFFFF")
        assertThat(page).contains("a { color: #0055AA; }")
    }

    @Test
    fun `a whole page sent as the content opens on its text, not on its navigation`() {
        // The content of an entry of https://www.nasa.gov/news-release/feed/ as it came on 2026-10-21.
        val content = javaClass.getResource("/feed/nasa-apod.html")!!.readText()
        val text = Jsoup.parse(html(body = content).substringAfter("<article>")).text()
        assertThat(text).startsWith("APOD Astronomy Picture of the Day")
        assertThat(text).contains("Jinkies, look at all those ghosts!")
        assertThat(text).doesNotContain("Today’s APOD")
    }

    @Test
    fun `content without navigation or buttons is kept as it came`() {
        val body = "<p>Text <em>here</em> and <a href=\"x\">a link</a></p><ul><li>One</li></ul>"
        assertThat(withoutPageChrome(body)).isSameInstanceAs(body)
        assertThat(withoutPageChrome("<p>A <b>b</b></p><nav><a href=\"/\">Home</a></nav><button>Menu</button><p>C</p>"))
            .isEqualTo("<p>A <b>b</b></p><p>C</p>")
    }
}
