package org.evsyukov.brook.ui.reader

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.os.Message
import android.view.MotionEvent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.evsyukov.brook.data.images.ImageCache
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowWebView
import java.io.File

/**
 * A long press asks the page for the link under the finger; a tap opens the link itself. The
 * release of a long press must not tap the same link again, and an answer that comes back for a
 * page that is gone must not open anything.
 */
@RunWith(AndroidJUnit4::class)
class ReaderGestureTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val url = "https://example.org/e/1"
    private val picture = "https://cdn.example.org/lead.png"
    private val destination = "https://example.org/from-the-picture"

    private val longPressed = mutableListOf<String>()
    private val tapped = mutableListOf<String>()
    private val views = mutableListOf<EntryWebView>()

    private fun page(requestHref: (WebView, Message) -> Unit = { _, _ -> }): EntryWebView =
        EntryWebView(context, threshold = 8f, requestHref = requestHref).apply {
            onLinkLongPress = { longPressed += it }
            onLink = { tapped += it }
        }.also { views += it }

    @After
    fun takeDownThePages() {
        views.forEach { it.destroy() }
        views.clear()
    }

    private fun touch(view: EntryWebView, action: Int) {
        val event = MotionEvent.obtain(0L, 0L, action, 0f, 0f, 0)
        try {
            view.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun hit(view: EntryWebView, type: Int, extra: String) {
        shadowOf(view).setHitTestResult(ShadowWebView.createHitTestResult(type, extra))
    }

    private fun longClick(view: EntryWebView): Boolean =
        shadowOf(view).onLongClickListener?.onLongClick(view) ?: false

    @Test
    fun aLongPressOnALinkAsksForTheChooser() {
        val view = page()
        hit(view, WebView.HitTestResult.SRC_ANCHOR_TYPE, url)

        assertThat(longClick(view)).isTrue()
        assertThat(longPressed).containsExactly(url)
        assertThat(tapped).isEmpty()
    }

    @Test
    fun aLongPressOnOrdinaryTextIsLeftToThePage() {
        val view = page()
        hit(view, WebView.HitTestResult.UNKNOWN_TYPE, "")

        assertThat(longClick(view)).isFalse()
        assertThat(longPressed).isEmpty()
    }

    @Test
    fun aLongPressOnALinkedPictureUsesItsDestination() {
        val view = page { _, message ->
            message.data.putString("url", destination)
            message.sendToTarget()
        }
        hit(view, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE, picture)

        assertThat(longClick(view)).isTrue()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(longPressed).containsExactly(destination)
        assertThat(longPressed).doesNotContain(picture)
    }

    @Test
    fun aLatePictureLinkFromAPageThatIsGoneIsDropped() {
        var pending: Message? = null
        val view = page { _, message -> pending = message }
        hit(view, WebView.HitTestResult.IMAGE_ANCHOR_TYPE, picture)
        longClick(view)

        view.invalidateLinkHit()
        pending!!.data.putString("url", destination)
        pending!!.sendToTarget()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(longPressed).isEmpty()
    }

    @Test
    fun aLatePictureLinkOfAnOlderTouchIsDroppedWhenANewTouchStarts() {
        var pending: Message? = null
        val view = page { _, message -> pending = message }
        hit(view, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE, picture)
        longClick(view)

        touch(view, MotionEvent.ACTION_DOWN)
        pending!!.data.putString("url", destination)
        pending!!.sendToTarget()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(longPressed).isEmpty()
    }

    @Test
    fun aCancelledTouchDropsThePictureLinkItAskedFor() {
        var pending: Message? = null
        val view = page { _, message -> pending = message }
        hit(view, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE, picture)
        longClick(view)

        touch(view, MotionEvent.ACTION_CANCEL)
        pending!!.data.putString("url", destination)
        pending!!.sendToTarget()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(longPressed).isEmpty()
    }

    @Test
    fun onlyTheAnswerToTheLatestTouchOpensTheChooser() {
        val pending = mutableListOf<Message>()
        val view = page { _, message -> pending += message }
        hit(view, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE, picture)

        longClick(view)
        touch(view, MotionEvent.ACTION_DOWN)
        longClick(view)

        pending[0].data.putString("url", "https://example.org/older")
        pending[0].sendToTarget()
        pending[1].data.putString("url", destination)
        pending[1].sendToTarget()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(longPressed).containsExactly(destination)
    }

    @Test
    fun theReleaseOfALongPressDoesNotTapTheLinkToo() {
        val view = page()
        hit(view, WebView.HitTestResult.ANCHOR_TYPE, url)
        longClick(view)

        view.tap(url)

        assertThat(longPressed).containsExactly(url)
        assertThat(tapped).isEmpty()
    }

    @Test
    fun theNextGestureTapsAsUsual() {
        val view = page()
        hit(view, WebView.HitTestResult.ANCHOR_TYPE, url)
        longClick(view)
        view.tap(url)

        touch(view, MotionEvent.ACTION_DOWN)
        view.tap(url)

        assertThat(tapped).containsExactly(url)
    }

    @Test
    fun aTapReachesTheCallbackOfTheEntryOnScreen() {
        val view = page()
        val before = mutableListOf<String>()
        val after = mutableListOf<String>()
        view.onLink = { before += it }
        view.onLink = { after += it }

        view.tap(url)

        assertThat(before).isEmpty()
        assertThat(after).containsExactly(url)
    }

    @Test
    fun thePageClientTurnsATapIntoTheLinkCallback() {
        val view = page()

        client().shouldOverrideUrlLoading(view, request(url))

        assertThat(tapped).containsExactly(url)
    }

    @Test
    fun thePageClientLeavesASchemeItCannotOpen() {
        val view = page()

        client().shouldOverrideUrlLoading(view, request("mailto:someone@example.org"))

        assertThat(tapped).isEmpty()
    }

    private fun client() = ReaderWebViewClient(ImageCache(File(context.cacheDir, "pictures"), OkHttpClient()))

    private fun request(url: String) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame(): Boolean = true
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = true
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
    }
}
