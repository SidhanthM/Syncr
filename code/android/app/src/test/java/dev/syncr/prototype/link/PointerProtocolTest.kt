package dev.syncr.prototype.link

import org.junit.Assert.*
import org.junit.Test

class PointerProtocolTest {
    @Test fun extensionRoundTripsWithoutChangingTheNoteLinkVersion() {
        for (control in listOf(Control.PointerMode(true), Control.PointerState(1, false, "Allow on Mac"),
            Control.Pointer(7, PointerInput(dx = -1.25, dy = 9.0, sx = 2.0, sy = -8.5, buttons = 1, click = 3)),
            Control.Pointer(8, PointerInput(scrollEnd = true)))) {
            assertEquals(control, Control.parse(control.encode()))
        }
        assertEquals(1, Control.VERSION)
    }

    @Test fun scrollEndIsAnOptionalKeyAbsentFromOrdinaryPackets() {
        val plain = String(Control.Pointer(1, PointerInput(dx = 1.0)).encode())
        assertFalse(plain.contains("\"se\""))
        assertTrue(String(Control.Pointer(2, PointerInput(scrollEnd = true)).encode()).contains("\"se\":true"))
    }

    @Test fun waitsForCapabilityAndApprovalAndStopsOnModeExit() {
        val h = SessionHarness()
        try {
            h.session.setPointer(true)
            h.session.pointerInput(PointerInput(click = 1))
            assertTrue(h.controls().isEmpty())
            h.session.start(); h.drain(); h.mac(Control.Welcome(1, "Test Mac", MAC_ID, 7)); h.drain()
            h.session.pointerInput(PointerInput(click = 1))
            assertTrue(h.controls().isEmpty()) // old Mac: no extension advertisement, no input
            h.mac(Control.PointerState(1, false, "Allow on Mac"))
            assertEquals(Control.PointerMode(true), h.controls().single())
            h.session.pointerInput(PointerInput(click = 1))
            assertTrue(h.controls().isEmpty()) // capability alone is not approval
            h.mac(Control.PointerState(1, true, "Ready"))
            h.session.pointerInput(PointerInput(dx = 5.0))
            assertEquals(Control.Pointer(1, PointerInput(dx = 5.0)), h.controls().single())
            h.session.setPointer(false)
            h.session.pointerInput(PointerInput(click = 1))
            assertEquals(listOf(Control.PointerMode(false)), h.controls())
        } finally { h.cleanup() }
    }

    @Test fun packetsCarryThePhoneClockOnlyWhenTheMacSaidItReadsIt() {
        val stamped = Control.Pointer(3, PointerInput(dx = 2.0), ts = 123_456L)
        assertEquals(stamped, Control.parse(stamped.encode()))
        assertFalse(String(Control.Pointer(1, PointerInput(dx = 1.0)).encode()).contains("\"ts\""))
        assertEquals(true, (Control.Hello("x", "p".repeat(32), null, 1, ptime = true).json())["ptime"])
        assertFalse(Control.Hello("x", "p".repeat(32), null, 0).json().containsKey("ptime"))
        assertFalse(Control.Welcome(1, "Mac", MAC_ID, 1).json().containsKey("ptime"))
        assertTrue((Control.parse(Control.Welcome(1, "Mac", MAC_ID, 1, ptime = true).encode()) as Control.Welcome).ptime)

        fun run(welcome: Control.Welcome): Control.Pointer {
            val h = SessionHarness()
            try {
                h.session.setPointer(true)
                h.session.start(); h.drain(); h.mac(welcome); h.drain()
                h.mac(Control.PointerState(1, true, "Ready"))
                h.drain()
                h.clock.now = 5_000
                h.session.pointerInput(PointerInput(dx = 5.0))
                return h.controls().single() as Control.Pointer
            } finally { h.cleanup() }
        }
        val told = run(Control.Welcome(1, "Test Mac", MAC_ID, 7, ptime = true))
        assertEquals(5_000L, told.ts) // the phone's own clock at the moment the packet was made
        assertNull(run(Control.Welcome(1, "Test Mac", MAC_ID, 7)).ts)           // an old Mac: today's packet
    }

    @Test fun helloAsksForPointerStampsOnlyAtLinkV1() {
        val h = SessionHarness()
        try {
            h.session.start()
            val hello = h.controls().filterIsInstance<Control.Hello>().single()
            assertEquals(hello.v >= 1, hello.ptime)
        } finally { h.cleanup() }
    }

    @Test fun rejectsUnboundedOrAmbiguousInput() {
        val base = """{"t":"pointer","v":1,"seq":1,"dx":0,"dy":0,"sx":0,"sy":0,"buttons":0,"click":0}"""
        for (bad in listOf(base.replace("\"v\":1", "\"v\":2"), base.replace("\"seq\":1", "\"seq\":0"),
            base.replace("\"dx\":0", "\"dx\":2049"), base.replace("\"dy\":0", "\"dy\":true"),
            base.replace("\"buttons\":0", "\"buttons\":4294967296"), base.replace("\"click\":0", "\"click\":4"))) {
            assertThrows(LinkProtocolException::class.java) { Control.parse(byteArrayOf(Wire.KIND_CONTROL.toByte()) + bad.toByteArray()) }
        }
        assertFalse(PointerInput(dx = Double.NaN).valid())
    }
}
