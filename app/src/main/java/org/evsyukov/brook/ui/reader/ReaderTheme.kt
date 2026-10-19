package org.evsyukov.brook.ui.reader

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.evsyukov.brook.data.settings.ReaderTheme

/** The colors of a reading theme of Brook's own. */
data class ReaderPalette(
    val background: Color,
    val text: Color,
    val secondary: Color,
    val link: Color,
    /** Code and other blocks on the page, and pressed buttons of the bars. */
    val surfaceHigh: Color,
    /** Dividers. */
    val outline: Color,
)

/** The palette of [theme]; null for the system theme, which keeps the colors of the app. */
fun readerPalette(theme: ReaderTheme): ReaderPalette? = when (theme) {
    ReaderTheme.SYSTEM -> null
    ReaderTheme.SEPIA -> ReaderPalette(
        background = Color(0xFFF6EEDC),
        text = Color(0xFF3A2E22),
        secondary = Color(0xFF6B5A45),
        link = Color(0xFF8B4A16),
        surfaceHigh = Color(0xFFEBDFC5),
        outline = Color(0xFFD8C9A8),
    )
    ReaderTheme.BLACK -> ReaderPalette(
        background = Color(0xFF000000),
        text = Color(0xFFDADADA),
        secondary = Color(0xFFA0A0A0),
        link = Color(0xFF8AB4F8),
        surfaceHigh = Color(0xFF1C1C1C),
        outline = Color(0xFF2E2E2E),
    )
}

/** [base] with the colors of [theme] for the page and the bars around it. */
fun readerColorScheme(theme: ReaderTheme, base: ColorScheme): ColorScheme {
    val p = readerPalette(theme) ?: return base
    return base.copy(
        primary = p.link,
        background = p.background,
        onBackground = p.text,
        surface = p.background,
        onSurface = p.text,
        onSurfaceVariant = p.secondary,
        surfaceContainer = p.background,
        surfaceContainerLow = p.background,
        surfaceContainerHigh = p.surfaceHigh,
        surfaceContainerHighest = p.surfaceHigh,
        outline = p.secondary,
        outlineVariant = p.outline,
    )
}
