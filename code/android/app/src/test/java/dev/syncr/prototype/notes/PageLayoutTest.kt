package dev.syncr.prototype.notes

import dev.syncr.core.Affine
import dev.syncr.core.SheetView
import org.junit.Assert.*
import org.junit.Test

class PageLayoutTest {
    @Test fun continuousCanvasHasNoGapsOrBottomBoundary() {
        val canvas = CanvasLayout()
        assertTrue(canvas.contains(0f, 0f)); assertTrue(canvas.contains(4800f, 1e7f))
        assertTrue(canvas.contains(1700f, 3201f)); assertTrue(canvas.contains(4700f, 6401f))
        assertFalse(canvas.contains(-1f, 0f)); assertFalse(canvas.contains(4801f, 10f)); assertFalse(canvas.contains(1f, -1f))
    }
    @Test fun portraitShowsLeftColumnAndLandscapeKeepsScaleAndStartsLeft() {
        val canvas = CanvasLayout(10000f)
        val portrait = Viewport.defaultView(canvas, 1080, 2340)
        assertEquals(1080f / 1600f, portrait.scale, 1e-6f)
        assertEquals(0f, portrait.tx); assertEquals(0f, portrait.ty)
        assertEquals(1600f, portrait.toSheet(1080f, 0f).first, .001f)
        val landscape = Viewport.resized(canvas, portrait.copy(tx = -1000f, ty = -2000f), 2340, 1080)
        assertEquals(portrait.scale, landscape.scale); assertEquals(0f, landscape.tx)
        assertEquals(3466.667f, landscape.toSheet(2340f, 0f).first, .01f)
        assertEquals(-2000f, landscape.ty)
        assertEquals(portrait.scale, Viewport.defaultView(canvas, 2340, 1080).scale)
    }
    @Test fun panClampsToWidthAndLowestInkPlusOneCurrentScreen() {
        val canvas = CanvasLayout(9000f)
        val clamped = Viewport.clamp(SheetMapping(.5f, -1e7f, -1e7f), canvas, 1080, 2000)
        assertEquals(1080f - 4800f * .5f, clamped.tx)
        assertEquals(-9000f * .5f, clamped.ty)
        assertEquals(9000f + 2000f / .5f, clamped.toSheet(0f, 2000f).second)
        val start = Viewport.clamp(SheetMapping(.5f, 1000f, 1000f), canvas, 1080, 2000)
        assertEquals(0f, start.tx); assertEquals(0f, start.ty)
        val empty = Viewport.clamp(SheetMapping(.5f, -900f, -1000f), CanvasLayout(), 1080, 2000)
        assertEquals(0f, empty.ty, 0f)
        val zoomedOut = Viewport.clamp(SheetMapping(.01f, -100f, 0f), canvas, 1080, 2000)
        assertEquals(1080f / 4800f, zoomedOut.scale); assertEquals(0f, zoomedOut.tx)
    }
    @Test fun fitReturnsToLeftColumnWithoutChangingTopCanvasRow() {
        val fit = Viewport.fitColumn(CanvasLayout(10000f), SheetMapping(1f, -300f, -2000f), 1080, 2340)
        assertEquals(1080f / 1600f, fit.scale, 1e-6f); assertEquals(0f, fit.tx)
        assertEquals(2000f, fit.toSheet(0f, 0f).second, .001f)
    }
    @Test fun legacyOriginIsAppliedAfterTransformNotScaledByIt() {
        val sheet = SheetView("old", "create", 0f, 3200f)
        val local = Affine(2.0, 0.0, 0.0, 2.0, 10.0, 20.0)
        assertEquals(210f to 3620f, transformedPoint(100f, 200f, canvasTransform(sheet, local)))
        // New canvas ink on the first sheet has no origin offset, even below old sheets.
        assertEquals(3400f to 10000f, transformedPoint(3400f, 10000f,
            canvasTransform(SheetView("first", "c", 0f, 0f), IDENTITY)))
    }
    @Test fun canvasSelectionResizeIsConjugatedForEachLegacySheet() {
        val sheet = SheetView("old", "create", 0f, 3200f)
        val gesture = scaleAbout(2.0, 100.0, 3300.0)
        val local = localGesture(sheet, gesture)
        val expected = transformedPoint(100f, 3400f, gesture)
        val actual = transformedPoint(100f, 200f, canvasTransform(sheet, local))
        assertEquals(expected, actual)
    }
    @Test fun pinchSnapsToColumnAndActualSizeOnlyWhenClose() {
        assertEquals(.65f, Viewport.snapScale(.68f, .65f), 0f)
        assertEquals(1f, Viewport.snapScale(1.05f, .65f), 0f)
        assertEquals(1.5f, Viewport.snapScale(1.5f, .65f), 0f)
        assertTrue(Viewport.isFit(.65f, .65f)); assertFalse(Viewport.isFit(.7f, .65f))
    }
}
