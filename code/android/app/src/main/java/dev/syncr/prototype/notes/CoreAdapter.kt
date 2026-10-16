package dev.syncr.prototype.notes

import dev.syncr.core.*

/** Blocking consumer boundary. Only NotesRepository's IO worker may call this. */
interface CoreAdapter {
    fun open(root: String): LibraryAccess
    fun encode(channels: SampleChannels): ByteArray
    fun decode(bytes: ByteArray): SampleChannels
}
interface LibraryAccess {
    fun authorId(): String
    fun listNotes(): List<NoteSummary>
    fun create(request: CreateNoteRequest): Receipt
    fun lookupCreation(id: String): OperationLookup
    fun openNote(id: String): NoteAccess
    fun listNotebooks(): List<NotebookSummary>
    fun createNotebook(name: String)
    fun deleteNotebook(name: String)
    fun renameNotebook(oldName: String, newName: String)
    /** Moves a closed note to Recently deleted (core refuses with NoteLocked while it is open). Idempotent. */
    fun deleteNote(id: String)
    fun listDeletedNotes(): List<DeletedNoteSummary>
    /** Back into the library; idempotent once restored. */
    fun restoreNote(id: String)
    /** Permanent; only for notes in Recently deleted. */
    fun purgeNote(id: String)
    fun purgeDeletedOlderThan(cutoffMs: Long): UInt
    fun release()
}
interface NoteAccess {
    fun snapshot(): NoteSnapshot
    fun samples(id: String): ByteArray
    fun submit(command: Command): Receipt
    fun lookup(id: String): OperationLookup
    fun changes(cursor: EventCursor): ChangePage
    fun export(path: String): ExportReceipt
    /** Fallible durable close THEN reference release. Never release after a failed close. */
    fun closeDurably()
}
class GeneratedCoreAdapter : CoreAdapter {
    override fun encode(channels: SampleChannels) = encodeSamples(channels)
    override fun decode(bytes: ByteArray) = decodeSamples(bytes)
    override fun open(root: String): LibraryAccess = GeneratedLibrary(openLibrary(root))
    private class GeneratedLibrary(private val handle: SyncrLibrary) : LibraryAccess {
        override fun authorId() = handle.authorId()
        override fun listNotes() = handle.listNotes()
        override fun create(request: CreateNoteRequest) = handle.createNote(request)
        override fun lookupCreation(id: String) = handle.lookupCreation(id)
        override fun openNote(id: String): NoteAccess = GeneratedNote(handle.openNote(id))
        override fun listNotebooks() = handle.listNotebooks()
        override fun createNotebook(name: String) = handle.createNotebook(name)
        override fun deleteNotebook(name: String) = handle.deleteNotebook(name)
        override fun renameNotebook(oldName: String, newName: String) = handle.renameNotebook(oldName, newName)
        override fun deleteNote(id: String) = handle.deleteNote(id)
        override fun listDeletedNotes() = handle.listDeletedNotes()
        override fun restoreNote(id: String) = handle.restoreNote(id)
        override fun purgeNote(id: String) = handle.purgeNote(id)
        override fun purgeDeletedOlderThan(cutoffMs: Long) = handle.purgeDeletedOlderThan(cutoffMs)
        override fun release() = handle.close()
    }
    private class GeneratedNote(private val handle: Note) : NoteAccess {
        override fun snapshot() = handle.snapshot()
        override fun samples(id: String) = handle.getSamples(id)
        override fun submit(command: Command) = handle.submit(command)
        override fun lookup(id: String) = handle.lookupOperation(id)
        override fun changes(cursor: EventCursor) = handle.changesSince(cursor, 256u)
        override fun export(path: String) = handle.exportCopy(path)
        override fun closeDurably() { handle.closeNote(); handle.close() }
    }
}

fun diagnostic(error: Throwable): String {
    // Binding amendment: safe payload is detail, not a hand-written message field.
    val detail = when (error) {
        is CoreException.InvalidInput -> error.detail
        is CoreException.UnsupportedVersion -> error.detail
        is CoreException.NotFound -> error.detail
        is CoreException.PermissionDenied -> error.detail
        is CoreException.StorageFull -> error.detail
        is CoreException.StorageIo -> error.detail
        is CoreException.NoteLocked -> error.detail
        is CoreException.StaleBase -> error.detail
        is CoreException.MissingDependency -> error.detail
        is CoreException.ResourceLimit -> error.detail
        is CoreException.IdCollision -> error.detail
        is CoreException.Closed -> error.detail
        is CoreException.Internal -> error.detail
        else -> "Local operation failed. Retry or export recovery."
    }
    return "${error.javaClass.simpleName}: $detail"
}
