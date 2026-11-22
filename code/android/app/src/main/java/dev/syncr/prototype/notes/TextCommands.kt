package dev.syncr.prototype.notes

import dev.syncr.core.*

/** Immutable UI draft: exact note and head prevent overwriting a newer synced revision. */
/** Where a new free text block goes: [blockId] is chosen up front so a retry saves the same block; [x] and [y] are sheet-local. */
data class FreePlace(val sheetId: String, val x: Float, val y: Float, val width: Float, val blockId: String)

data class TextDraft(val noteId: String, val blockId: String?, val head: String?, val text: String, val place: FreePlace? = null)

internal object TextCommands {
    /** The draft of block [id], or of the document; with [place] and no [id], of the free block being made there (empty until saved). */
    fun draft(note: NoteSnapshot, id: String? = null, place: FreePlace? = null): TextDraft {
        if (id == null && place != null) {
            val made = note.textBlocks.firstOrNull { !it.tombstone && it.id == place.blockId }
            return if (made != null) TextDraft(note.noteId, made.id, made.head.operationId, made.text) else TextDraft(note.noteId, null, null, "", place)
        }
        val block = note.textBlocks.firstOrNull { !it.tombstone && if (id == null) it.role == TextRole.DOCUMENT else it.id == id }
        require(id == null || block != null) { "This text no longer exists." }
        return TextDraft(note.noteId, block?.id, block?.head?.operationId, block?.text.orEmpty())
    }

    fun save(note: NoteSnapshot, draft: TextDraft, text: String, id: () -> String): Command? {
        require(note.noteId == draft.noteId) { "The open note changed. Your draft was kept." }
        require(text.toByteArray().size <= 1_000_000) { "Text is too large (1 MB maximum)." }
        if (draft.blockId == null && draft.place != null) {
            val place = draft.place
            if (text.isEmpty()) return null
            require(note.pages.none { it.sheetId == place.sheetId && it.tombstone } && note.sheets.any { it.id == place.sheetId }) { "That page no longer exists. Your text was kept in the field." }
            val sheet = note.sheets.first { it.id == place.sheetId }
            return Command(id(), listOf(sheet.creationOperationId), CommandBody.Marks(listOf(
                MarkMutation.AddText(place.blockId, sheet.id, TextRole.FREE, place.x, place.y, place.width, text, TextStyle(BrushSize.MEDIUM, InkColour.INK)))))
        }
        if (draft.blockId == null) {
            require(note.textBlocks.none { !it.tombstone && it.role == TextRole.DOCUMENT }) { "Text arrived from another device. Reopen the editor before saving." }
            if (text.isEmpty()) return null
            // The document goes on the first live page (the only sheet of a note without a page list).
            val sheet = note.pages.firstOrNull { !it.tombstone }?.let { p -> note.sheets.first { it.id == p.sheetId } } ?: note.sheets.first()
            return Command(id(), listOf(sheet.creationOperationId), CommandBody.Marks(listOf(
                MarkMutation.AddText(id(), sheet.id, TextRole.DOCUMENT, 0f, 0f, 1600f, text, TextStyle(BrushSize.MEDIUM, InkColour.INK)))))
        }
        val block = note.textBlocks.firstOrNull { it.id == draft.blockId && !it.tombstone }
        require(block != null && block.head.operationId == draft.head && block.text == draft.text) { "This text changed on another device. Copy your draft and reopen the editor." }
        if (text == draft.text) return null
        val splice = splice(draft.text, text)
        return Command(id(), listOf(block.head.operationId), CommandBody.Marks(listOf(
            MarkMutation.SpliceText(block.id, Base.Operation(block.head.operationId), splice.first.toUInt(), splice.second.toUInt(), splice.third))))
    }

    /** Common prefix/suffix on Unicode scalar boundaries; core offsets count UTF-8 bytes. */
    fun splice(before: String, after: String): Triple<Int, Int, String> {
        val a = before.codePoints().toArray(); val b = after.codePoints().toArray()
        var prefix = 0
        while (prefix < minOf(a.size, b.size) && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < minOf(a.size, b.size) - prefix && a[a.size - 1 - suffix] == b[b.size - 1 - suffix]) suffix++
        val start = String(a, 0, prefix).toByteArray().size
        val delete = String(a, prefix, a.size - prefix - suffix).toByteArray().size
        return Triple(start, delete, String(b, prefix, b.size - prefix - suffix))
    }

    fun state(note: NoteSnapshot, itemId: String, expectedHead: String, state: MarginState, id: () -> String): Command {
        val item = note.companionItems.firstOrNull { it.id == itemId && !it.tombstone }
        require(item != null && item.head.operationId == expectedHead) { "This response changed. Reopen it and try again." }
        val body = item.body as? CompanionBody.MarginNote ?: error("Only margin notes can be pinned or dismissed.")
        val anchor = note.companionItems.firstOrNull { it.id == body.anchorId && !it.tombstone }
            ?: error("This response's anchor is no longer available.")
        return Command(id(), listOf(item.head.operationId, anchor.head.operationId).distinct().sorted(), CommandBody.CompanionMarks(listOf(
            CompanionMutation(item.id, Base.Operation(item.head.operationId), false, body.copy(state = state)))))
    }

