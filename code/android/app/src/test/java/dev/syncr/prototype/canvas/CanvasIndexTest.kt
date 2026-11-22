package dev.syncr.prototype.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class CanvasIndexTest {
    private fun rects(vararg r: DoubleArray) = r.flatMap { it.toList() }.toDoubleArray()

    @Test fun queryMatchesABruteForceScanOnRandomRects() {
        val rnd = Random(7)
        val n = 3000
        val a = DoubleArray(4 * n)
        for (i in 0 until n) {
            val x = (rnd.nextDouble() - 0.5) * 2_000_000; val y = (rnd.nextDouble() - 0.5) * 2_000_000
            val w = rnd.nextDouble() * (if (i % 100 == 0) 60_000 else 900); val h = rnd.nextDouble() * (if (i % 100 == 0) 60_000 else 900)
            a[4 * i] = x; a[4 * i + 1] = y; a[4 * i + 2] = x + w; a[4 * i + 3] = y + h
        }
        val index = CanvasIndex(); index.rebuild(a, n)
        val out = IntArrayList()
        repeat(50) {
            val l = (rnd.nextDouble() - 0.5) * 2_000_000; val t = (rnd.nextDouble() - 0.5) * 2_000_000
            val r = l + rnd.nextDouble() * 200_000; val b = t + rnd.nextDouble() * 200_000
            index.query(l, t, r, b, out)
            val want = (0 until n).filter { a[4 * it + 2] >= l && a[4 * it] <= r && a[4 * it + 3] >= t && a[4 * it + 1] <= b }
            assertEquals(want, (0 until out.size).map { out[it] })
        }
    }

    @Test fun anItemWithNoExtentIsNeverReturnedAndOversizedOnesAre() {
        val a = rects(doubleArrayOf(Double.NaN, 0.0, 0.0, 0.0), doubleArrayOf(-1e7, -1e7, 1e7, 1e7), doubleArrayOf(0.0, 0.0, 10.0, 10.0))
        val index = CanvasIndex(); index.rebuild(a, 3)
        val out = IntArrayList()
        index.query(5.0, 5.0, 6.0, 6.0, out)
        assertEquals(listOf(1, 2), (0 until out.size).map { out[it] })
        index.query(5e6, 5e6, 5e6 + 1, 5e6 + 1, out)
        assertEquals(listOf(1), (0 until out.size).map { out[it] })
    }

    @Test fun anItemSpanningSeveralCellsComesBackOnce() {
        val a = rects(doubleArrayOf(0.0, 0.0, 3000.0, 3000.0))
        val index = CanvasIndex(); index.rebuild(a, 1)
        val out = IntArrayList(); index.query(-10.0, -10.0, 4000.0, 4000.0, out)
        assertEquals(1, out.size)
        assertTrue(index.size == 1)
    }

    @Test fun aHugeViewWalksOccupiedCellsNotItsOwnArea() {
        val a = rects(doubleArrayOf(10.0, 10.0, 20.0, 20.0), doubleArrayOf(5e8, 5e8, 5e8 + 5, 5e8 + 5))
        val index = CanvasIndex(); index.rebuild(a, 2)
        val out = IntArrayList(); index.query(-1e9, -1e9, 1e9, 1e9, out)
        assertEquals(2, out.size)
    }
}
