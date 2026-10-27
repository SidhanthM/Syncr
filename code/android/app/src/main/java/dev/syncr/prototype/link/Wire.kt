package dev.syncr.prototype.link

import java.io.EOFException
import java.io.InputStream

/** The stream framing of wifi-link-v0 §2.1: a 4-byte big-endian length (1..65,536), then the message; its first byte is the kind. */
object Wire {
    const val MAX_MESSAGE = 65_536
    const val KIND_PREVIEW = 0x50
    const val KIND_STROKE = 0x43
    const val KIND_CONTROL = 0x4A
    const val KIND_FILE = 0x46

    /** Data bytes per file chunk. */
    const val FILE_CHUNK_DATA = 65_000
    const val FILE_HEADER = 9
    const val MAX_FILE_BYTES = 256L * 1024 * 1024

    fun isKnownKind(kind: Int) = kind == KIND_PREVIEW || kind == KIND_STROKE || kind == KIND_CONTROL || kind == KIND_FILE

    /** [message] with its length prefix, as one array so it goes out in one write. */
    fun frame(message: ByteArray): ByteArray {
        require(message.size in 1..MAX_MESSAGE) { "message of ${message.size} bytes" }
        val out = ByteArray(4 + message.size)
        out[0] = (message.size ushr 24).toByte()
        out[1] = (message.size ushr 16).toByte()
        out[2] = (message.size ushr 8).toByte()
        out[3] = message.size.toByte()
        System.arraycopy(message, 0, out, 4, message.size)
        return out
    }

    /**
     * Reads one message. A length outside 1..65,536 or an unknown kind is a [LinkProtocolException]; the length is
     * checked before anything is allocated. End of stream is an [EOFException].
     */
    fun read(input: InputStream): ByteArray {
        val header = ByteArray(4)
        readFully(input, header)
        val length = ((header[0].toInt() and 0xff) shl 24) or ((header[1].toInt() and 0xff) shl 16) or
            ((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff)
        if (length < 1 || length > MAX_MESSAGE) throw LinkProtocolException("message length $length")
        val message = ByteArray(length)
        readFully(input, message)
        if (!isKnownKind(message[0].toInt() and 0xff)) throw LinkProtocolException("unknown message kind 0x%02x".format(message[0]))
        return message
    }

    private fun readFully(input: InputStream, into: ByteArray) {
        var n = 0
        while (n < into.size) {
            val r = input.read(into, n, into.size - n)
            if (r < 0) throw EOFException()
            n += r
        }
    }

    fun kind(message: ByteArray): Int = message[0].toInt() and 0xff

    /** `0x46`, transfer ID (u32 BE), chunk index (u32 BE), then up to 65,000 data bytes. */
    fun fileChunk(transfer: Long, index: Long, data: ByteArray, length: Int = data.size): ByteArray {
        require(length in 0..FILE_CHUNK_DATA)
        val out = ByteArray(FILE_HEADER + length)
        out[0] = KIND_FILE.toByte()
        putU32(out, 1, transfer)
        putU32(out, 5, index)
        System.arraycopy(data, 0, out, FILE_HEADER, length)
        return out
    }

    class FileChunk(val transfer: Long, val index: Long, val data: ByteArray)

    fun parseFileChunk(message: ByteArray): FileChunk {
        if (kind(message) != KIND_FILE || message.size < FILE_HEADER || message.size > FILE_HEADER + FILE_CHUNK_DATA) {
            throw LinkProtocolException("bad file chunk")
        }
        return FileChunk(getU32(message, 1), getU32(message, 5), message.copyOfRange(FILE_HEADER, message.size))
    }

    private fun putU32(out: ByteArray, at: Int, v: Long) {
        out[at] = (v ushr 24).toByte(); out[at + 1] = (v ushr 16).toByte(); out[at + 2] = (v ushr 8).toByte(); out[at + 3] = v.toByte()
    }

    private fun getU32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xff) shl 24) or ((b[at + 1].toLong() and 0xff) shl 16) or ((b[at + 2].toLong() and 0xff) shl 8) or (b[at + 3].toLong() and 0xff)
}
