package dev.syncr.prototype.notes

import dev.syncr.core.AuthorHead
import dev.syncr.core.CommandBody
import dev.syncr.core.CreateNoteRequest
import dev.syncr.core.NoteKind
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
    private val core0 = core
    private val editor = OpenNoteEditor(session, core, id)
    private val drafts = DraftQueue(session, core, editor, id)
    private val library = LibraryOperations(session, core, thumbnailDir, now)
    private val sync = SyncOperations(session, editor)

    val state: StateFlow<NotesState> = session.state

    /** Read mode for the open note: no edit of it is staged while this is set (see [OpenNoteEditor.readOnly]). */
    var readOnly: Boolean
        get() = editor.readOnly
        set(value) { editor.readOnly = value }

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

    suspend fun create(title: String, notebook: String? = null, kind: NoteKind = NoteKind.NOTE, layout: NewNoteLayout? = null) {
        // IDs are allocated once, before dispatch, so a failed or lost reply retries the same request.
        if (state.value.creation == null) {
            session.update {
                val request = CreateNoteRequest(id(), id(), id(), title, notebook, kind)
                it.copy(creation = request, creationLayout = layout.takeIf { kind == NoteKind.NOTE }, error = null, recoveryExported = false)
            }
        }
        retry()
    }

    // ---- Pages ----

    /** Stages one page command built from the open note, delivers it, and says what the core thought. Returns whether it was saved. */
    private suspend fun stagePage(build: (dev.syncr.core.NoteSnapshot) -> dev.syncr.core.Command?): Boolean {
        val noteId = state.value.snapshot?.noteId ?: return false
        val staged = try {
            editor.stageContent(noteId, build)
        } catch (e: PageCommands.Refused) {
            message(e.message ?: "That page action is not possible."); return false
        } catch (e: IllegalArgumentException) {
            message(e.message ?: "Wait for the current changes to finish saving."); return false
        }
        if (staged) retry()
        return staged && !state.value.unsaved
    }

    /** A page after [after] (null: first), [width] by [height]; the new page's sheet ID, or null if nothing was added. */
    suspend fun addPage(after: String?, width: Float, height: Float): String? {
        var added: String? = null
        val ok = stagePage { note -> PageCommands.add(note, after, width, height, id).let { (command, sheet) -> added = sheet; command } }
        return added.takeIf { ok }
    }

    suspend fun resizePage(sheetId: String, width: Float, height: Float) = stagePage { PageCommands.resize(it, sheetId, width, height, id) }

    suspend fun rotatePages(sheetIds: List<String>, turn: PageTurn) = stagePage { PageCommands.rotate(it, sheetIds, turn, id) }

    /** Copies after the last picked page; the copies' sheet IDs. */
    suspend fun duplicatePages(sheetIds: List<String>): List<String> {
        val noteId = state.value.snapshot?.noteId ?: return emptyList()
        var made = emptyList<String>()
        val staged = try {
            editor.stageContents(noteId, id()) { note ->
                val (commands, ids) = PageCommands.duplicate(note, sheetIds, state.value.samples::get, core0::encode, id)
                made = ids; commands
            }
        } catch (e: PageCommands.Refused) { message(e.message ?: "That page action is not possible."); return emptyList()
        } catch (e: IllegalArgumentException) { message(e.message ?: "Wait for the current changes to finish saving."); return emptyList() }
        if (staged) retry()
        return made.takeIf { staged && !state.value.unsaved }.orEmpty()
    }

    suspend fun deletePages(sheetIds: List<String>) = stagePage { PageCommands.delete(it, sheetIds, id) }

    suspend fun movePages(sheetIds: List<String>, after: String?) = stagePage { PageCommands.move(it, sheetIds, after, id) }

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

    fun textDraft(blockId: String? = null, place: FreePlace? = null): TextDraft = TextCommands.draft(requireNotNull(state.value.snapshot), blockId, place)

    suspend fun saveText(draft: TextDraft, text: String) {
        if (editor.stageContent(draft.noteId) { TextCommands.save(it, draft, text, id) }) retry()
        check(!state.value.unsaved) { state.value.error ?: "Text is queued but not yet saved. Retry saving from the note." }
    }

    /** Stores the canvas's whole waypoint list. */
    suspend fun saveWaypoints(noteId: String, items: List<dev.syncr.core.Waypoint>) {
        if (editor.stageContent(noteId) { dev.syncr.prototype.canvas.CanvasCommands.waypoints(it, items, id) }) retry()
        check(!state.value.unsaved) { state.value.error ?: "The waypoints are queued but not yet saved." }
    }

    /** A tap on a rendered task box: flips `[ ]` and `[x]` on [line] of block [blockId] and nothing else. */
    suspend fun flipTask(blockId: String, line: Int) {
        val draft = textDraft(blockId)
        val flipped = dev.syncr.prototype.editor.Typing.flipTask(draft.text, line) ?: return
        saveText(draft, flipped.text)
    }

    suspend fun setResponseState(noteId: String, itemId: String, head: String, value: dev.syncr.core.MarginState) {
        if (editor.stageContent(noteId) { TextCommands.state(it, itemId, head, value, id) }) retry()
        check(!state.value.unsaved) { state.value.error ?: "Response change is queued but not yet saved." }
    }

    /**
     * Saves an answered ask under its passage. With no passage (a note without words) the question itself becomes the
     * note's next paragraph and the answer goes under it. The picture is kept only if the core's filter accepts it;
     * false means the words were saved and the picture was not.
     */
    suspend fun saveAsk(draft: TextDraft, span: dev.syncr.prototype.companion.PhoneCompanion.Span?, question: String,
        answer: dev.syncr.prototype.companion.PhoneCompanion.Answer): Boolean {
        val asked = question.trim().ifEmpty { dev.syncr.prototype.companion.PhoneCompanion.DEFAULT_QUESTION }
        var d = draft
        var start = span?.start ?: 0; var end = span?.end ?: 0
        if (span == null) {
            val text = d.text.trimEnd().let { if (it.isEmpty()) asked else it + "\n\n" + asked }
            saveText(d, text)
            d = textDraft(d.blockId)
            start = text.length - asked.length; end = text.length
        }
        val clean = answer.svg?.let { svg -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { dev.syncr.core.filterPicture(svg) }.getOrNull() } }
        val words = answer.text.ifBlank { if (clean != null) "See the picture." else "" }
        check(words.isNotBlank()) { "The answer was empty. Nothing was saved." }
        var pictureId: String? = null
        val staged = editor.stageContent(d.noteId) { note ->
            TextCommands.ask(note, d, start, end, asked, words, clean?.let { dev.syncr.prototype.companion.PhoneCompanion.pictureTitle(it, asked) to it }, id)
                .also { pictureId = it.pictureId }.command
        }
        if (staged) retry()
        check(!state.value.unsaved) { state.value.error ?: "The answer is queued but not yet saved." }
        val picture = pictureId
        val block = d.blockId
        if (picture != null && block != null) {
            if (editor.stageContent(d.noteId) { note -> TextCommands.embed(note, block, picture, end, id) }) retry()
        }
        return answer.svg == null || clean != null
    }

    suspend fun saveExplanation(draft: TextDraft, start: Int, end: Int, answer: String) {
        if (editor.stageContent(draft.noteId) { TextCommands.explanation(it, draft, start, end, answer, id) }) retry()
        check(!state.value.unsaved) { state.value.error ?: "Response is queued but not yet saved." }
    }

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
                val thumbnail = closing?.let { buildThumbnail(it, current.samples::get, geometry = geometryOf(it)) }
                session.closeNote()
                session.update {
                    it.copy(
                        snapshot = null,
                        samples = emptyMap(),
                        pending = emptyList(),
                        creation = null,
                        creationLayout = null,
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

    /** Link v1: operation-set digests of [ids] (notes that are gone are left out). Silent, serialized on the worker. */
    suspend fun syncDigests(ids: List<String>): Map<String, SyncDigestInfo> {
        val out = HashMap<String, SyncDigestInfo>()
        session.background { for (id in ids) sync.digest(id)?.let { out[id] = it } }
        return out
    }

    /** Delta sync: the newest operation ID per author of [noteId] (author to ID); null when the note is gone. Silent, on the worker. */
    suspend fun syncHeads(noteId: String): Map<String, String>? {
        var heads: Map<String, String>? = null
        session.background { heads = sync.heads(noteId)?.associate { it.authorId to it.newestId } }
        return heads
    }

    /**
     * Delta sync: writes the operations a peer holding [base] lacks into [path], through the open handle or a side handle
     * like [exportCopy]. The exported sequence, or null when no delta should be sent (no common ground, not smaller than a copy).
     */
    suspend fun exportDelta(noteId: String, base: Map<String, String>, path: String): Long? {
        var sequence: Long? = null
        session.background {
            val open = session.note?.takeIf { session.current.snapshot?.noteId == noteId }
            val handle = open ?: session.openSide(noteId)
            try {
                val receipt = handle.exportDelta(base.map { AuthorHead(it.key, it.value) }, path)
                if (receipt.anchored) sequence = receipt.exportedLocalSequence.toLong()
            } finally {
                if (open == null) session.closeSide()
            }
        }
        return sequence
    }

    /** Delta sync: merges the Mac's delta for [noteId]. Waits a few seconds for the open note's own saves first, like [applyCopy]. */
    suspend fun applyDelta(noteId: String, path: String): AppliedCopy {
        var waited = 0
        while (state.value.unsaved && waited < 50) { kotlinx.coroutines.delay(100); waited++ }
        var result: Result<AppliedCopy> = Result.failure(IllegalStateException("not run"))
        session.background { result = runCatching { sync.applyDelta(noteId, path) }.recoverCatching { throw IllegalStateException(diagnostic(it)) } }
        return result.getOrThrow()
    }

    /** Link v1: IDs in Recently deleted, read from the core. */
    suspend fun trashIds(): List<String> {
        var ids = emptyList<String>()
        session.background { ids = session.library().listDeletedNotes().map { it.id } }
        return ids
    }

    /**
     * Link v1: puts the Mac's copy of [noteId], staged at [path], into the library (installs, merges, or rebases when the
     * merge rejects operations). Waits a few seconds for the open note's own saves first; fails with a message, never
     * silently. The library and the open note's snapshot are read back when it succeeds.
     */
    suspend fun applyCopy(noteId: String, path: String): AppliedCopy {
        var waited = 0
        while (state.value.unsaved && waited < 50) { kotlinx.coroutines.delay(100); waited++ }
        var result: Result<AppliedCopy> = Result.failure(IllegalStateException("not run"))
        session.background { result = runCatching { sync.apply(noteId, path) }.recoverCatching { throw IllegalStateException(diagnostic(it)) } }
        return result.getOrThrow()
    }

    /** Called only after screen closure; no note handle may be released here. */
    suspend fun releaseLibrary() = session.work {
        check(session.note == null && !session.current.unsaved)
        session.releaseLibrary()
    }
}
