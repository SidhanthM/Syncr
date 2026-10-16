package dev.syncr.prototype.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Quiet line icons for note menus, drawn like ToolDot's glyphs: 24×24 outlines, 1.8 stroke, round caps. */
enum class MenuGlyph { FOLDER, PENCIL, ARCHIVE, UNARCHIVE, TRASH, RESTORE }

private fun outline(d: String) = PathParser().parsePathString(d).toPath()
private const val BOX = "M3.5 5h17v4.5h-17z M5 9.5v8.8A1.7 1.7 0 0 0 6.7 20h10.6a1.7 1.7 0 0 0 1.7-1.7V9.5"
private val outlines = mapOf(
    MenuGlyph.FOLDER to outline("M3.5 7A1.5 1.5 0 0 1 5 5.5h4.2l2 2.3H19a1.5 1.5 0 0 1 1.5 1.5v8.7A1.5 1.5 0 0 1 19 19.5H5A1.5 1.5 0 0 1 3.5 18z"),
    MenuGlyph.PENCIL to outline("M4 20l1.2-4.4L15.8 5a1.9 1.9 0 0 1 2.7 0l.5.5a1.9 1.9 0 0 1 0 2.7L8.4 18.8z M14 7l3 3"),
    MenuGlyph.ARCHIVE to outline("$BOX M10 13h4"),
    MenuGlyph.UNARCHIVE to outline("$BOX M12 17.5v-5 M9.6 14.8l2.4-2.4 2.4 2.4"),
    MenuGlyph.TRASH to outline("M4 6.5h16 M9.5 6.5V4.8a.8.8 0 0 1 .8-.8h3.4a.8.8 0 0 1 .8.8v1.7 M6 6.5l.9 12.1A1.5 1.5 0 0 0 8.4 20h7.2a1.5 1.5 0 0 0 1.5-1.4L18 6.5 M10 10.5v5.5 M14 10.5v5.5"),
    MenuGlyph.RESTORE to outline("M4.5 12a7.5 7.5 0 1 0 2.2-5.3L4.5 9 M4.5 4.5V9H9"),
)

@Composable
fun MenuIcon(glyph: MenuGlyph, tint: Color, size: Dp = 20.dp) {
    val path = outlines.getValue(glyph)
    // Decorative: the row's text carries the meaning for accessibility.
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        withTransform({ scale(s / 24f, s / 24f, pivot = Offset.Zero) }) {
            drawPath(path, tint, style = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
