package org.evsyukov.brook.ui.reader

import com.google.common.truth.Truth.assertThat
import org.evsyukov.brook.data.remote.EnclosureDto
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
    ) = readerHtml(title, feedTitle, author, "1 Jan 2026", url, body, enclosures, colors)

    @Test
    fun `the title is escaped and links to the entry while the body stays HTML`() {
        val page = html(title = "<b>A & B</b>", body = "<p>Text <em>here</em></p>")
        assertThat(page).contains("<h1><a href=\"https://example.org/e/1\">&lt;b&gt;A &amp; B&lt;/b&gt;</a></h1>")
        assertThat(page).contains("<article><p>Text <em>here</em></p></article>")
    }

    @Test
    fun `the meta line has what is known`() {
        assertThat(html(author = "Ann")).contains("<p class=\"meta\">Feed · Ann · 1 Jan 2026</p>")
        assertThat(html(feedTitle = null)).contains("<p class=\"meta\">1 Jan 2026</p>")
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
}
