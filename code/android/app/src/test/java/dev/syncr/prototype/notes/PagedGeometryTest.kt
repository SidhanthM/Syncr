package dev.syncr.prototype.notes

import dev.syncr.core.EntityHead
import dev.syncr.core.EventCursor
import dev.syncr.core.NoteKind
import dev.syncr.core.NoteSnapshot
import dev.syncr.core.PageExtent
import dev.syncr.core.PageMode
import dev.syncr.core.PagePattern
import dev.syncr.core.PageRect
import dev.syncr.core.PageView
import dev.syncr.core.ResolvedPageStyle
import dev.syncr.core.Rgba
import dev.syncr.core.SheetView
import org.junit.Assert.*
import org.junit.Test

class PagedGeometryTest {
    private val style = ResolvedPageStyle(PagePattern.NONE, null, Rgba(150u, 150u, 150u, 128u), 82.6f)
    private fun page(id: String, w: Float, h: Float, auto: Boolean = false, dead: Boolean = false) =
        PageView(id, w, h, auto, dead, EntityHead("h-$id", null), null, EntityHead("s-$id", null), style)
    private fun note(pages: List<PageView>, sheets: List<String> = pages.map { it.sheetId }, kind: NoteKind = NoteKind.NOTE) = NoteSnapshot(
        "note", "T", null, EntityHead("c", null), EntityHead("c", null), sheets.map { SheetView(it, "c-$it", 0f, 0f) }, emptyList(), 1u,
        EventCursor("s", 1u), null, null, emptyList(), emptyList(), pages = pages, kind = kind)

    /** A stand-in for the core's layout: rows of one, stacked with the gap, left edges at 0. */
    private val stacked: (List<PageExtent>, PageMode, Float) -> List<PageRect> = { pages, _, gap ->
        var y = 0f
        pages.mapIndexed { i, p -> PageRect(p.sheetId, i.toUInt(), 0f, y, p.width, p.height).also { y += p.height + gap } }
    }

    private fun a4(i: Int) = PageSpot("p$i", i, i, 0f, i * (2262.9f + 20.6f), 1600f, 2262.9f, false, page("p$i", 1600f, 2262.9f))

    @Test fun legacyAndCanvasNotesHaveNoSpots() {
        assertTrue(PageSpots.of(note(listOf(page("a", 4800f, 3000f, auto = true))), PageViewSettings(), stacked).isEmpty())
        assertTrue(PageSpots.of(note(emptyList()), PageViewSettings(), stacked).isEmpty())
        assertTrue(PageSpots.of(note(emptyList(), listOf("a"), NoteKind.CANVAS), PageViewSettings(), stacked).isEmpty())
        assertTrue(PageSpots.isLegacy(note(listOf(page("a", 4800f, 3000f, auto = true), page("b", 1600f, 2000f, dead = true)))))
    }

    @Test fun aLegacyPageIsPlacedAsItsPhoneColumnInSingleModeButItsPaperKeepsItsWidth() {
        var seen = emptyList<PageExtent>()
        val spy: (List<PageExtent>, PageMode, Float) -> List<PageRect> = { p, m, g -> seen = p; stacked(p, m, g) }
        val spots = PageSpots.of(note(listOf(page("a", 4800f, 3100f, auto = true), page("b", 1600f, 2262.9f))), PageViewSettings(), spy)
        assertEquals(1600f, seen[0].width, 0f)
        assertEquals(4800f, spots[0].width, 0f)
        assertEquals(CanvasRect(0f, 0f, 4800f, 3100f), spots[0].rect)
        // In Double mode the real width is used so the pair does not overlap.
        PageSpots.of(note(listOf(page("a", 4800f, 3100f, auto = true), page("b", 1600f, 2262.9f))), PageViewSettings(PageArrangement.DOUBLE), spy)
        assertEquals(4800f, seen[0].width, 0f)
    }

    @Test fun deletedPagesAreLeftOutAndTheStripPutsRowsSideBySide() {
        val n = note(listOf(page("a", 1600f, 2000f), page("dead", 1600f, 2000f, dead = true), page("b", 1000f, 1500f)))
        val rows = PageSpots.of(n, PageViewSettings(), stacked)
        assertEquals(listOf("a", "b"), rows.map { it.sheetId })
        val strip = PageSpots.of(n, PageViewSettings(flip = true), stacked)
        assertEquals(listOf(0f, 0f), strip.map { it.y })
        assertEquals(0f, strip[0].x, 0f)
        assertEquals(1600f + PageSpots.FLIP_GAP, strip[1].x, 0.01f)
        assertEquals(2, PageSpots.rows(strip).size)
    }

