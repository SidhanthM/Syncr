package dev.syncr.prototype.notes

import java.util.concurrent.Executor

/**
 * Background work where only the newest request matters (latency-v1 T.4): at most one [work] call runs at a time and at most one
 * request waits, each [submit] replacing the one waiting. A running job asks [newerWaiting] before its expensive stages and
 * gives up when it is true. Nothing here is Android's, so it is tested on the host with a plain executor.
 */
internal class LatestWork<T : Any>(private val executor: Executor, private val work: (T) -> Unit) {
    private val lock = Any()
    private var newest: T? = null
    private var running = false

    /** True while a request is waiting behind the one running. */
    val newerWaiting: Boolean get() = synchronized(lock) { newest != null }

    fun submit(item: T) {
        synchronized(lock) {
            newest = item
            if (running) return
            running = true
        }
        executor.execute(::drain)
    }

    private fun drain() {
        while (true) {
            val item = synchronized(lock) { newest.also { newest = null; if (it == null) running = false } } ?: return
            work(item)
        }
    }
}
