package dev.syncr.prototype.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.syncr.prototype.link.android.LinkController
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.syncr.core.NoteKind
import dev.syncr.prototype.notes.NotesState
import dev.syncr.prototype.notes.PageArrangement
import dev.syncr.prototype.notes.PageFit
import dev.syncr.prototype.notes.PageViewSettings
import dev.syncr.prototype.ui.theme.Space
import dev.syncr.prototype.ui.theme.Syncr

class MoreActions(
    val onDismiss: () -> Unit,
    val onRename: () -> Unit,
    val onMove: () -> Unit,
    val onExport: () -> Unit,
    val onPointer: () -> Unit,
    val onFit: () -> Unit,
    val onLibrary: () -> Unit,
    val onArchive: (Boolean) -> Unit,
    val onDelete: () -> Unit,
    /** Pages (a Note only): reading settings for this note on this device, and what the page chrome asks of the editor. */
    val view: PageViewSettings = PageViewSettings(),
    val onView: (PageViewSettings) -> Unit = {},
    val onAddPage: () -> Unit = {},
    val onFitPage: (PageFit) -> Unit = {},
    val onZoom: (Boolean) -> Unit = {},
    val zoomPercent: () -> Int = { 100 },
    val onPageList: (() -> Unit)? = null,
    /** Read mode (a Note only): nothing can be changed by pen or finger; kept per note on this device. */
    val reading: Boolean = false,
    val onReading: (Boolean) -> Unit = {},
)

/**
 * "More" (the bar's last button): the note, whether it's saved, what you can do with it, and the modes. The Mac
 * part says what the Mac link is doing right now; pairing and the on/off switch live in Settings.
 */
