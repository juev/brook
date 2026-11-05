package org.evsyukov.brook.ui.reader

import android.content.Context
import android.os.Looper
import android.os.Message
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.evsyukov.brook.data.images.ImageCache
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowWebView
import java.io.File

/** The page is one WebView for every entry: built once, its document swapped, taken down by the screen. */
@RunWith(AndroidJUnit4::class)
class ReaderPageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val images = ImageCache(File(context.cacheDir, "pictures"), OkHttpClient())

    @Test
    fun everyEntryIsShownInThePageThatIsAlreadyThere() {
        val page = ReaderPage(context, images, threshold = 8f)
        try {
            val first = page.attach()
            page.load("<p>one</p>", "https://example.org/one")
            val second = page.attach()
            page.load("<p>two</p>", "https://example.org/two")

            assertThat(second).isSameInstanceAs(first)
            assertThat(page.loads).isEqualTo(2)
        } finally {
            page.destroy()
        }
    }

    @Test
    fun theEntryOnScreenIsNotLoadedAgainByADrawOfIt() {
        val page = ReaderPage(context, images, threshold = 8f)
        try {
            page.attach()
            page.load("<p>one</p>", null)
            // A recomposition with the same document leaves the reader where it was.
            page.load("<p>one</p>", null)
            assertThat(page.loads).isEqualTo(1)

            // An entry opened again starts at its top, even with the text it had.
            page.attach()
            page.load("<p>one</p>", null)
            assertThat(page.loads).isEqualTo(2)
        } finally {
            page.destroy()
        }
    }

    @Test
    fun aLinkFoundForTheEntryThatWasHereDoesNotOpenInTheNextOne() {
        var pending: Message? = null
        val page = ReaderPage(context, images, threshold = 8f, requestHref = { _, message -> pending = message })
        try {
            val view = page.attach()
            page.load("<p>one</p>", "https://example.org/one")
            val opened = mutableListOf<String>()
            view.onLinkLongPress = { opened += it }
            shadowOf(view).setHitTestResult(
                ShadowWebView.createHitTestResult(WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE, "https://example.org/p.png"),
            )
            shadowOf(view).onLongClickListener!!.onLongClick(view)

            page.load("<p>two</p>", "https://example.org/two")
            pending!!.data.putString("url", "https://example.org/late")
            pending!!.sendToTarget()
            shadowOf(Looper.getMainLooper()).idle()

            assertThat(opened).isEmpty()
        } finally {
            page.destroy()
        }
    }

    @Test
    fun aLinkFoundWhenTheReaderWasOnScreenDoesNotOpenAfterItIsGone() {
        var pending: Message? = null
        val page = ReaderPage(context, images, threshold = 8f, requestHref = { _, message -> pending = message })
        try {
            val view = page.attach()
            page.load("<p>one</p>", "https://example.org/one")
            val opened = mutableListOf<String>()
            view.onLinkLongPress = { opened += it }
            shadowOf(view).setHitTestResult(
                ShadowWebView.createHitTestResult(WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE, "https://example.org/p.png"),
            )
            shadowOf(view).onLongClickListener!!.onLongClick(view)

            // The reader is closed while the page is held for the list, so it is not taken down.
            page.release()
            pending!!.data.putString("url", "https://example.org/late")
            pending!!.sendToTarget()
            shadowOf(Looper.getMainLooper()).idle()

            assertThat(opened).isEmpty()
            assertThat(shadowOf(view).wasDestroyCalled()).isFalse()
            // The next entry still opens in the page that is already built.
            assertThat(page.attach()).isSameInstanceAs(view)
        } finally {
            page.destroy()
        }
    }

    @Test
    fun thePageIsTakenDownWithTheScreenAndBuiltAgainAfter() {
        val page = ReaderPage(context, images, threshold = 8f)
        val first = page.attach()
        page.load("<p>one</p>", null)
        page.destroy()

        assertThat(shadowOf(first).wasDestroyCalled()).isTrue()

        val next = page.attach()
        try {
            assertThat(next).isNotSameInstanceAs(first)
        } finally {
            page.destroy()
        }
    }
}
