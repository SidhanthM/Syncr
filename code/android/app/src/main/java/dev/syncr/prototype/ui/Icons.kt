package dev.syncr.prototype.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The app's one icon set: 24×24 outlines, 1.5 stroke, round caps and joins (design `<defs>`).
 * Pen, Highlighter, Eraser, Lasso and Bin are the design's glyphs verbatim; the rest are drawn in the same
 * vocabulary. Icons are black and tinted where used.
 */
object SyncrIcons {
    // The design's tool glyphs.
    val Pen = icon("Pen", "M4 20l1.2-4.4L15.8 5a1.9 1.9 0 0 1 2.7 0l.5.5a1.9 1.9 0 0 1 0 2.7L8.4 18.8z", "M14 7l3 3")
    val Highlighter = icon("Highlighter", "M9 15l7.5-7.5 3 3L12 18z", "M9 15l-2 2 1.5 1.5h3.5", "M4 21h9")
    val Eraser = icon("Eraser",
        "M8.5 19L4.7 15.2a1.5 1.5 0 0 1 0-2.1l8.4-8.4a1.5 1.5 0 0 1 2.1 0l4.1 4.1a1.5 1.5 0 0 1 0 2.1L11.5 19z",
        "M8 10l6 6", "M8.5 19H20")
    /** The design's lasso: a dashed loop (`stroke-dasharray="2.6 2.2"`) and its tail. Vectors have no dash, so the dashes are real segments. */
    val Lasso = icon("Lasso", "M7.5 13.5c-1.2 1.5-.8 3.3 1 3.6 1.6.3 1.9 1.9.6 3.4", extra = listOf(dashedEllipse(12f, 9.5f, 8f, 5f, 2.6f, 2.2f)))
    val Bin = icon("Bin", "M5 7h14", "M10 7V5h4v2", "M7 7l1 12h8l1-12")

    // Bar and app actions, in the same vocabulary.
    val Undo = icon("Undo", "M9 14L4 9l5-5", "M4 9h10.5a5.5 5.5 0 0 1 0 11H11")
    val Redo = icon("Redo", "M15 14l5-5-5-5", "M20 9H9.5a5.5 5.5 0 0 0 0 11H13")
    val Fit = icon("Fit", "M4 9V5.5A1.5 1.5 0 0 1 5.5 4H9", "M15 4h3.5A1.5 1.5 0 0 1 20 5.5V9",
        "M20 15v3.5a1.5 1.5 0 0 1-1.5 1.5H15", "M9 20H5.5A1.5 1.5 0 0 1 4 18.5V15")
    val More = icon("More", filled = listOf(dot(6f, 12f, 1.6f), dot(12f, 12f, 1.6f), dot(18f, 12f, 1.6f)))
    val Grip = icon("Grip", filled = listOf(dot(9f, 7f, 1.3f), dot(15f, 7f, 1.3f), dot(9f, 12f, 1.3f),
        dot(15f, 12f, 1.3f), dot(9f, 17f, 1.3f), dot(15f, 17f, 1.3f)))
    val Settings = icon("Settings", "M4 7h9", "M17 7h3", "M17 7a2 2 0 1 1-4 0a2 2 0 1 1 4 0z",
        "M4 17h3", "M11 17h9", "M11 17a2 2 0 1 1-4 0a2 2 0 1 1 4 0z")
    /** A landscape phone with a short trail: Tablet mode. */
    val Tablet = icon("Tablet", "M5 6.5h14A1.5 1.5 0 0 1 20.5 8v8a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 16V8A1.5 1.5 0 0 1 5 6.5z",
        "M7.5 13.5c1.4-2.4 2.8-2.4 4 0s2.6 2.4 4.5-.5")
    val Trackpad = icon("Trackpad", "M5 4.5h14A1.5 1.5 0 0 1 20.5 6v12a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 18V6A1.5 1.5 0 0 1 5 4.5z", "M3.5 15h17", "M12 15v4.5")
    /** A laptop: the Mac. */
    val Laptop = icon("Laptop", "M5.5 5h13A1.5 1.5 0 0 1 20 6.5V16H4V6.5A1.5 1.5 0 0 1 5.5 5z", "M2.5 19h19")
    val Plus = icon("Plus", "M12 5v14", "M5 12h14")
    /** A paged note: a sheet with its ruled lines. Straight lines, centred. */
    val NotePage = icon("NotePage", "M6.5 4h11v16h-11z", "M9.5 9h5", "M9.5 12h5", "M9.5 15h3")
    /** A canvas: a plane with no edges, a cross of two straight lines through a frame. */
    val CanvasPlane = icon("CanvasPlane", "M4 4h16v16H4z", "M4 12h16", "M12 4v16")
    val Minus = icon("Minus", "M5 12h14")
    val Close = icon("Close", "M6.5 6.5l11 11", "M17.5 6.5l-11 11")
    val Check = icon("Check", "M5 12.5l4.5 4.5L19 7.5")
    val Folder = icon("Folder", "M3.5 7A1.5 1.5 0 0 1 5 5.5h4.2l2 2.3H19a1.5 1.5 0 0 1 1.5 1.5v8.7A1.5 1.5 0 0 1 19 19.5H5A1.5 1.5 0 0 1 3.5 18z")
    val Pencil = Pen
    val Archive = icon("Archive", ARCHIVE_LID, ARCHIVE_BOX, "M10 13h4")
    val Unarchive = icon("Unarchive", ARCHIVE_LID, ARCHIVE_BOX, "M12 17v-4.5", "M9.8 14.7l2.2-2.2 2.2 2.2")
    val Restore = icon("Restore", "M4.5 12a7.5 7.5 0 1 0 2.2-5.3L4.5 9", "M4.5 4.5V9H9")
    val Trash = Bin
    /** Needs attention: a circled "!", so the state never relies on the amber alone. */
    val Attention = icon("Attention", "M12 3.5a8.5 8.5 0 1 1 0 17a8.5 8.5 0 1 1 0-17z", "M12 8v4.8", filled = listOf(dot(12f, 16f, 1f)))
    val Back = icon("Back", "M14.5 5l-7 7 7 7")
    val Chevron = icon("Chevron", "M9.5 5l7 7-7 7")
    // The editor toolbar.
    val Slash = icon("Slash", "M15.5 4.5l-7 15")
    val Heading = icon("Heading", "M6.5 5v14", "M17.5 5v14", "M6.5 12h11")
    val Bold = icon("Bold", "M8 5v14", "M8 5h5a3.5 3.5 0 0 1 0 7H8", "M8 12h6a3.5 3.5 0 0 1 0 7H8")
    val Italic = icon("Italic", "M10 5h7", "M7 19h7", "M14.5 5l-5 14")
    val Bullet = icon("Bullet", "M10 7h10", "M10 12h10", "M10 17h10", filled = listOf(dot(5.5f, 7f, 1.3f), dot(5.5f, 12f, 1.3f), dot(5.5f, 17f, 1.3f)))
    val Todo = icon("Todo", "M4.5 5.5h6a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1h-6a1 1 0 0 1-1-1v-6a1 1 0 0 1 1-1z", "M5.8 9.2l1.7 1.7 2.7-3", "M15 9.5h5.5", "M4 18.5h16.5")
    val Outdent = icon("Outdent", "M4 5h16", "M12 10h8", "M12 14h8", "M4 19h16", "M8 9.5l-3.5 2.5L8 14.5")
    val Indent = icon("Indent", "M4 5h16", "M12 10h8", "M12 14h8", "M4 19h16", "M4 9.5L7.5 12 4 14.5")
    /** Ask Syncr: a four-point spark. */
    val Spark = icon("Spark", "M12 4c.7 4.3 1.7 5.3 6 6-4.3.7-5.3 1.7-6 6-.7-4.3-1.7-5.3-6-6 4.3-.7 5.3-1.7 6-6z")
    val HideKeyboard = icon("HideKeyboard", "M5 4.5h14A1.5 1.5 0 0 1 20.5 6v7a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 13V6A1.5 1.5 0 0 1 5 4.5z", "M9 18.5l3 2.5 3-2.5",
        filled = listOf(dot(7f, 8f, .9f), dot(10.5f, 8f, .9f), dot(14f, 8f, .9f), dot(17.5f, 8f, .9f), dot(7f, 11.2f, .9f), dot(17.5f, 11.2f, .9f)),
        extra = listOf(addPathNodes("M10 11.2h4")))
    /** Type: a serif capital T, the tool that starts typing. */
    val Type = icon("Type", "M6 7V5.5h12V7", "M12 5.5v13", "M9.5 18.5h5")
    /** Export: an arrow leaving a tray. */
    val Export = icon("Export", "M12 4v10.5", "M8 8l4-4 4 4", "M5 13.5V18.5A1.5 1.5 0 0 0 6.5 20h11a1.5 1.5 0 0 0 1.5-1.5v-5")
}

