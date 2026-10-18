package dev.syncr.prototype.notes

import dev.syncr.core.Affine
import dev.syncr.core.SampleChannels
import kotlin.math.hypot

/** Same scalar in both axes: screen = units × scale + (tx, ty). Never stretches canvas ink. */
data class SheetMapping(val scale: Float, val tx: Float, val ty: Float) {
    fun toSheet(x: Float, y: Float) = Pair((x - tx) / scale, (y - ty) / scale)
    fun toScreen(x: Float, y: Float) = Pair(x * scale + tx, y * scale + ty)
    fun navigate(dx: Float, dy: Float, factor: Float, focusX: Float, focusY: Float): SheetMapping {
        val next = (scale * factor).coerceIn(0.05f, 10f)
        val ratio = next / scale
        return SheetMapping(next, focusX - (focusX - tx) * ratio + dx, focusY - (focusY - ty) * ratio + dy)
    }
}

/** Real measured samples only. Reaching a cap cancels the WHOLE gesture, never truncates. */
class MeasuredGesture(private val mapping: SheetMapping, private val limit: Int = 100_000,
    private val hasTilt: Boolean = false, private val hasOrientation: Boolean = false) {
    private val x = mutableListOf<Float>(); private val y = mutableListOf<Float>()
    private val pressure = mutableListOf<Float>(); private val elapsed = mutableListOf<UInt>()
    private val tilt = mutableListOf<Float>(); private val orientation = mutableListOf<Float>()
    private var start: Long? = null
    var cancelled = false; private set
    fun add(screenX: Float, screenY: Float, p: Float, time: Long, t: Float = 0f, o: Float = 0f): Boolean {
        if (cancelled) return false
        if (x.size == limit || !screenX.isFinite() || !screenY.isFinite() || !p.isFinite() ||
            (hasTilt && !t.isFinite()) || (hasOrientation && !o.isFinite())) { cancel(); return false }
        val origin = start ?: time.also { start = it }
        val ms = time - origin
        if (ms < 0 || ms > UInt.MAX_VALUE.toLong() || (elapsed.lastOrNull()?.toLong() ?: 0L) > ms) { cancel(); return false }
        val point = mapping.toSheet(screenX, screenY)
        x += point.first; y += point.second; pressure += p.coerceIn(0f, 1f); elapsed += ms.toUInt()
        if (hasTilt) tilt += t.coerceIn(0f, (Math.PI / 2).toFloat())
        if (hasOrientation) orientation += o.coerceIn(-Math.PI.toFloat(), Math.PI.toFloat())
        return true
    }
    fun cancel() { cancelled = true; x.clear(); y.clear(); pressure.clear(); elapsed.clear(); tilt.clear(); orientation.clear() }
    fun channels(): SampleChannels? = if (cancelled || x.isEmpty()) null else SampleChannels(
        x.toList(), y.toList(), pressure.toList(), elapsed.toList(),
        if (hasTilt) tilt.toList() else null, if (hasOrientation) orientation.toList() else null)
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
fun hitsSweep(a: Pair<Float, Float>, b: Pair<Float, Float>, radius: Float, samples: SampleChannels, affine: Affine): Boolean {
    var previous: Pair<Float, Float>? = null
    samples.x.indices.forEach { i ->
        val d = transformedPoint(samples.x[i], samples.y[i], affine)
        val c = previous ?: d
        fun cross(p: Pair<Float, Float>, q: Pair<Float, Float>, r: Pair<Float, Float>) =
            (q.first - p.first) * (r.second - p.second) - (q.second - p.second) * (r.first - p.first)
        val intersects = cross(a, b, c) * cross(a, b, d) < 0 && cross(c, d, a) * cross(c, d, b) < 0
        if (intersects || minOf(distanceToSegment(a.first, a.second, c, d), distanceToSegment(b.first, b.second, c, d),
                distanceToSegment(c.first, c.second, a, b), distanceToSegment(d.first, d.second, a, b)) <= radius) return true
        previous = d
    }
    return false
}
fun distanceToSegment(x: Float, y: Float, a: Pair<Float, Float>, b: Pair<Float, Float>): Float {
    val dx = b.first - a.first; val dy = b.second - a.second
    val length = dx * dx + dy * dy
    val t = if (length == 0f) 0f else (((x - a.first) * dx + (y - a.second) * dy) / length).coerceIn(0f, 1f)
    return hypot(x - a.first - t * dx, y - a.second - t * dy)
}
