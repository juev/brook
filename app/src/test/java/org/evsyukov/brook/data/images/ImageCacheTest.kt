package org.evsyukov.brook.data.images

import com.google.common.truth.Truth.assertThat
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImageCacheTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private lateinit var cache: ImageCache
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)

    @Before
    fun setUp() {
        server.start()
        cache = ImageCache(folder.newFolder("images"), OkHttpClient())
    }

    @After
    fun tearDown() = server.close()

    private fun picture() = MockResponse.Builder()
        .addHeader("Content-Type", "image/png; charset=binary")
        .body(Buffer().write(png))
        .build()

    @Test
    fun `a picture is downloaded once and then served from disk`() {
        server.enqueue(picture())
        val url = server.url("/a.png").toString()

        val first = cache.get(url)
        server.close()
        val second = cache.get(url)

        assertThat(first!!.file.readBytes()).isEqualTo(png)
        assertThat(first.mimeType).isEqualTo("image/png")
        assertThat(second!!.file).isEqualTo(first.file)
        assertThat(second.mimeType).isEqualTo("image/png")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `what is not a picture is not kept`() {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/html").body("<html>").build())
        val url = server.url("/page").toString()

        assertThat(cache.get(url)).isNull()
        assertThat(cache.cached(url)).isNull()
    }

    @Test
    fun `failures give nothing`() {
        server.enqueue(MockResponse.Builder().code(404).build())
        assertThat(cache.get(server.url("/missing.png").toString())).isNull()
        assertThat(cache.get("not a url")).isNull()
        val gone = server.url("/gone.png").toString()
        server.close()
        assertThat(cache.get(gone)).isNull()
    }

    @Test
    fun `removed pictures are gone from disk`() {
        server.enqueue(picture())
        val url = server.url("/a.png").toString()
        val file = cache.get(url)!!.file

        cache.remove(listOf(url))

        assertThat(file.exists()).isFalse()
        assertThat(cache.cached(url)).isNull()
    }
}
