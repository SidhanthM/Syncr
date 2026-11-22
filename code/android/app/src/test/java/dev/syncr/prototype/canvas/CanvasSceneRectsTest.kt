package dev.syncr.prototype.canvas

import dev.syncr.core.Affine
import dev.syncr.prototype.notes.CanvasFixtures
import dev.syncr.prototype.notes.CanvasFixtures.line
import dev.syncr.prototype.notes.CanvasFixtures.stroke
import dev.syncr.prototype.notes.ThumbStroke
import dev.syncr.prototype.notes.contentThumbnail
import dev.syncr.prototype.notes.brushWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasSceneRectsTest {
    @Test fun eachPreviewHasItsPlacedRectWithTheBrushReach() {
        val scene = CanvasFixtures.scene(CanvasFixtures.state(
            strokes = listOf(stroke("a"), stroke("b", transform = Affine(1.0, 0.0, 0.0, 1.0, 1000.0, -500.0))),
            samples = mapOf("a" to line(10f to 20f, 110f to 70f), "b" to line(0f to 0f, 50f to 50f)),
        ))
        val r = scene.inkRects(DoubleArray(0))
        val reach = brushWidth(CanvasFixtures.pen) / 2.0
        assertEquals(10.0 - reach, r[0], 1e-3); assertEquals(70.0 + reach, r[3], 1e-3)
        assertEquals(1000.0 - reach, r[4], 1e-3); assertEquals(-500.0 - reach, r[5], 1e-3); assertEquals(1050.0 + reach, r[6], 1e-3)
    }

    @Test fun revisionMovesWhenWhatIsDrawnMayHaveChanged() {
        val scene = CanvasFixtures.scene(CanvasFixtures.state())
        val before = scene.revision
        scene.handOffErase(setOf("x"))
        assertTrue(scene.revision > before)
    }

    @Test fun aCanvasCardShowsTheContentBoxCentredInTheCardsShape() {
        val ink = ThumbStroke(floatArrayOf(10_000f, 20_000f, 14_000f, 20_400f), CanvasFixtures.pen)
        val t = contentThumbnail(7u, listOf(ink))
        // Box about 4,000 x 400 (plus the brush reach): width-limited, so the frame is that wide and 5/4 as tall.
        assertEquals(t.width * 5f / 4f, t.viewHeight, 1e-3f)
        val xs = t.strokes.single().points.filterIndexed { i, _ -> i % 2 == 0 }
        val ys = t.strokes.single().points.filterIndexed { i, _ -> i % 2 == 1 }
        assertEquals(t.width / 2, (xs.min() + xs.max()) / 2, 1f)
        assertEquals(t.viewHeight / 2, (ys.min() + ys.max()) / 2, 1f)
        assertTrue(xs.min() > 0f && ys.min() > 0f && xs.max() < t.width)
    }

    @Test fun anEmptyCanvasKeepsTheLegacyFrame() {
        val t = contentThumbnail(1u, emptyList())
        assertTrue(t.strokes.isEmpty())
    }
}
