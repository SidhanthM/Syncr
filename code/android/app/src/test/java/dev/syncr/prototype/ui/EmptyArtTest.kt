package dev.syncr.prototype.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmptyArtTest {
    @Test fun strokesAreDrawnOneAfterAnother() {
        val lengths = listOf(10f, 30f, 10f)
        assertEquals(listOf(0f, 0f, 0f), drawnLengths(lengths, 0f))
        assertEquals(listOf(10f, 15f, 0f), drawnLengths(lengths, 0.5f))
        assertEquals(lengths, drawnLengths(lengths, 1f))
        assertEquals(lengths, drawnLengths(lengths, 2f))
    }
    @Test fun everyDrawingHasStrokesInsideTheSheet() {
        for (art in listOf(EmptyArtData.library, EmptyArtData.search, EmptyArtData.notebook)) {
            assertTrue(art.size > 6)
            val numbers = art.flatMap { it.d.split(" ").mapNotNull(String::toFloatOrNull) }
            assertTrue(numbers.all { it in -5f..125f })
            assertTrue(art.all { it.d.startsWith("M ") && it.d.all { ch -> ch in "MC 0123456789.-" } })
        }
    }
}
