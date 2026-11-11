package dev.syncr.prototype.editor

import dev.syncr.prototype.notes.MdKind
import dev.syncr.prototype.notes.SpanKind
import dev.syncr.prototype.notes.TextMarkdown

internal enum class SlashKind { LINE, BLOCK, INLINE, ACTION }
internal enum class SlashAction { ASK, DRAW, NOTE_COMPLETER }

/** One row of the slash menu (editor-v2 contract §3.5). [icon] is a short glyph the panel draws. */
internal data class SlashCommand(val name: String, val aliases: List<String>, val title: String, val group: String, val kind: SlashKind, val icon: String)

/** The `/` and what follows it up to the caret: [start] is the slash's offset, [end] the caret, [query] the typed word. */
internal data class SlashToken(val start: Int, val end: Int, val query: String)

/** What running a command did: the new text and selection, and the app action to take afterwards. */
internal data class SlashResult(val edit: TextSel, val action: SlashAction? = null)

internal object Slash {
    private fun c(name: String, aliases: String, title: String, group: String, kind: SlashKind, icon: String) =
        SlashCommand(name, aliases.split(' ').filter { it.isNotEmpty() }, title, group, kind, icon)

    /** The table of contract §3.5, in menu order. */
    val commands: List<SlashCommand> = listOf(
        c("text", "p paragraph", "Text", "Basic", SlashKind.LINE, "T"),
        c("h1", "heading1", "Heading 1", "Basic", SlashKind.LINE, "H1"),
        c("h2", "heading2", "Heading 2", "Basic", SlashKind.LINE, "H2"),
        c("h3", "heading3", "Heading 3", "Basic", SlashKind.LINE, "H3"),
        c("bullet", "ul list", "Bulleted list", "Basic", SlashKind.LINE, "•"),
        c("number", "ol ordered", "Numbered list", "Basic", SlashKind.LINE, "1."),
        c("todo", "task checkbox check", "To-do", "Basic", SlashKind.LINE, "☐"),
        c("quote", "blockquote", "Quote", "Basic", SlashKind.LINE, "❝"),
        c("code", "codeblock", "Code block", "Basic", SlashKind.BLOCK, "{}"),
        c("divider", "line hr rule", "Divider", "Basic", SlashKind.BLOCK, "—"),
        c("dashedline", "dashed", "Dashed line", "Basic", SlashKind.BLOCK, "- -"),
        c("dots", "dotted", "Dotted line", "Basic", SlashKind.BLOCK, "···"),
        c("bold", "b strong", "Bold", "Format", SlashKind.INLINE, "B"),
        c("italic", "i em", "Italic", "Format", SlashKind.INLINE, "I"),
        c("strike", "strikethrough", "Strikethrough", "Format", SlashKind.INLINE, "S"),
        c("highlight", "mark", "Highlight", "Format", SlashKind.INLINE, "=="),
        c("inlinecode", "mono", "Inline code", "Format", SlashKind.INLINE, "`"),
        c("link", "url", "Link", "Insert", SlashKind.INLINE, "↗"),
        c("note", "wikilink", "Link to a note", "Insert", SlashKind.INLINE, "[["),
        c("date", "today", "Date", "Insert", SlashKind.INLINE, "D"),
        c("time", "now", "Time", "Insert", SlashKind.INLINE, "t"),
        c("ask", "syncr explain", "Ask Syncr", "Syncr", SlashKind.ACTION, "?"),
        c("draw", "picture sketch", "Ask for a picture", "Syncr", SlashKind.ACTION, "✎"),
    )

    private fun isTokenChar(c: Char) = c.isLetterOrDigit() || c == '-'

    /**
     * The token the caret is in, or null: `/` must follow the start of the line or whitespace, and sit outside fenced code,
     * inline code, a link target and a bare URL (§3.1).
     */
    fun token(text: String, caret: Int): SlashToken? {
        if (caret < 1 || caret > text.length) return null
        var i = caret
        while (i > 0 && isTokenChar(text[i - 1]) && text[i - 1] != '/') i--
        if (i == 0 || text[i - 1] != '/') return null
        val slash = i - 1
        if (slash > 0 && !text[slash - 1].isWhitespace()) return null
        if (blocked(text, slash)) return null
        return SlashToken(slash, caret, text.substring(slash + 1, caret))
    }

    private fun blocked(text: String, slash: Int): Boolean {
        val line = TextMarkdown.lines(text).firstOrNull { slash >= it.start && slash <= it.end } ?: return false
        if (line.kind is MdKind.Code || line.kind is MdKind.Fence) return true
        for (span in line.spans) {
            if (slash < span.start || slash >= span.end) continue
            when (span.kind) {
                SpanKind.CODE -> return true
                SpanKind.LINK -> if (span.syntax.isEmpty() || slash >= (span.syntax.getOrNull(1)?.first ?: Int.MAX_VALUE)) return true
                else -> Unit
            }
        }
        return false
    }

    private fun subsequence(name: String, query: String): Boolean {
        var at = 0
        for (ch in query) { at = name.indexOf(ch, at); if (at < 0) return false; at++ }
        return true
    }

    /** Commands matching [query], by rank then table order (§3.3). An empty query lists everything. */
    fun matches(query: String, all: List<SlashCommand> = commands): List<SlashCommand> {
        val q = query.lowercase()
        if (q.isEmpty()) return all
        fun rank(cmd: SlashCommand): Int = when {
            cmd.name.startsWith(q) || cmd.aliases.any { it.startsWith(q) } -> 1
            cmd.title.lowercase().split(' ', '-').any { it.startsWith(q) } -> 2
            subsequence(cmd.name, q) -> 3
            else -> 0
        }
        return all.mapIndexed { index, cmd -> Triple(rank(cmd), index, cmd) }.filter { it.first > 0 }
            .sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
    }

