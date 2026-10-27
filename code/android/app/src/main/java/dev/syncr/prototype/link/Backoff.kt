package dev.syncr.prototype.link

/** Reconnect delays of wifi-link-v0 §2: 1, 2, 4, 8 seconds, then every 10. [reset] after a session was welcomed. */
class Backoff {
    private var attempt = 0

    fun nextMs(): Long = when (attempt++) {
        0 -> 1_000L
        1 -> 2_000L
        2 -> 4_000L
        3 -> 8_000L
        else -> 10_000L.also { attempt = 4 }
    }

    fun reset() { attempt = 0 }
}
