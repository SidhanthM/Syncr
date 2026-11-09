package dev.syncr.prototype.editor

import dev.syncr.prototype.notes.MdKind
import dev.syncr.prototype.notes.TextMarkdown

/** A text and its selection, UTF-16 offsets; [start] equals [end] for a caret. The unit every editing rule maps over. */
internal data class TextSel(val text: String, val start: Int, val end: Int = start) {
    val caret get() = start == end
    val lo get() = minOf(start, end)
    val hi get() = maxOf(start, end)
    init { require(start in 0..text.length && end in 0..text.length) { "selection $start..$end outside ${text.length}" } }
}

/** A line of the text: [start] and [end] exclude the newline. */
internal data class LineSpan(val start: Int, val end: Int)

internal fun lineAt(text: String, offset: Int): LineSpan {
    val at = offset.coerceIn(0, text.length)
    val start = text.lastIndexOf('\n', at - 1).let { if (at == 0) 0 else it + 1 }
    val end = text.indexOf('\n', at).let { if (it < 0) text.length else it }
    return LineSpan(start, end)
}

internal enum class Marker { NONE, HEADING, BULLET, TASK, NUMBER, QUOTE }

/**
 * The block prefix of one line: [indent] leading blanks, then the marker, then the words. [length] counts the indent
 * and the marker (where the words begin). [checked] and [number] are meaningful for TASK and NUMBER, [bullet] is the
 * bullet character, [level] the heading level.
 */
internal data class Prefix(val marker: Marker, val indent: Int, val length: Int, val checked: Boolean = false, val number: Int = 0,
    val bullet: Char = '-', val level: Int = 0)

internal object Prefixes {
    private val heading = Regex("^(#{1,6})\\s+")
    private val task = Regex("^(\\s*)([-*]) \\[([ xX])\\] ")
    private val bullet = Regex("^(\\s*)([-*]) ")
    private val numbered = Regex("^(\\s*)([0-9]+)\\. ")
    private val quote = Regex("^(\\s*)> ")

    /** Reads [line] (no newline) as the Markdown the reading layer reads. */
    fun of(line: String): Prefix {
        heading.find(line)?.let { return Prefix(Marker.HEADING, 0, it.value.length, level = it.groupValues[1].length) }
        task.find(line)?.let { return Prefix(Marker.TASK, it.groupValues[1].length, it.value.length, checked = it.groupValues[3] != " ", bullet = it.groupValues[2][0]) }
        bullet.find(line)?.let { return Prefix(Marker.BULLET, it.groupValues[1].length, it.value.length, bullet = it.groupValues[2][0]) }
        numbered.find(line)?.let { return Prefix(Marker.NUMBER, it.groupValues[1].length, it.value.length, number = it.groupValues[2].toIntOrNull() ?: 1) }
        quote.find(line)?.let { return Prefix(Marker.QUOTE, it.groupValues[1].length, it.value.length) }
        return Prefix(Marker.NONE, line.takeWhile { it == ' ' || it == '\t' }.length, 0)
    }

    /** The Markdown kind of the line holding [offset], with fences resolved over the whole text. */
    fun kindAt(text: String, offset: Int): MdKind {
        val at = lineAt(text, offset)
        return TextMarkdown.lines(text).firstOrNull { it.start == at.start }?.kind ?: MdKind.Plain
    }

    /** True when [offset] sits on a fence or code line, or on a horizontal rule: lines no rule rewrites. */
    fun isCodeAt(text: String, offset: Int): Boolean = kindAt(text, offset).let { it is MdKind.Code || it is MdKind.Fence }
}

internal const val INDENT_STEP = 4

/** Offsets in a text after a set of replacements: moves a caret across edits made elsewhere. */
internal fun shift(offset: Int, at: Int, removed: Int, inserted: Int): Int = when {
    offset <= at -> offset
    offset >= at + removed -> offset - removed + inserted
    else -> at + inserted
}
