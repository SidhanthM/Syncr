package dev.syncr.prototype.notes

import dev.syncr.core.Affine
import dev.syncr.core.SheetView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Continuous canvas geometry. Sheet origins are immutable core data, never inferred from order. */
data class CanvasLayout(val lowestInk: Float = 0f) {
    fun contains(x: Float, y: Float) = x in 0f..WIDTH && y >= 0f
    companion object {
        const val WIDTH = 4800f
        const val VIEW_HEIGHT = 3000f
        const val PHONE_COLUMN = 1600f
    }
}

fun canvasTransform(sheet: SheetView, local: Affine): Affine =
    compose(translation(sheet.originX.toDouble(), sheet.originY.toDouble()), local)

/** A canvas-space selection gesture must be conjugated into each target sheet's local space. */
fun localGesture(sheet: SheetView, canvas: Affine): Affine = compose(
    translation(-sheet.originX.toDouble(), -sheet.originY.toDouble()),
    compose(canvas, translation(sheet.originX.toDouble(), sheet.originY.toDouble())))

/** All axes use one scale. Rotation keeps scale but returns to the left edge. */
object Viewport {
    fun fitScale(viewWidth: Int, viewHeight: Int) =
        (min(viewWidth, viewHeight).toFloat() / CanvasLayout.PHONE_COLUMN).coerceAtLeast(0.001f)

    fun defaultView(layout: CanvasLayout, viewWidth: Int, viewHeight: Int) =
        clamp(SheetMapping(fitScale(viewWidth, viewHeight), 0f, 0f), layout, viewWidth, viewHeight)

    fun fitColumn(layout: CanvasLayout, current: SheetMapping?, viewWidth: Int, viewHeight: Int): SheetMapping {
        val scale = fitScale(viewWidth, viewHeight)
        val row = current?.let { -it.ty / it.scale } ?: 0f
        return clamp(SheetMapping(scale, 0f, -row * scale), layout, viewWidth, viewHeight)
    }

    /** A width change returns to the left edge; a height-only change (the keyboard) keeps the horizontal place. */
    fun resized(layout: CanvasLayout, current: SheetMapping?, viewWidth: Int, viewHeight: Int, keepLeft: Boolean = false): SheetMapping =
        if (current == null) defaultView(layout, viewWidth, viewHeight)
        else clamp(if (keepLeft) current else current.copy(tx = 0f), layout, viewWidth, viewHeight)

    fun scaleRange(fit: Float) = (fit * 0.5f)..max(fit * 6f, 3f)

    /** Visible bounds stay inside x=0..4800, y=0..lowest ink + one current screen. */
    fun clamp(m: SheetMapping, layout: CanvasLayout, viewWidth: Int, viewHeight: Int): SheetMapping {
        val minScale = viewWidth / CanvasLayout.WIDTH
        val scale = max(m.scale, minScale)
        val minX = min(0f, viewWidth - CanvasLayout.WIDTH * scale)
        val minY = -max(0f, layout.lowestInk) * scale
        return SheetMapping(scale, m.tx.coerceIn(minX, 0f), m.ty.coerceIn(minY, 0f))
    }

    fun snapScale(scale: Float, fit: Float, tolerance: Float = 0.08f): Float = when {
        abs(scale / fit - 1f) <= tolerance -> fit
        abs(scale - 1f) <= tolerance -> 1f
        else -> scale
    }
    fun isFit(scale: Float, fit: Float) = abs(scale / fit - 1f) <= 0.01f
}
