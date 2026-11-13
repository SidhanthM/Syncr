package dev.syncr.prototype.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** The one accent (design `--accent`): active tool, selection and the ring. A mark, never text on light paper. */
val DustyRose = Color(0xffd39999)

/**
 * Paper and chrome colours as ARGB ints, shared with the Android [dev.syncr.prototype.notes.NoteCanvasView].
 * The paper itself comes from the generated [NotePalette] so the phone and the Mac agree; ink colours stay
 * in `inkColour()` and the paper never competes with them.
 */
object PaperPalette {
    fun desk(dark: Boolean): Int = NotePalette.desk(dark)
    fun paper(dark: Boolean): Int = NotePalette.paper(dark)
    /** Soft page shadow and hairline edge; dotted lines are reserved for lasso and selection. */
    fun shadow(dark: Boolean): Int = if (dark) 0x66000000 else 0x1f000000
    fun hairline(dark: Boolean): Int = if (dark) 0xff2c2c2f.toInt() else 0xffe6e4e0.toInt()
    fun quiet(dark: Boolean): Int = if (dark) 0xff8f8e88.toInt() else 0xff9b9b9b.toInt() // --faint
}

/**
 * Colour tokens. Light values are the design's `:root`; dark values follow its dark sheet
 * (`#sheet-dark`: #161616 ground, #f0efea text, #8f8e88 quiet, #3a3a38 rules). Names predating the
 * design are kept where code uses them ([desk], [dots]).
 *
 * Surfaces rise in four steps ([desk], [surface1], [surface2], [surface3]); in dark mode each is lighter than the
 * last and the raised ones draw a faint top edge, in light mode they stay near white and cast a shadow
 * ([raised]). Pressed and selected fills are the ink colour at 6% and 10%, so they work on every level.
 *
 * Text contrast (WCAG AA, 4.5:1) holds for [ink], [muted] and [destructive] on all four surfaces (`ColorContrastTest`).
 * [faint], [accent] and [attention] are for marks and decoration only.
 */
@Immutable
data class SyncrColors(
    /** `--page`: the ground behind the library and settings. */
    val desk: Color,
    /** The note's paper ([NotePalette]); also the card thumbnails' ground. */
    val paper: Color,
    /** Note cards, the editing field's backing, inline panels. */
    val surface1: Color,
    /** Sheets, the tool bubble and its bar, the editor toolbar, the ask sheet. */
    val surface2: Color,
    /** Menus, popovers, dialogs and toasts: the highest level. */
    val surface3: Color,
    /** `--tint`: the pressed fill, the ink colour at 6%; also quiet placeholders. */
    val tint: Color,
    /** The selected fill, the ink colour at 10%. */
    val selectedFill: Color,
    /** `--ink`: primary text and glyphs. */
    val ink: Color,
    /** `--muted`: secondary text. */
    val muted: Color,
    /** `--faint`: disabled glyphs, decoration and the "not connected" ring. Not for reading text. */
    val faint: Color,
    /** `--line`: hairline dividers and card edges. */
    val dots: Color,
    /** `--line-strong`: chip outlines, dashed "New note" edge, floating chrome edge. */
    val lineStrong: Color,
    /** `--accent`. */
    val accent: Color,
    /** `--accent-soft`: the selected tool's fill. */
    val accentSoft: Color,
    /** `--amber`: needs-attention marks. */
    val attention: Color,
    /** `--amber-soft`: the attention banner's ground. */
    val attentionSoft: Color,
    /** Text and glyphs for permanent or destructive actions. */
    val destructive: Color,
    /** Behind sheets and dialogs. */
    val scrim: Color,
    val dark: Boolean,
)

internal val LightColors = SyncrColors(
    desk = Color(0xfffbfbfa),
    paper = Color(NotePalette.paper(false)),
    surface1 = Color(0xffffffff),
    surface2 = Color(0xffffffff),
    surface3 = Color(0xffffffff),
    tint = Color(0x0f1e1e1e),
    selectedFill = Color(0x1a1e1e1e),
    ink = Color(0xff1e1e1e),
    muted = Color(0xff6a6a6a),
    faint = Color(0xff9b9b9b),
    dots = Color(0x14000000),
    lineStrong = Color(0x24000000),
    accent = DustyRose,
    accentSoft = Color(0x29d39999),
    attention = Color(0xffd4a24c),
    attentionSoft = Color(0x24d4a24c),
    destructive = Color(0xffb3403a),
    scrim = Color(0x52000000),
    dark = false,
)

internal val DarkColors = SyncrColors(
    desk = Color(0xff141414),
    paper = Color(NotePalette.paper(true)),
    surface1 = Color(0xff1b1b1b),
    surface2 = Color(0xff222221),
    surface3 = Color(0xff2a2a29),
    tint = Color(0x0ff0efea),
    selectedFill = Color(0x1af0efea),
    ink = Color(0xfff0efea),
    muted = Color(0xffa8a7a1),
    faint = Color(0xff8f8e88),
    dots = Color(0x17ffffff),
    lineStrong = Color(0x29ffffff),
    accent = DustyRose,
    accentSoft = Color(0x33d39999),
    attention = Color(0xffe0b25c),
    attentionSoft = Color(0x2ee0b25c),
    destructive = Color(0xffee8a82),
    scrim = Color(0x99000000),
    dark = true,
)
