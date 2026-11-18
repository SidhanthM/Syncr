package dev.syncr.prototype.notes

import dev.syncr.core.BrushSize
import dev.syncr.core.InkColour
import dev.syncr.core.Oklch
import dev.syncr.core.PenLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InkPreferencesTest {
    private val green = Oklch(0.62f, 0.19f, 143.5f)

    @Test fun everyToolStartsOnItsOwnDefaultInkAndSize() {
        val prefs = InkPreferences()
        InkTool.entries.forEach { assertEquals(BrushSize.MEDIUM, prefs.sizeFor(it)) }
        assertEquals(Ink.Named(InkColour.INK), prefs.inkFor(InkTool.FOUNTAIN))
        assertEquals(Ink.Named(InkColour.ORANGE), prefs.inkFor(InkTool.HIGHLIGHTER))
        assertEquals(emptyList<Oklch>(), prefs.recentInks)
    }

    @Test fun eachToolRemembersItsOwnInkAndSize() {
        val prefs = InkPreferences()
            .withInk(InkTool.FOUNTAIN, Ink.Custom(green)).withSize(InkTool.FOUNTAIN, BrushSize.LARGE)
            .withInk(InkTool.BALLPOINT, Ink.Named(InkColour.BLUE))
        assertEquals(Ink.Custom(green), prefs.inkFor(InkTool.FOUNTAIN))
        assertEquals(BrushSize.LARGE, prefs.sizeFor(InkTool.FOUNTAIN))
        assertEquals(Ink.Named(InkColour.BLUE), prefs.inkFor(InkTool.BALLPOINT))
        assertEquals(Ink.Named(InkColour.INK), prefs.inkFor(InkTool.FINELINER)) // untouched
        assertEquals(BrushSize.MEDIUM, prefs.sizeFor(InkTool.BALLPOINT))
    }

    @Test fun recentInksAreNewestFirstDedupedAndCappedAtEight() {
        var prefs = InkPreferences()
        (0 until 12).forEach { prefs = prefs.withInk(InkTool.FOUNTAIN, Ink.Custom(Oklch(0.5f, 0.1f, it * 20f))) }
        assertEquals(InkPreferences.MAX_RECENT_INKS, prefs.recentInks.size)
        assertEquals(Oklch(0.5f, 0.1f, 220f), prefs.recentInks.first())
        prefs = prefs.withInk(InkTool.BALLPOINT, Ink.Custom(Oklch(0.5f, 0.1f, 160f))) // reuse moves it to the front
        assertEquals(Oklch(0.5f, 0.1f, 160f), prefs.recentInks.first())
        assertEquals(8, prefs.recentInks.size)
        assertEquals(8, prefs.recentInks.distinct().size)
        // Named inks never enter the list.
        assertEquals(prefs.recentInks, prefs.withInk(InkTool.FOUNTAIN, Ink.Named(InkColour.RED)).recentInks)
    }

    @Test fun inksSurviveTheirPersistenceText() {
        for (ink in listOf(Ink.Named(InkColour.GREEN), Ink.Custom(green), Ink.Custom(Oklch(0f, 0f, 0f)), Ink.Custom(Oklch(1f, 0.37f, 360f)))) {
            assertEquals(ink, InkCodec.decodeInk(InkCodec.encode(ink)))
        }
        val recents = listOf(green, Oklch(0.2f, 0.05f, 10f))
        assertEquals(recents, InkCodec.decodeRecents(InkCodec.encodeRecents(recents)))
    }

    @Test fun damagedPersistenceFallsBackInsteadOfCrashing() {
        for (bad in listOf(null, "", "named:PURPLE", "oklch:1,2", "oklch:2,0.1,10", "oklch:0.5,0.4,10", "oklch:NaN,0.1,10", "oklch:0.5,0.1,361", "x:y")) {
            assertNull(bad, InkCodec.decodeInk(bad))
        }
        assertEquals(listOf(green), InkCodec.decodeRecents("garbage;${InkCodec.encode(green)};0.5,0.9,1"))
        assertEquals(8, InkCodec.decodeRecents((0 until 20).joinToString(";") { "0.5,0.1,$it" }).size)
    }

    @Test fun theCurrentPenLookPicksTheTool() {
        assertEquals(InkTool.BALLPOINT, InkPreferences(penLook = PenLook.BALLPOINT).currentTool())
        assertEquals(InkTool.HIGHLIGHTER, InkPreferences(penLook = PenLook.BALLPOINT).currentTool(highlighter = true))
    }
}
