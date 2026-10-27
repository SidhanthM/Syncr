package dev.syncr.prototype.link

/** Anything the wire or a message refuses: bad length, unknown kind, malformed JSON or a wrong field type. The connection closes. */
class LinkProtocolException(message: String) : Exception(message)

/**
 * A small JSON reader and writer, plain JVM, so the link and its host tests need no Android `org.json`.
 * Values are `Map<String, Any?>`, `List<Any?>`, `String`, `Long`, `Double`, `Boolean` or null. Input is untrusted: depth and
 * size are bounded and every error is a [LinkProtocolException].
 */
internal object Json {
    private const val MAX_DEPTH = 8
    private const val MAX_NODES = 20_000

    fun parse(bytes: ByteArray, offset: Int = 0): Any? {
        val text = try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw LinkProtocolException("control message is not UTF-8")
        }
        return Reader(text).readDocument()
    }

    fun write(value: Any?): ByteArray = StringBuilder().also { append(it, value) }.toString().toByteArray(Charsets.UTF_8)

    private fun append(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> string(out, value)
            is Boolean -> out.append(value)
            is Int, is Long -> out.append(value.toString())
            is UInt, is ULong -> out.append(value.toString())
            is Double -> {
                require(value.isFinite()) { "JSON cannot carry a non-finite number" }
                out.append(value.toString())
            }
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    string(out, k as String)
                    out.append(':')
                    append(out, v)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { i, v -> if (i > 0) out.append(','); append(out, v) }
                out.append(']')
            }
            else -> throw IllegalArgumentException("unsupported JSON value ${value.javaClass}")
        }
    }

    private fun string(out: StringBuilder, s: String) {
        out.append('"')
        for (ch in s) {
            when {
                ch == '"' -> out.append("\\\"")
                ch == '\\' -> out.append("\\\\")
                ch == '\n' -> out.append("\\n")
                ch == '\r' -> out.append("\\r")
                ch == '\t' -> out.append("\\t")
                ch < ' ' -> out.append("\\u%04x".format(ch.code))
                else -> out.append(ch)
            }
        }
        out.append('"')
    }

    private class Reader(private val s: String) {
        private var i = 0
        private var nodes = 0

        fun readDocument(): Any? {
            val v = value(0)
            ws()
            if (i != s.length) fail("trailing data")
            return v
        }

        private fun fail(why: String): Nothing = throw LinkProtocolException("malformed JSON: $why")

        private fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        private fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("too deep")
            if (++nodes > MAX_NODES) fail("too many values")
            ws()
            if (i >= s.length) fail("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else fail("unexpected character")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("bad literal")
            i += word.length
            return v
        }

        private fun obj(depth: Int): Map<String, Any?> {
            i++
            val map = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return map }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail("expected key")
                val key = str()
                ws()
                if (i >= s.length || s[i] != ':') fail("expected colon")
                i++
                map[key] = value(depth + 1)
                ws()
                if (i >= s.length) fail("unterminated object")
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return map
                    else -> fail("expected comma or end of object")
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            i++
            val list = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return list }
            while (true) {
                list += value(depth + 1)
                ws()
                if (i >= s.length) fail("unterminated array")
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return list
                    else -> fail("expected comma or end of array")
                }
            }
        }

        private fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c < ' ' -> fail("control character in string")
                    c == '\\' -> {
                        if (i >= s.length) fail("bad escape")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("bad unicode escape")
                                val code = s.substring(i, i + 4).toIntOrNull(16) ?: fail("bad unicode escape")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> fail("bad escape")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            val digits = i
            while (i < s.length && s[i] in '0'..'9') i++
            if (i == digits) fail("bad number")
            if (s[digits] == '0' && i - digits > 1) fail("leading zero")
            var integral = true
            if (i < s.length && s[i] == '.') {
                integral = false
                i++
                val f = i
                while (i < s.length && s[i] in '0'..'9') i++
                if (i == f) fail("bad fraction")
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                integral = false
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                val e = i
                while (i < s.length && s[i] in '0'..'9') i++
                if (i == e) fail("bad exponent")
            }
            val text = s.substring(start, i)
            if (integral) text.toLongOrNull()?.let { return it }
            return text.toDoubleOrNull()?.takeIf { it.isFinite() } ?: fail("bad number")
        }
    }
}
