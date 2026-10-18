package dev.syncr.prototype.notes

import dev.syncr.core.*
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Disposable rendering data, never a document reducer. */
data class InkPreview(val id: String, val sheetId: String, val channels: SampleChannels, val brush: Brush)
data class PendingEdit(
    val id: String,
    val command: Command?,
    val preview: InkPreview? = null,
    val hidden: Set<String> = emptySet(),
    val dependencies: List<String> = emptyList(),
    val receipt: Receipt? = null,
    /** Visual overlay of a staged selection edit until its receipt is in a snapshot. */
    val transforms: Map<String, Affine> = emptyMap(),
    val restyles: Map<String, Brush> = emptyMap(),
)

/** One selection/eraser gesture = one atomic Marks command = one undo step. */
sealed interface MarkEdit {
    val ids: Set<String>
    data class Delete(override val ids: Set<String>) : MarkEdit
    /** Canvas-space [gesture], converted to each sheet's local space before composing with existing ink. */
    data class Transform(override val ids: Set<String>, val gesture: Affine) : MarkEdit
    data class Restyle(override val ids: Set<String>, val colour: InkColour) : MarkEdit
}

/** A metadata change to a CLOSED note from the library, run on the worker: open, submit, durable close. */
sealed interface NoteChange {
    data class Move(val notebook: String?) : NoteChange
    data class Rename(val title: String) : NoteChange
    data class Archive(val archived: Boolean) : NoteChange
}
sealed interface LibraryTask {
    /** [operationId] is allocated once; [command] is built once from the opened metadata head, then retried as is. */
    data class EditNote(val noteId: String, val operationId: String, val change: NoteChange, val command: Command? = null) : LibraryTask
    /** Core rename is per-note and not atomic; retrying the same call moves the remainder. */
    data class RenameNotebook(val oldName: String, val newName: String) : LibraryTask
    /** Moves a closed note to Recently deleted. The core call is idempotent, so a retry is the same call. */
    data class DeleteNote(val noteId: String) : LibraryTask
    data class RestoreNote(val noteId: String) : LibraryTask
    /** Delete forever, from Recently deleted only. */
    data class PurgeNote(val noteId: String) : LibraryTask
}

/** Recently deleted notes are purged after this long, on library load. */
const val DELETED_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

data class NotesState(
    val notes: List<NoteSummary> = emptyList(),
    val notebooks: List<NotebookSummary> = emptyList(),
    /** Recently deleted, newest first; read back from the core. */
    val deleted: List<DeletedNoteSummary> = emptyList(),
    /** The note just moved to Recently deleted, for the "Undo" snackbar. */
    val recentlyDeleted: String? = null,
    val thumbnails: Map<String, Thumbnail> = emptyMap(),
    val libraryTask: LibraryTask? = null,
    val snapshot: NoteSnapshot? = null,
    val samples: Map<String, SampleChannels> = emptyMap(),
    val pending: List<PendingEdit> = emptyList(),
    val creation: CreateNoteRequest? = null,
    val authorId: String? = null,
    val busy: Boolean = false,
    val closing: Boolean = false,
    val error: String? = null,
    val recoveryExported: Boolean = false,
) {
    val unsaved get() = pending.isNotEmpty() || creation != null
    val status get() = when {
        unsaved && error != null -> "Save failed — unsaved changes retained"
        unsaved && recoveryExported -> "Recovery exported — changes are not saved to the note"
        unsaved -> "Saving — not yet saved"
        busy -> "Working…"
        snapshot != null -> "Saved on this phone · Mac sync not available"
        else -> "Offline library"
    }
}

