package dev.syncr.prototype.notes

import dev.syncr.core.NoteSnapshot

/** Android ignores text. In particular, generic undo/redo must not accidentally edit a text or mixed batch. */
internal fun handwritingHistoryAvailable(snapshot: NoteSnapshot, redo: Boolean): Boolean {
    val target = if (redo) snapshot.redoTarget else snapshot.undoTarget
    if (target == null) return false
    val dependencies = if (redo) snapshot.redoDependencies else snapshot.undoDependencies
    return snapshot.textBlocks.none { it.head.operationId in dependencies }
}
