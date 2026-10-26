package dev.syncr.prototype.notes

import dev.syncr.core.InkColour
import dev.syncr.prototype.notes.CanvasFixtures.line
import dev.syncr.prototype.notes.CanvasFixtures.scene
import dev.syncr.prototype.notes.CanvasFixtures.state
import dev.syncr.prototype.notes.CanvasFixtures.stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasSelectionTest {
    private val square = listOf(0f to 0f, 100f to 0f, 100f to 100f, 0f to 100f)
    private val samples = mapOf("a" to line(20f to 20f, 80f to 80f), "b" to line(500f to 500f))
    private val strokes = listOf(stroke("a"), stroke("b"))

    /** Screen = canvas × 2: a 10 px slop is 5 canvas units. */
    private val mapping = SheetMapping(2f, 0f, 0f)

    private class Harness(state: NotesState, var accept: Boolean = true) {
        val scene = scene(state)
        val marks = mutableListOf<MarkEdit>()
        val counts = mutableListOf<Int>()
        val selection = CanvasSelection(scene, density = 1f, slop = 10f, onMarks = { marks += it; accept }, onCount = { counts += it })
    }

    private fun selected(accept: Boolean = true) = Harness(state(strokes, samples), accept).apply { selection.selectLasso(square) }

    @Test fun lassoSelectsAndReportsTheCountAndAnEmptyLassoClears() {
        val harness = selected()
        assertEquals(setOf("a"), harness.selection.ids)
        harness.selection.selectLasso(listOf(300f to 300f, 400f to 300f, 400f to 400f))
        assertTrue(harness.selection.ids.isEmpty())
        assertEquals(listOf(1, 0), harness.counts)
        harness.selection.clear() // nothing selected: no second report
        assertEquals(listOf(1, 0), harness.counts)
    }

    @Test fun aDragWithinSlopCommitsNothing() {
        val harness = selected()
        assertTrue(harness.selection.beginGesture(100f, 100f, mapping))
        assertEquals(IDENTITY, harness.selection.gesture)
        harness.selection.updateGesture(108f, 100f)
        harness.selection.commitGesture()
        assertTrue(harness.marks.isEmpty())
        assertNull(harness.selection.gesture)
    }

    @Test fun aMoveIsOneTransformInCanvasUnitsHandedOffUntilTheStateHasIt() {
        val harness = selected()
        assertFalse(harness.selection.beginGesture(1000f, 1000f, mapping)) // outside the selection
        assertTrue(harness.selection.beginGesture(100f, 100f, mapping))
        harness.selection.updateGesture(200f, 160f)
        assertEquals(translation(50.0, 30.0), harness.selection.gesture)
        val (outline, outlineMapping) = harness.selection.outline(SheetMapping(1f, 0f, 0f))!!
        assertEquals(mapping, outlineMapping) // drawn with the mapping frozen when the gesture began
        assertEquals(50f to 30f, outline.first())
        harness.selection.commitGesture()
        assertEquals(listOf<MarkEdit>(MarkEdit.Transform(setOf("a"), translation(50.0, 30.0))), harness.marks)
        assertEquals(translation(50.0, 30.0), harness.scene.transformOf("a"))
        assertEquals(50f to 30f, harness.selection.outline(mapping)!!.first.first())
    }

    @Test fun aRefusedMoveSnapsBack() {
        val harness = selected(accept = false)
        harness.selection.beginGesture(100f, 100f, mapping)
        harness.selection.updateGesture(200f, 160f)
        harness.selection.commitGesture()
        assertEquals(1, harness.marks.size)
        assertEquals(IDENTITY, harness.scene.transformOf("a"))
        assertEquals(square, harness.selection.outline(mapping)!!.first)
    }

    @Test fun aCornerHandleResizesUniformlyAboutTheOppositeCorner() {
        val harness = selected()
        // Bottom-right handle at canvas (100, 100) = screen (200, 200); the anchor is (0, 0).
        assertTrue(harness.selection.beginGesture(200f, 200f, mapping))
        harness.selection.updateGesture(400f, 400f)
        assertEquals(scaleAbout(2.0, 0.0, 0.0), harness.selection.gesture)
    }

    @Test fun aTapOutsideTheBoundsClearsAndInsideKeeps() {
        val harness = selected()
        harness.selection.tap(100f, 100f, mapping)
        assertEquals(setOf("a"), harness.selection.ids)
        harness.selection.tap(1000f, 1000f, mapping)
        assertTrue(harness.selection.ids.isEmpty())
    }

    @Test fun deleteHidesAtOnceAndClearsRestyleOnlyStages() {
        val harness = selected()
        harness.selection.restyle(InkColour.BLUE)
        assertEquals(MarkEdit.Restyle(setOf("a"), InkColour.BLUE), harness.marks.last())
        assertEquals(setOf("a"), harness.selection.ids)
        harness.selection.delete()
        assertEquals(MarkEdit.Delete(setOf("a")), harness.marks.last())
        assertTrue(harness.scene.isHidden("a"))
        assertTrue(harness.selection.ids.isEmpty())
    }

    @Test fun aSelectionWhoseStrokeIsDeletedDropsAway() {
        val harness = selected()
        harness.scene.load(state(listOf(stroke("a", tombstone = true), stroke("b")), samples))
        harness.selection.dropIfStale()
        assertTrue(harness.selection.ids.isEmpty())
        assertEquals(listOf(1, 0), harness.counts)
    }

    @Test fun longPressOutlinesOneStrokeWithPadding() {
        val harness = Harness(state(strokes, samples))
        assertTrue(harness.selection.selectStroke("a", mapping))
        assertFalse(harness.selection.selectStroke("missing", mapping))
        val bounds = Bounds.of(harness.selection.outline(mapping)!!.first)!!
        // 10 px / scale 2 = 5 units of padding, plus the 2-unit half-width.
        assertEquals(13f, bounds.left, 1e-3f)
        assertEquals(87f, bounds.right, 1e-3f)
        assertEquals(listOf(1), harness.counts)
    }
}
