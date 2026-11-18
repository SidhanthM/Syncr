package dev.syncr.prototype.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PickerMathHostTest {
    @Test fun aGreyIsInGamutAtAnyLightness() {
        for (l in listOf(0.1f, 0.5f, 0.9f)) assertTrue(PickerMath.inGamut(l, 0f, 0f))
    }

    @Test fun aVeryChromaticPointIsOutOfGamutAndSnapsByReducingChromaOnly() {
        assertFalse(PickerMath.inGamut(0.5f, 0.37f, 200f))
        val (l, c, h) = PickerMath.snap(0.5f, 0.37f, 200f)
        assertEquals(0.5f, l, 0f); assertEquals(200f, h, 0f)
        assertTrue(c < 0.37f && c > 0.02f)
        assertTrue(PickerMath.inGamut(l, c, h))
        assertFalse("just past the boundary is outside", PickerMath.inGamut(l, c + 0.01f, h))
    }

    @Test fun anInGamutPointDoesNotMove() {
        val (l, c, h) = PickerMath.snap(0.7f, 0.05f, 40f)
        assertEquals(0.7f, l, 0f); assertEquals(0.05f, c, 0f); assertEquals(40f, h, 0f)
    }

    @Test fun theRingColourIsReachableInEveryHue() {
        for (h in 0 until 360 step 5) assertTrue("hue $h", PickerMath.inGamut(PickerMath.RING_L, PickerMath.RING_C, h.toFloat()))
    }

    @Test fun maxChromaIsZeroAtBlackAndWhite() {
        assertEquals(0f, PickerMath.maxChroma(0f, 100f), 0f); assertEquals(0f, PickerMath.maxChroma(1f, 100f), 0f)
    }

    @Test fun hueAtTurnsClockwiseFromTheRight() {
        assertEquals(0f, PickerMath.hueAt(1f, 0f), 0.01f)
        assertEquals(90f, PickerMath.hueAt(0f, 1f), 0.01f)
        assertEquals(270f, PickerMath.hueAt(0f, -1f), 0.01f)
    }

    @Test fun theFieldMapsLightTopAndChromaRight() {
        assertEquals(1f, PickerMath.fieldPoint(0f, 0f).first, 0f)
        assertEquals(PickerMath.MAX_CHROMA, PickerMath.fieldPoint(1f, 1f).second, 0f)
    }

    @Test fun whiteAndRedConvertAsExpected() {
        assertEquals(0xFFFFFFFF.toInt(), PickerMath.argb(1f, 0f, 0f))
        val red = PickerMath.argb(0.628f, 0.2577f, 29.23f)
        assertTrue(((red shr 16) and 0xFF) > 250 && ((red shr 8) and 0xFF) < 8)
    }
}
