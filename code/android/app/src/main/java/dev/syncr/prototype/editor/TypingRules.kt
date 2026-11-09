package dev.syncr.prototype.editor

import dev.syncr.prototype.notes.MdKind
import dev.syncr.prototype.notes.SpanKind
import dev.syncr.prototype.notes.TextMarkdown

/** Numbered lists count on from their first number (T9). */
internal object Lists {
    /**
     * Rewrites the numbers of every numbered run (or only the run holding line [only]) so each counts on from its first
     * number, and moves the selection with the digits.
     */
    fun renumber(s: TextSel, only: Int? = null): TextSel {
        val lines = s.text.split("\n")
        val counters = HashMap<Int, Int>()
        val edits = ArrayList<Triple<Int, Int, String>>() // offset, removed length, replacement
        val runOf = IntArray(lines.size) { -1 }
        var offset = 0
        var run = 0
        var fence = false
        val starts = IntArray(lines.size)
        for ((i, line) in lines.withIndex()) {
            starts[i] = offset
            if (line.trimStart().startsWith("```")) { fence = !fence; counters.clear() }
            else if (!fence) {
                val p = Prefixes.of(line)
                if (line.isBlank()) { counters.clear(); run++ }
                else if (p.marker == Marker.NUMBER) {
                    counters.keys.removeAll { it > p.indent }
                    val next = counters[p.indent]?.plus(1) ?: p.number.also { run++ }
                    counters[p.indent] = next
                    runOf[i] = run
                    if (next != p.number) {
                        val digits = line.substring(p.indent).takeWhile { it.isDigit() }
                        edits += Triple(offset + p.indent, digits.length, next.toString())
                    }
                } else {
                    val indent = p.indent
                    counters.keys.removeAll { it >= indent }
                    if (indent == 0 && p.marker != Marker.NUMBER) counters.clear()
                }
            }
            offset += line.length + 1
        }
        val chosen = if (only == null) edits else edits.filter { e ->
            val i = starts.indexOfLast { it <= e.first }
            runOf.getOrElse(only) { -2 } != -1 && runOf[i] == runOf[only]
        }
        if (chosen.isEmpty()) return s
        var text = s.text; var a = s.start; var b = s.end
        for ((at, removed, insert) in chosen.sortedByDescending { it.first }) {
            text = text.substring(0, at) + insert + text.substring(at + removed)
            a = shift(a, at, removed, insert.length); b = shift(b, at, removed, insert.length)
        }
        return TextSel(text, a, b)
    }
}

