package dev.syncr.prototype.notes

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.PointerIcon
import androidx.core.graphics.createBitmap
import kotlin.math.roundToInt

/**
 * The stylus hover pointer. The OS draws and composites it, so hovering never redraws the canvas;
 * we only swap its picture for a ring the size of the brush on screen. Android 14+ honours this for
 * styluses; where an OEM build ignores it, its own dot stays.
 */
internal class StylusHoverIcon(private val density: Float) {
    private data class Key(val diameterPx: Int, val colour: Int, val dashed: Boolean)

    private var cached: Pair<Key, PointerIcon>? = null

    /** A ring [diameterPx] across (clamped to a visible range), dashed for the lasso. */
    fun icon(diameterPx: Float, colour: Int, dashed: Boolean): PointerIcon {
        val key = Key(diameterPx.coerceIn(6f * density, 96f * density).roundToInt(), colour, dashed)
        cached?.let { (cachedKey, icon) -> if (cachedKey == key) return icon }
        val stroke = 1.5f * density
        val size = key.diameterPx + (2 * stroke).roundToInt() + 2
        val bitmap = createBitmap(size, size)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = key.colour
            if (key.dashed) pathEffect = DashPathEffect(floatArrayOf(2 * density, 2 * density), 0f)
        }
        Canvas(bitmap).drawCircle(size / 2f, size / 2f, key.diameterPx / 2f, paint)
        return PointerIcon.create(bitmap, size / 2f, size / 2f).also { cached = key to it }
    }
}
