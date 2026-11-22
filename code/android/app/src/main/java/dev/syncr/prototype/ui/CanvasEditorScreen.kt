package dev.syncr.prototype.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.syncr.prototype.canvas.CanvasMode
import dev.syncr.prototype.notes.PageViewSettings
import dev.syncr.prototype.notes.NoteCanvasView
import dev.syncr.prototype.notes.NotesState
import dev.syncr.prototype.ui.theme.Motion
import dev.syncr.prototype.ui.theme.Space
import dev.syncr.prototype.ui.theme.Syncr
import dev.syncr.prototype.ui.theme.TouchTarget
import dev.syncr.prototype.ui.theme.floatingSurface

private enum class CanvasPopup { NONE, WAYPOINTS }

/**
 * The infinite canvas (P3): the same paper-and-pen note view with no edges, a quiet bar for zoom, Fit all, the minimap,
 * and waypoints, and the same tool bubble and typing as a note. Same signature as [NoteScreen], so the
 * app picks one by the note's kind. It has no title band: a canvas has no masthead.
 */
@Composable
fun CanvasEditorScreen(vm: NotesViewModel, state: NotesState, dark: Boolean, tools: ToolState, actions: NoteActions,
    banner: @Composable () -> Unit) {
    val snapshot = state.snapshot ?: return
    val c = Syncr.colors
    val context = LocalContext.current
    val mode = remember(context) { CanvasMode(context) }
    var view by remember { mutableStateOf<NoteCanvasView?>(null) }
    var selected by remember(snapshot.noteId) { mutableIntStateOf(0) }
    var temporaryEraser by remember(snapshot.noteId) { mutableStateOf(false) }
    var contentId by rememberSaveable(snapshot.noteId) { mutableStateOf<String?>(null) }
    var response by rememberSaveable(snapshot.noteId) { mutableStateOf(false) }
    var percent by remember(snapshot.noteId) { mutableIntStateOf(100) }
    var minimap by remember { mutableStateOf(mode.minimapOn) }
    var popup by remember { mutableStateOf(CanvasPopup.NONE) }

    Box(Modifier.fillMaxSize().background(c.desk).imePadding()) {
        NoteCanvas(vm, state, dark, tools, PageViewSettings(), actions, onView = { view = it }, onSelection = { selected = it },
            onTitle = {}, onUnderTop = {}, onScrolled = {}, onPenContact = {},
            onTemporaryEraser = { temporaryEraser = it },
            onContent = { if (contentId == null) { contentId = it.id; response = it.response } },
            canvasMode = mode, onZoom = { percent = it })

        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().safeDrawingPadding().padding(horizontal = Space.l, vertical = Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.s)) {
            banner()
            AnimatedVisibility(selected > 0, Modifier.align(Alignment.CenterHorizontally),
                enter = Motion.fadeInOnly(Syncr.reducedMotion), exit = Motion.fadeOutOnly(Syncr.reducedMotion)) {
                SelectionBar(selected,
                    onColour = { colour -> view?.restyleSelection(colour) },
                    onDelete = { view?.deleteSelection() },
                    onDeselect = { view?.deselect() })
            }
            if (contentId == null && selected == 0) CanvasBar(percent, minimap,
                onOut = { view?.zoomStep(false) }, onIn = { view?.zoomStep(true) }, onFit = { view?.fitAll() },
                onMinimap = { minimap = !minimap; mode.minimapOn = minimap; view?.setMinimap(minimap) },
                onWaypoints = { popup = CanvasPopup.WAYPOINTS },
                modifier = Modifier.align(Alignment.End))
        }

        if (contentId != null && !response) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding()) {
                androidx.compose.runtime.key(snapshot.noteId, contentId) {
                    MobileTextEditor(vm.repository, contentId?.ifEmpty { null }, view) { contentId = null }
                }
            }
        } else {
            NoteBubble(state, tools, actions, temporaryEraser, onType = { view?.startTyping() })
            PenHolsterEffect { inserted -> actions.onTools(tools.holster(inserted)) }
        }
    }
    contentId?.let { id ->
        if (response) ResponseInspector(vm.repository, snapshot, id) { contentId = null }
    }
    when (popup) {
        CanvasPopup.WAYPOINTS -> WaypointsSheet(vm, snapshot, view) { popup = CanvasPopup.NONE }
        CanvasPopup.NONE -> Unit
    }
}

/** Zoom out, the zoom, zoom in, Fit all, the minimap switch, and waypoints: one quiet pill. */
@Composable
private fun CanvasBar(percent: Int, minimap: Boolean, onOut: () -> Unit, onIn: () -> Unit, onFit: () -> Unit, onMinimap: () -> Unit,
    onWaypoints: () -> Unit, modifier: Modifier = Modifier) {
    val c = Syncr.colors
    Row(modifier.floatingSurface().padding(horizontal = Space.xs).semantics { contentDescription = "Canvas controls" },
        verticalAlignment = Alignment.CenterVertically) {
        BarButton(CanvasIcons.Minus, "Zoom out", onOut)
        Text("$percent%", style = Syncr.type.label, color = c.ink, maxLines = 1,
            modifier = Modifier.width(48.dp).semantics { contentDescription = "Zoom $percent percent" }, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        BarButton(SyncrIcons.Plus, "Zoom in", onIn)
        BarButton(SyncrIcons.Fit, "Fit all", onFit)
        BarButton(CanvasIcons.Map, if (minimap) "Hide minimap" else "Show minimap", onMinimap, on = minimap)
        BarButton(CanvasIcons.Waypoint, "Waypoints", onWaypoints)
    }
}

@Composable
private fun BarButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, on: Boolean = false) {
    val c = Syncr.colors
    Box(Modifier.size(width = 44.dp, height = TouchTarget).clickable(onClickLabel = label, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        SyncrIcon(icon, if (on) c.accent else c.ink, size = 22.dp)
    }
}
