package dev.syncr.prototype.notes

import dev.syncr.core.Affine
import dev.syncr.core.Base
import dev.syncr.core.Command
import dev.syncr.core.CommandBody
import dev.syncr.core.MarkMutation
import dev.syncr.core.NoteSnapshot
import dev.syncr.core.PagePattern
import dev.syncr.core.PageView
import dev.syncr.core.Rgba
import dev.syncr.core.SampleChannels
import dev.syncr.core.TextRole

/** A page turned by quarter turns: clockwise, anticlockwise, or half. Text boxes stay upright (as xnotes does). */
enum class PageTurn { CLOCKWISE, COUNTER_CLOCKWISE, HALF }

/** The page size and look a new note starts with (P1): size in note units, template, paper and pattern colour (with opacity). */
data class NewNoteLayout(val width: Float, val height: Float, val template: PagePattern = PagePattern.NONE,
    val paper: Rgba? = null, val patternColour: Rgba? = null) {
    val plain get() = template == PagePattern.NONE && paper == null
}

/**
 * Builds page commands from a snapshot: one `Marks` command each, so a page action and the content moves it needs are one
 * undo step. Pure and deterministic apart from the IDs [id] hands out; the core checks every base and dependency.
 */
internal object PageCommands {
    class Refused(message: String) : IllegalArgumentException(message)

    const val LAST_PAGE = "A note must keep at least one page."

    private fun live(note: NoteSnapshot) = note.pages.filter { !it.tombstone }

    private fun page(note: NoteSnapshot, sheetId: String): PageView =
        note.pages.firstOrNull { it.sheetId == sheetId && !it.tombstone } ?: throw Refused("That page no longer exists.")

    private fun creation(note: NoteSnapshot, sheetId: String) =
        note.sheets.firstOrNull { it.id == sheetId }?.creationOperationId ?: throw Refused("That page no longer exists.")

    private fun base(page: PageView) = Base.Operation(page.head.operationId)

    private fun command(id: () -> String, dependencies: Collection<String>, mutations: List<MarkMutation>) =
        Command(id(), dependencies.distinct().sorted(), CommandBody.Marks(mutations))

    /** A page after [after] (null: first), [width] by [height]. Returns the command and the new sheet's ID. */
    fun add(note: NoteSnapshot, after: String?, width: Float, height: Float, id: () -> String): Pair<Command, String> {
        val sheet = id()
        val deps = mutableListOf(note.sheets.first().creationOperationId)
        if (after != null) deps += creation(note, after)
        return command(id, deps, listOf(MarkMutation.AddPage(sheet, after, width, height))) to sheet
    }

    /** Page size: sets the size without scaling or moving anything on the page. */
    fun resize(note: NoteSnapshot, sheetId: String, width: Float, height: Float, id: () -> String): Command {
        val p = page(note, sheetId)
        return command(id, listOf(p.head.operationId), listOf(MarkMutation.ResizePage(sheetId, base(p), width, height)))
    }

