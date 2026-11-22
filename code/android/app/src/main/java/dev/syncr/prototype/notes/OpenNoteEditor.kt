package dev.syncr.prototype.notes

import dev.syncr.core.Affine
import dev.syncr.core.Base
import dev.syncr.core.Brush
import dev.syncr.core.ChangePage
import dev.syncr.core.Command
import dev.syncr.core.CommandBody
import dev.syncr.core.MarkMutation
import dev.syncr.core.StrokeView

/**
 * Edits to the open note. Staging runs on the caller's thread and only appends an immutable
 * [PendingEdit] to the state; [refresh] runs on the worker and reconciles those edits with the
 * core snapshot. There is no local document reducer.
 */
internal class OpenNoteEditor(
    private val session: CoreSession,
    private val core: CoreAdapter,
    private val newId: () -> String,
) {
    /** Re-reads the snapshot and drops each overlay whose receipt it now contains. Worker only. */
    fun refresh() {
        val handle = session.note ?: return
        // Changes only invalidate: re-read the snapshot once if events arrived in between.
        var snapshot = handle.snapshot()
        var changes = handle.changes(snapshot.cursor)
        if (changes is ChangePage.ResyncRequired || (changes is ChangePage.Events && changes.items.isNotEmpty())) {
            snapshot = handle.snapshot()
            changes = handle.changes(snapshot.cursor)
        }
        check(changes is ChangePage.Events && changes.items.isEmpty()) {
            "Core changed during serialized snapshot refresh"
        }
        check(session.current.pending.none { (it.receipt?.localSequence ?: 0uL) > snapshot.localSequence }) {
            "Snapshot has not reached the durable receipt"
        }
        val samples = buildMap {
            for (stroke in snapshot.strokes) {
                if (stroke.tombstone) continue
                put(stroke.id, session.current.samples[stroke.id] ?: core.decode(handle.samples(stroke.id)))
            }
        }
        session.update { current ->
            val remaining = current.pending.filterNot { edit ->
                val sequence = edit.receipt?.localSequence
                sequence != null && sequence <= snapshot.localSequence
            }
            current.copy(
                snapshot = snapshot,
                samples = samples,
                pending = remaining,
                error = if (remaining.isEmpty()) null else current.error,
            )
        }
    }

    /**
     * Read mode: every edit to the open note is refused here, whatever asked for it (pen, finger, text, page chrome, the
     * companion), so nothing is staged, nothing is saved and nothing is queued. Sync from the Mac does not come through here.
     */
    @Volatile var readOnly = false

    /** Retains the complete measured stroke before any asynchronous codec or submit. */
    fun stageStroke(preview: InkPreview): Boolean {
        if (readOnly) return false
        val state = session.current
        val snapshot = state.snapshot ?: return false
        if (state.closing) return false
        // New ink goes to the page under the pen, with that sheet's exact creation provenance; never to a deleted page.
        // A note with a page list takes ink on its live pages only; one without (and older sheets inside a legacy page) on its first sheet.
        val sheet = snapshot.sheets.firstOrNull { it.id == preview.sheetId }?.takeIf { s ->
            if (snapshot.pages.isEmpty()) s.id == snapshot.sheets.first().id else snapshot.pages.any { it.sheetId == s.id && !it.tombstone }
        } ?: return false
        val queuedSamples = state.pending.sumOf { it.preview?.channels?.x?.size ?: 0 }
        if (state.pending.size >= MAX_PENDING_EDITS || queuedSamples + preview.channels.x.size > MAX_PENDING_SAMPLES) {
            message("Too many unsaved changes. Retry saving, export a copy or discard them before writing more.")
            return false
        }
        val edit = PendingEdit(newId(), null, preview, dependencies = listOf(sheet.creationOperationId))
        stage(edit)
        return true
    }

    /** Operation IDs of eraser commands, by gesture, so history can take a whole gesture as one step. */
    private val gestures = HashMap<String, String>()

    /** The gesture an operation belongs to, if it was one of several eraser commands. */
    fun gestureOf(operationId: String): String? = gestures[operationId]

    /** The operation undo (or redo) would target now, or null when there is none. */
    fun historyTarget(redo: Boolean): String? {
        val snapshot = session.current.snapshot ?: return null
        if (!handwritingHistoryAvailable(snapshot, redo)) return null
        return if (redo) snapshot.redoTarget else snapshot.undoTarget
    }

    /**
     * Stages one atomic Marks command. Each target's base is its current head and the dependencies are
     * those heads, sorted. A delete may run while earlier edits are still saving: a stroke whose latest
     * change is still pending is based on that pending operation, which the queue submits first. Moves
     * and restyles still edit saved ink only.
     */
    fun stageMarks(edit: MarkEdit): Boolean {
        if (readOnly) return false
        if (edit.ids.isEmpty()) return false
        val state = session.current
        if (edit is MarkEdit.Delete) return stageDelete(edit)
        if (state.unsaved) {
            message("Wait a moment. Your last changes are still saving.")
            return false
        }
        if (edit.ids.size > MAX_GESTURE_STROKES) {
            message("That's too many strokes at once (1,024 at most). Nothing changed.")
            return false
        }
        val snapshot = state.snapshot ?: return false
        if (state.closing) return false
        val strokes = snapshot.strokes.filter { it.id in edit.ids && !it.tombstone }
        if (strokes.size != edit.ids.size) return false

        val transforms = mutableMapOf<String, Affine>()
        val restyles = mutableMapOf<String, Brush>()
        val changed = mutableListOf<Pair<StrokeView, MarkMutation>>()
        for (stroke in strokes) {
            val base = Base.Operation(stroke.head.operationId)
            val mutation = when (edit) {
                is MarkEdit.Delete -> MarkMutation.DeleteStroke(stroke.id, base)
                is MarkEdit.Transform -> {
                    val sheet = snapshot.sheets.first { it.id == stroke.sheetId }
                    val next = compose(localGesture(sheet, edit.gesture, edit.placement[sheet.id] ?: (0f to 0f)), stroke.transform)
                    if (!isValidTransform(next)) {
                        message("That move couldn't be applied, so nothing changed.")
                        return false
                    }
                    transforms[stroke.id] = next
                    MarkMutation.TransformStroke(stroke.id, base, next)
                }
                is MarkEdit.Restyle -> {
                    if (stroke.brush.colour == edit.colour) continue
                    val brush = stroke.brush.copy(colour = edit.colour)
                    restyles[stroke.id] = brush
                    MarkMutation.RestyleStroke(stroke.id, base, brush)
                }
            }
            changed += stroke to mutation
        }
        if (changed.isEmpty()) return false

        val dependencies = changed.map { it.first.head.operationId }.distinct().sorted()
        val command = Command(newId(), dependencies, CommandBody.Marks(changed.map { it.second }))
        val hidden = if (edit is MarkEdit.Delete) edit.ids.toSet() else emptySet()
        stage(PendingEdit(command.operationId, command, hidden = hidden, transforms = transforms, restyles = restyles))
        return true
    }

    private fun stageDelete(edit: MarkEdit.Delete): Boolean {
        val state = session.current
        val snapshot = state.snapshot ?: return false
        if (state.closing) return false
        if (edit.ids.size > MAX_GESTURE_STROKES) {
            message("That's too many strokes at once (1,024 at most). Nothing changed.")
            return false
        }
        val saved = snapshot.strokes.associateBy { it.id }
        // Never delete twice: skip strokes a pending edit already hides or the core already tombstoned.
        val targets = edit.ids.filterNot { id -> state.pending.any { id in it.hidden } || saved[id]?.tombstone == true }
        if (targets.isEmpty()) return true
        val mutations = mutableListOf<MarkMutation>()
        val dependencies = sortedSetOf<String>()
        for (id in targets) {
            val head = state.pending.lastOrNull { it.touches(id) }?.id ?: saved[id]?.head?.operationId ?: return false
            mutations += MarkMutation.DeleteStroke(id, Base.Operation(head))
            dependencies += head
        }
        if (state.pending.size >= MAX_PENDING_EDITS) {
            message("Too many unsaved changes. Retry saving, export a copy or discard them before writing more.")
            return false
        }
        val command = Command(newId(), dependencies.toList(), CommandBody.Marks(mutations))
        edit.gesture?.let { gestures[command.operationId] = it }
        stage(PendingEdit(command.operationId, command, hidden = targets.toSet()))
        return true
    }

    /** Whether this pending edit is the stroke's latest change: it adds, moves or restyles it. */
    private fun PendingEdit.touches(id: String) = preview?.id == id || id in transforms || id in restyles

    /**
     * Stages a rename, move or archive of the open note against its current metadata head. Like
     * history, it waits until nothing is unsaved or busy. Returns whether anything was staged.
     */
    fun stageMetadata(body: (Base) -> CommandBody): Boolean {
        if (readOnly) return false
        val state = session.current
        if (state.unsaved || state.busy) return false
        val head = state.snapshot?.metadataHead ?: return false
        stageCommand(Command(newId(), listOf(head.operationId), body(Base.Operation(head.operationId))))
        return true
    }

    /** Stages core-owned undo or redo. Returns whether anything was staged. */
    fun stageHistory(redo: Boolean): Boolean {
        if (readOnly) return false
        val state = session.current
        if (state.unsaved || state.busy) return false
        val snapshot = state.snapshot ?: return false
        if (!handwritingHistoryAvailable(snapshot, redo)) return false
        val target = (if (redo) snapshot.redoTarget else snapshot.undoTarget) ?: return false
        // Target and exact affected-mark heads come from the same atomic core snapshot.
        val dependencies = (if (redo) snapshot.redoDependencies else snapshot.undoDependencies).toList()
        val body = if (redo) CommandBody.Redo(target) else CommandBody.Undo(target)
        stageCommand(Command(newId(), dependencies, body))
        return true
    }

    fun stageContent(noteId: String, build: (dev.syncr.core.NoteSnapshot) -> Command?): Boolean {
        if (readOnly) return false
        val state = session.current
        require(!state.unsaved && !state.busy && !state.closing) { "Wait for the current changes to finish saving." }
        val note = state.snapshot
        require(note != null && note.noteId == noteId) { "The open note changed." }
        val command = build(note) ?: return false
        stageCommand(command)
        return true
    }

    /** Stages several commands in order as one undo step (history takes a gesture's commands together). */
    fun stageContents(noteId: String, gesture: String, build: (dev.syncr.core.NoteSnapshot) -> List<Command>): Boolean {
        if (readOnly) return false
        val state = session.current
        require(!state.unsaved && !state.busy && !state.closing) { "Wait for the current changes to finish saving." }
        val note = state.snapshot
        require(note != null && note.noteId == noteId) { "The open note changed." }
        val commands = build(note)
        if (commands.isEmpty()) return false
        for (command in commands) { gestures[command.operationId] = gesture; stageCommand(command) }
        return true
    }

    fun message(text: String) = session.update { it.copy(error = text) }

    private fun stageCommand(command: Command) = stage(PendingEdit(command.operationId, command))

    private fun stage(edit: PendingEdit) = session.update {
        it.copy(pending = it.pending + edit, error = null, recoveryExported = false)
    }

    private companion object {
        const val MAX_PENDING_EDITS = 64
        const val MAX_PENDING_SAMPLES = 500_000
        const val MAX_GESTURE_STROKES = 1024
    }
}
