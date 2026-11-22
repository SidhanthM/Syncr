package dev.syncr.prototype.notes

import dev.syncr.core.Affine
import dev.syncr.core.SampleChannels
import kotlin.math.hypot

/** Same scalar in both axes: screen = units × scale + (tx, ty). Never stretches canvas ink. */
data class SheetMapping(val scale: Float, val tx: Float, val ty: Float) {
    fun toSheet(x: Float, y: Float) = Pair((x - tx) / scale, (y - ty) / scale)
    fun toScreen(x: Float, y: Float) = Pair(x * scale + tx, y * scale + ty)
    fun navigate(dx: Float, dy: Float, factor: Float, focusX: Float, focusY: Float, range: ClosedFloatingPointRange<Float> = 0.001f..100f): SheetMapping {
        val next = (scale * factor).coerceIn(range)
        val ratio = next / scale
        return SheetMapping(next, focusX - (focusX - tx) * ratio + dx, focusY - (focusY - ty) * ratio + dy)
    }
}

/** Real measured samples only. Reaching a cap cancels the WHOLE gesture, never truncates. */
class MeasuredGesture(
    private val mapping: SheetMapping,
    private val limit: Int = 100_000,
    private val hasTilt: Boolean = false,
    private val hasOrientation: Boolean = false,
) {
    private val x = mutableListOf<Float>()
    private val y = mutableListOf<Float>()
    private val pressure = mutableListOf<Float>()
    private val elapsed = mutableListOf<UInt>()
    private val tilt = mutableListOf<Float>()
    private val orientation = mutableListOf<Float>()
    private var start: Long? = null

    var cancelled = false
        private set

    /** Adds one screen-space sample. Returns false, cancelling everything, on a cap or invalid input. */
    fun add(screenX: Float, screenY: Float, p: Float, time: Long, t: Float = 0f, o: Float = 0f): Boolean {
        if (cancelled) return false
        val invalid = !screenX.isFinite() || !screenY.isFinite() || !p.isFinite() ||
            (hasTilt && !t.isFinite()) || (hasOrientation && !o.isFinite())
        if (x.size == limit || invalid) {
            cancel()
            return false
        }
        val origin = start ?: time.also { start = it }
        val ms = time - origin
        // Elapsed time must fit the stored UInt and never go backwards.
        if (ms < 0 || ms > UInt.MAX_VALUE.toLong() || (elapsed.lastOrNull()?.toLong() ?: 0L) > ms) {
            cancel()
            return false
        }
        val point = mapping.toSheet(screenX, screenY)
        x += point.first
        y += point.second
        pressure += p.coerceIn(0f, 1f)
        elapsed += ms.toUInt()
        if (hasTilt) tilt += t.coerceIn(0f, (Math.PI / 2).toFloat())
        if (hasOrientation) orientation += o.coerceIn(-Math.PI.toFloat(), Math.PI.toFloat())
        return true
    }

    fun cancel() {
        cancelled = true
        x.clear()
        y.clear()
        pressure.clear()
        elapsed.clear()
        tilt.clear()
        orientation.clear()
    }

    fun channels(): SampleChannels? {
        if (cancelled || x.isEmpty()) return null
        return SampleChannels(
            x.toList(),
            y.toList(),
            pressure.toList(),
            elapsed.toList(),
            if (hasTilt) tilt.toList() else null,
            if (hasOrientation) orientation.toList() else null,
        )
    }
}

fun transformedPoint(x: Float, y: Float, affine: Affine): Pair<Float, Float> = Pair(
    (affine.a * x + affine.c * y + affine.tx).toFloat(),
    (affine.b * x + affine.d * y + affine.ty).toFloat())

fun hitsStroke(x: Float, y: Float, radius: Float, samples: SampleChannels, affine: Affine): Boolean {
    var previous: Pair<Float, Float>? = null
    samples.x.indices.forEach { index ->
        val point = transformedPoint(samples.x[index], samples.y[index], affine)
        if (distanceToSegment(x, y, previous ?: point, point) <= radius) return true
        previous = point
    }
    return false
}
/** Whether the eraser's sweep from [a] to [b], with [radius], touches the stroke. */
/**
 * Whether the eraser's sweep from [a] to [b] passes within [radius] of the stroke's polyline, placed by
 * [affine]. On the pen's path, once per sample per nearby stroke, so it allocates nothing.
 */
fun hitsSweep(a: Pair<Float, Float>, b: Pair<Float, Float>, radius: Float, samples: SampleChannels, affine: Affine): Boolean {
    val ax = a.first; val ay = a.second; val bx = b.first; val by = b.second
    val ma = affine.a.toFloat(); val mb = affine.b.toFloat(); val mc = affine.c.toFloat()
    val md = affine.d.toFloat(); val tx = affine.tx.toFloat(); val ty = affine.ty.toFloat()
    val xs = samples.x; val ys = samples.y
    val r2 = radius * radius
    var cx = 0f; var cy = 0f
    for (i in xs.indices) {
        val sx = xs[i]; val sy = ys[i]
        val dx = ma * sx + mc * sy + tx
        val dy = mb * sx + md * sy + ty
        if (i == 0) { cx = dx; cy = dy }
        val crosses = crossOf(ax, ay, bx, by, cx, cy) * crossOf(ax, ay, bx, by, dx, dy) < 0 &&
            crossOf(cx, cy, dx, dy, ax, ay) * crossOf(cx, cy, dx, dy, bx, by) < 0
        if (crosses || squaredDistanceToSegment(ax, ay, cx, cy, dx, dy) <= r2 || squaredDistanceToSegment(bx, by, cx, cy, dx, dy) <= r2 ||
            squaredDistanceToSegment(cx, cy, ax, ay, bx, by) <= r2 || squaredDistanceToSegment(dx, dy, ax, ay, bx, by) <= r2) return true
        cx = dx; cy = dy
    }
    return false
}

private fun crossOf(px: Float, py: Float, qx: Float, qy: Float, rx: Float, ry: Float) = (qx - px) * (ry - py) - (qy - py) * (rx - px)

private fun squaredDistanceToSegment(x: Float, y: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = bx - ax; val dy = by - ay
    val length = dx * dx + dy * dy
    val t = if (length == 0f) 0f else (((x - ax) * dx + (y - ay) * dy) / length).coerceIn(0f, 1f)
    val ex = x - ax - t * dx; val ey = y - ay - t * dy
    return ex * ex + ey * ey
}

private fun cross(p: Pair<Float, Float>, q: Pair<Float, Float>, r: Pair<Float, Float>) =
    (q.first - p.first) * (r.second - p.second) - (q.second - p.second) * (r.first - p.first)

fun distanceToSegment(x: Float, y: Float, a: Pair<Float, Float>, b: Pair<Float, Float>): Float {
    val dx = b.first - a.first
    val dy = b.second - a.second
    val length = dx * dx + dy * dy
    val t = if (length == 0f) 0f else (((x - a.first) * dx + (y - a.second) * dy) / length).coerceIn(0f, 1f)
    return hypot(x - a.first - t * dx, y - a.second - t * dy)
}
