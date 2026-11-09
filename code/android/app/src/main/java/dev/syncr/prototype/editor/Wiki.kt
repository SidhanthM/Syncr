package dev.syncr.prototype.editor

/** `[[` note completion (§3.2): the text typed after `[[` on this line up to the caret, and where the brackets start. */
internal data class WikiToken(val start: Int, val end: Int, val query: String)

internal object Wiki {
    fun token(text: String, caret: Int): WikiToken? {
        val line = lineAt(text, caret)
        val open = text.lastIndexOf("[[", caret - 1).takeIf { it >= line.start && it + 2 <= caret } ?: return null
        val typed = text.substring(open + 2, caret)
        if (typed.any { it == ']' || it == '\n' || it == '|' }) return null
        return WikiToken(open, caret, typed)
    }

    /** Titles containing [query] (case-insensitive), those starting with it first. */
    fun matches(query: String, titles: List<String>, limit: Int = 20): List<String> {
        val q = query.trim().lowercase()
        return titles.filter { it.isNotBlank() && it.lowercase().contains(q) }
            .sortedBy { if (it.lowercase().startsWith(q)) 0 else 1 }.take(limit)
    }

    /** Picks [title]: it replaces what was typed, and the caret goes past the closing brackets. */
    fun complete(s: TextSel, token: WikiToken, title: String): TextSel {
        val after = s.text.substring(token.end)
        val closed = after.startsWith("]]")
        val text = s.text.substring(0, token.start + 2) + title + (if (closed) after else "]]$after")
        return TextSel(text, token.start + 2 + title.length + 2)
    }
}
