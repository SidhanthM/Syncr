package dev.syncr.prototype.notes

import dev.syncr.core.Brush
import dev.syncr.core.InkColour
import dev.syncr.core.SheetView
import dev.syncr.prototype.notes.CanvasFixtures.line
import dev.syncr.prototype.notes.CanvasFixtures.pen
import dev.syncr.prototype.notes.CanvasFixtures.scene
import dev.syncr.prototype.notes.CanvasFixtures.state
import dev.syncr.prototype.notes.CanvasFixtures.stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasSceneTest {
    private val red = pen.copy(colour = InkColour.RED)

    @Test fun previewsAreSavedStrokesWithStagedRestylesThenNewStagedStrokes() {
        val samples = line(0f to 0f, 10f to 10f)
        val staged = InkPreview("new", "sheet", samples, pen)
        val duplicate = InkPreview("b", "sheet", samples, pen)
        val scene = scene(
            state(
                strokes = listOf(stroke("a", tombstone = true), stroke("b"), stroke("no-samples")),
                samples = mapOf("a" to samples, "b" to samples),
                pending = listOf(
                    PendingEdit("r", null, restyles = mapOf("b" to red)),
                    PendingEdit("p", null, staged),
                    PendingEdit("d", null, duplicate),
                ),
            ),
        )
        assertEquals(listOf("b", "new"), scene.previews.map { it.id })
        assertEquals(red, scene.previews.first().brush)
        assertEquals("sheet", scene.firstSheet)
    }

    @Test fun strokeHandoffIsDrawnUntilTheStateContainsIt() {
        val handoff = InkPreview("new", "sheet", line(0f to 0f), pen)
        val scene = scene(state())
        scene.handOffStroke(handoff)
        scene.load(state())
        assertEquals(listOf(handoff), scene.strokeHandoffs)
        assertTrue(scene.hasStrokeHandoff("new"))
        scene.load(state(pending = listOf(PendingEdit("new", null, handoff))))
        assertTrue(scene.strokeHandoffs.isEmpty())
    }

    @Test fun eraseHandoffHidesUntilStagedThenTheStagedEditHidesUntilTombstoned() {
        val samples = mapOf("a" to line(0f to 0f))
        val scene = scene(state(listOf(stroke("a")), samples))
        scene.handOffErase(setOf("a"))
        scene.load(state(listOf(stroke("a")), samples))
        assertTrue(scene.isHidden("a"))
        scene.load(state(listOf(stroke("a")), samples, pending = listOf(PendingEdit("e", null, hidden = setOf("a")))))
        assertTrue(scene.isHidden("a"))
        // The handoff ended when the staged edit represented it, so an undone delete shows the stroke again.
        scene.load(state(listOf(stroke("a")), samples))
        assertFalse(scene.isHidden("a"))
        assertTrue(scene.isLive("a"))
        scene.load(state(listOf(stroke("a", tombstone = true)), samples))
        assertFalse(scene.isLive("a"))
    }

    @Test fun transformHandoffIsSheetLocalAndOverridesUntilTheStoredTransformMatches() {
        val legacy = SheetView("old", "creation", 0f, 3200f)
        val sheets = listOf(CanvasFixtures.firstSheet, legacy)
        val strokes = listOf(stroke("a", sheet = "old"))
        val scene = scene(state(strokes, sheets = sheets))
        val gesture = scaleAbout(2.0, 0.0, 3200.0)
        scene.handOffTransform(setOf("a"), gesture)
        val local = compose(localGesture(legacy, gesture), IDENTITY)
        assertEquals(local, scene.transformOf("a"))
        // Canvas placement is the sheet origin after the local transform: the anchor stays fixed.
        assertEquals(0f to 3200f, transformedPoint(0f, 0f, scene.canvasTransformOf("a", "old")))
        assertEquals(20f to 3220f, transformedPoint(10f, 10f, scene.canvasTransformOf("a", "old")))

        val other = translation(5.0, 5.0)
        scene.load(state(strokes, sheets = sheets, pending = listOf(PendingEdit("t", null, transforms = mapOf("a" to other)))))
        assertEquals(local, scene.transformOf("a"))
        scene.load(state(strokes, sheets = sheets, pending = listOf(PendingEdit("t", null, transforms = mapOf("a" to local)))))
        scene.load(state(strokes, sheets = sheets, pending = listOf(PendingEdit("t", null, transforms = mapOf("a" to other)))))
        assertEquals(other, scene.transformOf("a"))
    }

    @Test fun lowestInkCountsBrushRadiusTransformsAndHandoffsButNotHiddenInk() {
        assertEquals(0f, scene(state()).lowestInk(), 0f)
        val strokes = listOf(stroke("a"), stroke("hidden"))
        val samples = mapOf("a" to line(0f to 100f), "hidden" to line(0f to 900f))
        val pending = listOf(PendingEdit("e", null, hidden = setOf("hidden")))
        val scene = scene(state(strokes, samples, pending))
        assertEquals(102f, scene.lowestInk(), 1e-4f)
        scene.handOffStroke(InkPreview("new", "sheet", line(0f to 300f), pen))
        assertEquals(302f, scene.lowestInk(), 1e-4f)
        // A 2x transform doubles both the position and the brush radius.
        val scaled = scene(state(listOf(stroke("a", transform = scaleAbout(2.0, 0.0, 0.0))), samples))
        assertEquals(204f, scaled.lowestInk(), 1e-4f)
    }

    @Test fun strokeAtPicksTheTopmostVisibleStroke() {
        val samples = mapOf("a" to line(0f to 0f, 100f to 0f), "b" to line(50f to -50f, 50f to 50f))
        val scene = scene(state(listOf(stroke("a"), stroke("b")), samples))
        assertEquals("b", scene.strokeAt(50f, 0f, radius = 5f))
        assertEquals("a", scene.strokeAt(10f, 0f, radius = 5f))
        assertNull(scene.strokeAt(10f, 30f, radius = 5f))
        scene.handOffErase(setOf("b"))
        assertEquals("a", scene.strokeAt(50f, 0f, radius = 5f))
    }

    @Test fun lassoSelectsVisibleStrokesMostlyInsideAndNothingBelowThreePoints() {
        val samples = mapOf("in" to line(10f to 10f, 20f to 20f), "out" to line(500f to 500f), "gone" to line(10f to 10f))
        val strokes = listOf(stroke("in"), stroke("out"), stroke("gone", tombstone = true))
        val scene = scene(state(strokes, samples))
        val square = listOf(0f to 0f, 100f to 0f, 100f to 100f, 0f to 100f)
        assertEquals(setOf("in"), scene.strokesInLasso(square))
        assertTrue(scene.strokesInLasso(square.take(2)).isEmpty())
    }

    @Test fun eraserSweepHitsBetweenSparseSamplesAndSkipsExcludedAndDeletedStrokes() {
        val vertical = line(50f to 0f, 50f to 100f)
        val samples = mapOf("a" to vertical, "b" to vertical, "gone" to vertical)
        val scene = scene(state(listOf(stroke("a"), stroke("b"), stroke("gone", tombstone = true)), samples))
        assertEquals(listOf("a", "b"), scene.strokesSwept(0f to 50f, 100f to 50f, exclude = emptySet()))
        assertEquals(listOf("b"), scene.strokesSwept(0f to 50f, 100f to 50f, exclude = setOf("a")))
        // Reach is 12 units beyond the stroke's half-width (2 for a medium pen).
        assertEquals(listOf("a", "b"), scene.strokesSwept(36f to 50f, 36f to 60f, exclude = emptySet()))
        assertTrue(scene.strokesSwept(35f to 50f, 35f to 60f, exclude = emptySet()).isEmpty())
    }

    @Test fun clearingHandoffsForgetsEveryKind() {
        val strokes = listOf(stroke("a"))
        val scene = scene(state(strokes, mapOf("a" to line(0f to 0f))))
        scene.handOffStroke(InkPreview("new", "sheet", line(0f to 0f), Brush(pen.family, pen.size, pen.colour)))
        scene.handOffErase(setOf("a"))
        scene.handOffTransform(setOf("a"), translation(1.0, 1.0))
        scene.clearHandoffs()
        assertTrue(scene.strokeHandoffs.isEmpty())
        assertFalse(scene.isHidden("a"))
        assertEquals(IDENTITY, scene.transformOf("a"))
    }
}
