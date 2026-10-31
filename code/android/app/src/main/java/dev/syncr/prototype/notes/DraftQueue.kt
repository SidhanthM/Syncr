package dev.syncr.prototype.notes

import dev.syncr.core.Command
import dev.syncr.core.CommandBody
import dev.syncr.core.MarkMutation
import dev.syncr.core.OperationLookup
import java.io.File

/**
 * Delivers retained work to the core and exports it for recovery. Every command is prepared once
 * and then retried or exported unchanged, with the same IDs. A lost reply is resolved by lookup,
 * never by a second submit. Worker only.
 */
internal class DraftQueue(
    private val session: CoreSession,
    private val core: CoreAdapter,
    private val editor: OpenNoteEditor,
    private val newId: () -> String,
) {
    /** Creates the pending note, if any, then opens it. */
    fun submitCreation() {
        val request = session.current.creation ?: return
        val library = session.library()
        if (library.lookupCreation(request.operationId) !is OperationLookup.Committed) library.create(request)
        // Creation is durable even if the following open or refresh fails.
        session.update { it.copy(creation = null) }
        session.reloadLibrary()
        if (session.note == null) session.openNote(request.noteId)
        editor.refresh()
    }

    /** Submits pending edits in order. A failure stops the queue, so later edits wait behind it. */
    fun submitPending() {
        val handle = session.note ?: return
        while (session.current.pending.isNotEmpty()) {
            val draft = session.current.pending.first()
            val command = prepare(draft)
            val receipt = draft.receipt ?: when (val lookup = handle.lookup(command.operationId)) {
                is OperationLookup.Committed -> lookup.receipt
                is OperationLookup.NotRecorded -> handle.submit(command)
            }
            session.update { state ->
                state.copy(pending = state.pending.map { if (it.id == draft.id) it.copy(receipt = receipt) else it })
            }
            // The overlay goes only once the receipt is represented in a snapshot.
            editor.refresh()
        }
    }

    /** Destination write and close must finish successfully before closure is authorized. */
    fun exportRecovery(write: (RecoveryData) -> Unit) {
        val before = session.current
        val commands = before.pending.map(::prepare)
        val data = RecoveryData(
            noteId = before.snapshot?.noteId ?: before.creation?.noteId,
            authorId = before.authorId ?: session.library().authorId(),
            creation = before.creation,
            commands = commands,
        )
        write(data.copy(backup = coreBackup()))
        session.update { state ->
            val unchanged = state.pending.map { it.id } == before.pending.map { it.id } && state.creation == before.creation
            state.copy(
                recoveryExported = unchanged,
                error = if (unchanged) null else "Copy exported, but you've written more since. Export again before leaving.",
            )
        }
    }

    /** Builds a stroke's command once, including when export precedes the first submit, and retains it. */
    private fun prepare(draft: PendingEdit): Command {
        draft.command?.let { return it }
        val preview = draft.preview!!
        val stroke = MarkMutation.AddStroke(preview.id, preview.sheetId, core.encode(preview.channels), preview.brush)
        val prepared = Command(draft.id, draft.dependencies, CommandBody.Marks(listOf(stroke)))
        session.update { state ->
            state.copy(pending = state.pending.map { if (it.id == draft.id) it.copy(command = prepared) else it })
        }
        return prepared
    }

    /** The core's own export of the open note, or null. Staged next to, never inside, the library. */
    private fun coreBackup(): ByteArray? {
        // A crash must not leave a backup inside the library, where it could appear as a duplicate note.
        val file = File(File(session.root).parentFile, "syncr-recovery-${newId()}.syncr")
        return try {
            session.note?.export(file.absolutePath)
            if (file.exists()) file.readBytes() else null
        } catch (_: Exception) {
            null // The commands alone still recover the work.
        } finally {
            file.delete()
        }
    }
}
