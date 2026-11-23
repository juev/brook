package org.evsyukov.brook

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubscriptionShareDeviceTest {
    @Test
    fun installedBrookRegistersForBrowserTextSharesButNotImages() {
        val app = ApplicationProvider.getApplicationContext<BrookApplication>()
        val share = Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(app.packageName)
            .putExtra(Intent.EXTRA_TEXT, "https://example.com/feed")

        val flags = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
        val info = app.packageManager.resolveActivity(share, flags)?.activityInfo
        assertThat(info?.name).isEqualTo(MainActivity::class.java.name)
        assertThat(info?.exported).isTrue()
        assertThat(info?.launchMode).isEqualTo(ActivityInfo.LAUNCH_SINGLE_TOP)

        share.type = "image/png"
        assertThat(app.packageManager.resolveActivity(share, flags)).isNull()
    }
}
