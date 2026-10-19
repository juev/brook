package org.evsyukov.brook.data.fulltext

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException

class FullTextExtractorTest {
    private val server = MockWebServer()
    private val extractor = FullTextExtractor(OkHttpClient())

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun page(name: String) = javaClass.getResource("/fulltext/$name")!!.readText()

    @Test
    fun `the article is taken and the site around it left out`() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().body(page("article.html")).addHeader("Content-Type", "text/html").build())

        val html = extractor.extract(server.url("/2026/rivers").toString())

        assertThat(html).contains("The rivers of the north freeze in November")
        assertThat(html).contains("Summer is short and busy")
        assertThat(html).contains("https://example.org/images/river.jpg")
        assertThat(html).doesNotContain("Sidebar link one")
        assertThat(html).doesNotContain("Subscribe today")
        assertThat(html).doesNotContain("All rights reserved")
    }

    @Test
    fun `relative addresses keep the port of the page`() = runBlocking<Unit> {
        val page = page("article.html").replace("https://example.org/images/river.jpg", "/images/river.jpg")
        server.enqueue(MockResponse.Builder().body(page).build())

        val html = extractor.extract(server.url("/2026/rivers").toString())

        assertThat(html).contains(server.url("/images/river.jpg").toString())
    }

    @Test
    fun `a page without an article is a failure`() {
        server.enqueue(MockResponse.Builder().body("<html><body><p>Not found.</p></body></html>").build())
        assertThrows(IOException::class.java) { runBlocking { extractor.extract(server.url("/x").toString()) } }
    }

    @Test
    fun `an error answer is a failure`() {
        server.enqueue(MockResponse.Builder().code(404).body(page("article.html")).build())
        assertThrows(IOException::class.java) { runBlocking { extractor.extract(server.url("/x").toString()) } }
    }

    @Test
    fun `an address that is not one is a failure`() {
        assertThrows(IOException::class.java) { runBlocking { extractor.extract("not a url") } }
    }
}
