package dev.syncr.prototype.tablet

import dev.syncr.prototype.link.Phase
import dev.syncr.prototype.link.StreamTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabletPolicyTest {
    @Test fun offAndUnpairedEachHaveOneDirectRecoveryAction() {
        assertEquals(StreamPadState("Mac link is off", "Mac link is off", StreamAction.ENABLE),
            streamPadState(false, true, Phase.CONNECTED, "Mac", ready))
        for (phase in listOf(Phase.IDLE, Phase.CONNECTING, Phase.NEEDS_CODE)) {
            val state = streamPadState(true, false, phase, null, StreamTarget())
            assertEquals("Pair your Mac in Mac link settings", state.message)
            assertEquals(StreamAction.PAIR, state.action)
        }
        assertEquals(StreamAction.PAIR, streamPadState(true, true, Phase.NEEDS_CODE, "Mac", ready).action)
    }

    @Test fun disconnectedPairedPadOffersTheMacLinkSettings() {
        for (phase in listOf(Phase.IDLE, Phase.CONNECTING)) {
            assertEquals(StreamPadState("Waiting for M2mac. Is the Syncr Mac app running?", "Mac is not connected", StreamAction.PAIR),
                streamPadState(true, true, phase, "M2mac", ready))
        }
    }

    @Test fun connectedWithoutWritableTargetWaitsRatherThanPromisingSavedInk() {
        for (target in listOf(StreamTarget(), ready.copy(writable = false), ready.copy(sheetId = null))) {
            assertEquals(StreamPadState("Waiting for a writable note on the Mac", "No writable target yet"),
                streamPadState(true, true, Phase.CONNECTED, "Mac", target))
        }
    }

    @Test fun readyNamesMacAndDestinationNotPhoneStorage() {
        val state = streamPadState(true, true, Phase.CONNECTED, "M2mac", ready)
        assertEquals("M2mac · Ink is saved in the Mac's note", state.message)
        assertNull(state.problem)
        assertNull(state.action)
        assertEquals("M2mac · Stream · Wi-Fi", LinkStatus.padCaption("M2mac", state.problem))
    }

    @Test fun aRestingPalmCannotActivateThePictureJustAfterPenUp() {
        assertEquals(false, tabletCaptionTapAllowed(true, 2000, 1000))
        assertEquals(false, tabletCaptionTapAllowed(false, 1299, 1000))
        assertEquals(true, tabletCaptionTapAllowed(false, 1300, 1000))
    }

    private val ready = StreamTarget("note", "sheet", true, 7)
}
