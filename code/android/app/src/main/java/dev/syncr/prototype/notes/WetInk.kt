package dev.syncr.prototype.notes

import android.view.MotionEvent
import android.view.View
import androidx.ink.brush.Brush as InkBrush

/**
 * What [NoteCanvasView] needs from a wet-ink layer: the Ink overlay ([WetInkBridge], the default and the
 * fallback) or the front-buffer pad ([FrontInkPad]). The canvas picks one per stroke and calls only that one.
 */
internal interface WetInk {
    /** The overlay; it only draws, the canvas view handles all input. */
    val view: View

    /** Whether there is somewhere on screen to draw; without it the canvas draws the live stroke itself. */
    val isAttached: Boolean

    val isDrawing: Boolean

    /** Finished on this layer but not yet handed to dry ink, which skips it meanwhile. */
    fun isFinishing(previewId: String): Boolean

    /** Pen-down. [frozen] maps canvas units to the view; the stroke is recorded in canvas units. */
    fun start(event: MotionEvent, pointerId: Int, frozen: SheetMapping, width: Int, height: Int, brush: InkBrush, dark: Boolean, prefs: InkPreferences)

    /** A pen move. Every event (history included) is consumed oldest-first. */
    fun add(event: MotionEvent)

    /** Pen-up: the stroke stays on screen until it is handed over. A null [preview] was not staged. */
    fun finish(event: MotionEvent, preview: InkPreview?)

    fun cancel()

    /** Drops everything, finished strokes included (a different note, or leaving the window). */
    fun clear()

    fun eagerInit()
}
