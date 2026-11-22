package dev.syncr.prototype.canvas

import kotlin.math.floor

/**
 * A 512-unit grid over item bounds, after xnotes' `SpatialIndex`, kept as one sorted array of packed (cell, item) longs
 * instead of a hash map of lists: a rebuild is a fill and a primitive sort (no boxing, no per-cell lists), which matters
 * because it runs after every save. An item spanning more than [MAX_SPAN] cells on an axis goes in an oversized list every
 * query scans. Items are dense indices into the caller's own list (the scene's previews).
 */
internal class CanvasIndex(private val cell: Double = CELL) {
    private var keys = LongArray(0)
    private var filled = 0
    private val oversized = IntArrayList()
    private var bounds = DoubleArray(0)
    private var stamp = IntArray(0)
    private var tick = 0

    var size = 0
        private set

    /** Rebuilds from [rects] (four doubles per item: left, top, right, bottom; NaN for an item with no extent). */
    fun rebuild(rects: DoubleArray, count: Int) {
        require(count <= MAX_ITEMS) { "Too many items for the grid." }
        oversized.clear()
        bounds = rects
        size = count
        if (stamp.size < count) stamp = IntArray(count)
        filled = 0
        for (i in 0 until count) {
            val l = rects[4 * i]
            if (l.isNaN()) continue
            val x0 = cellOf(l); val y0 = cellOf(rects[4 * i + 1]); val x1 = cellOf(rects[4 * i + 2]); val y1 = cellOf(rects[4 * i + 3])
            if (x1 - x0 + 1L > MAX_SPAN || y1 - y0 + 1L > MAX_SPAN) { oversized.add(i); continue }
            for (cy in y0..y1) for (cx in x0..x1) {
                if (filled == keys.size) keys = keys.copyOf(maxOf(64, keys.size * 2))
                keys[filled++] = pack(cx, cy, i)
            }
        }
        java.util.Arrays.sort(keys, 0, filled)
    }

    /** Appends the indices (ascending, once each) whose bounds touch the rectangle to [out], which is cleared first. */
    fun query(left: Double, top: Double, right: Double, bottom: Double, out: IntArrayList) {
        out.clear()
        tick++
        fun take(i: Int) {
            if (stamp[i] == tick) return
            val b = 4 * i
            if (bounds[b + 2] >= left && bounds[b] <= right && bounds[b + 3] >= top && bounds[b + 1] <= bottom) {
                stamp[i] = tick
                out.add(i)
            }
        }
        for (k in 0 until oversized.size) take(oversized[k])
        val x0 = cellOf(left); val y0 = cellOf(top); val x1 = cellOf(right); val y1 = cellOf(bottom)
        if ((x1 - x0 + 1L) * (y1 - y0 + 1L) > filled) {
            // A view far larger than the occupied area walks the entries instead of its own cells.
            for (k in 0 until filled) {
                val key = keys[k]
                val cx = cellX(key); val cy = cellY(key)
                if (cx in x0..x1 && cy in y0..y1) take(itemOf(key))
            }
        } else {
            for (cy in y0..y1) for (cx in x0..x1) {
                var k = lowerBound(pack(cx, cy, 0))
                val end = pack(cx, cy, MAX_ITEMS - 1)
                while (k < filled && keys[k] <= end) { take(itemOf(keys[k])); k++ }
            }
        }
        out.sort()
    }

    private fun lowerBound(key: Long): Int {
        var lo = 0; var hi = filled
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (keys[mid] < key) lo = mid + 1 else hi = mid }
        return lo
    }

    private fun cellOf(v: Double): Int = floor(v / cell).coerceIn(-CELL_LIMIT.toDouble(), CELL_LIMIT.toDouble()).toInt()

    // 22 bits of x, 22 of y (each offset to be positive) and 20 of the item: x in the high bits so cells sort together.
    private fun pack(cx: Int, cy: Int, item: Int): Long =
        ((cx + CELL_LIMIT).toLong() shl 42) or ((cy + CELL_LIMIT).toLong() shl 20) or item.toLong()
    private fun cellX(key: Long): Int = (key ushr 42).toInt() - CELL_LIMIT
    private fun cellY(key: Long): Int = ((key ushr 20) and 0x3FFFFF).toInt() - CELL_LIMIT
    private fun itemOf(key: Long): Int = (key and 0xFFFFF).toInt()

    companion object {
        const val CELL = 512.0
        const val MAX_SPAN = 64
        const val MAX_ITEMS = 1 shl 20
        /** Cells run +-2^21 (about +-1,000 million units), far past anything a hand can draw. */
        const val CELL_LIMIT = (1 shl 21) - 1
    }
}

/** A growable list of ints without boxing. */
internal class IntArrayList {
    private var data = IntArray(8)
    var size = 0
        private set
    operator fun get(i: Int) = data[i]
    fun add(v: Int) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
    fun clear() { size = 0 }
    fun sort() { java.util.Arrays.sort(data, 0, size) }
}
