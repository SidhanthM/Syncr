package dev.syncr.prototype.link

import java.net.URLDecoder

/**
 * The link a Mac's "Pair a phone" window shows as a QR code: `syncr://pair?h=<address>&p=<port>&m=<mac_id>&n=<name>`, and `e=<unix
 * seconds>` after which the Mac no longer offers it (remaining-v1 P.1). It carries where the Mac listens and who it is, never a
 * secret: the four digits the Mac shows once the phone reaches it are what pairs. Plain functions, checked on the host.
 */
data class PairLink(val host: String, val port: Int, val macId: String, val name: String, val expiresAt: Long? = null) {
    val endpoint get() = Endpoint(host, port, name, macId)

    fun expired(nowSeconds: Long) = expiresAt != null && nowSeconds > expiresAt

    companion object {
        const val SCHEME = "syncr"
        const val HOST = "pair"
        private val HEX32 = Regex("^[0-9a-fA-F]{32}$")
        private val NAME_HOST = Regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$")

        /** Null unless [text] is a well-formed pairing link to an address on this network. */
        fun parse(text: String?): PairLink? {
            val raw = text?.trim()?.takeIf { it.length <= 512 } ?: return null
            val head = "$SCHEME://$HOST?"
            if (!raw.startsWith(head, ignoreCase = true)) return null
            val query = HashMap<String, String>()
            for (part in raw.substring(head.length).substringBefore('#').split('&')) {
                if (part.isEmpty()) continue
                val eq = part.indexOf('=')
                if (eq <= 0) return null
                val key = part.substring(0, eq)
                if (key in query) return null
                query[key] = runCatching { URLDecoder.decode(part.substring(eq + 1).replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
            }
            val host = query["h"]?.takeIf { it.length <= 253 && local(it) } ?: return null
            val port = query["p"]?.takeIf { it.length <= 5 && it.all(Char::isDigit) }?.toInt()?.takeIf { it in 1..65535 } ?: return null
            val macId = query["m"]?.takeIf { HEX32.matches(it) }?.lowercase() ?: return null
            val name = cleanName(query["n"]) ?: "your Mac"
            val expires = query["e"]?.let { e -> e.takeIf { it.length <= 12 && it.all(Char::isDigit) }?.toLong() ?: return null }
            return PairLink(host, port, macId, name, expires)
        }

        /** The Mac's name as shown on the phone: printable, short. */
        fun cleanName(raw: String?): String? {
            val text = (raw ?: "").filter { !it.isISOControl() }.trim().take(64)
            return text.ifEmpty { null }
        }

        /**
         * Only an address on the local network: a private or link-local IPv4 literal, or a `.local` name. A link that points the
         * phone anywhere else is refused, so a code stuck on a lamp post cannot send a phone's link to a stranger's server.
         */
        fun local(host: String): Boolean {
            val parts = host.split('.')
            if (parts.size == 4 && parts.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) }) {
                val n = parts.map { it.toInt() }
                if (n.any { it > 255 }) return false
                return n[0] == 10 || (n[0] == 172 && n[1] in 16..31) || (n[0] == 192 && n[1] == 168) || (n[0] == 169 && n[1] == 254)
            }
            return NAME_HOST.matches(host) && host.endsWith(".local", ignoreCase = true)
        }
    }
}
