package dev.syncr.prototype.notes

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What the repository's parts share: the published state, the core handles and the one serialized
 * IO worker. Every core call goes through [work] or [background], so none runs on the UI thread
 * and no two run at once. Handle methods here may only be called from inside those blocks.
 */
internal class CoreSession(
    val root: String,
    private val core: CoreAdapter,
    private val io: CoroutineDispatcher,
) {
    private val mutable = MutableStateFlow(NotesState())
    val state: StateFlow<NotesState> = mutable.asStateFlow()
    val current: NotesState get() = mutable.value

    private val mutex = Mutex()
    private var library: LibraryAccess? = null

    /** The note shown in the editor. */
    var note: NoteAccess? = null
        private set

    /** A note opened only by a library task or thumbnail read; never shown, closed durably before anything else opens. */
    private var side: NoteAccess? = null

    fun update(change: (NotesState) -> NotesState) = mutable.update(change)

    /** Runs [block] on the worker with the busy flag set; a failure becomes the user-facing error. */
    suspend fun work(block: () -> Unit) = withContext(io) {
        mutex.withLock {
            update { it.copy(busy = true) }
            try {
                block()
            } catch (e: Exception) {
                update { it.copy(error = diagnostic(e)) }
            } finally {
                update { it.copy(busy = false) }
            }
        }
    }

    /** Same serialization as [work] but silent: no busy flag and no user-facing error (thumbnails). */
    suspend fun background(block: () -> Unit) = withContext(io) {
        mutex.withLock {
            try {
                block()
            } catch (_: Exception) {
                // Best effort by design: the caller retries on its next pass.
            }
        }
    }

    /** Opens the library on first use and publishes its author ID. */
    fun library(): LibraryAccess = library ?: core.open(root).also { opened ->
        library = opened
        val author = opened.authorId()
        update { it.copy(authorId = author) }
    }

    /** Reads the library lists back from the core; they are never inferred locally. */
    fun reloadLibrary() {
        val library = library()
        val notes = library.listNotes().sortedByDescending { it.modifiedAtMs }
        val notebooks = library.listNotebooks()
        update { it.copy(notes = notes, notebooks = notebooks) }
        // Separately: an unreadable deleted note reports an error but never hides the library.
        val deleted = library.listDeletedNotes()
        update { it.copy(deleted = deleted) }
    }

    /** Opens [noteId] in the editor. A side handle is closed durably first. */
    fun openNote(noteId: String) {
        closeSide()
        note = library().openNote(noteId)
    }

    /** Durable `closeNote` then release. On failure the handle is kept, so nothing is lost or released early. */
    fun closeNote() {
        note?.closeDurably()
        note = null
    }

    /** Opens [noteId] as the side handle, closing any previous one durably first. */
    fun openSide(noteId: String): NoteAccess {
        closeSide()
        return library().openNote(noteId).also { side = it }
    }

    /** Fallible durable close of the side handle; on failure it stays retained and nothing else opens. */
    fun closeSide() {
        side?.let {
            it.closeDurably()
            side = null
        }
    }

    fun releaseLibrary() {
        closeSide()
        library?.release()
        library = null
    }
}