    /** Turns [sheetIds] by [turn]: the size swaps for quarter turns and ink and shapes turn with the page; text boxes move but stay upright. */
    fun rotate(note: NoteSnapshot, sheetIds: List<String>, turn: PageTurn, id: () -> String): Command {
        val mutations = ArrayList<MarkMutation>(); val deps = ArrayList<String>()
        for (sheetId in sheetIds) {
            val p = page(note, sheetId)
            val w = p.width.toDouble(); val h = p.height.toDouble()
            val turn2 = when (turn) {
                PageTurn.CLOCKWISE -> Affine(0.0, 1.0, -1.0, 0.0, h, 0.0)
                PageTurn.COUNTER_CLOCKWISE -> Affine(0.0, -1.0, 1.0, 0.0, 0.0, w)
                PageTurn.HALF -> Affine(-1.0, 0.0, 0.0, -1.0, w, h)
            }
            val quarter = turn != PageTurn.HALF
            mutations += MarkMutation.ResizePage(sheetId, base(p), if (quarter) p.height else p.width, if (quarter) p.width else p.height)
            deps += p.head.operationId
            for (stroke in note.strokes) {
                if (stroke.sheetId != sheetId || stroke.tombstone) continue
                mutations += MarkMutation.TransformStroke(stroke.id, Base.Operation(stroke.head.operationId), compose(turn2, stroke.transform))
                deps += stroke.head.operationId
            }
            for (block in note.textBlocks) {
                if (block.sheetId != sheetId || block.tombstone) continue
                // Only the block's corner follows the page: translate it to where the turn puts that point.
                val x = block.originX + block.transform.tx; val y = block.originY + block.transform.ty
                val to = Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0).let {
                    val nx = turn2.a * x + turn2.c * y + turn2.tx; val ny = turn2.b * x + turn2.d * y + turn2.ty
                    translation(nx - x, ny - y)
                }
                mutations += MarkMutation.TransformText(block.id, Base.Operation(block.head.operationId), compose(to, block.transform))
                deps += block.head.operationId
            }
        }
        return command(id, deps, mutations)
    }

    /**
     * Duplicates [sheetIds] (in the note's order) as pages after the last of them: first the pages, then their ink and free text
     * (the core cannot put ink on a page created in the same command, so it depends on the first). The caller groups the two as one undo step.
     */
    fun duplicate(note: NoteSnapshot, sheetIds: List<String>, samples: (String) -> SampleChannels?, encode: (SampleChannels) -> ByteArray,
        id: () -> String): Pair<List<Command>, List<String>> {
        val order = live(note).map { it.sheetId }
        val picked = order.filter { it in sheetIds }
        if (picked.isEmpty()) throw Refused("That page no longer exists.")
        var after = picked.last()
        val pages = ArrayList<MarkMutation>(); val content = ArrayList<MarkMutation>()
        val deps = arrayListOf(note.sheets.first().creationOperationId, creation(note, after))
        val made = ArrayList<String>()
        for (sheetId in picked) {
            val p = page(note, sheetId)
            val copy = id()
            pages += MarkMutation.AddPage(copy, after, p.width, p.height)
            after = copy; made += copy
            for (stroke in note.strokes) {
                if (stroke.sheetId != sheetId || stroke.tombstone) continue
                val channels = samples(stroke.id) ?: continue
                val moved = channels.x.indices.map { transformedPoint(channels.x[it], channels.y[it], stroke.transform) }
                val baked = channels.copy(x = moved.map { it.first }, y = moved.map { it.second })
                content += MarkMutation.AddStroke(id(), copy, encode(baked), stroke.brush)
            }
            for (block in note.textBlocks) {
                if (block.sheetId != sheetId || block.tombstone || block.role != TextRole.FREE) continue
                content += MarkMutation.AddText(id(), copy, TextRole.FREE, block.originX + block.transform.tx.toFloat(),
                    block.originY + block.transform.ty.toFloat(), block.width, block.text, block.style)
            }
        }
        val addPages = command(id, deps, pages)
        val commands = if (content.isEmpty()) listOf(addPages) else listOf(addPages, command(id, listOf(addPages.operationId), content))
        return commands to made
    }

    /** Deletes [sheetIds]. The note keeps at least one page: refused here with the core's own words, before anything is staged. */
    fun delete(note: NoteSnapshot, sheetIds: List<String>, id: () -> String): Command {
        val doomed = live(note).filter { it.sheetId in sheetIds }
        if (doomed.isEmpty()) throw Refused("That page no longer exists.")
        if (doomed.size >= live(note).size) throw Refused(LAST_PAGE)
        return command(id, doomed.map { it.head.operationId }, doomed.map { MarkMutation.DeletePage(it.sheetId, base(it)) })
    }

    /** Moves [sheetIds] (keeping their order) to follow [after] (null: first). Pages already there stay. */
    fun move(note: NoteSnapshot, sheetIds: List<String>, after: String?, id: () -> String): Command {
        val order = live(note).map { it.sheetId }
        val picked = order.filter { it in sheetIds }
        if (picked.isEmpty() || (after != null && after in picked)) throw Refused("A page cannot be moved after itself.")
        val mutations = ArrayList<MarkMutation>(); val deps = ArrayList<String>()
        var anchor = after
        for (sheetId in picked) {
            val p = page(note, sheetId)
            mutations += MarkMutation.MovePage(sheetId, base(p), anchor)
            deps += p.head.operationId
            if (anchor != null) deps += creation(note, anchor)
            anchor = sheetId
        }
        return command(id, deps, mutations)
    }

    /** The new-note screen's layout, for a note that still has its one page: the page's size, then (when set) its look. */
    fun layout(note: NoteSnapshot, layout: NewNoteLayout, id: () -> String): List<Command> {
        val first = live(note).first()
        val out = ArrayList<Command>()
        out += resize(note, first.sheetId, layout.width, layout.height, id)
        val style = note.pageStyle
        if (!layout.plain && style != null) out += Command(id(), listOf(style.head.operationId),
            CommandBody.SetPageStyle(null, layout.template, layout.paper, layout.patternColour, null, Base.Operation(style.head.operationId)))
        return out
    }
}
