package dev.syncr.prototype.notes

import dev.syncr.core.Affine
import dev.syncr.core.SampleChannels
import org.junit.Assert.*
import org.junit.Test

class StrokeBoundsTest {
    private fun samples(vararg points: Pair<Float, Float>) = CanvasFixtures.line(*points)

    private fun view(l: Float, t: Float, r: Float, b: Float) = CullRect().apply { set(l, t, r, b) }
    private val identity = Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

    @Test fun boundsCoverSamplesPlusBrushPad() {
        val b = StrokeBounds.of(samples(10f to 20f, 30f to 5f, 25f to 40f), pad = 4f)!!
        assertEquals(6f, b.left, 0f); assertEquals(1f, b.top, 0f)
        assertEquals(34f, b.right, 0f); assertEquals(44f, b.bottom, 0f)
    }

    @Test fun emptyStrokeHasNoBounds() {
        assertNull(StrokeBounds.of(samples(), pad = 4f))
    }

    @Test fun strokeInsideOrTouchingTheViewIsVisible() {
        val b = Bounds(0f, 0f, 10f, 10f)
        assertTrue(StrokeBounds.intersects(b, identity, view(-5f, -5f, 5f, 5f)))
        assertTrue(StrokeBounds.intersects(b, identity, view(10f, 10f, 20f, 20f))) // edge contact
    }

    @Test fun strokeWhollyOutsideTheViewIsCulledOnEachSide() {
        val b = Bounds(0f, 0f, 10f, 10f)
        assertFalse(StrokeBounds.intersects(b, identity, view(11f, 0f, 20f, 10f)))
        assertFalse(StrokeBounds.intersects(b, identity, view(-20f, 0f, -1f, 10f)))
        assertFalse(StrokeBounds.intersects(b, identity, view(0f, 11f, 10f, 20f)))
        assertFalse(StrokeBounds.intersects(b, identity, view(0f, -20f, 10f, -1f)))
    }

    @Test fun transformMovesAndScalesTheBoundsBeforeTesting() {
        val b = Bounds(0f, 0f, 10f, 10f)
        val moved = Affine(2.0, 0.0, 0.0, 2.0, 100.0, 200.0) // lands at x 100..120, y 200..220
        assertTrue(StrokeBounds.intersects(b, moved, view(110f, 210f, 300f, 300f)))
        assertFalse(StrokeBounds.intersects(b, moved, view(0f, 0f, 99f, 300f)))
        assertFalse(StrokeBounds.intersects(b, moved, view(0f, 0f, 300f, 199f)))
    }

    @Test fun rotatedStrokeUsesItsTransformedExtent() {
        val b = Bounds(0f, 0f, 10f, 0f)
        val quarterTurn = Affine(0.0, 1.0, -1.0, 0.0, 0.0, 0.0) // x' = -y, y' = x: a vertical line down from the origin
        assertTrue(StrokeBounds.intersects(b, quarterTurn, view(-1f, 5f, 1f, 6f)))
        assertFalse(StrokeBounds.intersects(b, quarterTurn, view(5f, -1f, 6f, 1f)))
    }
}
