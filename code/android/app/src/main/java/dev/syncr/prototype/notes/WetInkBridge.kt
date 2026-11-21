package dev.syncr.prototype.notes

import android.content.Context
import android.graphics.Matrix
import android.graphics.Path
import android.view.MotionEvent
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor
import androidx.ink.brush.Brush as InkBrush

/**
 * Wet ink (design §4.1): Ink's [InProgressStrokesView] draws the stroke under the pen on the
 * front-buffer path, from the same pen MotionEvents and with the same brush as dry ink, so a stroke
 * keeps its shape when the pen lifts. A finished stroke stays on this layer until Ink reports it
 * finished, then becomes the preview's dry ink in the same frame: there is no blank frame. The Ink
 * stroke is only a rendering cache; canonical samples come from [MeasuredGesture].
 *
 * Prediction lives only here: [MotionEventPredictor] output goes straight to Ink's `addToStroke`,
 * which draws it as a provisional tail and discards it on the next real input or on finish. It is
 * never given to [PenCapture], the dry layer or the repository ("drawn and thrown away, never stored").
 *
 * Ink's stroke calls take a pointer ID (`MotionEvent.getPointerId`), not an index. The bridge keeps
 * the ID from pen-down, so callers can't mix the two up.
 */
internal class WetInkBridge(
    context: Context,
    /** Hands a finished stroke to dry ink, with the theme it was drawn under. */
    private val onFinished: (InkPreview, Boolean, Stroke) -> Unit,
    private val invalidate: () -> Unit,
) : WetInk {
    private class Live(
        val id: InProgressStrokeId,
        val pointerId: Int,
        val dark: Boolean,
        val prefs: InkPreferences,
        var preview: InkPreview? = null,
    )

    /** The overlay; it only draws, the canvas view handles all input. */
    override val view = InProgressStrokesView(context)

    private var active: Live? = null
    private val finished = mutableMapOf<InProgressStrokeId, Live>()

    /** Created on the first stroke, when the overlay is attached and knows its display. */
    private var predictor: MotionEventPredictor? = null

    init {
        view.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) = handOff(strokes)
        })
    }

    /** Without a window there is no front buffer; the canvas then draws the live stroke itself. */
    override val isAttached: Boolean get() = view.isAttachedToWindow

    override val isDrawing: Boolean get() = active != null

    /** Finished on this layer but not yet handed to dry ink, which skips it meanwhile. */
    override fun isFinishing(previewId: String): Boolean = finished.values.any { it.preview?.id == previewId }

    /** Pen-down. [frozen] maps canvas units to the view; the stroke is recorded in canvas units. */
    override fun start(event: MotionEvent, pointerId: Int, frozen: SheetMapping, width: Int, height: Int, brush: InkBrush, dark: Boolean, prefs: InkPreferences) {
        if (!isAttached) return
        trace(TraceNames.WET_START) {
            val canvasRight = frozen.tx + CanvasLayout.WIDTH * frozen.scale
            // Only the canvas's horizontal edges and top clip ink; there is no bottom edge.
            view.maskPath = Path().apply {
                addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
                val canvasArea = Path().apply { addRect(frozen.tx, frozen.ty, canvasRight, height.toFloat(), Path.Direction.CW) }
                op(canvasArea, Path.Op.DIFFERENCE)
            }
            // New stroke units are canvas units, exactly as MeasuredGesture stores them.
            val toCanvas = Matrix().apply {
                setTranslate(-frozen.tx, -frozen.ty)
                postScale(1f / frozen.scale, 1f / frozen.scale)
            }
            val live = Live(view.startStroke(event, pointerId, brush, toCanvas, Matrix()), pointerId, dark, prefs)
            active = live
            if (prefs.predictPenMotion && event.pointerCount == 1) {
                val p = predictor ?: MotionEventPredictor.newInstance(view).also { predictor = it }
                p.record(event)
            }
        }
    }

    /** A pen move. Every event (history included) is consumed oldest-first by Ink and the predictor. */
    override fun add(event: MotionEvent) {
        val live = active ?: return
        trace(TraceNames.WET_ADD) {
            val predicted = predictionFor(live, event)
            try {
                view.addToStroke(event, live.pointerId, live.id, predicted)
            } finally {
                predicted?.recycle() // Ink copies what it needs during the call
            }
        }
    }

    /** Pen-up: the stroke stays on screen until Ink hands it over. A null [preview] was not staged. */
    override fun finish(event: MotionEvent, preview: InkPreview?) {
        val live = active ?: return
        active = null
        trace(TraceNames.WET_FINISH) {
            if (preview == null) {
                view.cancelStroke(live.id, event)
                return
            }
            live.preview = preview
            finished[live.id] = live
            // No prediction here: Ink drops any provisional tail when the stroke ends on real input.
            view.finishStroke(event, live.pointerId, live.id)
        }
    }

    /**
     * The predicted continuation, or null (preference off, no prediction yet, or other pointers in
     * the event, which the predictor can't tell apart). The caller recycles it.
     */
    private fun predictionFor(live: Live, event: MotionEvent): MotionEvent? {
        if (!live.prefs.predictPenMotion || event.pointerCount != 1) return null
        val p = predictor ?: return null
        p.record(event)
        return p.predict()
    }

    override fun cancel() {
        // Once the overlay has left the window there is nothing on screen to cancel.
        active?.let { if (isAttached) view.cancelStroke(it.id, null) }
        active = null
    }

    /** Drops everything, finished strokes included (a different note, or leaving the window). */
    override fun clear() {
        cancel()
        finished.clear()
        if (isAttached) {
            view.cancelUnfinishedStrokes()
            view.removeFinishedStrokes(view.getFinishedStrokes().keys)
        }
    }

    /** Warms up the front-buffer renderer; call after the overlay is attached. */
    override fun eagerInit() {
        if (isAttached) view.eagerInit()
    }

    /** The finished Ink stroke becomes its preview's dry ink in the same frame it leaves this layer. */
    private fun handOff(strokes: Map<InProgressStrokeId, Stroke>) = trace(TraceNames.WET_HANDOFF) {
        strokes.forEach { (id, stroke) ->
            val live = finished.remove(id) ?: return@forEach
            val preview = live.preview ?: return@forEach
            onFinished(preview, live.dark, stroke)
        }
        invalidate()
        view.removeFinishedStrokes(strokes.keys)
    }
}
