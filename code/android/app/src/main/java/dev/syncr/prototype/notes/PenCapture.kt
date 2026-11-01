package dev.syncr.prototype.notes

import android.view.MotionEvent
import dev.syncr.core.Brush
import dev.syncr.core.BrushFamily
import dev.syncr.core.BrushSize
import dev.syncr.core.InkColour
import dev.syncr.core.SampleChannels
import kotlin.math.hypot

/** What the current pen contact is doing. */
internal enum class PenMode { NONE, IGNORED, INK, ERASE, LASSO, SELECTION }

/** Stylus tip or eraser end. Fingers are never pens, so they never ink. */
internal fun isPenTool(toolType: Int) =
    toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER

/** Palm rule v1: a finger landing within [palmMs] of the last pen-up is a resting palm. */
internal fun isRestingPalm(pen: Boolean, eventTime: Long, lastPenUp: Long, palmMs: Long) =
    !pen && eventTime - lastPenUp < palmMs

/**
 * The tool a pen-down uses. The eraser end, or the S Pen side button held at pen-down (when that
 * setting is on), erases whole strokes whatever the toolbar says.
 */
internal fun penModeFor(
    eraserSelected: Boolean,
    lassoSelected: Boolean,
    eraserEnd: Boolean,
    sideButtonHeld: Boolean,
    sideButtonErases: Boolean,
): PenMode {
    val erase = eraserSelected || eraserEnd || (sideButtonErases && sideButtonHeld)
    return when {
        erase -> PenMode.ERASE
        lassoSelected -> PenMode.LASSO
        else -> PenMode.INK
    }
}

/**
 * One pen contact, in canvas units under the mapping frozen at pen-down: measured ink samples, the
 * eraser's swept strokes, or lasso points. Reaching a limit rejects the rest of the gesture and input
 * stays ignored until every pointer lifts; strokes the eraser already committed stay erased. Pure, so
 * it is unit tested.
 */
internal class PenCapture(
    private val scene: CanvasScene,
    /** Lasso points closer than this on screen are dropped. */
    private val lassoStepPx: Float,
) {
    var mode = PenMode.NONE
        private set

    /** The mapping frozen at pen-down; panning never moves a stroke in progress. */
    var frozen = SheetMapping(1f, 0f, 0f)
        private set

    /** The brush at pen-down; toolbar changes mid-stroke don't apply. */
    var brush = Brush(BrushFamily.PEN, BrushSize.MEDIUM, InkColour.INK)
        private set

    /** This gesture will not be committed. */
    var rejected = false
        private set

    /** After a rejection, all input is ignored until every pointer lifts. */
    var requireLift = false

    private var ink: MeasuredGesture? = null
    private var eraseLast: Pt? = null
    private val erasedIds = linkedSetOf<String>()
    private val uncommittedIds = linkedSetOf<String>()
    private val lasso = mutableListOf<Pt>()

    /** Strokes the eraser has swept so far in this gesture; hidden while the pen is down. */
    val erased: Set<String> get() = erasedIds

    /** Swept strokes not yet handed to the repository. The canvas commits them while the pen moves. */
    val uncommitted: Set<String> get() = uncommittedIds

    /** These swept strokes were staged for deletion. */
    fun committed(ids: Set<String>) {
        uncommittedIds -= ids
    }

    val lassoPoints: List<Pt> get() = lasso

    /** Pen-down: forgets the previous gesture. */
    fun begin(brush: Brush) {
        rejected = false
        erasedIds.clear()
        uncommittedIds.clear()
        eraseLast = null
        lasso.clear()
        this.brush = brush
    }

    /** The pen moves the current selection instead of capturing. */
    fun startSelectionGesture() {
        mode = PenMode.SELECTION
    }

    /** The pen landed off the canvas. */
    fun ignore() {
        mode = PenMode.IGNORED
    }

    fun start(mode: PenMode, frozen: SheetMapping, hasTilt: Boolean, hasOrientation: Boolean) {
        this.mode = mode
        this.frozen = frozen
        ink = if (mode == PenMode.INK) MeasuredGesture(frozen, hasTilt = hasTilt, hasOrientation = hasOrientation) else null
    }

    /** Refuses this gesture without the lift requirement (e.g. erasing over unsaved ink). */
    fun refuse() {
        rejected = true
    }

    /** Returns a message when this sample rejects the gesture. */
    fun addInkSample(x: Float, y: Float, pressure: Float, time: Long, tilt: Float, orientation: Float): String? {
        if (ink?.add(x, y, pressure, time, tilt, orientation) == false) {
            return reject("That stroke couldn't be kept. Lift the pen and try again.")
        }
        return null
    }

    /** Sweeps the eraser from the previous point; returns a message when the gesture is rejected. */
    fun addErasePoint(x: Float, y: Float): String? {
        val point = frozen.toSheet(x, y)
        val swept = scene.strokesSwept(eraseLast ?: point, point, erasedIds)
        erasedIds += swept
        uncommittedIds += swept
        eraseLast = point
        // Strokes are committed as the pen moves, so this only trips on one enormous sweep or failing saves.
        if (uncommittedIds.size > MAX_ERASED) return reject("That erase covered too much at once and stopped. Lift the pen and try a smaller area.")
        return null
    }

    fun addLassoPoint(x: Float, y: Float) {
        val point = frozen.toSheet(x, y)
        val last = lasso.lastOrNull()
        if (last == null || hypot(point.first - last.first, point.second - last.second) * frozen.scale >= lassoStepPx) {
            lasso += point
        }
    }

    /** The finished stroke's measured samples, or null when rejected or empty. */
    fun inkChannels(): SampleChannels? = ink?.channels()

    /** Pen-up: the gesture is over. */
    fun finish() {
        ink = null
        erasedIds.clear()
        uncommittedIds.clear()
        eraseLast = null
        lasso.clear()
        mode = PenMode.NONE
    }

    /** Pause, lost contact or a second pen: nothing is committed. */
    fun cancel() {
        ink?.cancel()
        finish()
    }

    private fun reject(message: String): String {
        rejected = true
        requireLift = true
        ink?.cancel()
        erasedIds.clear()
        uncommittedIds.clear()
        lasso.clear()
        return message
    }

    private companion object {
        const val MAX_ERASED = 1024
    }
}
