package dev.syncr.prototype.link

/**
 * One note in a manifest: its ID, its `local_sequence` and its title. Link v1 adds [digest] (SHA-256 hex over the note's
 * sorted operation IDs) and [ops] (how many); a note announced without them is wanted by sequence only.
 */
data class NoteMeta(val id: String, val seq: Long, val title: String, val digest: String? = null, val ops: Long? = null)

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

    /** [trash] (v1, phone to Mac): IDs in this phone's Recently deleted. The Mac never sends it. */
    data class Notes(val notes: List<NoteMeta>, val trash: List<String>? = null) : Control() {
        override fun json(): Map<String, Any?> {
            val m = linkedMapOf<String, Any?>("t" to "notes", "notes" to notes.map { n ->
                val e = linkedMapOf<String, Any?>("id" to n.id, "seq" to n.seq, "title" to n.title)
                n.digest?.let { e["digest"] = it }
                n.ops?.let { e["ops"] = it }
                e
            })
            trash?.let { m["trash"] = it }
            return m
        }
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

    /** [digest] and [ops] (v1): the sender's operation set after installing. */
    data class Have(val id: String, val seq: Long, val digest: String? = null, val ops: Long? = null) : Control() {
        override fun json(): Map<String, Any?> {
            val m = linkedMapOf<String, Any?>("t" to "have", "id" to id, "seq" to seq)
            digest?.let { m["digest"] = it }
            ops?.let { m["ops"] = it }
            return m
        }
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

    /** The Mac's active note; [note] is null when there is none. [frame], [view] and [pageHeight] are the pad additions. */
    data class Target(
        val note: String?, val sheet: String?, val writable: Boolean, val rev: Long,
        val frame: CanvasRect? = null, val view: CanvasRect? = null, val pageHeight: Double? = null,
    ) : Control() {
        override fun json(): Map<String, Any?> {
            if (note == null) return mapOf("t" to "target", "note" to null)
            val m = linkedMapOf<String, Any?>("t" to "target", "note" to note, "sheet" to sheet, "writable" to writable, "rev" to rev)
            frame?.let { m["frame"] = rect(it) }
            view?.let { m["view"] = rect(it) }
            pageHeight?.let { m["page"] = mapOf("w" to 4800L, "h" to it) }
            return m
        }
    }

    /** Phone: how the pad maps ([local]: onto the pen frame), whether it wants pictures, and its width over height. */
    data class PadMode(val local: Boolean, val pics: Boolean, val aspect: Double) : Control() {
        override fun json() = mapOf("t" to "padmode", "local" to local, "pics" to pics, "aspect" to aspect)
    }

    /** Phone: asks for this pen frame. The Mac clamps it and answers with `target`. */
    data class Frame(val rect: CanvasRect) : Control() {
        override fun json() = mapOf("t" to "frame") + rect(rect)
    }

    /** Mac: preview frames may also go as datagrams to [port], each starting with [key] (16 hex characters). */
    data class Udp(val port: Int, val key: String) : Control() {
        override fun json() = mapOf("t" to "udp", "port" to port.toLong(), "key" to key)
    }

    data class PointerMode(val on: Boolean, val v: Int = 1) : Control() {
        override fun json() = mapOf("t" to "pointermode", "v" to v.toLong(), "on" to on)
    }
    data class PointerState(val v: Int, val ready: Boolean, val message: String) : Control() {
        override fun json() = mapOf("t" to "pointerstate", "v" to v.toLong(), "ready" to ready, "message" to message)
    }
    data class Pointer(val seq: Long, val input: PointerInput) : Control() {
        override fun json(): Map<String, Any?> {
            val m = linkedMapOf<String, Any?>("t" to "pointer", "v" to 1L, "seq" to seq, "dx" to input.dx, "dy" to input.dy,
                "sx" to input.sx, "sy" to input.sy, "buttons" to input.buttons.toLong(), "click" to input.click.toLong())
            if (input.scrollEnd) m["se"] = true // optional, so a Mac that predates it just sees a zero packet
            return m
        }
    }

    companion object {
        /** Sent in hello. A Mac that answers `welcome` with version 0 is an older app: the phone then syncs one way only. */
        const val VERSION = 1
        const val OLDEST = 0
        private const val MAX_STRING = 1024
        private const val MAX_LIST = 5000

        private fun rect(r: CanvasRect): Map<String, Any?> = linkedMapOf("x" to r.x, "y" to r.y, "w" to r.w, "h" to r.h)

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
                    NoteMeta(e.id("id"), e.int("seq"), e.str("title", MAX_STRING), e.optDigest("digest"), e.optInt("ops"))
                }, m.optList("trash")?.map { it as? String ?: throw LinkProtocolException("trash id is not a string") })
                "want" -> Want(m.list("ids").map { it as? String ?: throw LinkProtocolException("want id is not a string") })
                "file" -> File(m.u32("xfer"), m.id("id"), m.int("seq"), m.int("size"), m.str("sha256", 64), m.int("chunks"))
                "file_end" -> FileEnd(m.u32("xfer"))
                "have" -> Have(m.id("id"), m.int("seq"), m.optDigest("digest"), m.optInt("ops"))
                "file_error" -> FileError(m.u32("xfer"), m.str("id", 64), m.str("reason", MAX_STRING))
                "viewing" -> Viewing(m.optStr("id", 64))
                "pad" -> Pad(m.bool("on"))
                "pointermode" -> PointerMode(m.bool("on"), m.pointerVersion())
                "pointerstate" -> PointerState(m.pointerVersion(), m.bool("ready"), m.str("message"))
                "pointer" -> {
                    if (m.int("v") != 1L) throw LinkProtocolException("pointer version")
                    val buttons = m.int("buttons"); val click = m.int("click")
                    if (buttons !in 0L..1L || click !in 0L..3L) throw LinkProtocolException("pointer buttons")
                    val input = PointerInput(m.num("dx"), m.num("dy"), m.num("sx"), m.num("sy"), buttons.toInt(), click.toInt(),
                        scrollEnd = m.optBool("se"))
                    val seq = m.int("seq")
                    if (!input.valid() || seq < 1) throw LinkProtocolException("pointer bounds")
                    Pointer(seq, input)
                }
                "target" -> {
                    val note = m.optStr("note", 64)
                    if (note == null) Target(null, null, false, 0)
                    else Target(note, m.optStr("sheet", 64), m.bool("writable"), m.u32("rev"),
                        m.optRect("frame"), m.optRect("view"), m.optMap("page")?.optNum("h"))
                }
                "padmode" -> PadMode(m.bool("local"), m.bool("pics"), m.num("aspect"))
                "frame" -> Frame(m.rect())
                "udp" -> {
                    val key = m.str("key", 16)
                    val port = m.int("port")
                    if (port !in 1..65535 || key.length != 16 || !key.all { it in '0'..'9' || it in 'a'..'f' }) throw LinkProtocolException("udp port or key")
                    Udp(port.toInt(), key)
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

        fun optInt(key: String): Long? = if (map[key] == null) null else int(key)

        /** A SHA-256 digest as 64 lowercase hex characters, or null when the field is absent. */
        fun optDigest(key: String): String? {
            if (map[key] == null) return null
            val v = str(key, 64)
            if (v.length != 64 || !v.all { it in '0'..'9' || it in 'a'..'f' }) throw LinkProtocolException("field $key is not a digest")
            return v
        }

        fun optList(key: String): List<*>? = if (map[key] == null) null else list(key)

        /** A JSON number, whole or not. */
        fun num(key: String): Double = optNum(key) ?: throw LinkProtocolException("field $key is not a number")

        fun optNum(key: String): Double? = when (val v = map[key]) {
            is Long -> v.toDouble()
            is Double -> v.takeIf { it.isFinite() }
            else -> null
        }

        fun optMap(key: String): Fields? = (map[key] as? Map<*, *>)?.let(::Fields)

        /** This object's own x, y, w and h: a positive size and values a canvas could hold. */
        fun rect(): CanvasRect {
            val r = CanvasRect(num("x"), num("y"), num("w"), num("h"))
            if (r.w <= 0 || r.h <= 0 || listOf(r.x, r.y, r.w, r.h).any { kotlin.math.abs(it) > 10_000_000 }) throw LinkProtocolException("rectangle out of range")
            return r
        }

        /** A rectangle under [key], or null when it is absent or unusable (an optional addition never closes the link). */
        fun optRect(key: String): CanvasRect? = try { optMap(key)?.rect() } catch (_: LinkProtocolException) { null }

        fun int(key: String): Long = map[key] as? Long ?: throw LinkProtocolException("field $key is not an integer")

        fun u32(key: String): Long = int(key).also { if (it !in 0..0xFFFF_FFFFL) throw LinkProtocolException("field $key out of range") }

        fun pointerVersion(): Int = int("v").also { if (it !in 0L..100L) throw LinkProtocolException("pointer version") }.toInt()

        fun optBool(key: String): Boolean = map[key] as? Boolean ?: false

        fun bool(key: String): Boolean = map[key] as? Boolean ?: throw LinkProtocolException("field $key is not a boolean")

        fun list(key: String): List<*> {
            val v = map[key] as? List<*> ?: throw LinkProtocolException("field $key is not a list")
            if (v.size > MAX_LIST) throw LinkProtocolException("field $key has too many entries")
            return v
        }
    }
}
