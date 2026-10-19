package org.evsyukov.brook

import android.content.Context
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherIconTest {
    @Test
    fun theIconIsAdaptiveWithEveryLayerForMasksAndThemedIcons() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val icon = context.getDrawable(context.applicationInfo.icon)

        assertThat(icon).isInstanceOf(AdaptiveIconDrawable::class.java)
        icon as AdaptiveIconDrawable
        assertThat(icon.background).isNotNull()
        assertThat(icon.foreground).isNotNull()
        assertThat(icon.monochrome).isNotNull()
    }
}