    @Test fun aSinglePageNarrowerThanTheViewIsCentredAndPanLocksAcrossIt() {
        val g = PagedGeometry(0f, listOf(a4(0)))
        val scale = g.fitScale(1080, 2340)
        assertEquals(1080f / (1600f + 2 * Xn.SIDE_MARGIN), scale, 1e-4f)
        val m = g.clamp(SheetMapping(scale / 2, -5000f, 5000f), 1080, 2340)
        assertEquals((1080f - 1600f * m.scale) / 2, m.tx, 0.01f) // half-size page: centred
        assertEquals((2340f - 2262.9f * m.scale) / 2, m.ty, 0.01f)
    }

    @Test fun aTallDocumentScrollsFromItsTopMarginToItsBottomMargin() {
        val g = PagedGeometry(0f, listOf(a4(0), a4(1), a4(2)))
        val bottom = 2262.9f * 3 + 20.6f * 2
        val top = g.clamp(SheetMapping(0.65f, 0f, 1e6f), 1080, 2340)
        assertEquals(Xn.VERTICAL_MARGIN * 0.65f, top.ty, 0.01f)
        val end = g.clamp(SheetMapping(0.65f, 0f, -1e9f), 1080, 2340)
        assertEquals(2340f - (bottom + Xn.VERTICAL_MARGIN) * 0.65f, end.ty, 0.01f)
        val b = g.flingBounds(end, 1080, 2340)
        assertEquals(end.ty.toInt(), b.minY); assertEquals((Xn.VERTICAL_MARGIN * 0.65f).toInt(), b.maxY)
    }

    @Test fun zoomIsLimitedToTwelvePercentThroughSixteenHundred() {
        val g = PagedGeometry(0f, listOf(a4(0)))
        val range = g.scaleRange(0.7f)
        assertEquals(0.12f / Xn.UNITS, range.start, 1e-6f)
        assertEquals(16f / Xn.UNITS, range.endInclusive, 1e-6f)
        assertEquals(range.endInclusive, g.clamp(SheetMapping(100f, 0f, 0f), 1080, 2340).scale, 1e-6f)
        assertEquals(16f, Xn.zoomOf(range.endInclusive), 1e-4f)
    }

    @Test fun inkIsClippedToThePageUnderThePenAndTheDeskTakesNoInk() {
        val g = PagedGeometry(0f, listOf(a4(0), a4(1)))
        assertEquals(CanvasRect(0f, 0f, 1600f, 2262.9f), g.inkClip(10f, 10f))
        assertEquals(a4(1).rect, g.inkClip(10f, 2262.9f + 20.6f + 5f))
        assertNull(g.inkClip(1700f, 10f)); assertFalse(g.contains(1700f, 10f)); assertFalse(g.contains(10f, 2270f))
        assertTrue(g.contains(10f, 2300f))
        assertEquals("p1", g.pageAt(10f, 2300f)!!.sheetId)
    }

    @Test fun inPaginatedModeOnlyTheFocusedRowCanBePanned() {
        val strip = listOf(a4(0).copy(x = 0f, y = 0f), a4(1).copy(x = 2000f, y = 0f, row = 1))
        val whole = PagedGeometry(0f, strip)
        val first = PagedGeometry(0f, strip, focusRow = 0)
        val scale = 1.0f
        // Zoomed so a page is wider than the view: whole strip reaches the second page, the focused row does not.
        assertTrue(whole.clamp(SheetMapping(scale, -1e6f, 0f), 1080, 2340).tx < first.clamp(SheetMapping(scale, -1e6f, 0f), 1080, 2340).tx)
        assertEquals(1080f - (1600f + Xn.SIDE_MARGIN) * scale, first.clamp(SheetMapping(scale, -1e6f, 0f), 1080, 2340).tx, 0.01f)
    }

    @Test fun fitScaleUsesThe1600ColumnForALegacyPageAndTheWidestRowOtherwise() {
        val legacy = PageSpot("a", 0, 0, 0f, 0f, 4800f, 3100f, true, page("a", 4800f, 3100f, auto = true))
        val g = PagedGeometry(0f, listOf(legacy, a4(1).copy(y = 3120f)))
        assertEquals(1080f / (1600f + 2 * Xn.SIDE_MARGIN), g.fitScale(1080, 2340), 1e-4f)
        val wide = PagedGeometry(0f, listOf(a4(0), a4(1).copy(width = 3000f)))
        assertEquals(1080f / (3000f + 2 * Xn.SIDE_MARGIN), wide.fitScale(1080, 2340), 1e-4f)
    }

