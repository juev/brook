package org.evsyukov.brook.data.images

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

// Legacy graphics of Robolectric cannot draw into a bitmap.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(AndroidJUnit4::class)
class ThumbnailsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private lateinit var thumbnails: Thumbnails

    @Before
    fun setUp() {
        server.start()
        thumbnails = Thumbnails(ImageCache(folder.newFolder("images"), OkHttpClient()))
    }

    @After
    fun tearDown() = server.close()

    private fun svg(body: String) = MockResponse.Builder().addHeader("Content-Type", "image/svg+xml").body(body).build()

    @Test
    fun anSvgIconIsDrawnIntoTheSquare() = runBlocking<Unit> {
        // Sized but without a view box, as many favicons are: it still fills the square.
        server.enqueue(svg("""<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16"><rect width="16" height="16" fill="#FF0000"/></svg>"""))

        val bitmap = thumbnails.load(server.url("/favicon.svg").toString(), 48)!!

        assertThat(bitmap.width).isEqualTo(48)
        assertThat(bitmap.height).isEqualTo(48)
        assertThat(bitmap.getPixel(2, 2)).isEqualTo(Color.RED)
        assertThat(bitmap.getPixel(45, 45)).isEqualTo(Color.RED)
    }

    @Test
    fun aBrokenSvgIsNoPicture() = runBlocking<Unit> {
        server.enqueue(svg("<svg"))

        assertThat(thumbnails.load(server.url("/broken.svg").toString(), 48)).isNull()
    }
}
