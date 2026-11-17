package dev.syncr.prototype.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingThrottleTest {
    @Test fun theFirstKeystrokeAfterAQuietSpellIsStoredAtOnce() {
        assertEquals(0L, TypingThrottle.delayMs(now = 10_000, dirtySince = 10_000, lastStore = 0))
        assertEquals(0L, TypingThrottle.delayMs(now = 10_000, dirtySince = 10_000, lastStore = 9_000))
    }

    @Test fun storesAreAtLeast150MsApartWhileTypingGoesOn() {
        assertEquals(100L, TypingThrottle.delayMs(now = 10_050, dirtySince = 10_050, lastStore = 10_000))
        assertEquals(150L, TypingThrottle.delayMs(now = 10_000, dirtySince = 10_000, lastStore = 10_000))
    }

    @Test fun noUnsavedKeystrokeWaitsMoreThan250Ms() {
        assertEquals(50L, TypingThrottle.delayMs(now = 10_200, dirtySince = 10_000, lastStore = 10_190))
        assertEquals(0L, TypingThrottle.delayMs(now = 10_500, dirtySince = 10_000, lastStore = 10_400))
    }

    @Test fun aKeystrokeDoesNotPushTheDeadlineBack() {
        // Typing a key every 40 ms for two seconds, millisecond by millisecond: stores come every ~150 ms, none 250 apart or more.
        var last = 0L; var since = 0L; var deadline = -1L
        val stores = ArrayList<Long>()
        for (t in 1_000L until 3_000L) {
            if ((t - 1_000) % 40 == 0L) {
                if (since == 0L) since = t
                if (deadline < 0) deadline = t + TypingThrottle.delayMs(t, since, last)
            }
            if (deadline in 0..t) { stores += t; last = t; since = 0; deadline = -1 }
        }
        assertTrue("stores: $stores", stores.size >= 10)
        assertTrue("gaps: $stores", stores.drop(1).zipWithNext().all { (a, b) -> b - a in 150L..250L })
    }
}