    /** What [ask] built: the command, the ask thread's ID and the picture's ID when there is one. */
    class AskCommand(val command: Command, val threadId: String, val pictureId: String?)

    /**
     * A direct ask, stored as the Mac stores one (`CompanionStore.addAsk` and `apply`): an anchor on the passage, the
     * thread's parent note (a question read by "You"), the question as message 0 and the answer as message 1. So it shows
     * under its passage on both devices. A [picture] (title, then SVG exactly as the core's filter returned it) gets its
     * own anchor on the same passage. [start] and [end] are UTF-16 offsets into [draft]'s text.
     */
    fun ask(note: NoteSnapshot, draft: TextDraft, start: Int, end: Int, question: String, answer: String, picture: Pair<String, String>?, id: () -> String): AskCommand {
        require(note.noteId == draft.noteId && start >= 0 && end > start && end <= draft.text.length)
        val block = note.textBlocks.firstOrNull { it.id == draft.blockId && !it.tombstone }
        require(block != null && block.head.operationId == draft.head && block.text == draft.text) { "The passage changed while the answer was being written. Copy the answer or ask again." }
        require(question.isNotBlank() && answer.isNotBlank() && answer.toByteArray().size <= 64_000)
        fun anchor() = CompanionBody.Anchor(AnchorTarget.Text(block.id,
            draft.text.substring(0, start).toByteArray().size.toUInt(), draft.text.substring(0, end).toByteArray().size.toUInt(), false))
        val anchorId = id(); val thread = id()
        val mutations = mutableListOf(
            CompanionMutation(anchorId, Base.Absent, false, anchor()),
            CompanionMutation(thread, Base.Absent, false, CompanionBody.MarginNote(anchorId, MarginKind.QUESTION, "Question", ASK_READER, MarginState.OPEN, emptyList())),
            CompanionMutation(id(), Base.Absent, false, CompanionBody.ThreadMessage(thread, 0u, MessageWho.YOU, question.take(4000), emptyList())),
            CompanionMutation(id(), Base.Absent, false, CompanionBody.ThreadMessage(thread, 1u, MessageWho.COMPANION, answer, emptyList())),
        )
        var pictureId: String? = null
        if (picture != null) {
            val pictureAnchor = id()
            pictureId = id()
            mutations += CompanionMutation(pictureAnchor, Base.Absent, false, anchor())
            mutations += CompanionMutation(pictureId, Base.Absent, false, CompanionBody.Picture(pictureAnchor, picture.first.take(200), picture.second))
        }
        return AskCommand(Command(id(), listOf(block.head.operationId), CommandBody.CompanionMarks(mutations)), thread, pictureId)
    }

    /** The line that holds a picture's room in the text, after the paragraph its passage ends in (as the Mac inserts it). */
    fun embed(note: NoteSnapshot, blockId: String, pictureId: String, passageEnd: Int, id: () -> String): Command? {
        val block = note.textBlocks.firstOrNull { it.id == blockId && !it.tombstone } ?: return null
        val bytes = block.text.toByteArray()
        var at = block.text.substring(0, passageEnd.coerceIn(0, block.text.length)).toByteArray().size
        while (at < bytes.size && bytes[at] != 10.toByte()) at++
        return Command(id(), listOf(block.head.operationId), CommandBody.Marks(listOf(
            MarkMutation.SpliceText(block.id, Base.Operation(block.head.operationId), at.toUInt(), 0u, "\n![[syncr-drawing:$pictureId]]\n"))))
    }

    /** `CompanionStore.askReader` on the Mac. */
    const val ASK_READER = "You"

    fun explanation(note: NoteSnapshot, draft: TextDraft, start: Int, end: Int, answer: String, id: () -> String): Command {
        require(note.noteId == draft.noteId && start >= 0 && end > start && end <= draft.text.length)
        val block = note.textBlocks.firstOrNull { it.id == draft.blockId && !it.tombstone }
        require(block != null && block.head.operationId == draft.head && block.text == draft.text) { "The passage changed while generating. Copy the response or request it again." }
        require(answer.isNotBlank() && answer.toByteArray().size <= 64_000)
        val anchor = id()
        return Command(id(), listOf(block.head.operationId), CommandBody.CompanionMarks(listOf(
            CompanionMutation(anchor, Base.Absent, false, CompanionBody.Anchor(AnchorTarget.Text(block.id,
                draft.text.substring(0, start).toByteArray().size.toUInt(), draft.text.substring(0, end).toByteArray().size.toUInt(), false))),
            CompanionMutation(id(), Base.Absent, false, CompanionBody.MarginNote(anchor, MarginKind.EXPLANATION, answer, "Companion", MarginState.OPEN, emptyList())))))
    }
}