    /**
     * Runs [cmd] on [state] for [token]: removes exactly the token, applies the command, and returns the new text and
     * selection. The caller records it as one undo step. [date] and [time] fill the Insert group.
     */
    fun run(state: TextSel, token: SlashToken, cmd: SlashCommand, date: String, time: String): SlashResult {
        val text = state.text
        var cutStart = token.start
        val cutEnd = token.end
        // A line, block or action command takes the blank that separated the token from the words.
        if (cmd.kind != SlashKind.INLINE && cutStart > 0 && text[cutStart - 1] == ' ' &&
            (cutEnd == text.length || text[cutEnd] == '\n' || text[cutEnd] == ' ')) cutStart--
        val stripped = text.substring(0, cutStart) + text.substring(cutEnd)
        val caret = cutStart
        return when (cmd.kind) {
            SlashKind.LINE -> SlashResult(line(TextSel(stripped, caret), cmd.name))
            SlashKind.BLOCK -> SlashResult(block(TextSel(stripped, caret), cmd.name))
            SlashKind.ACTION -> SlashResult(TextSel(stripped, caret), if (cmd.name == "ask") SlashAction.ASK else SlashAction.DRAW)
            SlashKind.INLINE -> inline(TextSel(stripped, caret), cmd.name, date, time)
        }
    }

    private fun inline(s: TextSel, name: String, date: String, time: String): SlashResult {
        fun put(insert: String, from: Int = insert.length, to: Int = from, action: SlashAction? = null) =
            SlashResult(TextSel(s.text.substring(0, s.start) + insert + s.text.substring(s.start), s.start + from, s.start + to), action)
        return when (name) {
            "bold" -> put("****", 2)
            "italic" -> put("**", 1)
            "strike" -> put("~~~~", 2)
            "highlight" -> put("====", 2)
            "inlinecode" -> put("``", 1)
            "link" -> put("[text](url)", 1, 5)
            "note" -> put("[[]]", 2, action = SlashAction.NOTE_COMPLETER)
            "date" -> put(date)
            "time" -> put(time)
            else -> put("")
        }
    }

    /** Replaces the block prefix of the caret's line, keeping its indent and words (§3.4). */
    fun line(s: TextSel, name: String): TextSel {
        val at = lineAt(s.text, s.start)
        val line = s.text.substring(at.start, at.end)
        val prefix = Prefixes.of(line)
        val indent = line.substring(0, prefix.indent)
        val words = line.substring(prefix.length)
        val marker = when (name) {
            "text" -> ""
            "h1" -> "# "; "h2" -> "## "; "h3" -> "### "
            "bullet" -> "- "
            "number" -> "1. "
            "todo" -> "- [ ] "
            "quote" -> "> "
            else -> ""
        }
        val keepIndent = if (prefix.marker == Marker.HEADING || name.startsWith("h")) "" else indent
        val rebuilt = keepIndent + marker + words
        val text = s.text.substring(0, at.start) + rebuilt + s.text.substring(at.end)
        val end = at.start + rebuilt.length
        return if (name == "number") Lists.renumber(TextSel(text, end), only = lineIndex(text, at.start)) else TextSel(text, end)
    }

    private fun lineIndex(text: String, offset: Int) = text.substring(0, offset).count { it == '\n' }

    /** A block on its own lines: replaces an otherwise empty line, else goes after the current one (§3.4). */
    fun block(s: TextSel, name: String): TextSel {
        val at = lineAt(s.text, s.start)
        val empty = s.text.substring(at.start, at.end).isBlank()
        val body = when (name) { "code" -> "```\n\n```"; "divider" -> "---\n"; "dashedline" -> "- - -\n"; else -> "...\n" }
        val before = s.text.substring(0, if (empty) at.start else at.end)
        val after = s.text.substring(at.end)
        val lead = if (empty) "" else "\n"
        // Past a block there is always a fresh line for the caret; a code block puts it on the empty middle line.
        val text = before + lead + body + after
        val caret = if (name == "code") before.length + lead.length + 4 else before.length + lead.length + body.length
        return TextSel(text, caret)
    }
}

/**
 * The open menu for the text being edited: follows the token, remembers a dismissed one (§3.1 closes on Escape and does
 * not reopen for that token) and the selected row.
 */
internal class SlashMenu {
    class Open(val token: SlashToken, val rows: List<SlashCommand>, val selected: Int)

    private var dismissed: Int? = null
    private var lastToken: SlashToken? = null
    var selected = 0
        private set

    /** The menu for [s], or null when it is closed. */
    fun update(s: TextSel): Open? {
        val token = if (s.caret) Slash.token(s.text, s.start) else null
        if (token == null) { dismissed = null; lastToken = null; return null }
        if (dismissed == token.start) return null
        val rows = Slash.matches(token.query)
        if (token.start != lastToken?.start || token.query != lastToken?.query) selected = 0
        lastToken = token
        if (rows.isEmpty()) return null
        selected = selected.coerceIn(0, rows.lastIndex)
        return Open(token, rows, selected)
    }

    fun escape() { lastToken?.let { dismissed = it.start } }
    fun select(index: Int, count: Int) { if (count > 0) selected = ((index % count) + count) % count }
}
