package dev.syncr.prototype.canvas

import dev.syncr.core.Affine
import dev.syncr.prototype.notes.CanvasFixtures
import dev.syncr.prototype.notes.CanvasFixtures.line
import dev.syncr.prototype.notes.CanvasFixtures.stroke
import dev.syncr.prototype.notes.CullRect
import dev.syncr.prototype.notes.StrokeBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Culling with 5,000 strokes spread over a 60,000 x 40,000 unit area: the per-stroke bounds scan the page already does
 * against the 512-unit grid index, for a phone-sized view at three zooms. Prints what it measured; asserts only that the two
 * agree and that a frame's cull is far under a frame (the numbers are in the report).
 */
class CanvasCullBenchTest {
    private val n = 5000

    @Test fun theIndexAndTheLinearScanFindTheSameStrokesAndBothAreFarUnderAFrame() {
        val rnd = Random(3)
        val strokes = (0 until n).map { stroke("s$it", transform = Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)) }
        val samples = strokes.associate { s ->
            val x = rnd.nextFloat() * 60_000f - 30_000f; val y = rnd.nextFloat() * 40_000f - 20_000f
            s.id to line(x to y, x + 120f to y + 60f, x + 200f to y - 40f)
        }
        val scene = CanvasFixtures.scene(CanvasFixtures.state(strokes = strokes, samples = samples))
        val t0 = System.nanoTime()
        var rects = scene.inkRects(DoubleArray(0))
        val coldMs = (System.nanoTime() - t0) / 1e6
        val index = CanvasIndex()
        index.rebuild(rects, n)
        // What every later save costs: the sample bounds are cached, so only the placing and the grid are redone.
        repeat(3) { rects = scene.inkRects(rects); index.rebuild(rects, n) }
        val t1 = System.nanoTime()
        rects = scene.inkRects(rects)
        val rectsMs = (System.nanoTime() - t1) / 1e6
        val t2 = System.nanoTime()
        index.rebuild(rects, n)
        val buildMs = (System.nanoTime() - t2) / 1e6
        val local = scene.previews.map { StrokeBounds.of(it.channels, 0f)!! }
        val identity = Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        val out = IntArrayList()
        val view = CullRect()
        val report = StringBuilder("CULL5000 first rects (cold, bounds scanned) ${"%.1f".format(coldMs)} ms; later: rects ${"%.2f".format(rectsMs)} ms, index build ${"%.2f".format(buildMs)} ms")
        for ((name, half) in listOf("zoom 100% (phone view ~1400 x 3000 units)" to 1500f, "zoom 12% (~11,000 x 24,000)" to 12_000f, "zoom 2% (whole 60,000 x 40,000)" to 40_000f)) {
            var linearHits = 0; var indexHits = 0
            val rounds = 200
            // Warm up the JIT, then time rounds of 20 different view positions.
            repeat(2) { r -> for (k in 0 until 20) { val cx = (k - 10) * 2500f; view.set(cx - half / 2, -half, cx + half / 2, half); for (b in local) StrokeBounds.intersects(b, identity, view); index.query(view.left.toDouble(), view.top.toDouble(), view.right.toDouble(), view.bottom.toDouble(), out) } }
            val a0 = System.nanoTime()
            repeat(rounds / 20) { for (k in 0 until 20) { val cx = (k - 10) * 2500f; view.set(cx - half / 2, -half, cx + half / 2, half); linearHits = 0; for (b in local) if (StrokeBounds.intersects(b, identity, view)) linearHits++ } }
            val linearUs = (System.nanoTime() - a0) / 1e3 / rounds
            val b0 = System.nanoTime()
            repeat(rounds / 20) { for (k in 0 until 20) { val cx = (k - 10) * 2500f; view.set(cx - half / 2, -half, cx + half / 2, half); index.query(view.left.toDouble(), view.top.toDouble(), view.right.toDouble(), view.bottom.toDouble(), out); indexHits = out.size } }
            val indexUs = (System.nanoTime() - b0) / 1e3 / rounds
            assertEquals(name, linearHits, indexHits)
            assertTrue(linearUs < 4000 && indexUs < 4000)
            report.append("\nCULL5000 $name: linear ${"%.1f".format(linearUs)} us, index ${"%.1f".format(indexUs)} us, last view hits $indexHits")
        }
        println(report)
    }

    private fun CullRect.set(l: Float, t: Float, r: Float, b: Float) = set(l, t, r, b)
}
