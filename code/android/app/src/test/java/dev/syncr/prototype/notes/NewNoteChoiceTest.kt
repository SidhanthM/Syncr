package dev.syncr.prototype.notes

import dev.syncr.core.PagePattern
import org.junit.Assert.*
import org.junit.Test

class NewNoteChoiceTest {
    @Test fun a4PortraitIsTheDefaultAndIsExactly1600Wide() {
        val layout = NewNoteChoice().layout()!!
        assertEquals(1600f, layout.width, 0.01f); assertEquals(2262.857f, layout.height, 0.05f)
        assertTrue(layout.plain)
    }
    @Test fun landscapeSwapsTheSidesOfANamedSize() {
        val layout = NewNoteChoice(size = "Letter", landscape = true).layout()!!
        assertEquals(279.4f * NewNoteSizes.UNITS_PER_MM, layout.width, 0.01f)
        assertEquals(215.9f * NewNoteSizes.UNITS_PER_MM, layout.height, 0.01f)
    }
    @Test fun customSidesAreRangeCheckedWithTheContractsMessage() {
        assertEquals("Each side between 10 and 2000 mm", NewNoteChoice(size = "Custom", customWidth = "9.9").problem())
        assertEquals("Each side between 10 and 2000 mm", NewNoteChoice(size = "Custom", customHeight = "2000.5").problem())
        assertEquals("Each side between 10 and 2000 mm", NewNoteChoice(size = "Custom", customWidth = "abc").problem())
        assertNull(NewNoteChoice(size = "Custom", customWidth = "10", customHeight = "2000").problem())
        assertNull(NewNoteChoice(size = "Custom", customWidth = "5").layout())
    }
    @Test fun orientationOnACustomSizeSwapsItsFields() {
        val landscape = NewNoteChoice(size = "Custom", customWidth = "100", customHeight = "200").withOrientation(true)
        assertEquals("200" to "100", landscape.customWidth to landscape.customHeight)
        assertEquals(landscape, landscape.withOrientation(true))
        assertEquals("100" to "200", landscape.withOrientation(false).let { it.customWidth to it.customHeight })
    }
    @Test fun aTemplateCarriesItsColourAndOpacityButNoneCarriesNoPattern() {
        val grid = NewNoteChoice(template = PagePattern.GRID, paper = 0xfbf6e9, patternColour = 0x3366cc, opacity = 0.25f).layout()!!
        assertEquals(PagePattern.GRID, grid.template)
        assertEquals(0xfbu, grid.paper!!.r.toUInt()); assertEquals(255u, grid.paper!!.a.toUInt())
        assertEquals(0x33u, grid.patternColour!!.r.toUInt()); assertEquals(64u, grid.patternColour!!.a.toUInt())
        assertNull(NewNoteChoice(template = PagePattern.NONE, patternColour = 0x3366cc).layout()!!.patternColour)
        assertFalse(grid.plain)
    }
    @Test fun choicesRoundTripAndBadStoredTextFallsBackToDefaults() {
        val c = NewNoteChoice("Custom", true, "120.5", "80", PagePattern.DOTS, 0xffffff, null, 0.8f)
        assertEquals(c, NewNoteChoiceCodec.decode(NewNoteChoiceCodec.encode(c)))
        assertEquals(NewNoteChoice(), NewNoteChoiceCodec.decode("junk"))
        assertEquals(NewNoteChoice(), NewNoteChoiceCodec.decode(null))
        assertEquals("A4", NewNoteChoiceCodec.decode("Huge|false|1|2|GRID|||0.5").size)
    }
    @Test fun nextUntitledSkipsTakenNames() {
        assertEquals("Untitled canvas", nextUntitled(listOf("Other")))
        assertEquals("Untitled canvas 2", nextUntitled(listOf("untitled canvas")))
        assertEquals("Untitled canvas 3", nextUntitled(listOf("Untitled canvas", "Untitled canvas 2")))
    }
    @Test fun viewSettingsRoundTripAndForgiveJunk() {
        val s = PageViewSettings(PageArrangement.COVER, flip = true)
        assertEquals(s, NoteViewStore.decode(NoteViewStore.encode(s)))
        assertEquals(PageViewSettings(), NoteViewStore.decode("nonsense"))
        assertEquals(PageViewSettings(), NoteViewStore.decode(null))
    }
}
