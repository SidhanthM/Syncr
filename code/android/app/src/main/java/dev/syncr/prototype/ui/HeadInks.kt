package dev.syncr.prototype.ui

import androidx.compose.ui.graphics.Color
import dev.syncr.core.Brush
import dev.syncr.core.BrushFamily
import dev.syncr.core.BrushSize
import dev.syncr.core.InkColour
import dev.syncr.core.Oklch
import dev.syncr.prototype.notes.Ink
import dev.syncr.prototype.notes.InkPreferences
import dev.syncr.prototype.notes.InkTool
import dev.syncr.prototype.notes.inkColour
import dev.syncr.prototype.notes.strokeArgb

/** The remembered-ink tool a head stands for; the eraser, lasso and caret have none. */
fun Head.inkTool(): InkTool? = when (this) {
    Head.FOUNTAIN -> InkTool.FOUNTAIN
    Head.BALLPOINT -> InkTool.BALLPOINT
    Head.FINELINER -> InkTool.FINELINER
    Head.HIGHLIGHTER -> InkTool.HIGHLIGHTER
    else -> null
}

/** The head that shows [tool]. */
fun InkTool.head(): Head = when (this) {
    InkTool.FOUNTAIN -> Head.FOUNTAIN
    InkTool.BALLPOINT -> Head.BALLPOINT
    InkTool.FINELINER -> Head.FINELINER
    InkTool.HIGHLIGHTER -> Head.HIGHLIGHTER
}

/** The colour of an ink as a swatch shows it: opaque, as chosen (the theme's ink for the near-black and near-white greys, as strokes do). */
fun Ink.tipColour(dark: Boolean): Color = when (this) {
    is Ink.Named -> Color(inkColour(Brush(BrushFamily.PEN, BrushSize.MEDIUM, colour), dark) or 0xFF000000.toInt())
    is Ink.Custom -> Color(oklch.strokeArgb(dark))
}

/** The brush that restores a tool's remembered ink and size. */
fun InkPreferences.restore(tool: InkTool): Brush = brushFor(tool)

/** Where the picker opens when the tool's ink is a named one: the last custom ink used, or a calm blue. */
fun InkPreferences.pickerStart(tool: InkTool): Oklch = (inkFor(tool) as? Ink.Custom)?.oklch ?: recentInks.firstOrNull() ?: Oklch(0.62f, 0.13f, 255f)

/** An OKLCH ink in words, for the tray's spoken labels: "soft blue", "deep red", "grey". */
fun Oklch.inkName(): String {
    if (c < 0.03f) return when { l < 0.25f -> "black"; l > 0.9f -> "white"; else -> "grey" }
    val hue = when (((h % 360f) + 360f) % 360f) {
        in 15f..45f -> "red"; in 45f..80f -> "orange"; in 80f..115f -> "yellow"; in 115f..170f -> "green"
        in 170f..215f -> "teal"; in 215f..270f -> "blue"; in 270f..325f -> "purple"; else -> "pink"
    }
    return when { l >= 0.75f -> "soft $hue"; l < 0.42f -> "deep $hue"; else -> hue }
}

/** A named preset ink in words. */
fun InkColour.inkName(): String = if (this == InkColour.INK) "default" else name.lowercase()
