package org.evsyukov.brook

import android.content.Intent
import android.content.pm.PackageManager
import android.text.SpannableString
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubscriptionShareTest {
    @Test
    fun extractsTheBrowserAddressFromATitleAndLinkWithoutChangingIt() {
        val address = "HTTPS://example.com/posts?q=one%2Ftwo&category=rss#latest"
        val share = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "A blog about feeds\n$address")

        assertThat(share.sharedFeedUrl()).isEqualTo(address)
    }

    @Test
    fun acceptsAnHttpFeedSharedAsACharSequence() {
        val address = "http://example.com/feed.xml"
        val share = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, SpannableString("  $address  "))

        assertThat(share.sharedFeedUrl()).isEqualTo(address)
    }

    @Test
    fun ignoresUnsupportedActionsTypesAndMalformedOrNonWebText() {
        val address = "https://example.com/feed"
        val inputs = listOf(
            Intent(Intent.ACTION_VIEW).setType("text/plain").putExtra(Intent.EXTRA_TEXT, address),
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("text/plain").putExtra(Intent.EXTRA_TEXT, address),
            Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_TEXT, address),
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, 123),
            Intent(Intent.ACTION_SEND).setType("text/plain"),
        ) + listOf(
            "a title with no link", "content://example.com/feed", "javascript:https://example.com",
            "https:///example.com", "https://alice@example.com/feed", "https://",
            "${"x".repeat(16_384)} $address",
        ).map { Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, it) }

        for (intent in inputs) assertThat(intent.sharedFeedUrl()).isNull()
    }

    @Test
    fun androidOffersBrookForTextLinksSharedByTheBrowser() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val share = Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(app.packageName)
            .putExtra(Intent.EXTRA_TEXT, "https://example.com/rss")

        val activity = app.packageManager.resolveActivity(share, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo

        assertThat(activity?.name).isEqualTo(MainActivity::class.java.name)
        assertThat(activity?.exported).isTrue()
    }
}