/** Obsidian-style typing rules as pure functions over (text, selection) (editor-v2 contract §4). */
internal object Typing {
    private val wrappers = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '`' to '`', '*' to '*', '~' to '~', '=' to '=')
    private val autoPair = setOf('(', '[', '{', '"', '`')
    private val closers = setOf(')', ']', '}', '"', '`')

    // ---- Enter ----

    /** Enter with a caret: list, task, number and quote continuation, leaving a list, opening a fence. Null means a plain newline. */
    fun enter(s: TextSel): TextSel? {
        if (!s.caret) return null
        val at = lineAt(s.text, s.start)
        val line = s.text.substring(at.start, at.end)
        val kind = Prefixes.kindAt(s.text, at.start)
        if (kind is MdKind.Code || kind is MdKind.Rule) return null
        if (kind is MdKind.Fence) return fenceEnter(s, at, line)
        val p = Prefixes.of(line)
        if (p.marker == Marker.NONE || p.marker == Marker.HEADING) return null
        val caret = s.start - at.start
        if (caret < p.length) return null
        val words = line.substring(p.length)
        if (words.isBlank() && caret == line.length) {
            // An empty item ends the list; an indented one steps out a level first (T5, T6).
            val replacement = if (p.indent > 0) line.substring(0, maxOf(0, p.indent - INDENT_STEP)).let { it + line.substring(p.indent) } else ""
            return renumbered(TextSel(s.text.substring(0, at.start) + replacement + s.text.substring(at.end), at.start + replacement.length), at.start)
        }
        val indent = line.substring(0, p.indent)
        val marker = when (p.marker) {
            Marker.BULLET -> "${p.bullet} "
            Marker.TASK -> "${p.bullet} [ ] "
            Marker.NUMBER -> "${p.number + 1}. "
            else -> "> "
        }
        val insert = "\n$indent$marker"
        val text = s.text.substring(0, s.start) + insert + s.text.substring(s.start)
        return renumbered(TextSel(text, s.start + insert.length), at.start)
    }

    private fun renumbered(s: TextSel, lineOffset: Int): TextSel =
        Lists.renumber(s, only = s.text.substring(0, lineOffset).count { it == '\n' })

    private fun fenceEnter(s: TextSel, at: LineSpan, line: String): TextSel? {
        if (!line.trimStart().startsWith("```") || s.start != at.end) return null
        // An opening fence: an even number of fence lines come before it.
        val before = TextMarkdown.lines(s.text).count { it.start < at.start && it.kind is MdKind.Fence }
        if (before % 2 != 0) return null
        val insert = "\n\n```"
        return TextSel(s.text.substring(0, s.start) + insert + s.text.substring(s.start), s.start + 1)
    }

    // ---- Tab ----

    /** Indents or outdents the lines the selection touches by one step (T8); a caret in a plain line inserts the step. */
    fun tab(s: TextSel, outdent: Boolean): TextSel {
        val first = lineAt(s.text, s.lo)
        val last = lineAt(s.text, if (s.hi > s.lo && s.text[s.hi - 1] == '\n') s.hi - 1 else s.hi)
        val listLine = Prefixes.of(s.text.substring(first.start, first.end)).marker.let { it != Marker.NONE && it != Marker.HEADING }
        if (!outdent && s.caret && !listLine) {
            return TextSel(s.text.substring(0, s.start) + " ".repeat(INDENT_STEP) + s.text.substring(s.start), s.start + INDENT_STEP)
        }
        val starts = ArrayList<Int>()
        var at = first.start
        while (at <= last.start) { starts += at; at = lineAt(s.text, at).end + 1 }
        var text = s.text; var a = s.start; var b = s.end
        for (start in starts.asReversed()) { // later lines first, so earlier offsets stay valid
            val end = lineAt(text, start).end
            if (outdent) {
                val n = minOf(INDENT_STEP, text.substring(start, end).takeWhile { it == ' ' }.length)
                if (n == 0) continue
                text = text.substring(0, start) + text.substring(start + n)
                a = shift(a, start, n, 0); b = shift(b, start, n, 0)
            } else if (end > start) {
                text = text.substring(0, start) + " ".repeat(INDENT_STEP) + text.substring(start)
                if (a >= start) a += INDENT_STEP
                if (b >= start) b += INDENT_STEP
            }
        }
        return Lists.renumber(TextSel(text, a.coerceIn(0, text.length), b.coerceIn(0, text.length)))
    }

    // ---- Backspace and paired characters ----

    /** Backspace with a caret after a list marker (T10) or between a pair (T14). Null means a plain delete. */
    fun backspace(s: TextSel): TextSel? {
        if (!s.caret || s.start == 0) return null
        val at = lineAt(s.text, s.start)
        val line = s.text.substring(at.start, at.end)
        val p = Prefixes.of(line)
        val caret = s.start - at.start
        if (p.marker != Marker.NONE && p.marker != Marker.HEADING && caret == p.length) {
            // A marker goes; an indented one first steps out a level.
            val cut = if (p.indent >= INDENT_STEP) INDENT_STEP else p.length
            val text = s.text.substring(0, at.start) + s.text.substring(at.start + cut)
            return renumbered(TextSel(text, s.start - cut), at.start)
        }
        if (s.start < s.text.length) {
            val open = s.text[s.start - 1]; val close = s.text[s.start]
            if (wrappers[open] == close && open in autoPair) {
                return TextSel(s.text.substring(0, s.start - 1) + s.text.substring(s.start + 1), s.start - 1)
            }
        }
        return null
    }

    /** A typed character [ch]: wraps a selection, auto-pairs, steps over a closer. Null means insert it as typed. */
    fun type(s: TextSel, ch: Char): TextSel? {
        if (!s.caret) {
            val close = wrappers[ch] ?: return null
            val word = s.text.substring(s.lo, s.hi)
            val text = s.text.substring(0, s.lo) + ch + word + close + s.text.substring(s.hi)
            return TextSel(text, s.lo + 1, s.lo + 1 + word.length)
        }
        if (ch in closers && s.start < s.text.length && s.text[s.start] == ch) {
            // Stepping over: a quote or backtick only when it closes a pair opened before the caret on this line.
            if (ch != '"' && ch != '`' || s.text.substring(lineAt(s.text, s.start).start, s.start).count { it == ch } % 2 == 1) return TextSel(s.text, s.start + 1)
        }
        if (ch in autoPair) {
            val next = s.text.getOrNull(s.start)
            val prev = s.text.getOrNull(s.start - 1)
            val quoteLike = ch == '"' || ch == '`'
            if (quoteLike && prev != null && prev.isLetterOrDigit()) return null
            if (next == null || next.isWhitespace() || next in closers || next in ".,;:") {
                return TextSel(s.text.substring(0, s.start) + ch + wrappers.getValue(ch) + s.text.substring(s.start), s.start + 1)
            }
        }
        return null
    }

    /** A URL pasted over a selection becomes a link (T15). Null means paste as it is. */
    fun paste(s: TextSel, pasted: String): TextSel? {
        if (s.caret) return null
        val url = pasted.trim()
        if (!Regex("^https?://\\S+$").matches(url)) return null
        val word = s.text.substring(s.lo, s.hi)
        val link = "[$word]($url)"
        return TextSel(s.text.substring(0, s.lo) + link + s.text.substring(s.hi), s.lo + link.length)
    }

    // ---- Formatting toggles ----

    enum class Format(val open: String, val close: String, val span: SpanKind) {
        BOLD("**", "**", SpanKind.BOLD), ITALIC("*", "*", SpanKind.ITALIC), STRIKE("~~", "~~", SpanKind.STRIKE),
        HIGHLIGHT("==", "==", SpanKind.HIGHLIGHT), CODE("`", "`", SpanKind.CODE), LINK("[", "](url)", SpanKind.LINK),
    }

    /** True when the selection or caret already sits in a span of [f]. */
    fun active(s: TextSel, f: Format): Boolean = spanAround(s, f) != null

    private fun spanAround(s: TextSel, f: Format): dev.syncr.prototype.notes.MdSpan? {
        val at = lineAt(s.text, s.lo)
        val line = TextMarkdown.lines(s.text).firstOrNull { it.start == at.start } ?: return null
        return line.spans.firstOrNull { it.kind == f.span && it.start <= s.lo && s.hi <= it.end && (it.syntax.isNotEmpty()) }
            ?: line.spans.firstOrNull { it.kind == f.span && it.syntax.isNotEmpty() && s.lo <= it.start && it.end <= s.hi && lineAt(s.text, s.hi).start == at.start }
    }

    /** Wraps or unwraps (T11, T12). A caret takes the word it is in; with no word, an empty pair with the caret between. */
    fun toggle(s: TextSel, f: Format): TextSel {
        spanAround(s, f)?.let { span ->
            var text = s.text; var a = s.start; var b = s.end
            for (r in span.syntax.sortedByDescending { it.first }) {
                val len = r.last - r.first + 1
                text = text.substring(0, r.first) + text.substring(r.last + 1)
                a = shift(a, r.first, len, 0); b = shift(b, r.first, len, 0)
            }
            return TextSel(text, a, b)
        }
        var lo = s.lo; var hi = s.hi
        if (s.caret) {
            while (lo > 0 && isWordChar(s.text[lo - 1])) lo--
            while (hi < s.text.length && isWordChar(s.text[hi])) hi++
        }
        val word = s.text.substring(lo, hi)
        if (f == Format.LINK) {
            val text = s.text.substring(0, lo) + "[" + word + "](url)" + s.text.substring(hi)
            val urlStart = lo + 1 + word.length + 2
            return TextSel(text, urlStart, urlStart + 3)
        }
        val text = s.text.substring(0, lo) + f.open + word + f.close + s.text.substring(hi)
        return if (s.caret) {
            TextSel(text, s.start + f.open.length)
        } else TextSel(text, lo + f.open.length, hi + f.open.length)
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'

    // ---- Line toggles ----

    /** Cycles the heading of the caret's line: none, 1, 2, 3, none. */
    fun cycleHeading(s: TextSel): TextSel {
        val at = lineAt(s.text, s.start)
        val p = Prefixes.of(s.text.substring(at.start, at.end))
        val next = if (p.marker == Marker.HEADING) p.level + 1 else 1
        return Slash.line(s, if (next > 3) "text" else "h$next")
    }

    /** Toggles the bullet of the caret's line. */
    fun toggleBullet(s: TextSel): TextSel {
        val at = lineAt(s.text, s.start)
        return Slash.line(s, if (Prefixes.of(s.text.substring(at.start, at.end)).marker == Marker.BULLET) "text" else "bullet")
    }

    /** `- a`, `- [ ] a`, `- [x] a`, `- [ ] a` (T17): a plain or other list line becomes an open task first. */
    fun toggleTask(s: TextSel): TextSel {
        val at = lineAt(s.text, s.start)
        val line = s.text.substring(at.start, at.end)
        val p = Prefixes.of(line)
        if (p.marker != Marker.TASK) return Slash.line(s, "todo")
        val box = at.start + p.length - 3 // the character inside "[ ] "
        val text = s.text.substring(0, box) + (if (p.checked) " " else "x") + s.text.substring(box + 1)
        return TextSel(text, s.start, s.end)
    }

    /** A tap on a rendered task box (T18): flips the box in line [lineIndex] and moves nothing else. Null if it is no task. */
    fun flipTask(text: String, lineIndex: Int): TextSel? {
        val lines = text.split("\n")
        if (lineIndex !in lines.indices) return null
        val start = lines.take(lineIndex).sumOf { it.length + 1 }
        val p = Prefixes.of(lines[lineIndex])
        if (p.marker != Marker.TASK) return null
        val box = start + p.length - 3
        return TextSel(text.substring(0, box) + (if (p.checked) " " else "x") + text.substring(box + 1), 0)
    }

    // ---- Reconciling what the keyboard did with the rules ----

    /**
     * The editor lets the keyboard change the text, then asks what the rules make of it: [before] is the state just
     * before the change, [afterText] the text after it. Returns the corrected state, or null to accept it as typed.
     * While the keyboard [composing] a word, nothing is paired (§4).
     */
    fun reconcile(before: TextSel, afterText: String, composing: Boolean): TextSel? {
        if (before.caret && before.start > 0 && afterText.length < before.text.length) {
            for (n in 1..2) {
                if (before.start - n >= 0 && afterText == before.text.substring(0, before.start - n) + before.text.substring(before.start)) {
                    val special = if (n == 1 || !before.text[before.start - 1].isLowSurrogate()) backspace(before) else null
                    return special?.let { renumberAfter(it) }
                        ?: renumberAfter(TextSel(afterText, before.start - n)).takeIf { it.text != afterText }
                }
            }
        }
        val ins = inserted(before, afterText)
        if (ins == null) return null
        val (lo, hi, typed) = ins
        val caretAfter = lo + typed.length
        var result: TextSel? = null
        if (typed == "\n") {
            result = if (before.caret) enter(before) else null
        } else if (!composing && typed.length == 1 && before.lo == lo && before.hi == hi) {
            result = type(before, typed[0])
        } else if (!composing && typed.length > 1 && !before.caret && before.lo == lo && before.hi == hi) {
            result = paste(before, typed)
        }
        val settled = result ?: TextSel(afterText, caretAfter.coerceIn(0, afterText.length))
        val numbered = renumberAfter(settled)
        return if (result == null && numbered === settled) null else numbered
    }

    private fun renumberAfter(s: TextSel): TextSel {
        val line = s.text.substring(0, s.start).count { it == '\n' }
        val at = lineAt(s.text, s.start)
        val nearNumber = Prefixes.of(s.text.substring(at.start, at.end)).marker == Marker.NUMBER ||
            (line > 0 && Prefixes.of(s.text.lines().getOrElse(line - 1) { "" }).marker == Marker.NUMBER)
        return if (nearNumber) Lists.renumber(s, only = line) else s
    }

    /** [lo, hi) of [before] was replaced by the returned text, or null when the change cannot be read that way. */
    fun inserted(before: TextSel, afterText: String): Triple<Int, Int, String>? {
        val lo = before.lo; val hi = before.hi
        val tailLength = before.text.length - hi
        if (afterText.length >= lo + tailLength && afterText.startsWith(before.text.substring(0, lo)) &&
            afterText.endsWith(before.text.substring(hi))) {
            return Triple(lo, hi, afterText.substring(lo, afterText.length - tailLength))
        }
        // A deletion by backspace has the caret at the end of what went; a general edit is read by its common ends.
        var prefix = 0
        while (prefix < minOf(before.text.length, afterText.length) && before.text[prefix] == afterText[prefix]) prefix++
        var suffix = 0
        while (suffix < minOf(before.text.length, afterText.length) - prefix &&
            before.text[before.text.length - 1 - suffix] == afterText[afterText.length - 1 - suffix]) suffix++
        return Triple(prefix, before.text.length - suffix, afterText.substring(prefix, afterText.length - suffix))
    }
}
