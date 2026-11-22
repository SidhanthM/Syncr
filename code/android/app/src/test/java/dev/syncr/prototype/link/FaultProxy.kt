package dev.syncr.prototype.link

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue

/**
 * A TCP proxy on 127.0.0.1 between the phone's link client and the Mac that injects the faults of a Wi-Fi path (K.0). Each
 * direction has its own [Dir] and can be changed while connections are open:
 *
 * - PASS: bytes go through (after [Dir.delayMs], at no more than [Dir.bytesPerSec] when that is set: delay and throttle).
 * - STALL: bytes are held and released when the direction goes back to PASS. A path that blips: TCP survives it.
 *
 * [blackhole] is a fault of the connections open at that moment: their bytes vanish, nothing is closed and they never recover on
 * their own; a connection made afterwards works (the path is fine, the old flow is dead).
 *
 * [cut] closes every open connection at once (a FIN, or an RST with `rst`). New connections are answered by [accepting]:
 * OK, REFUSE (closed at once, as a Wi-Fi that is off refuses a connect) or HANG (accepted and left silent: no answer ever).
 */
class FaultProxy(private val targetHost: String, targetPort: Int, private val log: (String) -> Unit = {}) : AutoCloseable {
    enum class Mode { PASS, STALL }
    enum class Accepting { OK, REFUSE, HANG }

    class Dir {
        @Volatile var mode = Mode.PASS
        @Volatile var delayMs = 0L
        @Volatile var bytesPerSec = 0L
    }

    /** Phone to Mac. */
    val up = Dir()
    /** Mac to phone. */
    val down = Dir()
    @Volatile var accepting = Accepting.OK
    @Volatile var targetPort = targetPort

    private val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
    val port: Int get() = server.localPort
    private val pipes = CopyOnWriteArrayList<Pipe>()
    @Volatile private var open = true
    @Volatile var connections = 0
        private set

    init {
        Thread({
            while (open) {
                val client = try { server.accept() } catch (_: IOException) { break }
                connections++
                when (accepting) {
                    Accepting.REFUSE -> { reset(client) }
                    Accepting.HANG -> pipes += Pipe(client, null)
                    Accepting.OK -> {
                        val upstream = try { Socket().apply { tcpNoDelay = true; connect(InetSocketAddress(targetHost, this@FaultProxy.targetPort), 2_000) } }
                        catch (_: IOException) { reset(client); null }
                        if (upstream != null) pipes += Pipe(client, upstream)
                    }
                }
            }
        }, "fault-proxy-accept").apply { isDaemon = true; start() }
    }

    fun setBoth(mode: Mode) { up.mode = mode; down.mode = mode }

    /** Every connection open now loses its bytes in the given directions, silently and for good. */
    fun blackhole(up: Boolean = true, down: Boolean = true) {
        for (p in pipes) { if (up) p.holeUp = true; if (down) p.holeDown = true }
    }

    /** Everything back to a clean path (held bytes are released). */
    fun heal() {
        for (d in listOf(up, down)) { d.mode = Mode.PASS; d.delayMs = 0; d.bytesPerSec = 0 }
        accepting = Accepting.OK
    }

    /** Closes every open connection now: a FIN, or an RST when [rst]. */
    fun cut(rst: Boolean = false) {
        for (p in pipes) p.close(rst)
        pipes.clear()
    }

    /** Open connections (the ones neither side or the proxy has closed). */
    fun live(): Int = pipes.count { !it.closed }

    private fun reset(s: Socket) { try { s.setSoLinger(true, 0); s.close() } catch (_: IOException) { } }

    override fun close() { open = false; cut(); try { server.close() } catch (_: IOException) { } }

    private inner class Pipe(val client: Socket, val upstream: Socket?) {
        @Volatile var closed = false
        @Volatile var holeUp = false
        @Volatile var holeDown = false
        private val toMac = LinkedBlockingQueue<Pair<Long, ByteArray>>()
        private val toPhone = LinkedBlockingQueue<Pair<Long, ByteArray>>()

        init {
            if (upstream != null) {
                reader(client, up, toMac) { holeUp }
                writer(upstream, up, toMac) { holeUp }
                reader(upstream, down, toPhone) { holeDown }
                writer(client, down, toPhone) { holeDown }
            } else {
                // HANG: read and ignore.
                Thread({ try { val b = ByteArray(4096); while (client.getInputStream().read(b) >= 0) { } } catch (_: IOException) { } ; close(false) }, "fault-proxy-hang").apply { isDaemon = true; start() }
            }
        }

        private fun reader(from: Socket, dir: Dir, queue: LinkedBlockingQueue<Pair<Long, ByteArray>>, holed: () -> Boolean) = Thread({
            val buffer = ByteArray(16 * 1024)
            try {
                val input = from.getInputStream()
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (holed()) continue
                    queue.add(System.nanoTime() / 1_000_000 + dir.delayMs to buffer.copyOf(n))
                }
            } catch (_: IOException) { }
            // The peer closed (or the socket was cut): pass the FIN on once what it sent has been delivered, unless the path is held.
            Thread({
                while (!queue.isEmpty() && !closed) Thread.sleep(5)
                if (!holed()) close(false)
            }, "fault-proxy-fin").apply { isDaemon = true; start() }
        }, "fault-proxy-read").apply { isDaemon = true; start() }

        private fun writer(to: Socket, dir: Dir, queue: LinkedBlockingQueue<Pair<Long, ByteArray>>, holed: () -> Boolean) = Thread({
            try {
                val output = to.getOutputStream()
                while (!closed) {
                    val item = queue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    // A stalled direction holds the head of its queue until it is released.
                    while (!closed && dir.mode == Mode.STALL) Thread.sleep(5)
                    if (closed) break
                    if (holed()) continue
                    val wait = item.first - System.nanoTime() / 1_000_000
                    if (wait > 0) Thread.sleep(wait)
                    val rate = dir.bytesPerSec
                    if (rate > 0) {
                        var at = 0
                        while (at < item.second.size && !closed) {
                            val n = minOf(item.second.size - at, maxOf(1, (rate / 50).toInt()))
                            output.write(item.second, at, n); output.flush(); at += n
                            Thread.sleep(20)
                        }
                    } else { output.write(item.second); output.flush() }
                }
            } catch (_: IOException) { close(false) } catch (_: InterruptedException) { }
        }, "fault-proxy-write").apply { isDaemon = true; start() }

        fun close(rst: Boolean) {
            if (closed) return
            closed = true
            for (s in listOfNotNull(client, upstream)) { try { if (rst) s.setSoLinger(true, 0); s.close() } catch (_: IOException) { } }
            pipes.remove(this)
        }
    }
}
