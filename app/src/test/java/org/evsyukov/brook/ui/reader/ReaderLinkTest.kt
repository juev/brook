package org.evsyukov.brook.ui.reader

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class ReaderLinkTest {
    private val url = "https://example.org/e/1"

    @Test
    fun aTappedLinkIsOpenedWithoutAChooserOfOurs() {
        val view = linkIntent(url)

        assertThat(view.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(view.dataString).isEqualTo(url)
        assertThat(view.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isEqualTo(Intent.FLAG_ACTIVITY_NEW_TASK)
        assertThat(view.hasExtra(Intent.EXTRA_INTENT)).isFalse()
        assertThat(view.hasExtra(Intent.EXTRA_ALTERNATE_INTENTS)).isFalse()
    }

    @Test
    fun aTapStartsTheDefaultAppForTheLink() {
        val context = ApplicationProvider.getApplicationContext<Application>()

        openLink(context, url)

        val started = shadowOf(context).nextStartedActivity
        assertThat(started).isNotNull()
        assertThat(started!!.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(started.dataString).isEqualTo(url)
        assertThat(started.hasExtra(Intent.EXTRA_INTENT)).isFalse()
    }

    @Test
    fun aLinkNoAppTakesLeavesTheReaderWhereItIs() {
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun startActivity(intent: Intent) {
                throw ActivityNotFoundException("nothing takes $intent")
            }
        }

        openLink(context, url)
    }

    @Test
    fun onlyHttpLinksAreOpened() {
        val context = ApplicationProvider.getApplicationContext<Application>()

        listOf(
            "mailto:someone@example.org",
            "file:///data/local/tmp/page.html",
            "intent://scan/#Intent;scheme=zxing;end",
            "javascript:alert(1)",
        ).forEach { unsafe ->
            openLink(context, unsafe)
            assertThat(shadowOf(context).nextStartedActivity).isNull()
        }
    }

    @Test
    fun aLongPressOpensTheChooserInANewTask() {
        val chooser = linkChooser(url)

        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        assertThat(chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isEqualTo(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun theChooserOpensTheLinkInABrowser() {
        val view = linkChooser(url).getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!

        assertThat(view.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(view.dataString).isEqualTo(url)
    }

    @Test
    fun theChooserAlsoOffersTheAppsThatTakeTheLinkAsText() {
        // The array goes in as Parcelable[]; the untyped getter reads it back as it was put.
        @Suppress("DEPRECATION")
        val alternates = linkChooser(url).getParcelableArrayExtra(Intent.EXTRA_ALTERNATE_INTENTS)!!.toList()

        assertThat(alternates).hasSize(1)
        val send = alternates.single() as Intent
        assertThat(send.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(send.type).isEqualTo("text/plain")
        assertThat(send.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo(url)
    }
}
