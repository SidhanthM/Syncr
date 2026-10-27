package dev.syncr.prototype.link

/** No credentials or full IDs belong in diagnostics. */
fun linkStatusLine(status: SessionStatus): String = when (status.phase) {
    Phase.CONNECTING -> "state connecting mac=${status.macName ?: "unknown"}"
    Phase.NEEDS_CODE -> "state needs code mac=${status.macName ?: "unknown"}"
    Phase.CONNECTED -> "state connected mac=${status.macName ?: "unknown"}"
    Phase.IDLE -> "state disconnected reason=${status.message ?: "idle"}"
}

fun targetLine(target: StreamTarget): String =
    "target present=${target.noteId != null && target.sheetId != null} writable=${target.writable} rev=${target.rev} " +
        "note=${target.noteId?.take(8) ?: "none"} sheet=${target.sheetId?.take(8) ?: "none"}"
