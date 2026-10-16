package dev.syncr.prototype.notes

import org.junit.Assert.*
import org.junit.Test

class PageLayoutTest {
    private val phone = PageLayout(1600f, 3200f, 3, gap = 50f)

    @Test fun pagesStackVerticallyAndGapsBelongToNoPage() {
        assertEquals(0f, phone.top(0)); assertEquals(3250f, phone.top(1)); assertEquals(6500f, phone.top(2))
        assertEquals(3 * 3200f + 2 * 50f, phone.height)
        assertEquals(0, phone.pageAt(800f, 10f)); assertEquals(0, phone.pageAt(0f, 3200f))
        assertNull(phone.pageAt(800f, 3225f)) // gap
        assertEquals(1, phone.pageAt(800f, 3250f)); assertEquals(2, phone.pageAt(1600f, 9700f))
        assertNull(phone.pageAt(800f, 9701f)); assertNull(phone.pageAt(-1f, 10f)); assertNull(phone.pageAt(1601f, 10f))
        assertNull(phone.pageAt(800f, -1f)); assertNull(PageLayout(1600f, 1000f, 0).pageAt(1f, 1f))
    }
    @Test fun pageMappingStoresSamplesInThatPagesOwnUnits() {
        val view = SheetMapping(0.5f, 20f, 100f)
        val second = phone.pageMapping(view, 1)
        // A screen point on page 2 maps to page-2 units starting at 0, not document units.
        val (x, y) = second.toSheet(20f + 800f * 0.5f, 100f + (3250f + 400f) * 0.5f)
        assertEquals(800f, x, .001f); assertEquals(400f, y, .001f)
        val (dx, dy) = view.toSheet(20f + 800f * 0.5f, 100f + (3250f + 400f) * 0.5f)
        assertEquals(1, phone.pageAt(dx, dy))
    }
    @Test fun visibleAndCurrentPageFollowScroll() {
        val view = SheetMapping(0.25f, 0f, 0f) // page height 800px, gap 12.5px
        assertEquals(0..1, phone.visible(view, 1000))
        assertEquals(1, phone.current(view, 1000))
        val scrolled = view.copy(ty = -(3250f * 0.25f))
        assertEquals(1..2, phone.visible(scrolled, 1000)); assertEquals(2, phone.current(scrolled, 1000))
        assertEquals(3, phone.current(view.copy(ty = -100000f), 1000)) // past the end: nearest page
        assertTrue(phone.visible(view.copy(ty = 5000f), 1000).isEmpty())
    }
    @Test fun fitWidthUsesGuttersAndKeepsTheMiddleRow() {
        val fit = Viewport.fitWidth(phone, null, 1080, 2000, gutter = 20f, top = 50f)
        assertEquals(1040f / 1600f, fit.scale, 1e-6f); assertEquals(20f, fit.tx, 1e-3f); assertEquals(50f, fit.ty, 1e-3f)
        val zoomed = SheetMapping(1f, -300f, -2000f)
        val middleBefore = (1000f - zoomed.ty) / zoomed.scale
        val refit = Viewport.fitWidth(phone, zoomed, 1080, 2000, 20f, 50f)
        assertEquals(middleBefore, (1000f - refit.ty) / refit.scale, 0.5f)
    }
    @Test fun clampKeepsPagesReachableAndCentresNarrowPages() {
        val narrow = Viewport.clamp(SheetMapping(0.3f, 500f, 999f), phone, 1080, 2000, 20f, 50f, 100f)
        assertEquals((1080f - 1600f * 0.3f) / 2, narrow.tx, 1e-3f); assertEquals(50f, narrow.ty, 1e-3f)
        val bottom = Viewport.clamp(SheetMapping(1f, 0f, -1e7f), phone, 1080, 2000, 20f, 50f, 100f)
        assertEquals(2000f - phone.height - 100f, bottom.ty, 1e-2f)
        val wide = Viewport.clamp(SheetMapping(2f, 400f, 0f), phone, 1080, 2000, 20f, 50f, 100f)
        assertEquals(20f, wide.tx, 1e-3f)
    }
    @Test fun pinchSnapsToFitAndToActualSizeOnlyWhenClose() {
        assertEquals(0.65f, Viewport.snapScale(0.68f, 0.65f), 0f)
        assertEquals(1f, Viewport.snapScale(1.05f, 0.65f), 0f)
        assertEquals(1.5f, Viewport.snapScale(1.5f, 0.65f), 0f)
        assertTrue(Viewport.isFit(0.65f, 0.65f)); assertFalse(Viewport.isFit(0.7f, 0.65f))
    }
    @Test fun autoAddOnlyFromLowOnTheLastPageAndNeverWhilePending() {
        assertTrue(shouldAutoAddPage(2, 3, 2400f, 3200f, addPending = false))
        assertFalse(shouldAutoAddPage(2, 3, 2399f, 3200f, addPending = false))
        assertFalse(shouldAutoAddPage(1, 3, 3100f, 3200f, addPending = false))
        assertFalse(shouldAutoAddPage(2, 3, 3100f, 3200f, addPending = true))
        assertTrue(shouldAutoAddPage(0, 1, 800f, 1000f, addPending = false)) // 16:10 sheets too
        assertFalse(shouldAutoAddPage(-1, 1, 900f, 1000f, addPending = false)) // unknown page
    }
}
