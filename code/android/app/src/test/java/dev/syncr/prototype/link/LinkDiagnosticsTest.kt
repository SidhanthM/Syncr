package dev.syncr.prototype.link

import org.junit.Assert.*
import org.junit.Test

class LinkDiagnosticsTest {
    @Test fun penReasonsAreBoundedAndOnlyLoggedByConsumer() {
        val queue = PenDiagnostics(2)
        val lines = ArrayList<String>()
        queue.offer("Mac link is off"); queue.offer("no target"); queue.offer("overflow")
        assertTrue(lines.isEmpty())
        queue.drain(lines::add)
        assertEquals(listOf("stream stroke not sent reason=Mac link is off", "stream stroke not sent reason=no target",
            "stream diagnostics dropped=1 (queue full)"), lines)
        queue.offer("next"); queue.drain(lines::add)
        assertEquals("stream stroke not sent reason=next", lines.last())
    }

    @Test fun statusLinesNameStateAndDisconnectionReason() {
        assertEquals("state connecting mac=M2mac", linkStatusLine(SessionStatus(Phase.CONNECTING, "M2mac")))
        assertEquals("state needs code mac=M2mac", linkStatusLine(SessionStatus(Phase.NEEDS_CODE, "M2mac")))
        assertEquals("state connected mac=M2mac", linkStatusLine(SessionStatus(Phase.CONNECTED, "M2mac")))
        assertEquals("state disconnected reason=The Mac stopped answering.",
            linkStatusLine(SessionStatus(message = "The Mac stopped answering.")))
        assertEquals("target present=true writable=true rev=7 note=12345678 sheet=abcdefgh",
            targetLine(StreamTarget("1234567890123456", "abcdefghijklmnop", true, 7)))
        assertEquals("target present=false writable=false rev=0 note=none sheet=none", targetLine(StreamTarget()))
    }

    @Test fun radioContextDoesNotClaimDriverEffectOrRequireLocation() {
        assertEquals("2.4GHz", wifiBand(2412)); assertEquals("5GHz", wifiBand(5180))
        assertEquals("6GHz", wifiBand(6115)); assertEquals("unavailable", wifiBand(null))
        val line = radioLine(true, "WIFI_MODE_FULL_LOW_LATENCY", 866, 5180, -52, false)
        assertTrue(line.contains("wifi-lock-held=true requested=WIFI_MODE_FULL_LOW_LATENCY low-latency-effect=unobservable"))
        assertTrue(line.contains("wifi-speed=866Mbps wifi-frequency=5180MHz band=5GHz rssi=-52dBm battery-saver=false"))
        assertTrue(line.contains("no-location-permission"))
        assertTrue(radioLine(false, "none", null, null, null, null).contains("wifi-speed=unavailable"))
        val logs = ArrayList<String>()
        LatencyLog({ line }, logs::add).report("stroke 1")
        assertTrue(logs.single().contains(line))
        assertTrue(logs.single().contains("excludes: digitiser, display scan-out"))
    }
}
