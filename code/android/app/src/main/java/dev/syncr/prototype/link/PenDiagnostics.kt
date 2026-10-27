package dev.syncr.prototype.link

import java.util.concurrent.atomic.AtomicInteger

/** Bounded SPSC reasons queue: the pen only publishes constant strings; formatting and logcat stay on a worker. */
class PenDiagnostics(private val capacity: Int = 64) {
    private val reasons = arrayOfNulls<String>(capacity)
    private val head = AtomicInteger()
    private val tail = AtomicInteger()
    private val dropped = AtomicInteger()

    fun offer(reason: String) {
        val t = tail.get()
        if (t - head.get() >= capacity) { dropped.incrementAndGet(); return }
        reasons[t % capacity] = reason
        tail.set(t + 1)
    }

    fun drain(log: (String) -> Unit) {
        var h = head.get()
        val t = tail.get()
        while (h != t) {
            val reason = reasons[h % capacity]
            reasons[h % capacity] = null
            h++
            head.set(h)
            log("stream stroke not sent reason=$reason")
        }
        val n = dropped.getAndSet(0)
        if (n != 0) log("stream diagnostics dropped=$n (queue full)")
    }
}
