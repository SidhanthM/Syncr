package dev.syncr.prototype.link

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WireTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun header(n: Long) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())

    private fun refused(bytes: ByteArray) {
        try {
            Wire.read(ByteArrayInputStream(bytes))
            fail("expected a protocol error")
        } catch (_: LinkProtocolException) { }
    }

    @Test fun frameAndReadRoundTrip() {
        val message = Control.Ping(1, 2).encode()
        val framed = Wire.frame(message)
        assertEquals(message.size + 4, framed.size)
        assertArrayEquals(message, Wire.read(ByteArrayInputStream(framed)))
    }

    @Test fun lengthLimitsAreEnforcedBeforeAllocating() {
        refused(header(0) + byteArrayOf(0x4A))
        refused(header(65_537))
        refused(header(0xFFFF_FFFFL))
        refused(header(Int.MAX_VALUE.toLong()))
        // The largest message is fine.
        val biggest = ByteArray(65_536).also { it[0] = Wire.KIND_FILE.toByte() }
        assertEquals(65_536, Wire.read(ByteArrayInputStream(Wire.frame(biggest))).size)
        try { Wire.frame(ByteArray(65_537)); fail() } catch (_: IllegalArgumentException) { }
        try { Wire.frame(ByteArray(0)); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun unknownKindClosesTheConnection() {
        refused(header(2) + byteArrayOf(0x51, 0))
        refused(header(1) + byteArrayOf(0))
    }

    @Test fun truncatedInputIsEndOfStreamNotACrash() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(0, 0), header(10) + byteArrayOf(0x4A, 1))) {
            try { Wire.read(ByteArrayInputStream(bytes)); fail() } catch (_: EOFException) { }
        }
    }

    @Test fun knownKindsOtherThanControlPass() {
        for (kind in listOf(Wire.KIND_PREVIEW, Wire.KIND_STROKE, Wire.KIND_FILE)) {
            val m = byteArrayOf(kind.toByte(), 1, 2, 3)
            assertArrayEquals(m, Wire.read(ByteArrayInputStream(Wire.frame(m))))
        }
    }

    @Test fun fileChunkLayoutAndLimits() {
        val chunk = Wire.fileChunk(0xFFFF_FFFFL, 0x0102_0304L, byteArrayOf(9, 8, 7))
        assertArrayEquals(byteArrayOf(0x46, -1, -1, -1, -1, 1, 2, 3, 4, 9, 8, 7), chunk)
        val parsed = Wire.parseFileChunk(chunk)
        assertEquals(0xFFFF_FFFFL, parsed.transfer)
        assertEquals(0x0102_0304L, parsed.index)
        assertArrayEquals(byteArrayOf(9, 8, 7), parsed.data)
        val full = Wire.fileChunk(1, 0, ByteArray(Wire.FILE_CHUNK_DATA))
        assertEquals(Wire.FILE_HEADER + 65_000, full.size)
        assertTrue(full.size <= Wire.MAX_MESSAGE)
        try { Wire.fileChunk(1, 0, ByteArray(65_001)); fail() } catch (_: IllegalArgumentException) { }
        try { Wire.parseFileChunk(byteArrayOf(0x46, 0, 0)); fail() } catch (_: LinkProtocolException) { }
        try { Wire.parseFileChunk(ByteArray(Wire.FILE_HEADER + 65_001).also { it[0] = 0x46 }); fail() } catch (_: LinkProtocolException) { }
    }

    @Test fun hostileControlMessagesAreRefusedNotCrashed() {
        fun control(text: String) = byteArrayOf(0x4A) + text.toByteArray()
        val bad = listOf(
            "", "{", "[]", "null", "\"hello\"", "{\"t\":", "{\"t\":1}", "{\"t\":\"hello\"}", "{\"t\":\"ping\",\"id\":\"x\",\"at\":1}",
            "{\"t\":\"welcome\",\"v\":0,\"mac\":\"m\",\"mac_id\":\"x\",\"epoch\":4294967296}",
            "{\"t\":\"welcome\",\"v\":0,\"mac\":\"m\",\"mac_id\":\"x\",\"epoch\":-1}",
            "{\"t\":\"want\",\"ids\":[1,2]}", "{\"t\":\"want\",\"ids\":\"x\"}", "{\"t\":\"notes\",\"notes\":[1]}",
            "{\"t\":\"notes\",\"notes\":[{\"id\":\"a\",\"seq\":1.5,\"title\":\"t\"}]}",
            "{\"t\":\"target\",\"note\":\"n\",\"sheet\":\"s\",\"writable\":\"yes\",\"rev\":1}",
            "{\"t\":\"ping\",\"id\":1,\"at\":99999999999999999999999}", "{\"t\":\"pad\",\"on\":null}",
            "{\"t\":\"bye\",\"reason\":\"" + "x".repeat(2000) + "\"}", "{\"t\":\"ping\",\"id\":1,\"at\":2} trailing",
            "[".repeat(5000), "{\"a\":".repeat(100) + "1" + "}".repeat(100), "{\"t\":\"ping\",\"id\":01,\"at\":2}",
            "{\"t\":\"hello\",\"v\":0,\"device\":\"d\",\"phone_id\":\"p\",\"token\":5}",
        )
        for (text in bad) {
            try {
                Control.parse(control(text))
                fail("accepted: ${text.take(60)}")
            } catch (_: LinkProtocolException) { }
        }
        // Not UTF-8.
        try { Control.parse(byteArrayOf(0x4A, '{'.code.toByte(), 0xC3.toByte(), 0x28, '}'.code.toByte())); fail() } catch (_: LinkProtocolException) { }
        // Not a control message at all.
        try { Control.parse(byteArrayOf(0x50, 1)); fail() } catch (_: LinkProtocolException) { }
    }

    @Test fun unknownControlTypeIsIgnored() {
        assertNull(Control.parse(byteArrayOf(0x4A) + "{\"t\":\"future\",\"x\":[1,2,{\"y\":null}]}".toByteArray()))
        assertNull(Control.parse(byteArrayOf(0x4A) + "{\"t\":\"\"}".toByteArray()))
    }

    @Test fun randomBytesNeverCrashTheParser() {
        val random = java.util.Random(7)
        repeat(2000) {
            val bytes = ByteArray(random.nextInt(200) + 1).also { random.nextBytes(it) }
            bytes[0] = Wire.KIND_CONTROL.toByte()
            try { Control.parse(bytes) } catch (_: LinkProtocolException) { }
            try { Wire.read(ByteArrayInputStream(bytes)) } catch (_: LinkProtocolException) { } catch (_: EOFException) { }
        }
    }

    // ---- the shared vectors ----

    private val vectors: List<Map<*, *>> by lazy {
        val file = File("../../shared/link-v0/vectors.json").canonicalFile
        val root = Json.parse(file.readBytes()) as Map<*, *>
        (root["vectors"] as List<*>).map { it as Map<*, *> }
    }

    @Test fun everySharedVectorParses() {
        assertTrue(vectors.size >= 20)
        for (v in vectors) {
            val name = v["name"] as String
            val framed = hex(v["framed_hex"] as String)
            val message = Wire.read(ByteArrayInputStream(framed))
            // The frame is exactly one message.
            assertEquals(name, framed.size, message.size + 4)
            if (v.containsKey("json")) {
                val parsed = Control.parse(message) ?: throw AssertionError("$name did not parse")
                // What the vector says, key for key (the vector's own JSON through the same reader).
                val expected = v["json"] as Map<*, *>
                val actual = Json.parse(parsed.encode(), 1) as Map<*, *>
                assertEquals(name, expected, actual)
                // And parsing what we produce gives the same message back.
                assertEquals(name, parsed, Control.parse(parsed.encode()))
            } else {
                val chunk = Wire.parseFileChunk(message)
                assertEquals((v["transfer"] as Long), chunk.transfer)
                assertEquals((v["index"] as Long), chunk.index)
                assertArrayEquals(hex(v["data_hex"] as String), chunk.data)
                assertArrayEquals(message, Wire.fileChunk(chunk.transfer, chunk.index, chunk.data))
            }
        }
    }

    @Test fun linkV1VectorsParseAndRoundTrip() {
        val root = Json.parse(File("../../shared/link-v0/vectors-v1.json").canonicalFile.readBytes()) as Map<*, *>
        val list = (root["vectors"] as List<*>).map { it as Map<*, *> }
        assertTrue(list.size >= 5)
        for (v in list) {
            val name = v["name"] as String
            val message = Wire.read(ByteArrayInputStream(hex(v["framed_hex"] as String)))
            val parsed = Control.parse(message) ?: throw AssertionError("$name did not parse")
            assertEquals(name, v["json"], Json.parse(parsed.encode(), 1))
            assertEquals(name, parsed, Control.parse(parsed.encode()))
        }
        val notes = Control.parse(Wire.read(ByteArrayInputStream(hex(list.first { it["name"] == "notes_v1_phone" }["framed_hex"] as String)))) as Control.Notes
        assertEquals(12L, notes.notes.single().ops)
        assertEquals(listOf("01JCN0TE000000000000000002"), notes.trash)
    }

    @Test fun vectorsCoverEveryControlTypeWeParse() {
        val seen = vectors.mapNotNull { (it["json"] as? Map<*, *>)?.get("t") as? String }.toSet()
        assertTrue(seen.containsAll(listOf("hello", "welcome", "pair", "code", "paired", "denied", "bye", "ping", "pong", "notes", "want",
            "file", "file_end", "have", "file_error", "viewing", "pad", "target")))
    }

    @Test fun everyMessageThePhoneSendsParsesBackToTheSameValues() {
        val sent = listOf(
            Control.Hello("Galaxy S23 Ultra", "0123456789abcdef0123456789abcdef", null),
            Control.Hello("Galaxy", "0123456789abcdef0123456789abcdef", "ab".repeat(32)),
            Control.Code("0427"), Control.Ping(3, 123456), Control.Pong(3, 123456, 987654),
            Control.Notes(listOf(NoteMeta("01JCN0TE000000000000000001", 42, "Ünïcode “title” \"quoted\" \\ \n tab\t"), NoteMeta("b", 0, ""))),
            Control.File(1, "01JCN0TE000000000000000001", 42, 131072, "0".repeat(64), 3), Control.FileEnd(1),
            Control.Viewing("01JCN0TE000000000000000001"), Control.Viewing(null), Control.Pad(true), Control.Pad(false),
            Control.Want(listOf("a", "b")), Control.Have("a", 9), Control.FileError(1, "a", "hash"),
            Control.Target("n", "s", true, 12), Control.Target(null, null, false, 0), Control.Bye("off"), Control.Denied(4),
            Control.PairRequest, Control.Paired("cd".repeat(32)), Control.Welcome(0, "Mac", MAC_ID, 4_294_967_295L),
        )
        for (c in sent) assertEquals(c, Control.parse(c.encode()))
    }

    @Test fun controlEncodingIsKindByteThenJson() {
        val bytes = Control.Pad(true).encode()
        assertEquals(0x4A, bytes[0].toInt())
        assertEquals("{\"t\":\"pad\",\"on\":true}", String(bytes, 1, bytes.size - 1))
        // Non-ASCII goes out as UTF-8, like the vector.
        val notes = Control.Notes(listOf(NoteMeta("a", 1, "Ü"))).encode()
        assertTrue(String(notes, 1, notes.size - 1, Charsets.UTF_8).contains("Ü"))
    }
}