@Composable
fun MoreSheet(state: NotesState, actions: MoreActions) {
    val c = Syncr.colors
    val snapshot = state.snapshot ?: return
    val status = saveStatus(state)
    val editable = !state.unsaved && !state.busy && !actions.reading
    SyncrSheet(actions.onDismiss) {
        Text(snapshot.title, style = Syncr.type.subheading, color = c.ink,
            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
        val created = ulidMillis(snapshot.noteId)?.let { " · Created ${displayDate(it)}" } ?: ""
        Text("${snapshot.notebook ?: "Inbox"}${if (snapshot.archived) " · Archived" else ""}$created",
            style = Syncr.type.caption, color = c.muted)

        Row(Modifier.padding(top = Space.l), verticalAlignment = Alignment.CenterVertically) {
            if (status.ring == SaveRing.ATTENTION) {
                SyncrIcon(SyncrIcons.Attention, c.attention, size = 20.dp)
                Spacer(Modifier.width(Space.s))
            }
            Text(status.headline, style = Syncr.type.body, color = c.ink)
        }
        state.error?.let { raw ->
            Column(Modifier.padding(top = Space.xs)) {
                val friendly = friendlyError(raw, saving = state.unsaved)
                Text(friendly, style = Syncr.type.caption, color = c.ink)
                if (friendly != raw) SelectionContainer { Text(raw, style = Syncr.type.caption, color = c.muted) }
            }
        }
        if (state.unsaved) MenuRow("Export a copy", "Save your unsaved changes to a file you choose", !state.busy,
            icon = SyncrIcons.Export, onClick = actions.onExport)

        Hairline(Modifier.padding(top = Space.l, bottom = Space.s))
        if (snapshot.kind == NoteKind.NOTE) {
            if (actions.reading) MenuRow("Edit this note", "Reading view is on: the pen only scrolls and zooms", icon = SyncrIcons.Pencil) { actions.onReading(false) }
            else MenuRow("Reading view", "The pen scrolls and zooms; nothing can be written, erased or typed", icon = SyncrIcons.Eye) { actions.onReading(true) }
            PagesSection(actions, editable)
        }
        MenuRow("Fit page to screen", "Double-tap empty paper does the same", icon = SyncrIcons.Fit, onClick = actions.onFit)
        MenuRow("Move to notebook…", enabled = editable, icon = SyncrIcons.Folder, onClick = actions.onMove)
        MenuRow("Rename…", enabled = editable, icon = SyncrIcons.Pencil, onClick = actions.onRename)
        if (snapshot.archived) MenuRow("Unarchive", "Show it in All and its notebook again", editable,
            icon = SyncrIcons.Unarchive) { actions.onArchive(false) }
        else MenuRow("Archive", "Keep it, out of the way, under Archived", editable, icon = SyncrIcons.Archive) { actions.onArchive(true) }
        // Leaving goes through the unsaved-changes guard first; the library then offers Undo.
        MenuRow("Delete", "Moves it to Recently deleted for 30 days", !state.busy, icon = SyncrIcons.Trash,
            tone = ActionTone.DESTRUCTIVE, onClick = actions.onDelete)

        Hairline(Modifier.padding(vertical = Space.s))
        MenuRow("Tablet mode", "Use the phone as a pen pad for the note open on your Mac, over Wi-Fi. Nothing is stored on the phone.",
            !state.busy, icon = SyncrIcons.Tablet, onClick = actions.onPointer)
        MenuRow("Back to library", null, !state.busy, icon = SyncrIcons.Folder, onClick = actions.onLibrary)

        SectionLabel("Mac")
        val context = LocalContext.current
        val link by remember(context) { LinkController.get(context).state }.collectAsState()
        Text(macSectionText(link), style = Syncr.type.caption, color = c.muted)

        SectionLabel("Gestures")
        Text("The pen writes; hold its side button to erase. One finger scrolls, pinch zooms, and a double-tap on empty " +
            "paper fits the page. Tap with two fingers to undo, three to redo. Long-press a stroke or use Lasso to select, " +
            "then drag to move or pull a corner to resize; tap empty paper to deselect.",
            style = Syncr.type.caption, color = c.muted)
    }
}

/** Pages: add one, see them all, how they are laid out and read, how to fit them, and the zoom. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PagesSection(actions: MoreActions, editable: Boolean) {
    val c = Syncr.colors
    var percent by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(actions.zoomPercent()) }
    MenuRow("Add page", "After this page, the same size", editable, icon = SyncrIcons.Plus, onClick = actions.onAddPage)
    actions.onPageList?.let { MenuRow("Pages", "Thumbnails: reorder, duplicate, rotate, resize, delete", icon = SyncrIcons.NotePage, onClick = it) }
    Text("Layout", style = Syncr.type.caption, color = c.muted, modifier = Modifier.padding(top = Space.s))
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Space.s)) {
        for ((label, mode) in listOf("Single" to PageArrangement.SINGLE, "Double" to PageArrangement.DOUBLE, "Cover" to PageArrangement.COVER))
            Chip(label, actions.view.mode == mode, description = "$label page layout") { actions.onView(actions.view.copy(mode = mode)) }
        Chip("Scroll", !actions.view.flip, description = "Scroll pages vertically") { actions.onView(actions.view.copy(flip = false)) }
        Chip("Flip", actions.view.flip, description = "Flip between pages") { actions.onView(actions.view.copy(flip = true)) }
    }
    Text("Fit", style = Syncr.type.caption, color = c.muted, modifier = Modifier.padding(top = Space.s))
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Space.s)) {
        Chip("Whole page", false, description = "Fit whole page") { actions.onFitPage(PageFit.WHOLE) }
        Chip("Page width", false, description = "Fit page width") { actions.onFitPage(PageFit.WIDTH) }
        Chip("Page height", false, description = "Fit page height") { actions.onFitPage(PageFit.HEIGHT) }
    }
    Row(Modifier.padding(top = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Text("Zoom", style = Syncr.type.body, color = c.ink, modifier = Modifier.weight(1f))
        IconAction(SyncrIcons.Minus, "Zoom out", { actions.onZoom(false); percent = actions.zoomPercent() })
        Text("$percent%", style = Syncr.type.label, color = c.ink, modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconAction(SyncrIcons.Plus, "Zoom in", { actions.onZoom(true); percent = actions.zoomPercent() })
    }
    Hairline(Modifier.padding(vertical = Space.s))
}
