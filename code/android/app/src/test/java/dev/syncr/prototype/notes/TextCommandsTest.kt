package dev.syncr.prototype.notes

import org.junit.Assert.*
import org.junit.Test

class TextCommandsTest {
    @Test fun spliceUsesUtf8AndDoesNotSplitSurrogates() {
        for ((before, after) in listOf("café 😀 end" to "café 😃 end", "a😀" to "a😀!", "中间" to "中", "" to "hello", "abc" to "")) {
            val s = TextCommands.splice(before, after)
            val bytes = before.toByteArray()
            val rebuilt = bytes.copyOfRange(0, s.first) + s.third.toByteArray() + bytes.copyOfRange(s.first + s.second, bytes.size)
            assertEquals(after, String(rebuilt))
        }
        assertEquals(Triple(6, 4, "😃"), TextCommands.splice("café 😀 end", "café 😃 end"))
    }
    @Test fun hitsAreBoundedAndDistinguishResponses() {
        val hit = ContentHit(ContentTarget("block", false), 100f, 260f, 1400f, 64f)
        assertTrue(hit.contains(120f, 270f)); assertFalse(hit.contains(99f, 270f))
        assertFalse(hit.contains(120f, 325f)); assertFalse(hit.target.response)
    }
}
