package dev.syncr.prototype.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class DisplayTitleTest {
    private val zone = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 11, 17, 8, 0).toInstant(zone).toEpochMilli()
    private fun show(title: String, h24: Boolean = true) = displayTitle(title, now, h24, zone)

    @Test fun stampsReadLikeWords() {
        assertEquals("Today, 23:49", show("2026-11-17 23.49"))
        assertEquals("Yesterday, 09:12", show("2026-11-16 09.12"))
        assertEquals("Fri 13 Nov, 23:49", show("2026-11-13 23.49"))
        assertEquals("16 Nov 2025", show("2025-11-16 23.49"))
    }

    @Test fun followsTheTwelveHourSetting() {
        assertEquals("Today, 11:49 PM", show("2026-11-17 23.49", h24 = false))
        assertEquals("Yesterday, 9:12 AM", show("2026-11-16 09.12", h24 = false))
    }

    @Test fun otherTitlesAreLeftAlone() {
        for (t in listOf("Two sum", "2026-11-17 23.49 notes", "2026-11-17 23:49", "2026-13-17 23.49", "2026-11-17 24.10", "", " 2026-11-17 23.49"))
            assertEquals(t, show(t))
    }

    @Test fun aNewNotesDefaultTitleReadsAsToday() {
        assertEquals("Today, 08:00", show(defaultNoteTitle(now, zone)))
    }

    @Test fun friendlyTimeForTheMetaLine() {
        assertEquals("Today, 08:00", friendlyTime(now, now, true, zone))
        assertEquals("Mon 16 Nov, 23:49", friendlyTime(LocalDateTime.of(2026, 11, 16, 23, 49), LocalDateTime.of(2026, 11, 18, 1, 0), true))
    }
}
