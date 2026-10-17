package dev.syncr.prototype.tablet

import dev.syncr.prototype.AbsoluteMouse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class TabletMappingTest {
    private val screen = MacScreen.DEFAULT // 290 x 189 mm
    // S23 Ultra-ish landscape pad: 3088 x 1440 px at ~500 dpi (about 157 x 73 mm).
    private val pad = PadRect(0.0, 0.0, 3000.0, 1400.0)
    private val dpi = PhoneDpi(500.0, 500.0)

    private fun HidPoint.mm(s: MacScreen) = Pair(x / 32767.0 * s.widthMm, y / 32767.0 * s.heightMm)

    // ---- rounding and clamping ----

    @Test fun hidRoundsAndClamps() {
        assertEquals(0, TabletMapping.hid(0.0))
        assertEquals(32767, TabletMapping.hid(1.0))
        assertEquals(16384, TabletMapping.hid(0.5)) // 16383.5 rounds up
        assertEquals(1, TabletMapping.hid(1.4 / 32767))
        assertEquals(2, TabletMapping.hid(1.6 / 32767))
        assertEquals(0, TabletMapping.hid(0.49 / 32767))
        assertEquals(0, TabletMapping.hid(-0.2))
        assertEquals(32767, TabletMapping.hid(7.0))
    }

    @Test fun penOutsideActiveAreaClampsToEdges() {
        val m = TabletMapper(MappingMode.WHOLE_SCREEN, pad, dpi, screen)
        val a = m.activeArea
        assertEquals(HidPoint(0, 0), m.toHid(a.left - 50, a.top - 50))
        assertEquals(HidPoint(32767, 32767), m.toHid(a.right + 50, a.bottom + 50))
        val actual = TabletMapper(MappingMode.ACTUAL_SIZE, pad, dpi, screen, 0.0, 0.0)
        assertEquals(HidPoint(0, 0), actual.toHid(-100.0, -100.0))
    }

    /** The integer sent is what AbsoluteMouse encodes: feeding X with width 32767 is lossless. */
    @Test fun hidValuesPassThroughAbsoluteMouseUnchanged() {
        for (x in 0..32767) {
            val bytes = AbsoluteMouse.report(x.toFloat(), (32767 - x).toFloat(), 32767f, 32767f, true)
            assertEquals(x, (bytes[1].toInt() and 0xff) or ((bytes[2].toInt() and 0xff) shl 8))
            assertEquals(32767 - x, (bytes[3].toInt() and 0xff) or ((bytes[4].toInt() and 0xff) shl 8))
        }
    }

    // ---- equal scale: circles stay circles ----

    private fun assertCircleStaysRound(m: TabletMapper, cx: Double, cy: Double, rxPx: Double, ryPx: Double, tolMm: Double) {
        val c = m.toHid(cx, cy).mm(screen)
        val radii = (0 until 36).map { k ->
            val t = 2 * PI * k / 36
            val p = m.toHid(cx + rxPx * cos(t), cy + ryPx * sin(t)).mm(screen)
            hypot(p.first - c.first, p.second - c.second)
        }
        val spread = radii.max() - radii.min()
        assertTrue("radii spread $spread mm: $radii", spread < tolMm)
    }

    @Test fun actualSizeKeepsCirclesRoundAndIsOneToOne() {
        val m = TabletMapper(MappingMode.ACTUAL_SIZE, pad, dpi, screen, 40.0, 30.0)
        assertCircleStaysRound(m, 1500.0, 700.0, 400.0, 400.0, 0.05)
        // 1 cm of pen travel is 1 cm of cursor travel.
        val oneCmPx = TabletMapping.mmToPx(10.0, dpi.x)
        val a = m.toHid(1000.0, 700.0).mm(screen)
        val b = m.toHid(1000.0 + oneCmPx, 700.0).mm(screen)
        assertEquals(10.0, b.first - a.first, 0.02)
        val d = m.toHid(1000.0, 700.0 + oneCmPx).mm(screen)
        assertEquals(10.0, d.second - a.second, 0.02)
    }

    @Test fun actualSizeUsesEachAxisDpi() {
        // Non-square pixels: a physical circle is an ellipse in pixels, and must come out round on the Mac.
        val skew = PhoneDpi(500.0, 400.0)
        val m = TabletMapper(MappingMode.ACTUAL_SIZE, pad, skew, screen)
        val rMm = 20.0
        assertCircleStaysRound(m, 1500.0, 700.0, TabletMapping.mmToPx(rMm, skew.x), TabletMapping.mmToPx(rMm, skew.y), 0.05)
    }

    @Test fun wholeScreenScalesBothAxesEqually() {
        for (p in listOf(pad, PadRect(10.0, 20.0, 1000.0, 2000.0), PadRect(0.0, 0.0, 1600.0, 1000.0))) {
            val m = TabletMapper(MappingMode.WHOLE_SCREEN, p, dpi, screen)
            val a = m.activeArea
            assertEquals(screen.widthMm / a.width, screen.heightMm / a.height, 1e-9) // mm per pad pixel
            assertCircleStaysRound(m, a.left + a.width / 2, a.top + a.height / 2, a.height / 3, a.height / 3, 0.1)
        }
    }

    /** Regression: the old u = x / w, v = y / h stretched a wide pad onto the Mac. */
    @Test fun oldStretchingMappingWouldFail() {
        val stretchX = screen.widthMm / pad.width
        val stretchY = screen.heightMm / pad.height
        assertTrue(kotlin.math.abs(stretchX - stretchY) > 0.01)
    }

    // ---- letterboxing ----

    @Test fun letterboxWidePadCentresHorizontally() {
        val a = TabletMapping.letterbox(PadRect(0.0, 0.0, 2000.0, 1000.0), 16.0, 10.0)
        assertEquals(PadRect(200.0, 0.0, 1600.0, 1000.0), a)
    }

    @Test fun letterboxTallPadCentresVertically() {
        val a = TabletMapping.letterbox(PadRect(10.0, 20.0, 1000.0, 2000.0), 16.0, 10.0)
        assertEquals(10.0, a.left, 1e-9); assertEquals(1000.0, a.width, 1e-9)
        assertEquals(625.0, a.height, 1e-9); assertEquals(20.0 + (2000.0 - 625.0) / 2, a.top, 1e-9)
    }

    @Test fun wholeScreenCornersHitScreenCorners() {
        val m = TabletMapper(MappingMode.WHOLE_SCREEN, pad, dpi, screen)
        val a = m.activeArea
        assertEquals(HidPoint(0, 0), m.toHid(a.left, a.top))
        assertEquals(HidPoint(32767, 32767), m.toHid(a.right, a.bottom))
        assertEquals(HidPoint(16384, 16384), m.toHid(a.left + a.width / 2, a.top + a.height / 2))
    }

    // ---- actual-size window offset ----

    @Test fun windowOffsetIsClampedToScreen() {
        val m = TabletMapper(MappingMode.ACTUAL_SIZE, pad, dpi, screen)
        val wmm = 3000 * 25.4 / 500 // 152.4
        val hmm = 1400 * 25.4 / 500 // 71.12
        assertEquals(wmm, m.windowWidthMm, 1e-9)
        assertEquals(screen.widthMm - wmm, m.maxOffsetXMm, 1e-9)
        assertEquals(screen.heightMm - hmm, m.maxOffsetYMm, 1e-9)
        assertEquals(0.0, m.withOffset(-50.0, -1.0).offsetXMm, 0.0)
        val far = m.withOffset(1e6, 1e6)
        assertEquals(m.maxOffsetXMm, far.offsetXMm, 1e-9)
        assertEquals(m.maxOffsetYMm, far.offsetYMm, 1e-9)
        assertEquals(0.0, m.withOffset(Double.NaN, 0.0).offsetXMm, 0.0)
        // At max offset the pad's far corner is the screen's far corner.
        assertEquals(HidPoint(32767, 32767), far.toHid(pad.right, pad.bottom))
        assertEquals(HidPoint(0, 0), m.toHid(0.0, 0.0))
    }

    @Test fun windowFollowsFingerDragInMillimetres() {
        val m = TabletMapper(MappingMode.ACTUAL_SIZE, pad, dpi, screen, 10.0, 10.0)
        val moved = m.movedBy(500.0, -100.0)
        assertEquals(10.0 + 500 * 25.4 / 500, moved.offsetXMm, 1e-9)
        assertEquals(10.0 - 100 * 25.4 / 500, moved.offsetYMm, 1e-9)
        assertEquals(0.0, m.movedBy(-1e5, -1e5).offsetXMm, 0.0)
    }

    @Test fun padLargerThanScreenShrinksWindowToScreen() {
        val tiny = MacScreen(100.0, 50.0)
        val m = TabletMapper(MappingMode.ACTUAL_SIZE, pad, dpi, tiny, 30.0, 30.0)
        assertEquals(100.0, m.windowWidthMm, 1e-9)
        assertEquals(50.0, m.windowHeightMm, 1e-9)
        assertEquals(0.0, m.offsetXMm, 0.0); assertEquals(0.0, m.offsetYMm, 0.0)
        val a = m.activeArea
        assertEquals(HidPoint(0, 0), m.toHid(a.left, a.top))
        assertEquals(HidPoint(32767, 32767), m.toHid(a.right, a.bottom))
    }

    // ---- caption / link status ----

    @Test fun linkStatusTracksControllerMessages() {
        var host: String? = null
        host = LinkStatus.connectedHost("Bluetooth controller ready. Select a paired Mac.", host)
        assertEquals(null, host)
        host = LinkStatus.connectedHost("Connected to Sid's MacBook Air. Draw on the pad.", host)
        assertEquals("Sid's MacBook Air", host)
        host = LinkStatus.connectedHost("HID report failed; check connection", host)
        assertEquals("Sid's MacBook Air", host)
        host = LinkStatus.connectedHost("Disconnected. Select the paired Mac to reconnect.", host)
        assertEquals(null, host)
    }

    @Test fun captionShowsLinkAndCalibration() {
        assertEquals("POINTER · BLUETOOTH · NOT CONNECTED · ACTUAL SIZE · UNCALIBRATED",
            LinkStatus.caption(null, MappingMode.ACTUAL_SIZE, calibrated = false))
        assertEquals("POINTER · BLUETOOTH · CONNECTED TO MAC · ACTUAL SIZE",
            LinkStatus.caption("Mac", MappingMode.ACTUAL_SIZE, calibrated = true))
        assertEquals("POINTER · BLUETOOTH · CONNECTED TO MAC · WHOLE SCREEN",
            LinkStatus.caption("Mac", MappingMode.WHOLE_SCREEN, calibrated = false))
    }
}
