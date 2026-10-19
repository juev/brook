package org.evsyukov.brook.sync

import com.google.common.truth.Truth.assertThat
import org.evsyukov.brook.data.remote.Streams
import org.evsyukov.brook.data.remote.UnreadCountDto
import org.evsyukov.brook.data.remote.UnreadCountsDto
import org.junit.Test

class NewEntriesDetectorTest {
    private fun counts(newest: String, unread: Long, max: Long = unread) = UnreadCountsDto(
        max = max,
        counts = listOf(
            UnreadCountDto("feed/1", unread, newest),
            UnreadCountDto(Streams.READING_LIST, unread, newest),
        ),
    )

    private val detector = NewEntriesDetector()

    @Test
    fun `the first answer only records`() {
        assertThat(detector.changed(counts("100", 5))).isFalse()
        assertThat(detector.changed(counts("100", 5))).isFalse()
    }

    @Test
    fun `a newer entry is a change`() {
        detector.changed(counts("100", 5))
        assertThat(detector.changed(counts("200", 5))).isTrue()
        assertThat(detector.changed(counts("200", 5))).isFalse()
    }

    @Test
    fun `reading elsewhere is a change`() {
        detector.changed(counts("100", 5))
        assertThat(detector.changed(counts("100", 3))).isTrue()
    }

    @Test
    fun `a change of max is a change`() {
        detector.changed(counts("100", 5, max = 5))
        assertThat(detector.changed(counts("100", 5, max = 6))).isTrue()
    }

    @Test
    fun `after a reset the next answer only records`() {
        detector.changed(counts("100", 5))
        detector.reset()
        assertThat(detector.changed(counts("300", 9))).isFalse()
        assertThat(detector.changed(counts("300", 9))).isFalse()
    }
}
