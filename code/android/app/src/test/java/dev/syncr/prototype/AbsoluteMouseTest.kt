package dev.syncr.prototype

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AbsoluteMouseTest {
    @Test fun cornersAndButtonState() {
        assertArrayEquals(byteArrayOf(1, 0, 0, 0, 0), AbsoluteMouse.report(0f, 0f, 100f, 200f, true))
        assertArrayEquals(
            byteArrayOf(0, 0xFF.toByte(), 0x7F, 0xFF.toByte(), 0x7F),
            AbsoluteMouse.report(100f, 200f, 100f, 200f, false)
        )
    }

    @Test fun clampsOutOfBoundsAndIsStableAtSamePoint() {
        val first = AbsoluteMouse.report(50f, 100f, 100f, 200f, true)
        assertArrayEquals(first, AbsoluteMouse.report(50f, 100f, 100f, 200f, true))
        assertEquals(0x4000, (first[1].toInt() and 0xff) or ((first[2].toInt() and 0xff) shl 8))
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 0xFF.toByte(), 0x7F),
            AbsoluteMouse.report(-10f, 999f, 100f, 200f, false)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesZeroSizedSurface() { AbsoluteMouse.report(1f, 2f, 0f, 200f, true) }
}
