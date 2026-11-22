package dev.syncr.prototype.notes

import dev.syncr.core.Affine
import dev.syncr.core.SheetView
import kotlin.math.abs

fun canvasTransform(sheet: SheetView, local: Affine): Affine =
    compose(translation(sheet.originX.toDouble(), sheet.originY.toDouble()), local)

/** A canvas-space selection gesture must be conjugated into each target sheet's local space. */
fun localGesture(sheet: SheetView, canvas: Affine): Affine = compose(
    translation(-sheet.originX.toDouble(), -sheet.originY.toDouble()),
    compose(canvas, translation(sheet.originX.toDouble(), sheet.originY.toDouble())))

/** Every navigation rule below asks the [NoteGeometry] where the paper is; this object holds only the geometry-free arithmetic. */
object Viewport {
    fun fitScale(geometry: NoteGeometry, viewWidth: Int, viewHeight: Int) = geometry.fitScale(viewWidth, viewHeight)

    fun defaultView(geometry: NoteGeometry, viewWidth: Int, viewHeight: Int) =
        clamp(SheetMapping(fitScale(geometry, viewWidth, viewHeight), 0f, 0f), geometry, viewWidth, viewHeight)

    fun fitColumn(geometry: NoteGeometry, current: SheetMapping?, viewWidth: Int, viewHeight: Int): SheetMapping {
        val scale = fitScale(geometry, viewWidth, viewHeight)
        val row = current?.let { -it.ty / it.scale } ?: 0f
        return clamp(SheetMapping(scale, 0f, -row * scale), geometry, viewWidth, viewHeight)
    }

    /** A width change returns to the left edge; a height-only change (the keyboard) keeps the horizontal place. */
    fun resized(geometry: NoteGeometry, current: SheetMapping?, viewWidth: Int, viewHeight: Int, keepLeft: Boolean = false): SheetMapping =
        if (current == null) defaultView(geometry, viewWidth, viewHeight)
        else clamp(if (keepLeft) current else current.copy(tx = 0f), geometry, viewWidth, viewHeight)

    fun clamp(m: SheetMapping, geometry: NoteGeometry, viewWidth: Int, viewHeight: Int) = geometry.clamp(m, viewWidth, viewHeight)

    fun snapScale(scale: Float, fit: Float, tolerance: Float = 0.08f): Float = when {
        abs(scale / fit - 1f) <= tolerance -> fit
        abs(scale - 1f) <= tolerance -> 1f
        else -> scale
    }
    fun isFit(scale: Float, fit: Float) = abs(scale / fit - 1f) <= 0.01f
}
