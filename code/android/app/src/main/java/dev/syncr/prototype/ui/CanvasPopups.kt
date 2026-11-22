package dev.syncr.prototype.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.syncr.core.NoteSnapshot
import dev.syncr.core.Waypoint
import dev.syncr.prototype.canvas.CanvasCommands
import dev.syncr.prototype.canvas.CanvasMode
import dev.syncr.prototype.canvas.CanvasWaypoints
import dev.syncr.prototype.notes.NoteCanvasView
import dev.syncr.prototype.ui.theme.Space
import dev.syncr.prototype.ui.theme.Syncr
import kotlinx.coroutines.delay

/** Runs a canvas command on the repository's worker; a refusal is shown in the banner instead of crashing. */
private fun NotesViewModel.canvasTask(block: suspend () -> Unit) = run {
    try { block() } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e }
    catch (e: Exception) { repository.message(e.message ?: "Could not save that.") }
}

/** Save the current view under a name, jump to a saved one, or remove it. Stored in the note, so they sync. */
@Composable
internal fun WaypointsSheet(vm: NotesViewModel, snapshot: NoteSnapshot, view: NoteCanvasView?, onDismiss: () -> Unit) {
    val c = Syncr.colors
    val items = snapshot.waypoints?.items.orEmpty()
    var name by remember { mutableStateOf(CanvasWaypoints.nextName(items)) }
    var problem by remember { mutableStateOf<String?>(null) }
    SyncrSheet(onDismiss, "Waypoints") {
        SyncrTextField(name, { name = it; problem = null }, Modifier.fillMaxWidth(), label = "Name", singleLine = true, error = problem)
        Row(Modifier.fillMaxWidth().padding(vertical = Space.s), horizontalArrangement = Arrangement.End) {
            PrimaryAction("Save this view") {
                val here = view?.currentWaypoint(name.trim())
                val next = here?.let { CanvasWaypoints.saved(items, it) }
                if (next == null) problem = if (name.isBlank()) "Give it a name." else "A canvas holds 100 waypoints."
                else vm.canvasTask { vm.repository.saveWaypoints(snapshot.noteId, next) }.also { name = CanvasWaypoints.nextName(next) }
            }
        }
        if (items.isEmpty()) Text("No waypoints yet. Save a view to come back to it.", style = Syncr.type.caption, color = c.muted)
        items.forEach { wp ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    MenuRow(wp.name, "${Math.round(wp.zoom * 100f)}%") { view?.goTo(wp); onDismiss() }
                }
                IconAction(SyncrIcons.Close, "Remove ${wp.name}", { vm.canvasTask { vm.repository.saveWaypoints(snapshot.noteId, CanvasWaypoints.removed(items, wp.name)) } })
            }
        }
    }
}
