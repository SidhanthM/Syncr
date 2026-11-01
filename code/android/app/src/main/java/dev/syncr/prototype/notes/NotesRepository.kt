package dev.syncr.prototype.notes

import dev.syncr.core.CommandBody
import dev.syncr.core.CreateNoteRequest
import dev.syncr.core.newId
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/**
 * The app's only door to the Rust core. Every blocking call runs on one serialized IO worker
 * ([CoreSession]); nothing is reported saved until a durable core receipt is represented in a
 * snapshot, and retained commands keep their IDs and payloads across retry and export.
 *
 * Parts: [OpenNoteEditor] stages edits to the open note, [DraftQueue] delivers and exports them,
 * [LibraryOperations] runs library tasks and thumbnails.
 */
class NotesRepository(
    root: String,
    core: CoreAdapter = GeneratedCoreAdapter(),
    io: CoroutineDispatcher = Dispatchers.IO,
    thumbnailDir: File? = null,
    now: () -> Long = System::currentTimeMillis,
    private val id: () -> String = ::newId,
) {
    private val session = CoreSession(root, core, io)
    private val editor = OpenNoteEditor(session, core, id)
    private val drafts = DraftQueue(session, core, editor, id)
    private val library = LibraryOperations(session, core, thumbnailDir, now)

    val state: StateFlow<NotesState> = session.state

    // ---- Library ----

    suspend fun loadLibrary() = session.work {
        session.reloadLibrary()
        session.update { it.copy(error = null) }
        library.purgeExpired()
    }

    /** Reloads the open note, or the library when no note is open and nothing is unsaved. */
    suspend fun refresh() = session.work {
        if (session.note != null) {
            editor.refresh()
        } else if (!session.current.unsaved) {
            session.reloadLibrary()
            session.update { it.copy(error = if (it.libraryTask != null) it.error else null) }
        }
    }

    suspend fun createNotebook(name: String) = session.work {
        session.library().createNotebook(name)
        session.reloadLibrary()
    }

    /** The core refuses while any note uses the name; the UI explains that before asking. */
    suspend fun deleteNotebook(name: String) = session.work {
        session.library().deleteNotebook(name)
        session.reloadLibrary()
    }

    suspend fun renameNotebook(oldName: String, newName: String) =
        startTask(LibraryTask.RenameNotebook(oldName, newName))

    suspend fun moveNote(noteId: String, notebook: String?) =
        startTask(LibraryTask.EditNote(noteId, id(), NoteChange.Move(notebook)))

    suspend fun renameNote(noteId: String, title: String) =
        startTask(LibraryTask.EditNote(noteId, id(), NoteChange.Rename(title)))

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
        session.update { it.copy(recentlyDeleted = null) }
        restoreNote(noteId)
    }

    fun dismissDeleted() = session.update { it.copy(recentlyDeleted = null) }

    /** Fills missing or stale card thumbnails one note at a time, so user actions interleave on the worker. */
    suspend fun loadThumbnails() {
        val current = state.value
        val wanted = current.notes.filter { current.thumbnails[it.id]?.localSequence != it.localSequence }
        for (summary in wanted) {
            var keepGoing = true
            session.background { keepGoing = library.loadThumbnail(summary) }
            if (!keepGoing) break
        }
    }

    /** Library tasks run only from the library: never while a note is open or anything is unsaved. */
    private suspend fun startTask(task: LibraryTask) {
        val current = state.value
        if (current.libraryTask != null || current.snapshot != null || current.unsaved) return
        session.update { it.copy(libraryTask = task, error = null) }
        retry()
    }

    // ---- Open note ----

    suspend fun create(title: String, notebook: String? = null) {
        // IDs are allocated once, before dispatch, so a failed or lost reply retries the same request.
        if (state.value.creation == null) {
            session.update {
                val request = CreateNoteRequest(id(), id(), id(), title, notebook)
                it.copy(creation = request, error = null, recoveryExported = false)
            }
        }
        retry()
    }

    suspend fun open(noteId: String) = session.work {
        check(session.note == null && !session.current.unsaved)
        session.openNote(noteId)
        editor.refresh()
    }

    /** Retains the complete measured stroke before any asynchronous codec or submit. */
    fun stageStroke(preview: InkPreview): Boolean = editor.stageStroke(preview)

    fun stageErase(ids: Set<String>): Boolean = stageMarks(MarkEdit.Delete(ids))

    /** Stages one atomic Marks command for a whole selection or eraser gesture. */
    fun stageMarks(edit: MarkEdit): Boolean = editor.stageMarks(edit)

    /** Moves the OPEN note; like rename it waits for saved changes. */
    suspend fun moveOpenNote(notebook: String?) {
        if (editor.stageMetadata { base -> CommandBody.SetNotebook(notebook, base) }) retry()
    }

    /** Archives or unarchives the OPEN note; like move it waits for saved changes. */
    suspend fun archiveOpenNote(archived: Boolean) {
        if (editor.stageMetadata { base -> CommandBody.SetArchived(archived, base) }) retry()
    }

    suspend fun rename(title: String) {
        if (editor.stageMetadata { base -> CommandBody.RenameNote(title, base) }) retry()
    }

    /**
     * Undoes or redoes one step. An eraser gesture is several commands, one per stretch of the sweep, so
     * its commands are undone (or redone) together, each saved before the next is staged.
     */
    suspend fun history(redo: Boolean) {
        val gesture = editor.historyTarget(redo)?.let(editor::gestureOf)
        do {
            if (!editor.stageHistory(redo)) return
            retry()
        } while (gesture != null && editor.historyTarget(redo)?.let(editor::gestureOf) == gesture)
    }

    // ---- Delivery, recovery and closure ----

    /** Delivers everything retained: the pending creation, a library task, then the open note's edits. */
    suspend fun retry() = session.work {
        session.update { it.copy(error = null) }
        drafts.submitCreation()
        if (session.note == null) session.current.libraryTask?.let(library::run)
        drafts.submitPending()
    }

    fun message(text: String) = editor.message(text)

    /** Clears a plain message. Never hides a save failure: while anything is unsaved the error stays. */
    fun dismissMessage() = session.update { if (it.unsaved) it else it.copy(error = null, libraryTask = null) }

    /**
     * Closes the open note durably. Unsaved work blocks this unless it was exported or the user
     * confirmed [discard]. If the durable close fails, drafts and the handle are kept, even after a
     * confirmed discard. Returns whether the note was closed.
     */
    suspend fun leave(discard: Boolean = false): Boolean {
        var closed = false
        session.update { it.copy(closing = true) }
        try {
            session.work {
                val current = session.current
                if (current.unsaved && !discard && !current.recoveryExported) return@work
                // The card comes from the durable snapshot only, never from drafts, and needs no extra FFI.
                val closing = current.snapshot
                val thumbnail = closing?.let { buildThumbnail(it, current.samples::get) }
                session.closeNote()
                session.update {
                    it.copy(
                        snapshot = null,
                        samples = emptyMap(),
                        pending = emptyList(),
                        creation = null,
                        error = null,
                        recoveryExported = false,
                    )
                }
                closed = true
                if (closing != null && thumbnail != null) library.storeThumbnail(closing.noteId, thumbnail)
                // A listing failure after this point reports an error, but the close already succeeded.
                session.reloadLibrary()
            }
            return closed
        } finally {
            session.update { it.copy(closing = false) }
        }
    }

    /** Destination write and close must finish successfully before closure is authorized. */
    suspend fun exportRecovery(write: (RecoveryData) -> Unit) = session.work { drafts.exportRecovery(write) }

    /**
     * The core's own `export_copy` of [noteId] into [path], for the Mac link. Silent (no busy flag, no user-facing error) and
     * serialized with every other core call on the worker, so it never runs on the pen's thread. The open note is exported
     * through its open handle, any other through a side handle. Returns the exported sequence, or null if it failed.
     */
    suspend fun exportCopy(noteId: String, path: String): Long? {
        var sequence: Long? = null
        session.background {
            val open = session.note?.takeIf { session.current.snapshot?.noteId == noteId }
            val handle = open ?: session.openSide(noteId)
            try {
                sequence = handle.export(path).exportedLocalSequence.toLong()
            } finally {
                if (open == null) session.closeSide()
            }
        }
        return sequence
    }

    /** Called only after screen closure; no note handle may be released here. */
    suspend fun releaseLibrary() = session.work {
        check(session.note == null && !session.current.unsaved)
        session.releaseLibrary()
    }
}
