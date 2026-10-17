package dev.syncr.prototype.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

class NoteDatesTest {
    private val utc = ZoneOffset.UTC

    @Test fun decodesUlidTimestamp() {
        // Reference ULID from the ULID spec README.
        assertEquals(1469922850259L, ulidMillis("01ARZ3NDEKTSV4RRFFQ69G5FAV"))
        // 2026-11-11T14:32:00Z encoded by hand: 01M75GBNR0.
        assertEquals(1794407520000L, ulidMillis("01M75GBNR0" + "0".repeat(16)))
        assertEquals(0L, ulidMillis("0".repeat(26)))
        assertEquals(0xFFFF_FFFF_FFFFL, ulidMillis("7ZZZZZZZZZ" + "0".repeat(16)))
    }

    @Test fun acceptsLowercase() {
        assertEquals(1469922850259L, ulidMillis("01arz3ndektsv4rrffq69g5fav"))
    }

    @Test fun rejectsNonUlids() {
        assertNull(ulidMillis(""))
        assertNull(ulidMillis("note"))
        assertNull(ulidMillis("01ARZ3NDEKTSV4RRFFQ69G5FA")) // 25 chars
        assertNull(ulidMillis("01ARZ3NDEUTSV4RRFFQ69G5FAV")) // U is not Crockford
        assertNull(ulidMillis("01ARZ3NDEITSV4RRFFQ69G5FAV")) // I is excluded
        assertNull(ulidMillis("8ZZZZZZZZZ" + "0".repeat(16))) // beyond 48 bits
    }

    @Test fun formatsDisplayDateAndDefaultTitle() {
        assertEquals("2026.11.11", displayDate(1794407520000L, utc))
        assertEquals("2026-11-11 14.32", defaultNoteTitle(1794407520000L, utc))
        // Zone-aware: the same instant is late evening in Tokyo.
        assertEquals("2026-11-11 23.32", defaultNoteTitle(1794407520000L, ZoneId.of("Asia/Tokyo")))
    }

    @Test fun relativeDatesMatchTheLibraryMock() {
        val now = 1794407520000L // 2026-11-11 14:32 UTC, a Wednesday
        val minute = 60_000L; val hour = 60 * minute; val day = 24 * hour
        assertEquals("Just now", relativeDate(now - 30_000, now, utc))
        assertEquals("Just now", relativeDate(now + hour, now, utc)) // clock moved back
        assertEquals("1 minute ago", relativeDate(now - minute, now, utc))
        assertEquals("59 minutes ago", relativeDate(now - 59 * minute, now, utc))
        assertEquals("2 hours ago", relativeDate(now - 2 * hour, now, utc))
        assertEquals("Today", relativeDate(now - 7 * hour, now, utc))
        assertEquals("Yesterday", relativeDate(now - day, now, utc))
        assertEquals("Monday", relativeDate(now - 2 * day, now, utc))
        assertEquals("Last week", relativeDate(now - 8 * day, now, utc))
        assertEquals("Oct 1", relativeDate(now - 41 * day, now, utc))
        assertEquals("Nov 11, 2025", relativeDate(now - 365 * day, now, utc))
    }

    @Test fun deletedCaptionCountsWholeDaysAndTimeLeft() {
        val day = 86_400_000L; val now = 100 * day
        assertEquals("Deleted today · removed in 30 days", deletedCaption(now - 1000, now))
        assertEquals("Deleted today · removed in 30 days", deletedCaption(now + day, now)) // clock moved back
        assertEquals("Deleted 1 day ago · removed in 29 days", deletedCaption(now - day - 1, now))
        assertEquals("Deleted 12 days ago · removed in 18 days", deletedCaption(now - 12 * day, now))
        assertEquals("Deleted 29 days ago · removed tomorrow", deletedCaption(now - 29 * day, now))
        assertEquals("Deleted 31 days ago · removed on next launch", deletedCaption(now - 31 * day, now))
    }
}
