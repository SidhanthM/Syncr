package dev.syncr.prototype.notes

import dev.syncr.core.Affine
import dev.syncr.core.SampleChannels

/** A reusable canvas-space rectangle, so the per-frame viewport costs no allocation. */
internal class CullRect {
    var left = 0f; var top = 0f; var right = 0f; var bottom = 0f
    fun set(l: Float, t: Float, r: Float, b: Float) { left = l; top = t; right = r; bottom = b }
}

/**
 * Pure bounds and culling for dry ink. A stroke's bounds are computed once, when its Ink stroke is
 * built or cached, never per frame; each frame only transforms four corners and compares.
 */
internal object StrokeBounds {
    /**
     * Stroke-local bounds of the samples, grown by [pad] for the brush. Pressure can widen the line to
     * twice [brushSize] / 2 and the tip rounds past a sample, so callers pass the full brush size.
     * Null for a stroke without samples.
     */
    fun of(samples: SampleChannels, pad: Float): Bounds? {
        if (samples.x.isEmpty()) return null
        var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY; var b = Float.NEGATIVE_INFINITY
        for (i in samples.x.indices) {
            val x = samples.x[i]; val y = samples.y[i]
            if (x < l) l = x
            if (x > r) r = x
            if (y < t) t = y
            if (y > b) b = y
        }
        return Bounds(l - pad, t - pad, r + pad, b + pad)
    }

    /** Whether [local] bounds, placed by [affine], touch the canvas-space [view] rectangle. */
    fun intersects(local: Bounds, affine: Affine, view: CullRect): Boolean {
        val a = affine.a.toFloat(); val b = affine.b.toFloat(); val c = affine.c.toFloat()
        val d = affine.d.toFloat(); val tx = affine.tx.toFloat(); val ty = affine.ty.toFloat()
        var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY; var bo = Float.NEGATIVE_INFINITY
        for (corner in 0 until 4) {
            val x = if (corner == 0 || corner == 3) local.left else local.right
            val y = if (corner < 2) local.top else local.bottom
            // Same convention as transformedPoint: x' = a x + c y + tx, y' = b x + d y + ty.
            val px = a * x + c * y + tx
            val py = b * x + d * y + ty
            if (px < l) l = px
            if (px > r) r = px
            if (py < t) t = py
            if (py > bo) bo = py
        }
        return r >= view.left && l <= view.right && bo >= view.top && t <= view.bottom
    }
}
