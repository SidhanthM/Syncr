package dev.syncr.prototype.notes

import dev.syncr.core.Affine
import dev.syncr.core.SampleChannels
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Pure selection geometry (design §8.5). Affine convention matches the core and transformedPoint():
 * x' = a·x + c·y + tx, y' = b·x + d·y + ty.
 */

val IDENTITY = Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

/** outer ∘ inner: apply [inner] first, then [outer]. */
fun compose(outer: Affine, inner: Affine) = Affine(
    a = outer.a * inner.a + outer.c * inner.b,
    b = outer.b * inner.a + outer.d * inner.b,
    c = outer.a * inner.c + outer.c * inner.d,
    d = outer.b * inner.c + outer.d * inner.d,
    tx = outer.a * inner.tx + outer.c * inner.ty + outer.tx,
    ty = outer.b * inner.tx + outer.d * inner.ty + outer.ty,
)

/** Inverse, or null when singular/non-finite (the core rejects those transforms). */
fun invert(m: Affine): Affine? {
    val det = m.a * m.d - m.b * m.c
    if (!det.isFinite() || abs(det) < 1e-12) return null
    return Affine(m.d / det, -m.b / det, -m.c / det, m.a / det, (m.c * m.ty - m.d * m.tx) / det, (m.b * m.tx - m.a * m.ty) / det)
}

fun isValidTransform(m: Affine) = listOf(m.a, m.b, m.c, m.d, m.tx, m.ty).all { it.isFinite() } && invert(m) != null
fun translation(dx: Double, dy: Double) = Affine(1.0, 0.0, 0.0, 1.0, dx, dy)
fun scaleAbout(s: Double, x: Double, y: Double) = Affine(s, 0.0, 0.0, s, x - s * x, y - s * y)
fun isIdentity(m: Affine, epsilon: Double = 1e-9) = abs(m.a - 1) < epsilon && abs(m.b) < epsilon && abs(m.c) < epsilon &&
    abs(m.d - 1) < epsilon && abs(m.tx) < epsilon && abs(m.ty) < epsilon

typealias Pt = Pair<Float, Float>

/** Even-odd ray casting. Fewer than three vertices encloses nothing. */
fun pointInPolygon(x: Float, y: Float, polygon: List<Pt>): Boolean {
    if (polygon.size < 3) return false
    var inside = false
    var j = polygon.size - 1
    for (i in polygon.indices) {
        val (xi, yi) = polygon[i]; val (xj, yj) = polygon[j]
        if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
        j = i
    }
    return inside
}

/** Lasso rule: a stroke is selected when at least [threshold] of its points (after its transform) are inside. */
fun lassoSelects(samples: SampleChannels, transform: Affine, polygon: List<Pt>, threshold: Float = 0.5f): Boolean {
    if (samples.x.isEmpty() || polygon.size < 3) return false
    var inside = 0
    samples.x.indices.forEach { i ->
        val (x, y) = transformedPoint(samples.x[i], samples.y[i], transform)
        if (pointInPolygon(x, y, polygon)) inside++
    }
    return inside >= threshold * samples.x.size
}

data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
    fun inflate(by: Float) = Bounds(left - by, top - by, right + by, bottom + by)
    /** Corners clockwise from top-left. Handle i's anchor is the opposite corner (i + 2) % 4. */
    fun corners(): List<Pt> = listOf(left to top, right to top, right to bottom, left to bottom)
    companion object {
        fun of(points: Iterable<Pt>): Bounds? {
            var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY
            var r = Float.NEGATIVE_INFINITY; var b = Float.NEGATIVE_INFINITY
            points.forEach { (x, y) -> l = min(l, x); t = min(t, y); r = max(r, x); b = max(b, y) }
            return if (l.isFinite()) Bounds(l, t, r, b) else null
        }
    }
}

fun strokePoints(samples: SampleChannels, transform: Affine): List<Pt> =
    samples.x.indices.map { transformedPoint(samples.x[it], samples.y[it], transform) }

/** Rounded-rectangle outline hugging [bounds] with [pad], as a polygon (long-press selection). */
fun roundedOutline(bounds: Bounds, pad: Float, radius: Float, segments: Int = 4): List<Pt> {
    val b = bounds.inflate(pad)
    val r = min(radius, min(b.width, b.height) / 2)
    val centres = listOf(b.right - r to b.top + r, b.right - r to b.bottom - r, b.left + r to b.bottom - r, b.left + r to b.top + r)
    return centres.flatMapIndexed { corner, (cx, cy) ->
        (0..segments).map { step ->
            val angle = -PI / 2 + (corner + step.toDouble() / segments) * PI / 2
            (cx + r * cos(angle)).toFloat() to (cy + r * sin(angle)).toFloat()
        }
    }
}

fun transformPolygon(points: List<Pt>, m: Affine) = points.map { (x, y) -> transformedPoint(x, y, m) }

/**
 * Uniform resize from a corner handle: the drag projected onto the anchor→handle diagonal gives the
 * scale factor, clamped to [minScale] so a selection cannot collapse or flip.
 */
fun handleScale(anchor: Pt, handleStart: Pt, handleNow: Pt, minScale: Float = 0.1f, maxScale: Float = 20f): Float {
    val dx = handleStart.first - anchor.first; val dy = handleStart.second - anchor.second
    val length = dx * dx + dy * dy
    if (length < 1e-6f) return 1f
    val projected = ((handleNow.first - anchor.first) * dx + (handleNow.second - anchor.second) * dy) / length
    return projected.coerceIn(minScale, maxScale)
}

/** Index of the corner handle within [radius] of the point, or null. */
fun handleAt(bounds: Bounds, x: Float, y: Float, radius: Float): Int? =
    bounds.corners().withIndex().filter { (_, c) -> hypot(c.first - x, c.second - y) <= radius }
        .minByOrNull { (_, c) -> hypot(c.first - x, c.second - y) }?.index
