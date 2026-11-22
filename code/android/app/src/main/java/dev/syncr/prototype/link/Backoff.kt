package dev.syncr.prototype.link

/**
 * Reconnect delays (link K.2): the first retry is at once, then 0.5, 1, 2, 4, 8 and every 10 seconds, each with up to 20% of
 * jitter either way so two phones do not knock together. Only failed attempts climb it ([afterWelcomedSession] starts it again).
 */
class Backoff(private val random: () -> Double = { Math.random() }) {
    private var attempt = 0

    fun nextMs(): Long {
        val base = when (attempt) {
            0 -> 0L
            1 -> 500L
            2 -> 1_000L
            3 -> 2_000L
            4 -> 4_000L
            5 -> 8_000L
            else -> 10_000L
        }
        if (attempt < 6) attempt++
        return if (base == 0L) 0L else (base * (0.8 + 0.4 * random())).toLong()
    }

    fun reset() { attempt = 0; flaps = 0 }

    private var flaps = 0

    /**
     * A session that was welcomed has ended. The ladder is for connection attempts that fail: a welcome proves the path works, so
     * it starts again. After a session that lasted ([stable]) the retry is at once; so it is after the first that dropped straight
     * away; a link that keeps dropping straight away is retried half a second apart and never backs off further (twice a second at
     * most, and back within half a second of the path returning).
     */
    fun afterWelcomedSession(stable: Boolean) {
        if (stable) { flaps = 0; attempt = 0 } else { flaps++; attempt = if (flaps <= 1) 0 else 1 }
    }

    companion object {
        /** A session shorter than this does not count as having worked. */
        const val STABLE_MS = 2_000L
    }
}
