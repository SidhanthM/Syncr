package dev.syncr.prototype.notes

import android.view.MotionEvent
import dev.syncr.core.InkColour
import dev.syncr.prototype.notes.CanvasFixtures.line
import dev.syncr.prototype.notes.CanvasFixtures.pen
import dev.syncr.prototype.notes.CanvasFixtures.scene
import dev.syncr.prototype.notes.CanvasFixtures.state
import dev.syncr.prototype.notes.CanvasFixtures.stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PenCaptureTest {
    @Test fun eraserEndAndSideButtonBeatTheToolbarOnlyWhenTheSettingIsOn() {
        fun mode(eraser: Boolean = false, lasso: Boolean = false, end: Boolean = false, button: Boolean = false, erases: Boolean = true) =
            penModeFor(eraser, lasso, end, button, erases)
        assertEquals(PenMode.INK, mode())
        assertEquals(PenMode.ERASE, mode(eraser = true))
        assertEquals(PenMode.LASSO, mode(lasso = true))
        assertEquals(PenMode.ERASE, mode(lasso = true, eraser = true))
        assertEquals(PenMode.ERASE, mode(end = true))
        assertEquals(PenMode.ERASE, mode(lasso = true, end = true))
        assertEquals(PenMode.ERASE, mode(button = true))
        assertEquals(PenMode.INK, mode(button = true, erases = false))
        assertEquals(PenMode.LASSO, mode(lasso = true, button = true, erases = false))
    }

    @Test fun palmRuleBlocksFingersForPalmMsAfterPenUpAndNeverBlocksThePen() {
        assertTrue(isRestingPalm(pen = false, eventTime = 1299, lastPenUp = 1000, palmMs = 300))
        assertFalse(isRestingPalm(pen = false, eventTime = 1300, lastPenUp = 1000, palmMs = 300))
        assertFalse(isRestingPalm(pen = true, eventTime = 1001, lastPenUp = 1000, palmMs = 300))
        assertTrue(isRestingPalm(pen = false, eventTime = 1599, lastPenUp = 1000, palmMs = 600))
        assertFalse(isRestingPalm(pen = false, eventTime = 1000, lastPenUp = 1000, palmMs = 0))
        assertFalse(isRestingPalm(pen = false, eventTime = 0, lastPenUp = Long.MIN_VALUE / 2, palmMs = 300))
    }

    @Test fun onlyStylusTipAndEraserEndArePens() {
        assertTrue(isPenTool(MotionEvent.TOOL_TYPE_STYLUS))
        assertTrue(isPenTool(MotionEvent.TOOL_TYPE_ERASER))
        assertFalse(isPenTool(MotionEvent.TOOL_TYPE_FINGER))
        assertFalse(isPenTool(MotionEvent.TOOL_TYPE_MOUSE))
        assertFalse(isPenTool(MotionEvent.TOOL_TYPE_UNKNOWN))
    }

    @Test fun inkUsesTheMappingAndBrushFrozenAtPenDown() {
        val capture = PenCapture(scene(state()), lassoStepPx = 2f)
        capture.begin(pen)
        capture.start(PenMode.INK, SheetMapping(2f, 100f, 0f), hasTilt = false, hasOrientation = false)
        assertNull(capture.addInkSample(100f, 10f, 0.5f, 1000, 0f, 0f))
        assertNull(capture.addInkSample(140f, 30f, 0.7f, 1010, 0f, 0f))
        val channels = capture.inkChannels()!!
        assertEquals(listOf(0f, 20f), channels.x)
        assertEquals(listOf(5f, 15f), channels.y)
        assertEquals(listOf(0u, 10u), channels.elapsedMs)
        assertEquals(pen, capture.brush)
        capture.begin(pen.copy(colour = InkColour.RED))
        assertEquals(InkColour.RED, capture.brush.colour)
    }

    @Test fun anInvalidSampleRejectsTheWholeStrokeUntilEveryPointerLifts() {
        val capture = PenCapture(scene(state()), lassoStepPx = 2f)
        capture.begin(pen)
        capture.start(PenMode.INK, SheetMapping(1f, 0f, 0f), hasTilt = false, hasOrientation = false)
        assertNull(capture.addInkSample(0f, 0f, 0.5f, 1000, 0f, 0f))
        assertNotNull(capture.addInkSample(1f, 1f, 0.5f, 900, 0f, 0f)) // time went backwards
        assertTrue(capture.rejected)
        assertTrue(capture.requireLift)
        assertNull(capture.inkChannels())
        // A new pen-down clears the rejection, but the lift requirement is the view's to clear.
        capture.begin(pen)
        assertFalse(capture.rejected)
        assertTrue(capture.requireLift)
    }

    @Test fun refusingDoesNotRequireALift() {
        val capture = PenCapture(scene(state()), lassoStepPx = 2f)
        capture.begin(pen)
        capture.start(PenMode.ERASE, SheetMapping(1f, 0f, 0f), hasTilt = false, hasOrientation = false)
        capture.refuse()
        assertTrue(capture.rejected)
        assertFalse(capture.requireLift)
    }

    @Test fun eraserCollectsEveryStrokeItSweepsInSnapshotOrder() {
        val vertical = line(50f to 0f, 50f to 100f)
        val scene = scene(state(listOf(stroke("a"), stroke("b")), mapOf("a" to vertical, "b" to vertical.copy())))
        val capture = PenCapture(scene, lassoStepPx = 2f)
        capture.begin(pen)
        capture.start(PenMode.ERASE, SheetMapping(1f, 0f, 0f), hasTilt = false, hasOrientation = false)
        assertNull(capture.addErasePoint(0f, 50f))
        assertTrue(capture.erased.isEmpty())
        // Sparse events: the sweep between them still hits the strokes in the gap.
        assertNull(capture.addErasePoint(100f, 50f))
        assertEquals(listOf("a", "b"), capture.erased.toList())
        // The canvas commits as the pen moves; committed strokes stay hidden for the rest of the gesture.
        assertEquals(listOf("a", "b"), capture.uncommitted.toList())
        capture.committed(setOf("a"))
        assertEquals(listOf("b"), capture.uncommitted.toList())
        assertEquals(listOf("a", "b"), capture.erased.toList())
        capture.finish()
        assertTrue(capture.erased.isEmpty())
        assertEquals(PenMode.NONE, capture.mode)
    }

    @Test fun sweepingMoreThan1024UncommittedStrokesStopsTheGesture() {
        val ids = (0..1024).map { "s$it" }
        val samples = ids.withIndex().associate { (i, id) -> id to line(i.toFloat() to 0f, i.toFloat() to 100f) }
        val capture = PenCapture(scene(state(ids.map { stroke(it) }, samples)), lassoStepPx = 2f)
        capture.begin(pen)
        capture.start(PenMode.ERASE, SheetMapping(1f, 0f, 0f), hasTilt = false, hasOrientation = false)
        assertNull(capture.addErasePoint(-20f, 50f))
        assertEquals("That erase covered too much at once and stopped. Lift the pen and try a smaller area.", capture.addErasePoint(1100f, 50f))
        assertTrue(capture.erased.isEmpty())
        assertTrue(capture.requireLift)
    }

    @Test fun lassoDropsPointsCloserThanTheScreenStep() {
        val capture = PenCapture(scene(state()), lassoStepPx = 3f)
        capture.begin(pen)
        capture.start(PenMode.LASSO, SheetMapping(2f, 0f, 0f), hasTilt = false, hasOrientation = false)
        capture.addLassoPoint(0f, 0f)
        capture.addLassoPoint(2f, 0f) // 2 px on screen
        capture.addLassoPoint(4f, 0f) // 4 px from the last kept point
        assertEquals(listOf(0f to 0f, 2f to 0f), capture.lassoPoints)
    }
}
