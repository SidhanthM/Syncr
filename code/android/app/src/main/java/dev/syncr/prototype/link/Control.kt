package dev.syncr.prototype.link

/** One note in a manifest: its ID, its `local_sequence` and its title. */
data class NoteMeta(val id: String, val seq: Long, val title: String)

/**
 * Control messages of wifi-link-v0 §3. [parse] accepts every message either side sends (so a phone can be tested
 * against its own output and the shared vectors); an unknown `t` is `null` (ignored), a known one with a wrong
 * field is a [LinkProtocolException]. [encode] is `0x4A` plus the JSON.
 */
sealed class Control {
    abstract fun json(): Map<String, Any?>

    fun encode(): ByteArray {
        val body = Json.write(json())
        return ByteArray(1 + body.size).also { it[0] = Wire.KIND_CONTROL.toByte(); System.arraycopy(body, 0, it, 1, body.size) }
    }

    data class Hello(val device: String, val phoneId: String, val token: String?, val v: Int = VERSION) : Control() {
        override fun json() = mapOf("t" to "hello", "v" to v, "device" to device, "phone_id" to phoneId, "token" to token)
    }

    data class Welcome(val v: Int, val mac: String, val macId: String, val epoch: Long) : Control() {
        override fun json() = mapOf("t" to "welcome", "v" to v, "mac" to mac, "mac_id" to macId, "epoch" to epoch)
    }

    data object PairRequest : Control() {
        override fun json() = mapOf("t" to "pair")
    }

    data class Code(val code: String) : Control() {
        override fun json() = mapOf("t" to "code", "code" to code)
    }

    data class Paired(val token: String) : Control() {
        override fun json() = mapOf("t" to "paired", "token" to token)
    }

    data class Denied(val left: Int) : Control() {
        override fun json() = mapOf("t" to "denied", "left" to left)
    }

    data class Bye(val reason: String) : Control() {
        override fun json() = mapOf("t" to "bye", "reason" to reason)
    }

    data class Ping(val id: Long, val at: Long) : Control() {
        override fun json() = mapOf("t" to "ping", "id" to id, "at" to at)
    }

    data class Pong(val id: Long, val at: Long, val now: Long) : Control() {
        override fun json() = mapOf("t" to "pong", "id" to id, "at" to at, "now" to now)
    }

    data class Notes(val notes: List<NoteMeta>) : Control() {
        override fun json() = mapOf("t" to "notes", "notes" to notes.map { mapOf("id" to it.id, "seq" to it.seq, "title" to it.title) })
    }

    data class Want(val ids: List<String>) : Control() {
        override fun json() = mapOf("t" to "want", "ids" to ids)
    }

    data class File(val xfer: Long, val id: String, val seq: Long, val size: Long, val sha256: String, val chunks: Long) : Control() {
        override fun json() = mapOf("t" to "file", "xfer" to xfer, "id" to id, "seq" to seq, "size" to size, "sha256" to sha256, "chunks" to chunks)
    }

    data class FileEnd(val xfer: Long) : Control() {
        override fun json() = mapOf("t" to "file_end", "xfer" to xfer)
    }

    data class Have(val id: String, val seq: Long) : Control() {
        override fun json() = mapOf("t" to "have", "id" to id, "seq" to seq)
    }

    data class FileError(val xfer: Long, val id: String, val reason: String) : Control() {
        override fun json() = mapOf("t" to "file_error", "xfer" to xfer, "id" to id, "reason" to reason)
    }

    data class Viewing(val id: String?) : Control() {
        override fun json() = mapOf("t" to "viewing", "id" to id)
    }

    data class Pad(val on: Boolean) : Control() {
        override fun json() = mapOf("t" to "pad", "on" to on)
    }

    /** The Mac's active note; [note] is null when there is none. */
    data class Target(val note: String?, val sheet: String?, val writable: Boolean, val rev: Long) : Control() {
        override fun json() = if (note == null) mapOf("t" to "target", "note" to null)
        else mapOf("t" to "target", "note" to note, "sheet" to sheet, "writable" to writable, "rev" to rev)
    }

    companion object {
        const val VERSION = 0
        private const val MAX_STRING = 1024
        private const val MAX_LIST = 5000

        /** [message] is a whole `0x4A` message. */
        fun parse(message: ByteArray): Control? {
            if (Wire.kind(message) != Wire.KIND_CONTROL) throw LinkProtocolException("not a control message")
            val root = Json.parse(message, 1) as? Map<*, *> ?: throw LinkProtocolException("control message is not an object")
            val m = Fields(root)
            return when (m.str("t")) {
                "hello" -> Hello(m.str("device", 256), m.id("phone_id"), m.optStr("token", 128), m.int("v").toInt())
                "welcome" -> Welcome(m.int("v").toInt(), m.str("mac", 256), m.id("mac_id"), m.u32("epoch"))
                "pair" -> PairRequest
                "code" -> Code(m.str("code", 16))
                "paired" -> Paired(m.str("token", 128))
                "denied" -> Denied(m.int("left").toInt())
                "bye" -> Bye(m.str("reason", MAX_STRING))
                "ping" -> Ping(m.int("id"), m.int("at"))
                "pong" -> Pong(m.int("id"), m.int("at"), m.int("now"))
                "notes" -> Notes(m.list("notes").map { entry ->
                    val e = Fields(entry as? Map<*, *> ?: throw LinkProtocolException("note entry is not an object"))
                    NoteMeta(e.id("id"), e.int("seq"), e.str("title", MAX_STRING))
                })
                "want" -> Want(m.list("ids").map { it as? String ?: throw LinkProtocolException("want id is not a string") })
                "file" -> File(m.u32("xfer"), m.id("id"), m.int("seq"), m.int("size"), m.str("sha256", 64), m.int("chunks"))
                "file_end" -> FileEnd(m.u32("xfer"))
                "have" -> Have(m.id("id"), m.int("seq"))
                "file_error" -> FileError(m.u32("xfer"), m.str("id", 64), m.str("reason", MAX_STRING))
                "viewing" -> Viewing(m.optStr("id", 64))
                "pad" -> Pad(m.bool("on"))
                "target" -> {
                    val note = m.optStr("note", 64)
                    if (note == null) Target(null, null, false, 0)
                    else Target(note, m.optStr("sheet", 64), m.bool("writable"), m.u32("rev"))
                }
                else -> null
            }
        }
    }

    private class Fields(private val map: Map<*, *>) {
        fun str(key: String, max: Int = MAX_STRING): String {
            val v = map[key] as? String ?: throw LinkProtocolException("field $key is not a string")
            if (v.length > max) throw LinkProtocolException("field $key is too long")
            return v
        }

        fun optStr(key: String, max: Int): String? = if (map[key] == null) null else str(key, max)

        /** IDs are short opaque strings; the core validates them where it uses them. */
        fun id(key: String): String = str(key, 64)

        fun int(key: String): Long = map[key] as? Long ?: throw LinkProtocolException("field $key is not an integer")

        fun u32(key: String): Long = int(key).also { if (it !in 0..0xFFFF_FFFFL) throw LinkProtocolException("field $key out of range") }

        fun bool(key: String): Boolean = map[key] as? Boolean ?: throw LinkProtocolException("field $key is not a boolean")

        fun list(key: String): List<*> {
            val v = map[key] as? List<*> ?: throw LinkProtocolException("field $key is not a list")
            if (v.size > MAX_LIST) throw LinkProtocolException("field $key has too many entries")
            return v
        }
    }
}
