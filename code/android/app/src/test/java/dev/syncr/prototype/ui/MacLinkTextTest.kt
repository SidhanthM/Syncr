package dev.syncr.prototype.ui

import dev.syncr.prototype.link.LinkPolicy
import dev.syncr.prototype.link.Phase
import dev.syncr.prototype.link.SessionStatus
import dev.syncr.prototype.link.android.LinkUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MacLinkTextTest {
    @Test fun macSectionTellsTheTruthAboutTheLink() {
        assertTrue(macSectionText(LinkUi()).startsWith("The Mac link is off"))
        val connected = LinkUi(enabled = true, status = SessionStatus(Phase.CONNECTED, "Studio Mac"))
        assertEquals("Connected to Studio Mac. This note is copied to the Mac after you change it.", macSectionText(connected))
        val looking = LinkUi(enabled = true, pairedMac = "Studio Mac")
        assertTrue(macSectionText(looking).startsWith("Looking for Studio Mac"))
        assertFalse(listOf(LinkUi(), connected, looking).any { "later version" in macSectionText(it) || "not available" in macSectionText(it) })
    }

    @Test fun forgetRemainsAvailableWhenLinkIsOffOrDisconnected() {
        assertEquals("Studio Mac", forgettableMac(LinkUi(enabled = false, pairedMac = "Studio Mac")))
        assertEquals("Studio Mac", forgettableMac(LinkUi(enabled = true, pairedMac = "Studio Mac")))
        assertEquals(null, forgettableMac(LinkUi(enabled = false)))
    }

    @Test fun linkIsOffByDefaultAndTouchesNothingWhileOff() {
        assertFalse(LinkPolicy.ENABLED_BY_DEFAULT)
        assertFalse(LinkUi().enabled)
        // Off: no client or discovery, in front or not; and no Wi-Fi lock without a connected session.
        for (started in 0..3) assertFalse(LinkPolicy.shouldRun(false, started))
        assertFalse(LinkPolicy.wantsWifiLock(1, connected = false))
        // On: only while an activity is started; the lock only while connected and in front.
        assertFalse(LinkPolicy.shouldRun(true, 0))
        assertTrue(LinkPolicy.shouldRun(true, 1))
        assertFalse(LinkPolicy.wantsWifiLock(0, connected = true))
        assertTrue(LinkPolicy.wantsWifiLock(1, connected = true))
    }
}
