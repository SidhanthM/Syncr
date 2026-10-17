package dev.syncr.prototype.tablet

import org.junit.Assert.assertEquals
import org.junit.Test

class PadCaptionTest {
    @Test fun captionNamesTheMacOrTheProblem() {
        assertEquals("Sid's MacBook Air · Pointer · Bluetooth", LinkStatus.padCaption("Sid's MacBook Air", null))
        assertEquals("Not connected · Pointer · Bluetooth", LinkStatus.padCaption(null, null))
        assertEquals("Bluetooth is off · Pointer · Bluetooth", LinkStatus.padCaption("Mac", "Bluetooth is off"))
    }

    @Test fun attentionBeatsConnectedBeatsNotConnected() {
        assertEquals(LinkState.ATTENTION, LinkStatus.state("Mac", "Needs Bluetooth permission"))
        assertEquals(LinkState.CONNECTED, LinkStatus.state("Mac", null))
        assertEquals(LinkState.NOT_CONNECTED, LinkStatus.state(null, null))
    }
}
