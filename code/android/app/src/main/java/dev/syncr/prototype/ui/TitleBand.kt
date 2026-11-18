package dev.syncr.prototype.ui

/**
 * When the sticky title band is shown (look-and-ink v1 §4.1). Pure: callers pass the clock in milliseconds.
 *
 * It needs a scrolled-off masthead, and it steps aside for the pen, typing and scrolling down. It comes back on a
 * scroll up, a tap on the status area, or [RETURN_MS] after writing stops (never while typing, never when the last
 * thing written sits under the band: then only a scroll up or a tap brings it back).
 */
data class TitleBand(
    val titleOnScreen: Boolean = true,
    val hidden: Boolean = false,
    val penDown: Boolean = false,
    val typing: Boolean = false,
    /** When writing or typing stopped with the band still free to return; null when only a scroll up or a tap can. */
    val quietSince: Long? = null,
) {
    /** Whether the band is on screen. */
    val visible get() = !titleOnScreen && !hidden

    fun masthead(onScreen: Boolean) = copy(titleOnScreen = onScreen)
    fun penDown() = copy(hidden = true, penDown = true, quietSince = null)
    /** [underBand]: the stroke ended beneath the band, so it stays out of the way of what was just written. */
    fun penUp(now: Long, underBand: Boolean) = copy(penDown = false, quietSince = if (underBand || typing) null else now)
    fun typingOpened() = copy(hidden = true, typing = true, quietSince = null)
    fun typingClosed(now: Long, underBand: Boolean) = copy(typing = false, quietSince = if (underBand || penDown) null else now)
    fun scrolledDown() = copy(hidden = true, quietSince = null)
    fun scrolledUp() = if (typing || penDown) this else copy(hidden = false, quietSince = null)
    fun statusTapped() = scrolledUp()
    /** Time passing: the band returns once writing has been quiet for [RETURN_MS]. */
    fun tick(now: Long): TitleBand {
        val since = quietSince ?: return this
        return if (hidden && !penDown && !typing && now - since >= RETURN_MS) copy(hidden = false, quietSince = null) else this
    }
    /** How long until [tick] would change anything, or null when nothing is pending. */
    fun wakeInMs(now: Long): Long? = quietSince?.takeIf { hidden && !penDown && !typing }?.let { maxOf(0L, RETURN_MS - (now - it)) }

    companion object {
        const val RETURN_MS = 1500L
        /** A scroll smaller than this many pixels is a jitter, not a direction. */
        const val SCROLL_SLOP_PX = 3f
    }
}
