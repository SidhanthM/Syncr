package dev.syncr.prototype.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The canvas editor's glyphs, in the app's vocabulary (24 x 24, 1.5 stroke, round caps): straight and centred, no wobble. */
internal object CanvasIcons {
    val Minus = icon("Minus", "M5 12h14")
    /** The minimap: a frame and the view inside it. */
    val Map = icon("Map", "M5.5 5h13A1.5 1.5 0 0 1 20 6.5v11a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 17.5v-11A1.5 1.5 0 0 1 5.5 5z",
        "M9 9.5h6v5H9z")
    /** A waypoint: a flag on a pole. */
    val Waypoint = icon("Waypoint", "M7 20V4", "M7 5h10.5l-2.5 3.5 2.5 3.5H7")
    /** A canvas: an open frame with no paper edge, for the library card's kind mark. */
    val Canvas = icon("Canvas", "M4 9V6.5A1.5 1.5 0 0 1 5.5 5H9", "M15 5h3.5A1.5 1.5 0 0 1 20 6.5V9", "M20 15v2.5a1.5 1.5 0 0 1-1.5 1.5H15", "M9 19H5.5A1.5 1.5 0 0 1 4 17.5V15")

    private fun icon(name: String, vararg strokes: String): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        strokes.forEach {
            builder.addPath(addPathNodes(it), stroke = SolidColor(Color.Black), strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
        return builder.build()
    }
}
