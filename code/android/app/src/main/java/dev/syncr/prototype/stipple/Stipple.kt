package dev.syncr.prototype.stipple

import dev.syncr.prototype.ui.theme.Motion

/**
 * The companion's ordered-dither stipple (editor-v2 §8), the same pattern as the Mac's `Dither.swift`: a 4 by 4 Bayer
 * matrix, cells of about 3 dp. Something new dissolves in through the pattern from its top edge down; leaving runs the
 * same frames backwards in [Motion.LEAVE_MS]; waiting is a denser band of cells drifting across placeholder lines.
 * Pure: the drawing code asks for masks and draws them.
 */
internal object Stipple {
    private val bayer = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    /** The pattern's threshold at a cell, in 0..1 (the Mac's `Dither.threshold`). */
    fun threshold(x: Int, y: Int): Double = (bayer[(y and 3) * 4 + (x and 3)] + 0.5) / 16

    /** How far the dissolve front has passed a cell: the pattern breaks up an edge that travels down. */
    fun arrival(x: Int, y: Int, rows: Int): Double = threshold(x, y) * 0.55 + y.toDouble() / maxOf(1, rows) * 0.45

    /** Whether the cell shows at [progress] (0 nothing, 1 everything) of an arriving dissolve. */
    fun shown(x: Int, y: Int, rows: Int, progress: Double): Boolean = progress >= 1.0 || arrival(x, y, rows) < progress

    /** The step ([0, STIPPLE_FRAMES]) of a dissolve at [progress]; a leaving dissolve plays the same frames backwards. */
    fun frame(progress: Float, leaving: Boolean = false): Int {
        val p = (if (leaving) 1f - progress else progress).coerceIn(0f, 1f)
        return Math.round(p * Motion.STIPPLE_FRAMES)
    }

    fun columns(width: Float, cell: Float) = maxOf(4, minOf(512, Math.ceil((width / cell).toDouble()).toInt()))
    fun rows(height: Float, cell: Float) = maxOf(4, minOf(512, Math.ceil((height / cell).toDouble()).toInt()))

    /** Cell size in canvas units that is [dp] dp on screen at [pixelsPerUnit] pixels per unit (the page is in canvas units). */
    fun cellUnits(pixelsPerUnit: Float, density: Float, dp: Float = Motion.STIPPLE_CELL_DP): Float =
        (dp * density / maxOf(0.01f, pixelsPerUnit)).coerceIn(1f, 64f)

    /** Row-major flags of the cells shown at step [k] of [Motion.STIPPLE_FRAMES]. */
    fun mask(columns: Int, rows: Int, k: Int): BooleanArray {
        val passed = k.toDouble() / Motion.STIPPLE_FRAMES
        return BooleanArray(columns * rows) { i -> k >= Motion.STIPPLE_FRAMES || arrival(i % columns, i / columns, rows) < passed }
    }

    /** Density of the waiting band at horizontal position [u] (0..1 across the loop): a soft bump the pattern turns into cells. */
    fun band(u: Double): Double {
        val d = Math.abs(((u % 1.0) + 1.0) % 1.0 - 0.5) * 2 // 0 at the middle of the band
        return (1.0 - d * 1.6).coerceIn(0.0, 1.0)
    }

    /** `SYNCR` debug builds only: a dissolve stops at this point and stays (screenshots). Null runs normally. */
    @Volatile var freeze: Float? = null
}
