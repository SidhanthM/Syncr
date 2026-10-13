package dev.syncr.example
import dev.syncr.core.*
fun main(args: Array<String>) {
    openLibrary(args.single()).use { library ->
        library.openNote(library.listNotes().single().id).use { note ->
            val s = note.snapshot()
            check(s.localSequence == 5uL)
            check(s.sheets.single().creationOperationId == s.structureHead.operationId)
            check(s.strokes.single().tombstone)
            check(s.undoDependencies == listOf(s.strokes.single().head.operationId))
            check(s.redoDependencies.isEmpty())
            check(decodeSamples(note.getSamples(s.strokes.single().id)).x == listOf(10f, 20f))
            val undo = Command(newId(), s.undoDependencies, CommandBody.Undo(s.undoTarget!!))
            note.submit(undo)
            check(!note.snapshot().strokes.single().tombstone)
            check(note.submit(undo).duplicate)
            check(note.snapshot().redoDependencies == listOf(undo.operationId))
            note.closeNote()
        }
    }
    println("PASS: pre-amendment library sequence-5 file reopened with provenance, persisted undo/redo, immutable samples and retry; no migration")
}
