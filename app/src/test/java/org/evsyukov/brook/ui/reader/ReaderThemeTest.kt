package org.evsyukov.brook.ui.reader

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.evsyukov.brook.data.settings.ReaderTheme
import org.junit.Test

class ReaderThemeTest {
    /** WCAG 2 contrast ratio. */
    private fun contrast(a: Color, b: Color): Float {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05f) / (dark + 0.05f)
    }

    @Test
    fun `text of every own theme meets WCAG AA on its background and blocks`() {
        for (theme in listOf(ReaderTheme.SEPIA, ReaderTheme.BLACK)) {
            val p = readerPalette(theme)!!
            for ((name, color) in listOf("text" to p.text, "secondary" to p.secondary, "link" to p.link)) {
                assertWithMessage("$theme $name").that(contrast(color, p.background)).isAtLeast(4.5f)
            }
            assertWithMessage("$theme text on blocks").that(contrast(p.text, p.surfaceHigh)).isAtLeast(4.5f)
        }
    }

    @Test
    fun `the system theme keeps the colors of the app`() {
        val light = lightColorScheme()
        assertThat(readerPalette(ReaderTheme.SYSTEM)).isNull()
        assertThat(readerColorScheme(ReaderTheme.SYSTEM, light)).isSameInstanceAs(light)
    }

    @Test
    fun `an own theme colors the page and the bars whatever the system is`() {
        for (base in listOf(lightColorScheme(), darkColorScheme())) {
            val sepia = readerColorScheme(ReaderTheme.SEPIA, base)
            assertThat(sepia.surface).isEqualTo(Color(0xFFF6EEDC))
            assertThat(sepia.onSurface).isEqualTo(Color(0xFF3A2E22))
            assertThat(readerColorScheme(ReaderTheme.BLACK, base).surface).isEqualTo(Color.Black)
        }
    }
}
