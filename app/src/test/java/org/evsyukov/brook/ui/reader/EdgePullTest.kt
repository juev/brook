package org.evsyukov.brook.ui.reader

import com.google.common.truth.Truth.assertThat
import org.evsyukov.brook.ui.reader.EdgePull.Edge
import org.junit.Test

class EdgePullTest {
    private val pull = EdgePull(threshold = 100f)

    private fun drag(vararg moves: Float, atTop: Boolean = false, atBottom: Boolean = false): Edge? {
        pull.start()
        for (dy in moves) pull.move(dy, atTop, atBottom)
        return pull.end()
    }

    @Test
    fun `a pull up at the bottom opens the next entry once it is long enough`() {
        assertThat(drag(-60f, -50f, atBottom = true)).isEqualTo(Edge.BOTTOM)
        assertThat(drag(-60f, -30f, atBottom = true)).isNull()
    }

    @Test
    fun `a pull down at the top opens the previous entry`() {
        assertThat(drag(70f, 40f, atTop = true)).isEqualTo(Edge.TOP)
    }

    @Test
    fun `a drag in the middle of the page only scrolls it`() {
        assertThat(drag(-150f)).isNull()
        assertThat(drag(150f)).isNull()
        // At the bottom, a drag down scrolls back up.
        assertThat(drag(150f, atBottom = true)).isNull()
    }

    @Test
    fun `coming back past the start cancels the pull`() {
        pull.start()
        pull.move(-80f, atTop = false, atBottom = true)
        assertThat(pull.progress).isEqualTo(0.8f)
        pull.move(90f, atTop = false, atBottom = true)
        assertThat(pull.edge).isNull()
        assertThat(pull.end()).isNull()
    }

    @Test
    fun `a short page can be pulled either way`() {
        assertThat(drag(-120f, atTop = true, atBottom = true)).isEqualTo(Edge.BOTTOM)
        assertThat(drag(120f, atTop = true, atBottom = true)).isEqualTo(Edge.TOP)
    }
}