class NotesRepository(
    private val root: String,
    private val core: CoreAdapter = GeneratedCoreAdapter(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val thumbnailDir: File? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val id: () -> String = ::newId,
) {
    private val mutable = MutableStateFlow(NotesState())
    val state = mutable.asStateFlow()
    private val mutex = Mutex()
    private var library: LibraryAccess? = null
    private var note: NoteAccess? = null
    /** A note opened only by a library task or thumbnail read; never shown, closed durably before anything else opens. */
    private var side: NoteAccess? = null

    private suspend fun work(block: () -> Unit) = withContext(io) {
        mutex.withLock {
            mutable.update { it.copy(busy = true) }
            try { block() } catch (e: Exception) {
                mutable.update { it.copy(error = diagnostic(e)) }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
    /** Same serialization as [work] but silent: no busy flag and no user-facing error (thumbnails). */
    private suspend fun background(block: () -> Unit) = withContext(io) {
        mutex.withLock { try { block() } catch (_: Exception) { } }
    }
    private fun lib(): LibraryAccess = library ?: core.open(root).also {
        library = it
        val author = it.authorId()
        mutable.update { s -> s.copy(authorId = author) }
    }
    /** Fallible durable close of a side handle; on failure it stays retained and nothing else opens. */
    private fun closeSide() { side?.let { it.closeDurably(); side = null } }
    private fun reloadLibraryLocked() {
        val l = lib()
        val notes = l.listNotes().sortedByDescending { it.modifiedAtMs }
        val notebooks = l.listNotebooks()
        mutable.update { it.copy(notes = notes, notebooks = notebooks) }
        // Separately: an unreadable deleted note reports an error but never hides the library.
        val deleted = l.listDeletedNotes()
        mutable.update { it.copy(deleted = deleted) }
    }
    suspend fun loadLibrary() = work {
        reloadLibraryLocked()
        mutable.update { it.copy(error = null) }
        purgeExpiredLocked()
    }
    /**
     * Housekeeping on load: deletes forever what has been in Recently deleted for 30 days, then drops
     * cached cards of notes that no longer exist. A failure is not shown: expired notes simply stay
     * listed in Recently deleted and are retried on the next load.
     */
    private fun purgeExpiredLocked() {
        val cutoff = now() - DELETED_RETENTION_MS
        try {
            if (state.value.deleted.any { it.deletedAtMs < cutoff }) lib().purgeDeletedOlderThan(cutoff)
        } catch (_: Exception) { }
        try {
            reloadLibraryLocked()
            val s = state.value
            val keep = (s.notes.map { it.id } + s.deleted.map { it.id }).toSet()
            mutable.update { it.copy(thumbnails = it.thumbnails.filterKeys { id -> id in keep }) }
            ThumbnailCache.retainOnly(thumbnailDir, keep)
        } catch (_: Exception) { }
    }
    suspend fun create(title: String, notebook: String? = null) {
        // Allocate once, before dispatch. A failed/lost reply retains the same request.
        if (state.value.creation == null) mutable.update {
            it.copy(creation = CreateNoteRequest(id(), id(), id(), title, notebook), error = null, recoveryExported = false)
        }
        retry()
    }
    suspend fun open(noteId: String) = work {
        check(note == null && !state.value.unsaved)
        closeSide()
        note = lib().openNote(noteId)
        refreshLocked()
    }
    suspend fun refresh() = work {
        if (note != null) refreshLocked() else if (!state.value.unsaved) {
            reloadLibraryLocked()
            mutable.update { it.copy(error = if (it.libraryTask != null) it.error else null) }
        }
    }

    suspend fun createNotebook(name: String) = work {
        lib().createNotebook(name)
        reloadLibraryLocked()
    }
    /** The core refuses while any note uses the name; the UI explains that before asking. */
    suspend fun deleteNotebook(name: String) = work {
        lib().deleteNotebook(name)
        reloadLibraryLocked()
    }
    suspend fun renameNotebook(oldName: String, newName: String) = startTask(LibraryTask.RenameNotebook(oldName, newName))
    suspend fun moveNote(noteId: String, notebook: String?) = startTask(LibraryTask.EditNote(noteId, id(), NoteChange.Move(notebook)))
    suspend fun renameNote(noteId: String, title: String) = startTask(LibraryTask.EditNote(noteId, id(), NoteChange.Rename(title)))
    suspend fun archiveNote(noteId: String, archived: Boolean) =
        startTask(LibraryTask.EditNote(noteId, id(), NoteChange.Archive(archived)))
    /** No dialog: the note goes to Recently deleted and the library offers Undo. */
    suspend fun deleteNote(noteId: String) = startTask(LibraryTask.DeleteNote(noteId))
    suspend fun restoreNote(noteId: String) = startTask(LibraryTask.RestoreNote(noteId))
    /** Caller confirms first: this is permanent. */
    suspend fun purgeNote(noteId: String) = startTask(LibraryTask.PurgeNote(noteId))
    /** "Undo" on the snackbar: restore the note that was just deleted. */
    suspend fun undoDelete() {
        val noteId = state.value.recentlyDeleted ?: return
        mutable.update { it.copy(recentlyDeleted = null) }
        restoreNote(noteId)
    }
    fun dismissDeleted() { mutable.update { it.copy(recentlyDeleted = null) } }
    private suspend fun startTask(task: LibraryTask) {
        val s = state.value
        if (s.libraryTask != null || s.snapshot != null || s.unsaved) return
        mutable.update { it.copy(libraryTask = task, error = null) }
        retry()
    }
    private fun runTaskLocked(task: LibraryTask) {
        when (task) {
            is LibraryTask.RenameNotebook -> lib().renameNotebook(task.oldName, task.newName)
            is LibraryTask.DeleteNote -> {
                // A side handle (thumbnail read) on this note would make the core refuse; close it durably first.
                closeSide()
                lib().deleteNote(task.noteId)
                mutable.update { it.copy(recentlyDeleted = task.noteId) }
            }
            is LibraryTask.RestoreNote -> {
                lib().restoreNote(task.noteId)
                mutable.update { if (it.recentlyDeleted == task.noteId) it.copy(recentlyDeleted = null) else it }
            }
            is LibraryTask.PurgeNote -> {
                lib().purgeNote(task.noteId)
                mutable.update { it.copy(thumbnails = it.thumbnails - task.noteId) }
                ThumbnailCache.delete(thumbnailDir, task.noteId)
            }
            is LibraryTask.EditNote -> {
                closeSide()
                val handle = lib().openNote(task.noteId).also { side = it }
                val before = handle.snapshot()
                val command = task.command ?: run {
                    val head = before.metadataHead.operationId
                    val body = when (val change = task.change) {
                        is NoteChange.Move -> CommandBody.SetNotebook(change.notebook, Base.Operation(head))
                        is NoteChange.Rename -> CommandBody.RenameNote(change.title, Base.Operation(head))
                        is NoteChange.Archive -> CommandBody.SetArchived(change.archived, Base.Operation(head))
                    }
                    Command(task.operationId, listOf(head), body).also { built ->
                        mutable.update { it.copy(libraryTask = task.copy(command = built)) }
                    }
                }
                if (handle.lookup(command.operationId) is OperationLookup.NotRecorded) handle.submit(command)
                val after = handle.snapshot().localSequence
                // Metadata only: an up-to-date thumbnail stays valid at the new sequence.
                state.value.thumbnails[task.noteId]?.takeIf { it.localSequence == before.localSequence }?.let { old ->
                    val bumped = Thumbnail(after, old.width, old.viewHeight, old.strokes)
                    mutable.update { it.copy(thumbnails = it.thumbnails + (task.noteId to bumped)) }
                    ThumbnailCache.write(thumbnailDir, task.noteId, bumped)
                }
                closeSide()
            }
        }
        // Done only now: the library list below is read back from the core, never inferred.
        mutable.update { it.copy(libraryTask = null) }
        reloadLibraryLocked()
    }

    /** Fills missing/stale card thumbnails one note at a time so user actions interleave on the worker. */
    suspend fun loadThumbnails() {
        val wanted = state.value.notes.filter { state.value.thumbnails[it.id]?.localSequence != it.localSequence }
        for (summary in wanted) {
            var stop = false
            background {
                if (note != null || state.value.unsaved || state.value.libraryTask != null) { stop = true; return@background }
                val thumbnail = ThumbnailCache.read(thumbnailDir, summary.id, summary.localSequence) ?: run {
                    closeSide()
                    val handle = lib().openNote(summary.id).also { side = it }
                    val snapshot = handle.snapshot()
                    val built = buildThumbnail(snapshot, { mark -> core.decode(handle.samples(mark)) })
                    closeSide()
                    ThumbnailCache.write(thumbnailDir, summary.id, built)
                    built
                }
                mutable.update { it.copy(thumbnails = it.thumbnails + (summary.id to thumbnail)) }
            }
            if (stop) break
        }
    }
    private fun refreshLocked() {
        val handle = note ?: return
        // Snapshot/event reconciliation only invalidates; no frontend operation reducer.
        var snapshot = handle.snapshot()
        var invalidation = handle.changes(snapshot.cursor)
        if (invalidation is ChangePage.ResyncRequired ||
            (invalidation is ChangePage.Events && invalidation.items.isNotEmpty())) {
            snapshot = handle.snapshot()
            invalidation = handle.changes(snapshot.cursor)
        }
        check(invalidation is ChangePage.Events && invalidation.items.isEmpty()) {
            "Core changed during serialized snapshot refresh"
        }
        check(state.value.pending.none { (it.receipt?.localSequence ?: 0uL) > snapshot.localSequence }) {
            "Snapshot has not reached the durable receipt"
        }
        val cache = buildMap {
            snapshot.strokes.filterNot { it.tombstone }.forEach { stroke ->
                put(stroke.id, state.value.samples[stroke.id] ?: core.decode(handle.samples(stroke.id)))
            }
        }
        mutable.update { current ->
            val remaining = current.pending.filterNot { it.receipt?.localSequence?.let { seq -> seq <= snapshot.localSequence } == true }
            current.copy(snapshot = snapshot, samples = cache, pending = remaining,
                error = if (remaining.isEmpty()) null else current.error)
        }
    }

    /** Retain the complete real measured draft before any asynchronous codec or submit. */
    fun stageStroke(preview: InkPreview): Boolean {
        val s = state.value
        val snapshot = s.snapshot ?: return false
        if (s.closing) return false
        val sheet = snapshot.sheets.firstOrNull()?.takeIf { it.id == preview.sheetId } ?: return false
        if (s.pending.size >= 64 || s.pending.sumOf { it.preview?.channels?.x?.size ?: 0 } + preview.channels.x.size > 500_000) {
            message("Draft limit reached. Retry, export recovery or discard before drawing more.")
            return false
        }
        val pending = PendingEdit(id(), null, preview, dependencies = listOf(sheet.creationOperationId))
        mutable.update { it.copy(pending = it.pending + pending, error = null, recoveryExported = false) }
        return true
    }
    fun stageErase(ids: Set<String>): Boolean = stageMarks(MarkEdit.Delete(ids))

    /**
     * Stages one atomic Marks command for a whole gesture. Each target's base is its current head and
     * the dependencies are those heads, sorted. Only saved ink can be edited, so bases are never pending.
     */
    fun stageMarks(edit: MarkEdit): Boolean {
        if (edit.ids.isEmpty()) return false
        val s = state.value
        val (verb, limit) = when (edit) {
            is MarkEdit.Delete -> "erasing" to "Eraser limit"
            is MarkEdit.Transform -> "moving or resizing" to "Selection limit"
            is MarkEdit.Restyle -> "recolouring" to "Selection limit"
        }
        if (s.unsaved) { message("Wait for saved ink, or resolve unsaved changes before $verb."); return false }
        if (edit.ids.size > 1024) { message("$limit: entire gesture cancelled (maximum 1024 strokes)."); return false }
        val snapshot = s.snapshot ?: return false
        if (s.closing) return false
        val strokes = snapshot.strokes.filter { it.id in edit.ids && !it.tombstone }
        if (strokes.size != edit.ids.size) return false
        val transforms = mutableMapOf<String, Affine>()
        val restyles = mutableMapOf<String, Brush>()
        val changed = strokes.mapNotNull { stroke ->
            val base = Base.Operation(stroke.head.operationId)
            val mutation = when (edit) {
                is MarkEdit.Delete -> MarkMutation.DeleteStroke(stroke.id, base)
                is MarkEdit.Transform -> {
                    val sheet = snapshot.sheets.first { it.id == stroke.sheetId }
                    val next = compose(localGesture(sheet, edit.gesture), stroke.transform)
                    if (!isValidTransform(next)) { message("That transform can't be applied. Entire gesture cancelled."); return false }
                    transforms[stroke.id] = next
                    MarkMutation.TransformStroke(stroke.id, base, next)
                }
                is MarkEdit.Restyle -> if (stroke.brush.colour == edit.colour) null else {
                    val brush = stroke.brush.copy(colour = edit.colour)
                    restyles[stroke.id] = brush
                    MarkMutation.RestyleStroke(stroke.id, base, brush)
                }
            }
            mutation?.let { stroke to it }
        }
        if (changed.isEmpty()) return false
        val command = Command(id(), changed.map { it.first.head.operationId }.distinct().sorted(), CommandBody.Marks(changed.map { it.second }))
        mutable.update { it.copy(pending = it.pending + PendingEdit(command.operationId, command,
            hidden = if (edit is MarkEdit.Delete) edit.ids.toSet() else emptySet(), transforms = transforms, restyles = restyles),
            error = null, recoveryExported = false) }
        return true
    }
    private fun stageCommand(command: Command, hidden: Set<String> = emptySet()) {
        mutable.update { it.copy(pending = it.pending + PendingEdit(command.operationId, command, hidden = hidden),
            error = null, recoveryExported = false) }
    }
    /** Moves the OPEN note; like rename it waits for saved changes. */
    suspend fun moveOpenNote(notebook: String?) {
        val s = state.value
        if (s.unsaved || s.busy) return
        val head = s.snapshot?.metadataHead ?: return
        stageCommand(Command(id(), listOf(head.operationId), CommandBody.SetNotebook(notebook, Base.Operation(head.operationId))))
        retry()
    }
    /** Archives or unarchives the OPEN note; like move it waits for saved changes. */
    suspend fun archiveOpenNote(archived: Boolean) {
        val s = state.value
        if (s.unsaved || s.busy) return
        val head = s.snapshot?.metadataHead ?: return
        stageCommand(Command(id(), listOf(head.operationId), CommandBody.SetArchived(archived, Base.Operation(head.operationId))))
        retry()
    }
    suspend fun rename(title: String) {
        val s = state.value
        if (s.unsaved || s.busy) return
        val head = s.snapshot?.metadataHead ?: return
        stageCommand(Command(id(), listOf(head.operationId), CommandBody.RenameNote(title, Base.Operation(head.operationId))))
        retry()
    }
    suspend fun history(redo: Boolean) {
        val s = state.value
        if (s.unsaved || s.busy) return
        val snapshot = s.snapshot ?: return
        val target = (if (redo) snapshot.redoTarget else snapshot.undoTarget) ?: return
        // Target and exact affected-mark heads come from the same atomic core snapshot.
        val dependencies = (if (redo) snapshot.redoDependencies else snapshot.undoDependencies).toList()
        stageCommand(Command(id(), dependencies, if (redo) CommandBody.Redo(target) else CommandBody.Undo(target)))
        retry()
    }
    suspend fun retry() = work {
        mutable.update { it.copy(error = null) }
        state.value.creation?.let { request ->
            val l = lib()
            if (l.lookupCreation(request.operationId) !is OperationLookup.Committed) l.create(request)
            // Creation is durable even if subsequent open/refresh fails.
            mutable.update { it.copy(creation = null) }
            reloadLibraryLocked()
            if (note == null) { closeSide(); note = l.openNote(request.noteId) }
            refreshLocked()
        }
        if (note == null) state.value.libraryTask?.let(::runTaskLocked)
        val handle = note ?: return@work
        while (state.value.pending.isNotEmpty()) {
            val draft = state.value.pending.first()
            val command = prepareCommand(draft)
            val receipt = draft.receipt ?: when (val lookup = handle.lookup(command.operationId)) {
                is OperationLookup.Committed -> lookup.receipt
                is OperationLookup.NotRecorded -> handle.submit(command)
            }
            mutable.update { s -> s.copy(pending = s.pending.map { if (it.id == draft.id) it.copy(receipt = receipt) else it }) }
            refreshLocked() // Overlay removed only after the receipt is represented in a snapshot.
        }
    }
    /** Prepare once on the IO worker, including when export precedes the first submit. */
    private fun prepareCommand(draft: PendingEdit): Command = draft.command ?: Command(
        draft.id, draft.dependencies, CommandBody.Marks(listOf(
            MarkMutation.AddStroke(draft.preview!!.id, draft.preview.sheetId, core.encode(draft.preview.channels), draft.preview.brush)
        ))
    ).also { prepared ->
        mutable.update { s -> s.copy(pending = s.pending.map { if (it.id == draft.id) it.copy(command = prepared) else it }) }
    }
    fun message(text: String) { mutable.update { it.copy(error = text) } }
    /** Clears a plain message. Never hides a save failure: while anything is unsaved the error stays. */
    fun dismissMessage() { mutable.update { if (it.unsaved) it else it.copy(error = null, libraryTask = null) } }

    /** User-authorized resolution only; caller must show confirmation for discard. */
    suspend fun leave(discard: Boolean = false): Boolean {
        var closed = false
        mutable.update { it.copy(closing = true) }
        try {
            work {
                if (state.value.unsaved && !discard && !state.value.recoveryExported) return@work
                // Keep all drafts and handle if durable close fails, including after confirmed discard.
                // Thumbnail from the durable snapshot only, never from drafts; no extra FFI.
                val closing = state.value.snapshot
                val thumbnail = closing?.let { snapshot -> buildThumbnail(snapshot, state.value.samples::get) }
                note?.closeDurably()
                note = null
                mutable.update { it.copy(snapshot = null, samples = emptyMap(), pending = emptyList(), creation = null,
                    error = null, recoveryExported = false) }
                closed = true
                if (closing != null && thumbnail != null) {
                    mutable.update { it.copy(thumbnails = it.thumbnails + (closing.noteId to thumbnail)) }
                    ThumbnailCache.write(thumbnailDir, closing.noteId, thumbnail)
                }
                // A listing failure after this point reports an error but the close already succeeded.
                reloadLibraryLocked()
            }
            return closed
        } finally { mutable.update { it.copy(closing = false) } }
    }
    /** Destination write and close must finish successfully before closure is authorized. */
    suspend fun exportRecovery(write: (RecoveryData) -> Unit) = work {
        val s = state.value
        val commands = s.pending.map(::prepareCommand)
        val data = RecoveryData(s.snapshot?.noteId ?: s.creation?.noteId, s.authorId ?: lib().authorId(), s.creation, commands)
        // Never stage a backup inside the library: a crash must not publish a duplicate note.
        val backupFile = File(File(root).parentFile, "syncr-recovery-${id()}.syncr")
        val backup = try {
            note?.export(backupFile.absolutePath)
            if (backupFile.exists()) backupFile.readBytes() else null
        } catch (_: Exception) { null } finally { backupFile.delete() }
        write(data.copy(backup = backup))
        mutable.update {
            val unchanged = it.pending.map { p -> p.id } == s.pending.map { p -> p.id } && it.creation == s.creation
            it.copy(recoveryExported = unchanged,
                error = if (unchanged) null else "Recovery exported, but newer drafts remain. Export again before leaving.")
        }
    }
    /** Called only after screen closure; no note handle may be released here. */
    suspend fun releaseLibrary() = work {
        check(note == null && !state.value.unsaved)
        closeSide()
        library?.release(); library = null
    }
}

data class RecoveryData(val noteId: String?, val authorId: String, val creation: CreateNoteRequest?, val commands: List<Command>,
    val backup: ByteArray? = null)
