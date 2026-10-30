package dev.syncr.prototype.link.android

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dev.syncr.prototype.link.Endpoint

/** A Mac seen on the network. [macId] and [name] come from the TXT records `id` and `name`. */
data class FoundMac(val serviceName: String, val name: String, val host: String, val port: Int, val macId: String?) {
    val endpoint get() = Endpoint(host, port, name, macId)
}

/**
 * Discovery of `_syncr._tcp` with [NsdManager]. Services are resolved one at a time (the platform refuses overlapping
 * resolves). Nothing here needs a runtime permission.
 */
class NsdFinder(private val nsd: NsdManager, private val onChange: (List<FoundMac>) -> Unit) {
    private val found = LinkedHashMap<String, FoundMac>()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null

    @Synchronized
    fun start() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { synchronized(this@NsdFinder) { listener = null } }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(this@NsdFinder) { queue += info }
                resolveNext()
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                val changed = synchronized(this@NsdFinder) { found.remove(info.serviceName) != null }
                if (changed) publish()
            }
        }
        listener = l
        try {
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (_: RuntimeException) {
            listener = null
        }
    }

    @Synchronized
    fun stop() {
        listener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: RuntimeException) { } }
        listener = null
        queue.clear()
        resolving = false
        found.clear()
        onChange(emptyList())
    }

    private fun resolveNext() {
        val next = synchronized(this) {
            if (resolving) return
            queue.removeFirstOrNull()?.also { resolving = true } ?: return
        }
        try {
            @Suppress("DEPRECATION")
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = done()
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = @Suppress("DEPRECATION") info.host?.hostAddress
                    if (host != null && info.port in 1..65535) {
                        val txt = info.attributes
                        fun text(key: String) = txt[key]?.toString(Charsets.UTF_8)?.take(128)
                        val mac = FoundMac(info.serviceName, text("name") ?: info.serviceName, host, info.port, text("id"))
                        synchronized(this@NsdFinder) { found[info.serviceName] = mac }
                        publish()
                    }
                    done()
                }
            })
        } catch (_: RuntimeException) {
            done()
        }
    }

    private fun done() {
        synchronized(this) { resolving = false }
        resolveNext()
    }

    private fun publish() = onChange(synchronized(this) { found.values.toList() })

    companion object {
        const val TYPE = "_syncr._tcp"
    }
}
