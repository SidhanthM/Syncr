package dev.syncr.prototype.editor

/**
 * Undo and redo for one editing session (§5.3 toolbar). Typing runs coalesce while they are plain, close in time and
 * unbroken; a command, a rule's rewrite or a paste is a step of its own, so undoing a slash command restores the token
 * as typed (S20).
 */
internal class EditHistory(initial: TextSel, private val gapMs: Long = 1000, private val limit: Int = 400) {
    private val states = arrayListOf(initial)
    private var at = 0
    private var lastCoalescible = false
    private var lastAt = 0L

    val current get() = states[at]
    val canUndo get() = at > 0
    val canRedo get() = at < states.lastIndex

    /** Records the state after a change. [coalesce] marks plain typing, which merges into the previous such step. */
    fun record(next: TextSel, coalesce: Boolean, now: Long) {
        if (next == states[at]) return
        while (states.lastIndex > at) states.removeAt(states.lastIndex)
        if (coalesce && lastCoalescible && at > 0 && now - lastAt <= gapMs) states[at] = next
        else { states += next; at++ }
        lastCoalescible = coalesce; lastAt = now
        if (states.size > limit) { states.removeAt(0); at-- }
    }

    fun undo(): TextSel? { if (!canUndo) return null; lastCoalescible = false; at--; return states[at] }
    fun redo(): TextSel? { if (!canRedo) return null; lastCoalescible = false; at++; return states[at] }

    /** The selection moved without a change: keep it with the state, so undo returns the caret where it was. */
    fun moved(selection: TextSel) { if (selection.text == states[at].text) states[at] = selection }
}
