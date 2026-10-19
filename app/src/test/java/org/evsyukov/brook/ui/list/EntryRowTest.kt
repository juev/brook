package org.evsyukov.brook.ui.list

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.evsyukov.brook.data.remote.EnclosureDto
import org.evsyukov.brook.sync.encodeEnclosures
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class EntryRowTest {
    private val page = "https://example.org/posts/1.html"

    @Test
    fun theThumbnailIsTheFirstPictureResolvedAgainstTheEntry() {
        assertThat(thumbnailUrl("<img src=\"/a.png\"><img src=\"https://cdn.example.org/b.png\">", page, ""))
            .isEqualTo("https://example.org/a.png")
    }

    @Test
    fun countersAndInlineDataAreSkipped() {
        val tag = "<img src=\"https://stats.example.org/p.gif\" width=\"1\" height=\"1\"><img src=\"data:image/png;base64,AA\"><img src=\"c.jpg\">"
        assertThat(thumbnailUrl(tag, page, "")).isEqualTo("https://example.org/posts/c.jpg")
    }

    @Test
    fun withoutAPictureInTheTextAPictureEnclosureServes() {
        val enclosures = encodeEnclosures(
            listOf(EnclosureDto("https://cdn.example.org/a.mp3", "audio/mpeg"), EnclosureDto("https://cdn.example.org/d.jpg", "image/jpeg")),
        )
        assertThat(thumbnailUrl(null, page, enclosures)).isEqualTo("https://cdn.example.org/d.jpg")
        assertThat(thumbnailUrl(null, page, "")).isNull()
    }

    @Test
    fun theLastTwoDaysHaveNamesAndOlderOnesADate() {
        val today = LocalDate.of(2026, 10, 19)
        fun label(day: LocalDate) = dayLabel(day, today, Locale.US, "Today", "Yesterday")

        assertThat(label(today)).isEqualTo("Today")
        assertThat(label(LocalDate.of(2026, 10, 18))).isEqualTo("Yesterday")
        assertThat(label(LocalDate.of(2026, 10, 16))).isEqualTo("Friday, October 16")
        assertThat(label(LocalDate.of(2025, 12, 31))).isEqualTo("Wednesday, December 31, 2025")
    }

    @Test
    fun theDayOfAnEntryIsTakenInTheZoneOfTheDevice() {
        // 2026-10-18 23:30 UTC is already the 19th in Moscow.
        val seconds = 1_792_366_200L
        assertThat(dayOf(seconds, ZoneId.of("UTC"))).isEqualTo(LocalDate.of(2026, 10, 18))
        assertThat(dayOf(seconds, ZoneId.of("Europe/Moscow"))).isEqualTo(LocalDate.of(2026, 10, 19))
    }
}
