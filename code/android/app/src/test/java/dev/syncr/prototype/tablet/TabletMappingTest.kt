package dev.syncr.prototype.tablet

import org.junit.Assert.assertEquals
import org.junit.Test

class TabletMappingTest {
    private val pad = PadRect(0.0, 0.0, 3000.0, 1400.0)

    @Test fun activeAreaIsTheLargestCentredRectangleOfTheViewsShape() {
        val a = TabletMapper(pad, 4800.0, 3000.0).activeArea
        assertEquals(1400.0, a.height, 1e-9)
        assertEquals(1400.0 * 4800 / 3000, a.width, 1e-9)
        assertEquals((3000.0 - a.width) / 2, a.left, 1e-9)
        assertEquals(0.0, a.top, 1e-9)
    }

    @Test fun uvSpansZeroToOneAcrossTheArea() {
        val m = TabletMapper(pad, 4800.0, 3000.0)
        val a = m.activeArea
        assertEquals(0.0 to 0.0, m.uv(a.left, a.top))
        assertEquals(1.0 to 1.0, m.uv(a.right, a.bottom))
        assertEquals(true, m.uv(a.left - 10, a.top).first < 0)
    }

    @Test fun portraitPadKeepsOneScaleForBothAxes() {
        val a = TabletMapper(PadRect(0.0, 0.0, 1000.0, 2300.0), 4800.0, 3000.0).activeArea
        assertEquals(1000.0, a.width, 1e-9)
        assertEquals(1000.0 * 3000 / 4800, a.height, 1e-9)
    }

    @Test fun captionsNameWifiStream() {
        assertEquals("Sid's MacBook Air · Stream · Wi-Fi", LinkStatus.padCaption("Sid's MacBook Air", null))
        assertEquals("Not connected · Stream · Wi-Fi", LinkStatus.padCaption(null, null))
        assertEquals("Mac is off · Stream · Wi-Fi", LinkStatus.padCaption("Mac", "Mac is off"))
        assertEquals("Tablet mode pad. Stream over Wi-Fi. Writing into the note open on Mac. Whole note view.", LinkStatus.caption("Mac", null))
    }
}
