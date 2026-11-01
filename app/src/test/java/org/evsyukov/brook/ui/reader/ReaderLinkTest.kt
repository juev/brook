package org.evsyukov.brook.ui.reader

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderLinkTest {
    private val url = "https://example.org/e/1"

    @Test
    fun aTappedLinkOpensTheChooserInANewTask() {
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
