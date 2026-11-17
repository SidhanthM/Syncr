package dev.syncr.prototype.ui

/**
 * When typed text is stored (latency-v1 T.2). A throttle, not a debounce: the first keystroke after a quiet spell is
 * stored at once, then at most one store per [MIN_GAP_MS] while typing goes on, and no unsaved keystroke waits more than
 * [MAX_WAIT_MS]. A debounce that restarts per keystroke stores nothing at all until the typist pauses.
 */
internal object TypingThrottle {
    const val MIN_GAP_MS = 150L
    const val MAX_WAIT_MS = 250L

    /**
     * How long to wait before storing, at [now]. [dirtySince] is when the oldest unsaved keystroke arrived and
     * [lastStore] when the text was last stored (0 for never); all in the same millisecond clock.
     */
    fun delayMs(now: Long, dirtySince: Long, lastStore: Long): Long {
        val gap = lastStore + MIN_GAP_MS - now
        val cap = dirtySince + MAX_WAIT_MS - now
        return maxOf(0L, minOf(gap, cap))
    }
}
