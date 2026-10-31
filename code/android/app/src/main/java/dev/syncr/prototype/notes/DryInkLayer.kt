package dev.syncr.prototype.notes

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.withMatrix
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import dev.syncr.core.Affine
import dev.syncr.core.Brush
import dev.syncr.core.SampleChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dry ink: AndroidX Ink strokes built from canonical samples, a disposable rendering cache. The full
 * set is rebuilt off the UI thread while the previous strokes keep drawing. A stroke the cache lacks
 * comes from the wet layer's handoff, or is built in-frame within a small per-frame budget, or is
 * drawn as a plain measured path until the background build catches up.
 */
internal class DryInkLayer(
    initialPreferences: InkPreferences,
    private val scope: () -> CoroutineScope,
    /** Whether a preview is a stroke handoff still waiting for the next state. */
    private val isHandedOff: (String) -> Boolean,
    private val invalidate: () -> Unit,
) {
    /**
     * One cache entry, valid for one preview under one theme and one set of preferences. [bounds] are
     * the stroke-local extents, computed when the entry is built so drawing can cull cheaply.
     */
    private class CachedInk(val preview: InkPreview, val dark: Boolean, val prefs: InkPreferences, val stroke: Stroke) {
        val bounds = StrokeBounds.of(preview.channels, brushWidth(preview.brush))
    }

    /** The strokes the cache is built for, in drawing order. */
    var previews = emptyList<InkPreview>()
        private set

    var dark = false
        private set

    var prefs = initialPreferences
        private set

    private var cache = emptyMap<String, CachedInk>()

    /** Ink handed over by the wet layer, or built in-frame, until the next cache build includes it. */
    private val extra = mutableMapOf<String, CachedInk>()
    private var job: Job? = null
    private var frameBudgetEnd = 0L

    private val renderer by lazy { CanvasStrokeRenderer.create() }
    private val local = Matrix()
    private val localValues = FloatArray(9)
    private val strokeToScreen = Matrix()
    private val measuredPath = Path()
    private val measuredPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** Rebuilds when the set of strokes or the theme changes. */
    fun show(next: List<InkPreview>, night: Boolean) {
        val themeChanged = night != dark
        dark = night
        if (next != previews || themeChanged) {
            previews = next
            rebuild()
        }
    }

    fun setPreferences(next: InkPreferences) {
        if (next == prefs) return
        prefs = next
        rebuild()
        invalidate()
    }

    /** After re-attaching, the next [show] rebuilds everything. */
    fun forgetPreviews() {
        previews = emptyList()
    }

    /** A different note: no handed-over ink applies. */
    fun clearHandoffInk() {
        extra.clear()
    }

    fun cancel() {
        job?.cancel()
    }

    /** Keeps a finished wet stroke as this preview's dry ink until the cache has it. */
    fun adoptWetStroke(preview: InkPreview, dark: Boolean, prefs: InkPreferences, stroke: Stroke) {
        val wanted = previews.any { it.id == preview.id } || isHandedOff(preview.id)
        if (cache[preview.id]?.preview != preview && wanted) extra[preview.id] = CachedInk(preview, dark, prefs, stroke)
    }

    /**
     * False only when the stroke is cached and, placed by [affine], lies wholly outside the
     * canvas-space [view]. An uncached stroke (cold load) is always drawn.
     */
    fun isVisible(preview: InkPreview, affine: Affine, view: CullRect): Boolean {
        val bounds = entryFor(preview)?.bounds ?: return true
        return StrokeBounds.intersects(bounds, affine, view)
    }

    /** Starts this frame's in-frame build budget. */
    fun beginFrame() {
        frameBudgetEnd = System.nanoTime() + SYNC_BUDGET_NS
    }

    /** Draws one stroke. [canvas] maps canvas units to pixels at [scale]; [affine] places the stroke. */
    fun draw(canvas: Canvas, preview: InkPreview, affine: Affine, scale: Float) {
        localValues[0] = affine.a.toFloat()
        localValues[1] = affine.c.toFloat()
        localValues[2] = affine.tx.toFloat()
        localValues[3] = affine.b.toFloat()
        localValues[4] = affine.d.toFloat()
        localValues[5] = affine.ty.toFloat()
        localValues[6] = 0f
        localValues[7] = 0f
        localValues[8] = 1f
        local.setValues(localValues)
        canvas.withMatrix(local) {
            val stroke = inkFor(preview)
            if (stroke != null) {
                // The renderer picks its tessellation detail from the full stroke-to-screen transform.
                strokeToScreen.set(local)
                strokeToScreen.postScale(scale, scale)
                renderer.draw(this, stroke, strokeToScreen)
            } else {
                drawMeasured(this, preview.channels, preview.brush)
            }
        }
    }

    /** Draws an in-progress stroke directly, for when the wet layer is not attached. */
    fun drawLive(canvas: Canvas, channels: SampleChannels, brush: Brush, scale: Float) {
        strokeToScreen.setScale(scale, scale)
        renderer.draw(canvas, InkBrushes.stroke(channels, InkBrushes.brush(brush, dark, prefs)), strokeToScreen)
    }

    private fun rebuild() {
        val wanted = previews
        val night = dark
        val style = prefs
        val reusable = cache.values + extra.values
        job?.cancel()
        job = scope().launch {
            val built = withContext(Dispatchers.Default) {
                val old = reusable.filter { it.dark == night && it.prefs == style }.associateBy { it.preview }
                wanted.associate { preview ->
                    ensureActive()
                    preview.id to (old[preview] ?: makeInk(preview, night, style))
                }
            }
            cache = built
            extra.keys.retainAll { id -> id !in built && isHandedOff(id) }
            invalidate()
        }
    }

    private fun entryFor(preview: InkPreview): CachedInk? =
        cache[preview.id]?.takeIf { it.preview == preview } ?: extra[preview.id]?.takeIf { it.preview == preview }

    private fun inkFor(preview: InkPreview): Stroke? {
        entryFor(preview)?.let { return it.stroke }
        if (System.nanoTime() > frameBudgetEnd) return null
        return makeInk(preview, dark, prefs).also { extra[preview.id] = it }.stroke
    }

    private fun makeInk(preview: InkPreview, night: Boolean, style: InkPreferences) =
        CachedInk(preview, night, style, InkBrushes.stroke(preview.channels, InkBrushes.brush(preview.brush, night, style)))

    /** Cold-load placeholder only, while a large note's dry ink is still being built. */
    private fun drawMeasured(canvas: Canvas, samples: SampleChannels, brush: Brush) {
        measuredPaint.color = inkColour(brush, dark)
        measuredPaint.strokeWidth = brushWidth(brush)
        if (samples.x.size == 1) {
            canvas.drawPoint(samples.x[0], samples.y[0], measuredPaint)
            return
        }
        measuredPath.rewind()
        for (i in samples.x.indices) {
            if (i == 0) measuredPath.moveTo(samples.x[i], samples.y[i]) else measuredPath.lineTo(samples.x[i], samples.y[i])
        }
        canvas.drawPath(measuredPath, measuredPaint)
    }

    private companion object {
        /** In-frame dry-ink builds per frame (cold load); the rest come from the background build. */
        const val SYNC_BUDGET_NS = 4_000_000L
    }
}
