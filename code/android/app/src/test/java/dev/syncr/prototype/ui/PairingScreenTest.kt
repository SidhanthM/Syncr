package dev.syncr.prototype.ui

import dev.syncr.prototype.link.Endpoint
import dev.syncr.prototype.link.Phase
import dev.syncr.prototype.link.SessionStatus
import dev.syncr.prototype.link.android.LinkUi
import dev.syncr.prototype.link.android.PairingTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingScreenTest {
    private val target = PairingTarget("MacBook Air", Endpoint("192.168.1.20", 5050, "MacBook Air", "0123456789abcdef0123456789abcdef"))
    private fun ui(status: SessionStatus, pairing: PairingTarget? = target) = LinkUi(enabled = true, status = status, pairing = pairing)

    @Test fun noScreenWithoutASubject() {
        assertNull(pairingStage(ui(SessionStatus(Phase.NEEDS_CODE), pairing = null)))
    }

    @Test fun followsTheLinkFromConnectingToPaired() {
        assertEquals(PairingStage.CONNECTING, pairingStage(ui(SessionStatus())))
        assertEquals(PairingStage.CONNECTING, pairingStage(ui(SessionStatus(Phase.CONNECTING, "MacBook Air", message = "Connecting to 192.168.1.20:5050…"))))
        assertEquals(PairingStage.CODE, pairingStage(ui(SessionStatus(Phase.NEEDS_CODE, "MacBook Air"))))
        assertEquals(PairingStage.CODE, pairingStage(ui(SessionStatus(Phase.NEEDS_CODE, "MacBook Air", 3, "That code was wrong."))))
        assertEquals(PairingStage.PAIRED, pairingStage(ui(SessionStatus(Phase.CONNECTED, "MacBook Air"))))
    }

    @Test fun aConnectionToAnotherMacIsNotThisPairing() {
        assertEquals(PairingStage.CONNECTING, pairingStage(ui(SessionStatus(Phase.CONNECTED, "Studio"))))
    }

    @Test fun aFailureOrAProblemIsSaid() {
        val failed = ui(SessionStatus(Phase.IDLE, message = "Too many wrong codes. Start again for a new code."))
        assertEquals(PairingStage.FAILED, pairingStage(failed))
        assertEquals("Too many wrong codes. Start again for a new code.", pairingLine(failed, PairingStage.FAILED))
        val expired = ui(SessionStatus(Phase.CONNECTED, "Other"), PairingTarget("MacBook Air", null, "This code has expired."))
        assertEquals(PairingStage.PROBLEM, pairingStage(expired))
        assertEquals("This code has expired.", pairingLine(expired, PairingStage.PROBLEM))
    }
}
