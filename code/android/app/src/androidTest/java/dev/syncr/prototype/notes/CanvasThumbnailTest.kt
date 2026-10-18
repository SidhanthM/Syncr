package dev.syncr.prototype.notes

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.syncr.core.*
import dev.syncr.prototype.ui.renderThumbnail
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native Ink/Canvas check. Compilation is not device execution. */
@RunWith(AndroidJUnit4::class)
class CanvasThumbnailTest {
    @Test fun topLeftCanvasPreviewScalesRightHandInkIntoTheCard() {
        val brush = Brush(BrushFamily.PEN, BrushSize.MEDIUM, InkColour.INK)
        val thumbnail = Thumbnail(1u, CanvasLayout.WIDTH, CanvasLayout.VIEW_HEIGHT,
            listOf(ThumbStroke(floatArrayOf(4000f, 200f, 4100f, 240f), brush)))
        val bitmap = renderThumbnail(thumbnail, false, InkPreferences())
        try {
            // 400px card, 16px margin, scale=368/4800. Unscaled ink would be outside the bitmap.
            assertTrue((300..350).any { x -> (20..50).any { y -> Color.alpha(bitmap.getPixel(x, y)) > 0 } })
            assertTrue((0..200).all { x -> (0 until bitmap.height).all { y -> Color.alpha(bitmap.getPixel(x, y)) == 0 } })
        } finally { bitmap.recycle() }
    }
}
