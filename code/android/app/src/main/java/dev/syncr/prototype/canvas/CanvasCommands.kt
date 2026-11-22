package dev.syncr.prototype.canvas

import dev.syncr.core.Base
import dev.syncr.core.Command
import dev.syncr.core.CommandBody
import dev.syncr.core.NoteSnapshot
import dev.syncr.core.Waypoint

/** The canvas's own commands (waypoints; the background is not used, canvases are plain paper). */
internal object CanvasCommands {
    fun waypoints(note: NoteSnapshot, items: List<Waypoint>, id: () -> String): Command {
        val head = requireNotNull(note.waypoints) { "This note is not a canvas." }.head.operationId
        return Command(id(), listOf(head), CommandBody.SetWaypoints(items, Base.Operation(head)))
    }
}
