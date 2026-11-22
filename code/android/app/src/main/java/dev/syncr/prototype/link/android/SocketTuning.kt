package dev.syncr.prototype.link.android

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import dev.syncr.prototype.link.LinkTuning
import dev.syncr.prototype.link.Transport
import java.net.Socket

/**
 * The phone's TCP transport with the kernel's unsent queue capped ([LinkTuning.NOTSENT_LOWAT]): without it a note copy can
 * leave a send buffer's worth (Linux doubles the 128 KB asked for) queued ahead of the next pen frame, about 100 ms at
 * 20 Mbit/s. Best effort: a kernel that refuses keeps today's behaviour, and says so once in the log.
 */
internal val TunedTcp = Transport { endpoint, timeoutMs -> Transport.TCP.connect(endpoint, timeoutMs).also(::capUnsent) }

private fun capUnsent(socket: Socket) {
    try {
        ParcelFileDescriptor.fromSocket(socket).use { pfd ->
            Os.setsockoptInt(pfd.fileDescriptor, OsConstants.IPPROTO_TCP, TCP_NOTSENT_LOWAT, LinkTuning.NOTSENT_LOWAT)
            Log.i(LinkController.TAG, "socket notsent-lowat=${LinkTuning.NOTSENT_LOWAT} set")
        }
    } catch (e: Exception) {
        Log.i(LinkController.TAG, "socket notsent-lowat unavailable reason=${e.javaClass.simpleName}")
    }
}

/** Linux's TCP_NOTSENT_LOWAT (include/uapi/linux/tcp.h); android.system.OsConstants does not name it. */
private const val TCP_NOTSENT_LOWAT = 25
