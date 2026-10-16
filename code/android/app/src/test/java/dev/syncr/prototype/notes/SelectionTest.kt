package dev.syncr.prototype.notes

import dev.syncr.core.Affine
import dev.syncr.core.SampleChannels
import org.junit.Assert.*
import org.junit.Test

class SelectionTest {
    private fun line(vararg points: Pair<Float, Float>) = SampleChannels(points.map { it.first }, points.map { it.second },
        points.map { 1f }, points.indices.map { it.toUInt() }, null, null)
    private val square = listOf(0f to 0f, 100f to 0f, 100f to 100f, 0f to 100f)
    private fun assertAffine(expected: Affine, actual: Affine) {
        listOf(expected.a to actual.a, expected.b to actual.b, expected.c to actual.c, expected.d to actual.d,
            expected.tx to actual.tx, expected.ty to actual.ty).forEach { (e, a) -> assertEquals(e, a, 1e-9) }
    }

    @Test fun pointInPolygonHandlesConcaveShapesAndDegenerateInput() {
        assertTrue(pointInPolygon(50f, 50f, square)); assertFalse(pointInPolygon(150f, 50f, square))
        val u = listOf(0f to 0f, 30f to 0f, 30f to 70f, 70f to 70f, 70f to 0f, 100f to 0f, 100f to 100f, 0f to 100f)
        assertFalse(pointInPolygon(50f, 30f, u)) // inside the U's notch
        assertTrue(pointInPolygon(15f, 30f, u)); assertTrue(pointInPolygon(50f, 85f, u))
        assertFalse(pointInPolygon(1f, 1f, listOf(0f to 0f, 10f to 10f)))
    }
    @Test fun lassoSelectsStrokesMostlyInsideAfterTheirTransform() {
        val half = line(10f to 50f, 90f to 50f, 110f to 50f, 190f to 50f) // 2 of 4 inside
        assertTrue(lassoSelects(half, IDENTITY, square))
        assertFalse(lassoSelects(line(10f to 50f, 110f to 50f, 150f to 50f), IDENTITY, square))
        val outside = line(200f to 50f, 250f to 50f)
        assertFalse(lassoSelects(outside, IDENTITY, square))
        assertTrue(lassoSelects(outside, translation(-180.0, 0.0), square)) // stored transform moves it inside
        assertFalse(lassoSelects(line(), IDENTITY, square))
    }
    @Test fun compositionAppliesInnerFirstAndInversionRoundTrips() {
        val inner = Affine(2.0, 0.0, 0.0, 3.0, 5.0, 7.0)
        val outer = translation(10.0, -4.0)
        val composed = compose(outer, inner)
        val (x, y) = transformedPoint(1f, 1f, composed)
        assertEquals(1f * 2 + 5 + 10, x, 1e-5f); assertEquals(1f * 3 + 7 - 4, y, 1e-5f)
        assertNotEquals(compose(inner, outer), composed) // order matters
        val rotation = Affine(0.0, 1.0, -1.0, 0.0, 3.0, 4.0)
        assertAffine(IDENTITY, compose(invert(rotation)!!, rotation))
        assertAffine(IDENTITY, compose(composed, invert(composed)!!))
        assertNull(invert(Affine(1.0, 2.0, 2.0, 4.0, 0.0, 0.0)))
        assertFalse(isValidTransform(scaleAbout(0.0, 1.0, 1.0)))
        assertFalse(isValidTransform(Affine(Double.NaN, 0.0, 0.0, 1.0, 0.0, 0.0)))
        assertTrue(isIdentity(compose(translation(1.0, 2.0), translation(-1.0, -2.0))))
    }
    @Test fun scaleAboutKeepsTheAnchorFixed() {
        val m = scaleAbout(2.5, 100.0, 50.0)
        assertEquals(100f to 50f, transformedPoint(100f, 50f, m))
        assertEquals(350f to 50f, transformedPoint(200f, 50f, m))
    }
    @Test fun handleDragScalesUniformlyAndNeverCollapsesOrFlips() {
        val anchor = 0f to 0f; val handle = 100f to 100f
        assertEquals(2f, handleScale(anchor, handle, 200f to 200f), 1e-5f)
        assertEquals(1.5f, handleScale(anchor, handle, 200f to 100f), 1e-5f) // projected on the diagonal
        assertEquals(0.1f, handleScale(anchor, handle, -50f to -50f), 1e-5f)
        assertEquals(1f, handleScale(anchor, anchor, 10f to 10f), 0f)
        val bounds = Bounds(0f, 0f, 100f, 50f)
        assertEquals(2, handleAt(bounds, 98f, 52f, 10f)); assertNull(handleAt(bounds, 50f, 25f, 10f))
        assertEquals(0 to 2, 0 to (0 + 2) % 4) // anchor of handle 0 is the opposite corner
        assertEquals(bounds.corners()[0], 0f to 0f); assertEquals(bounds.corners()[2], 100f to 50f)
    }
    @Test fun longPressOutlineHugsTheStrokeWithPadding() {
        val outline = roundedOutline(Bounds(10f, 20f, 110f, 40f), pad = 5f, radius = 4f)
        val b = Bounds.of(outline)!!
        assertEquals(5f, b.left, 1e-3f); assertEquals(15f, b.top, 1e-3f)
        assertEquals(115f, b.right, 1e-3f); assertEquals(45f, b.bottom, 1e-3f)
        assertTrue(pointInPolygon(60f, 30f, outline))
        val moved = transformPolygon(outline, translation(10.0, 0.0))
        assertEquals(b.left + 10f, Bounds.of(moved)!!.left, 1e-3f)
    }
}
