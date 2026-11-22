package dev.syncr.prototype.notes

import android.content.Context
import dev.syncr.core.PagePattern
import dev.syncr.core.Rgba

/** A named page size in millimetres (portrait, short by long), as `page_sizes()` names them. */
data class SizeOption(val name: String, val mmWidth: Float, val mmHeight: Float)

/** The sizes the New note screen offers, in xnotes' order; the last is [CUSTOM]. */
object NewNoteSizes {
    const val CUSTOM = "Custom"
    val named = listOf(SizeOption("A4", 210f, 297f), SizeOption("A5", 148f, 210f), SizeOption("Letter", 215.9f, 279.4f),
        SizeOption("Legal", 215.9f, 355.6f), SizeOption("Slide 16:9", 190.5f, 338.7f), SizeOption("Slide 4:3", 190.5f, 254f))
    const val MIN_MM = 10f
    const val MAX_MM = 2000f
    /** Note units per millimetre: 1600 units across 210 mm. */
    const val UNITS_PER_MM = 1600f / 210f
}

/**
 * What the New note screen has chosen, and what is remembered as the next defaults (as xnotes remembers its new-note style):
 * a size (or Custom, in [customWidth] and [customHeight] millimetres), orientation, the page template, the paper colour
 * (null: the theme's) and the pattern colour (null: the default grey) with its [opacity] (0..1).
 */
data class NewNoteChoice(
    val size: String = "A4",
    val landscape: Boolean = false,
    val customWidth: String = "210",
    val customHeight: String = "297",
    val template: PagePattern = PagePattern.NONE,
    val paper: Int? = null,
    val patternColour: Int? = null,
    val opacity: Float = 0.5f,
) {
    val custom get() = size == NewNoteSizes.CUSTOM

    /** The page's width and height in millimetres, or null for a Custom size that is not a number. */
    fun millimetres(): Pair<Float, Float>? {
        if (custom) {
            val w = customWidth.trim().replace(',', '.').toFloatOrNull() ?: return null
            val h = customHeight.trim().replace(',', '.').toFloatOrNull() ?: return null
            return w to h
        }
        val option = NewNoteSizes.named.firstOrNull { it.name == size } ?: NewNoteSizes.named[0]
        return if (landscape) option.mmHeight to option.mmWidth else option.mmWidth to option.mmHeight
    }

    /** "Each side between 10 and 2000 mm" for a Custom size outside the range (or not a number); null when it is fine. */
    fun problem(): String? {
        val (w, h) = millimetres() ?: return if (custom) RANGE_ERROR else null
        return if (w in NewNoteSizes.MIN_MM..NewNoteSizes.MAX_MM && h in NewNoteSizes.MIN_MM..NewNoteSizes.MAX_MM) null else RANGE_ERROR
    }

    /** The core's layout for the new note, or null while the size is invalid. */
    fun layout(): NewNoteLayout? {
        if (problem() != null) return null
        val (w, h) = millimetres() ?: return null
        val pattern = template != PagePattern.NONE
        val colour = (patternColour ?: DEFAULT_PATTERN).let { Rgba(((it shr 16) and 0xff).toUByte(), ((it shr 8) and 0xff).toUByte(), (it and 0xff).toUByte(), (opacity.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toUByte()) }
        return NewNoteLayout(w * NewNoteSizes.UNITS_PER_MM, h * NewNoteSizes.UNITS_PER_MM, template,
            paper?.let { Rgba(((it shr 16) and 0xff).toUByte(), ((it shr 8) and 0xff).toUByte(), (it and 0xff).toUByte(), 255u) },
            if (pattern) colour else null)
    }

    /** Switching orientation on a Custom size swaps its two fields so the shape follows. */
    fun withOrientation(toLandscape: Boolean): NewNoteChoice {
        if (!custom) return copy(landscape = toLandscape)
        val (w, h) = millimetres() ?: return copy(landscape = toLandscape)
        return if ((w > h) == toLandscape) copy(landscape = toLandscape) else copy(landscape = toLandscape, customWidth = customHeight, customHeight = customWidth)
    }

    companion object {
        const val RANGE_ERROR = "Each side between 10 and 2000 mm"
        /** The default pattern grey, rgb(150,150,150). */
        const val DEFAULT_PATTERN = 0x969696
    }
}

/** Reading and writing [NewNoteChoice] as one string, so it is unit-tested without Android. */
object NewNoteChoiceCodec {
    fun encode(c: NewNoteChoice) = listOf(c.size, c.landscape, c.customWidth, c.customHeight, c.template.name, c.paper ?: "", c.patternColour ?: "", c.opacity).joinToString("|")

    fun decode(text: String?): NewNoteChoice {
        val p = text?.split('|') ?: return NewNoteChoice()
        if (p.size < 8) return NewNoteChoice()
        val d = NewNoteChoice()
        val size = p[0].takeIf { s -> s == NewNoteSizes.CUSTOM || NewNoteSizes.named.any { it.name == s } } ?: d.size
        return NewNoteChoice(size, p[1] == "true", p[2].ifBlank { d.customWidth }, p[3].ifBlank { d.customHeight },
            PagePattern.entries.firstOrNull { it.name == p[4] } ?: d.template, p[5].toIntOrNull(), p[6].toIntOrNull(),
            (p[7].toFloatOrNull() ?: d.opacity).coerceIn(0f, 1f))
    }

    private const val FILE = "syncr-new-note"
    fun get(context: Context): NewNoteChoice = try {
        decode(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("choice", null))
    } catch (_: Exception) { NewNoteChoice() }

    fun set(context: Context, choice: NewNoteChoice) {
        try { context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("choice", encode(choice)).apply() } catch (_: Exception) { }
    }
}

/** "Untitled canvas", then "Untitled canvas 2" and so on: the first name that no existing title already uses (ignoring case). */
fun nextUntitled(existing: Collection<String>, base: String = "Untitled canvas"): String {
    val taken = existing.map { it.trim().lowercase() }.toSet()
    if (base.lowercase() !in taken) return base
    var n = 2
    while ("$base $n".lowercase() in taken) n++
    return "$base $n"
}
