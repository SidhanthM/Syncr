package dev.syncr.prototype.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTest {
    private val token = "ab".repeat(32)

    @Test fun firstConnectionSendsHelloWithNullTokenThenPairs() {
        val h = SessionHarness()
        h.session.start()
        val hello = h.controls().single() as Control.Hello
        assertEquals("0123456789abcdef0123456789abcdef", hello.phoneId)
        assertNull(hello.token)
        assertEquals(0, hello.v)

        h.mac(Control.PairRequest)
        assertEquals(Phase.NEEDS_CODE, h.session.phase)
        // Not four digits: nothing is sent.
        assertFalse(h.session.submitCode("12"))
        assertFalse(h.session.submitCode("12a4"))
        assertFalse(h.session.submitCode("12345"))
        assertTrue(h.controls().isEmpty())

        assertTrue(h.session.submitCode("0427"))
        assertEquals(Control.Code("0427"), h.controls().single())

        h.mac(Control.Paired(token))
        assertNull(h.store.current) // stored with the Mac's ID, which arrives with welcome
        h.welcome()
        assertEquals(Pairing(MAC_ID, "Test Mac", token), h.store.current)
        assertEquals(Phase.CONNECTED, h.session.phase)
        assertTrue(h.feed.enabled)
        assertEquals(7L, h.pump.epoch)
        h.cleanup()
    }

    @Test fun wrongCodeShowsTriesLeftAndFiveWrongClose() {
        val h = SessionHarness()
        h.session.start(); h.drain(); h.mac(Control.PairRequest)
        for (left in 4 downTo 1) {
            h.session.submitCode("0000")
            h.mac(Control.Denied(left))
            assertEquals(left, h.statuses.last().triesLeft)
            assertEquals(Phase.NEEDS_CODE, h.statuses.last().phase)
        }
        assertNull(h.closed)
        h.mac(Control.Denied(0))
        assertEquals(true, h.closed?.second) // a new code is needed: no automatic retry
        assertNull(h.store.current)
        h.cleanup()
    }

    @Test fun storedTokenIsReusedAndGoesStraightToWelcome() {
        val h = SessionHarness(MemoryStore(current = Pairing(MAC_ID, "Old name", token)))
        h.session.start()
        val hello = h.controls().single() as Control.Hello
        assertEquals(token, hello.token)
        h.welcome(epoch = 9)
        assertEquals(Phase.CONNECTED, h.session.phase)
        assertEquals("Test Mac", h.store.current?.macName) // refreshed
        assertEquals(token, h.store.current?.token)
        assertEquals(9L, h.pump.epoch)
        h.cleanup()
    }

    @Test fun storedTokenIsNotSentToADifferentMac() {
        val other = Pairing("11111111111111111111111111111111", "Other", token)
        val h = SessionHarness(MemoryStore(current = other))
        h.session.start()
        assertNull((h.controls().single() as Control.Hello).token)
        h.cleanup()
    }

    @Test fun anUnknownTokenMeansTheMacForgotUsSoWeForgetToo() {
        val h = SessionHarness(MemoryStore(current = Pairing(MAC_ID, "Mac", token)))
        h.session.start(); h.drain()
        h.mac(Control.PairRequest)
        assertNull(h.store.current)
        assertEquals(Phase.NEEDS_CODE, h.session.phase)
        h.cleanup()
    }

    @Test fun byeUnpairedForgetsAndByeVersionIsFatal() {
        val h = SessionHarness(MemoryStore(current = Pairing(MAC_ID, "Mac", token)))
        h.session.start(); h.drain()
        h.mac(Control.Bye("unpaired"))
        assertNull(h.store.current)
        assertEquals(true, h.closed?.second)

        val v = SessionHarness()
        v.session.start(); v.mac(Control.Bye("version"))
        assertEquals(true, v.closed?.second)

        val off = SessionHarness()
        off.session.start(); off.mac(Control.Bye("off"))
        assertEquals(false, off.closed?.second) // link turned off on the Mac: keep trying
        h.cleanup(); v.cleanup(); off.cleanup()
    }

    @Test fun welcomeWithAnotherVersionIsRefused() {
        val h = SessionHarness()
        h.session.start(); h.drain()
        h.mac(Control.Welcome(1, "Mac", MAC_ID, 1))
        assertEquals(true, h.closed?.second)
        assertFalse(h.feed.enabled)
        h.cleanup()
    }

    @Test fun forgetDeletesThePairing() {
        val store = MemoryStore(current = Pairing(MAC_ID, "Mac", token))
        store.forget()
        assertNull(store.pairing())
        assertEquals("0123456789abcdef0123456789abcdef", store.phoneId()) // the install's identity stays
    }

    @Test fun pingsEveryTwoSecondsAndPongsFeedTheRoundTrip() {
        val h = SessionHarness()
        h.session.start(); h.drain(); h.welcome(); h.drain()
        h.session.tick()
        assertTrue(h.controls().isEmpty())
        h.clock.now += 2_000
        h.session.tick()
        val ping = h.controls().single() as Control.Ping
        assertEquals(h.clock.now, ping.at)
        h.clock.now += 6
        h.mac(Control.Pong(ping.id, ping.at, 5_000))
        assertEquals(1, h.latency.roundTrip.size())
        assertTrue(h.latency.roundTrip.summary().contains("p50=6.0ms"))
        h.session.tick()
        assertTrue(h.controls().isEmpty())
        h.clock.now += 2_000
        h.session.tick()
        assertEquals(ping.id + 1, (h.controls().single() as Control.Ping).id)
        h.cleanup()
    }

    @Test fun macPingsAreAnsweredWithTheEchoedTimeAndOurClock() {
        val h = SessionHarness()
        h.session.start(); h.drain()
        h.clock.now = 5_555
        h.mac(Control.Ping(8, 123))
        assertEquals(Control.Pong(8, 123, 5_555), h.controls().single())
        h.cleanup()
    }

    @Test fun welcomeSendsManifestViewingAndPadState() {
        val h = SessionHarness()
        h.source.notes += NoteMeta("n1", 3, "One")
        h.session.adopt("n1", true, "n1")
        h.session.start(); h.drain()
        h.welcome()
        val sent = h.controls()
        assertEquals(Control.Notes(listOf(NoteMeta("n1", 3, "One"))), sent[0])
        assertEquals(Control.Viewing("n1"), sent[1])
        assertEquals(Control.Pad(true), sent[2])
        h.session.setViewing(null)
        h.session.setPad(false)
        assertEquals(listOf(Control.Viewing(null), Control.Pad(false)), h.controls())
        h.cleanup()
    }

    @Test fun targetIsForwardedOnlyAfterWelcome() {
        val h = SessionHarness()
        h.session.start(); h.drain()
        h.mac(Control.Target("n", "s", true, 1))
        assertTrue(h.targets.isEmpty())
        h.welcome()
        h.mac(Control.Target("n", "s", true, 2))
        h.mac(Control.Target(null, null, false, 0))
        assertEquals(listOf(StreamTarget("n", "s", true, 2), StreamTarget()), h.targets)
        h.cleanup()
    }

    @Test fun messagesThatOnlyThePhoneSendsAreIgnoredFromTheMac() {
        val h = SessionHarness()
        h.session.start(); h.drain(); h.welcome(); h.drain()
        for (c in listOf(Control.Hello("x", "y", null), Control.Code("1234"), Control.Pad(true), Control.Viewing("a"),
            Control.Notes(emptyList()), Control.File(1, "a", 1, 1, "x", 1), Control.FileEnd(1))) h.mac(c)
        assertTrue(h.controls().isEmpty())
        assertEquals(Phase.CONNECTED, h.session.phase)
        h.cleanup()
    }

    @Test fun syncMessagesBeforeWelcomeAreIgnored() {
        val h = SessionHarness()
        h.source.notes += NoteMeta("n1", 3, "One")
        h.session.start(); h.drain()
        h.mac(Control.Want(listOf("n1")))
        h.mac(Control.Have("n1", 3))
        assertTrue(h.controls().isEmpty())
        h.cleanup()
    }
}
