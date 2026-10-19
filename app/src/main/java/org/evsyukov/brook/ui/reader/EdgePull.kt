package org.evsyukov.brook.ui.reader

/**
 * A drag past an end of the page: up while the page is at its bottom, down while it is at its top.
 * Released after [threshold] pixels, it asks for the entry beyond that end.
 */
class EdgePull(private val threshold: Float) {
    enum class Edge { TOP, BOTTOM }

    /** The end being pulled, null while the drag only scrolls the page. */
    var edge: Edge? = null
        private set

    private var distance = 0f

    /** How far the pull is, 1 and more once releasing it opens the next entry. */
    val progress: Float get() = if (edge == null) 0f else distance / threshold

    fun start() {
        edge = null
        distance = 0f
    }

    /** The finger moved by [dy] pixels, down being positive; [atTop] and [atBottom] tell where the page is. */
    fun move(dy: Float, atTop: Boolean, atBottom: Boolean) {
        if (edge == null) {
            edge = when {
                atBottom && dy < 0 -> Edge.BOTTOM
                atTop && dy > 0 -> Edge.TOP
                else -> return
            }
        }
        distance += if (edge == Edge.BOTTOM) -dy else dy
        // Back past where it started: the page scrolls again.
        if (distance <= 0f) start()
    }

    /** The finger is up: the end to open, or null when the pull was too short. */
    fun end(): Edge? {
        val opened = edge?.takeIf { distance >= threshold }
        start()
        return opened
    }
}