    @Test fun theCurrentPageIsTheOneMostInViewAndNeverNullWhenThereArePages() {
        val spots = listOf(a4(0), a4(1), a4(2))
        val m = SheetMapping(0.5f, 0f, -(2262.9f + 20.6f) * 0.5f + 200f)
        assertEquals("p1", PageNav.currentPage(spots, m, 1080, 2340)!!.sheetId)
        assertEquals("p2", PageNav.currentPage(spots, SheetMapping(0.1f, 0f, -1e6f), 1080, 2340)!!.sheetId) // nothing visible: nearest
        assertNull(PageNav.currentPage(emptyList(), m, 1080, 2340))
    }

    @Test fun fitWholeWidthAndHeightFollowXnotesFormula() {
        val p = a4(0)
        val w = 1080f / (1600f + 2 * Xn.SIDE_MARGIN); val h = 2200f / (2262.9f + 2 * Xn.VERTICAL_MARGIN)
        assertEquals(w, PageNav.fitScale(p, PageFit.WIDTH, 1080, 2200), 1e-5f)
        assertEquals(h, PageNav.fitScale(p, PageFit.HEIGHT, 1080, 2200), 1e-5f)
        assertEquals(minOf(w, h), PageNav.fitScale(p, PageFit.WHOLE, 1080, 2200), 1e-5f)
        val shown = PageNav.showing(p, w, 1080, 2200)
        assertEquals((1080f - 1600f * w) / 2, shown.tx, 0.01f) // narrower than the view: centred
        assertEquals((Xn.SIDE_MARGIN) * h, PageNav.showing(p, h, 1080, 2200).tx, 0.01f) // wider: its left edge a margin in
        assertEquals(PageNav.snapToFit(w * 1.04f, w), w, 0f); assertEquals(w * 1.06f, PageNav.snapToFit(w * 1.06f, w), 1e-6f)
    }

    @Test fun sheetsThatAreNotPagesSitInsidePageOne() {
        val n = note(listOf(page("a", 4800f, 3100f, auto = true), page("b", 1600f, 2262.9f)), sheets = listOf("a", "old", "b"))
        val spots = listOf(PageSpot("a", 0, 0, 7f, 9f, 4800f, 3100f, true, n.pages[0]), PageSpot("b", 1, 1, 0f, 3120f, 1600f, 2262.9f, false, n.pages[1]))
        val placed = PageNav.placements(n, spots)
        assertEquals(7f to 9f, placed["a"]); assertEquals(7f to 9f, placed["old"]); assertEquals(0f to 3120f, placed["b"])
        assertTrue(PageNav.placements(n, emptyList()).isEmpty())
        assertEquals("a", PageNav.textSheet(n, spots))
    }

    @Test fun placedInkIsConjugatedThroughThePageOffsetAndDeletedPagesHideTheirInk() {
        val s = CanvasFixtures.stroke("s1", sheet = "b")
        val n0 = CanvasFixtures.state(listOf(s), mapOf("s1" to CanvasFixtures.line(10f to 20f, 30f to 40f)),
            sheets = listOf(SheetView("a", "ca", 0f, 0f), SheetView("b", "cb", 0f, 0f)))
        val scene = CanvasFixtures.scene(n0).apply { placement = mapOf("a" to (0f to 0f), "b" to (100f to 3000f)) }
        assertEquals(110f to 3020f, transformedPoint(10f, 20f, scene.canvasTransformOf("s1", "b")))
        // A canvas-space move of +5,+7 becomes the same +5,+7 on the sheet, whatever the page offset.
        val local = localGesture(SheetView("b", "cb", 0f, 0f), translation(5.0, 7.0), 100f to 3000f)
        assertEquals(5.0, local.tx, 1e-9); assertEquals(7.0, local.ty, 1e-9)
        val dead = n0.copy(snapshot = n0.snapshot!!.copy(pages = listOf(page("a", 1600f, 2000f), page("b", 1600f, 2000f, dead = true))))
        val hidden = CanvasScene().apply { load(dead) }
        assertTrue(hidden.previews.isEmpty())
        assertNull(hidden.strokeAt(15f, 25f, 100f))
    }

    @Test fun aPairOfPagesFitsTheWidthTogetherAndAWideRowFillsItsOwnWidth() {
        val pair = listOf(a4(0).copy(y = 0f), a4(1).copy(x = 1600f + Xn.GAP, y = 0f, row = 0))
        val m = PageNav.fitRow(pair, 1080, 2340)
        assertEquals(1080f / (3200f + Xn.GAP + 2 * Xn.SIDE_MARGIN), m.scale, 1e-5f)
        assertEquals((1080f - (3200f + Xn.GAP) * m.scale) / 2, m.tx, 0.01f) // exactly the margins either side
        val single = PageNav.fitRow(listOf(a4(0)), 1080, 1000) // taller than the view: top margin
        assertEquals(Xn.VERTICAL_MARGIN * single.scale, single.ty, 0.01f)
    }
}