private const val ARCHIVE_LID = "M4.5 4.5h15a1 1 0 0 1 1 1V8a1 1 0 0 1-1 1h-15a1 1 0 0 1-1-1V5.5a1 1 0 0 1 1-1z"
private const val ARCHIVE_BOX = "M5 9v9.5A1.5 1.5 0 0 0 6.5 20h11a1.5 1.5 0 0 0 1.5-1.5V9"

/** An icon of the set. Decorative by default: the control that holds it carries the label. */
@Composable
fun SyncrIcon(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp, description: String? = null) {
    Icon(icon, contentDescription = description, modifier = modifier.size(size), tint = tint)
}

private fun icon(name: String, vararg strokes: String, filled: List<String> = emptyList(),
    extra: List<List<PathNode>> = emptyList()): ImageVector {
    val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    (strokes.map { addPathNodes(it) } + extra).forEach { nodes ->
        builder.addPath(nodes, stroke = SolidColor(Color.Black), strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    }
    filled.forEach { builder.addPath(addPathNodes(it), fill = SolidColor(Color.Black)) }
    return builder.build()
}

private fun dot(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

/** Dash segments along an ellipse, starting at its top and running clockwise, as the SVG dash pattern does. */
private fun dashedEllipse(cx: Float, cy: Float, rx: Float, ry: Float, on: Float, off: Float): List<PathNode> {
    val steps = 720
    val points = List(steps + 1) { i ->
        val t = -PI / 2 + 2 * PI * i / steps
        (cx + rx * cos(t)).toFloat() to (cy + ry * sin(t)).toFloat()
    }
    val path = PathBuilder()
    var travelled = 0f
    var drawing = false
    for (i in 1..steps) {
        val (x0, y0) = points[i - 1]
        val (x1, y1) = points[i]
        val phase = travelled % (on + off)
        val inDash = phase < on
        if (inDash && !drawing) path.moveTo(x0, y0)
        if (inDash) path.lineTo(x1, y1)
        drawing = inDash
        travelled += hypot(x1 - x0, y1 - y0)
    }
    return path.nodes
}
