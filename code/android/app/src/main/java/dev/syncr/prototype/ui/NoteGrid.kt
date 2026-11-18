package dev.syncr.prototype.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import dev.syncr.core.NoteSummary
import dev.syncr.prototype.notes.LibraryFilter
import dev.syncr.prototype.notes.NotesState
import dev.syncr.prototype.notes.Thumbnail
import dev.syncr.prototype.notes.drawThumbnail
import dev.syncr.prototype.ui.theme.Radius
import dev.syncr.prototype.ui.theme.Space
import dev.syncr.prototype.ui.theme.Syncr
import dev.syncr.prototype.ui.theme.cardSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NoteGridActions(
    val onNew: () -> Unit,
    val onOpen: (String) -> Unit,
    val onMove: (NoteSummary) -> Unit,
    val onRename: (NoteSummary) -> Unit,
    val onArchive: (NoteSummary) -> Unit,
    val onDelete: (NoteSummary) -> Unit,
)

/** The design's `.lib-grid`: "New note" first, then cards newest first; an empty state when there is nothing. */
@Composable
fun NoteGrid(state: NotesState, filter: LibraryFilter, now: Long, enabled: Boolean, ready: Boolean,
    contentPadding: PaddingValues, actions: NoteGridActions) {
    val shown = state.notes.filter { filter.accepts(it.notebook, it.archived) }
    val showNotebook = filter == LibraryFilter.All || filter == LibraryFilter.Archived
    LazyVerticalGrid(GridCells.Adaptive(CARD_MIN_WIDTH), Modifier.fillMaxSize(), contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(Space.m), verticalArrangement = Arrangement.spacedBy(Space.l)) {
        if (filter.allowsNewNotes) item(key = "new") { NewNoteCard(enabled, actions.onNew) }
        when {
            !ready -> items(3, key = { "placeholder-$it" }) { PlaceholderCard() }
            shown.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                val (icon, title, hint) = emptyCopy(filter, state.notes.isEmpty())
                EmptyState(icon, title, hint, art = if (state.notes.isEmpty() && filter == LibraryFilter.All) EmptyArtData.library else EmptyArtData.notebook)
            }
            else -> items(shown, key = { it.id }) { note ->
                NoteCard(note, state.thumbnails[note.id], showNotebook, now, enabled,
                    onOpen = { actions.onOpen(note.id) }, onMove = { actions.onMove(note) },
                    onRename = { actions.onRename(note) }, onArchive = { actions.onArchive(note) },
                    onDelete = { actions.onDelete(note) })
            }
        }
    }
}

/** Icon, line and hint for an empty place. */
internal fun emptyCopy(filter: LibraryFilter, libraryEmpty: Boolean): Triple<ImageVector, String, String?> = when {
    filter == LibraryFilter.Archived -> Triple(SyncrIcons.Archive, "Nothing archived",
        "Long-press a note and choose Archive to keep it out of the way without deleting it.")
    libraryEmpty && filter == LibraryFilter.All -> Triple(SyncrIcons.Pen, "No notes yet",
        "Tap New note to start writing. Notes stay on this phone.")
    filter == LibraryFilter.Inbox -> Triple(SyncrIcons.Folder, "Inbox is empty", "Notes without a notebook land here.")
    filter is LibraryFilter.Notebook -> Triple(SyncrIcons.Folder, "Nothing in ${quoted(filter.name)} yet",
        "New notes made here go into this notebook.")
    else -> Triple(SyncrIcons.Pen, "No notes here", "Archived notes are under Archived.")
}

/** The first cell (design `.lib-new`): a dashed outline, a plus and "New note". */
@Composable
private fun NewNoteCard(enabled: Boolean, onNew: () -> Unit) {
    val c = Syncr.colors
    val tint = if (enabled) c.muted else c.faint
    // Quieter than a card: no fill, a soft dashed edge and muted marks, so the notes are what the eye finds first.
    Column(Modifier.fillMaxWidth().aspectRatio(CARD_ASPECT).clip(Radius.card)
        .dashedOutline(c.lineStrong.copy(alpha = c.lineStrong.alpha * 0.5f))
        .combinedClickable(enabled = enabled, role = Role.Button, onClick = onNew)
        .semantics(mergeDescendants = true) { contentDescription = "New note" },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        SyncrIcon(SyncrIcons.Plus, tint, size = 24.dp)
        Text("New note", style = Syncr.type.caption, color = tint, modifier = Modifier.padding(top = Space.s))
    }
}

/** A note card: the top of the page, its title and when it was last edited. Long-press (or TalkBack actions) for more. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteCard(note: NoteSummary, thumbnail: Thumbnail?, showNotebook: Boolean, now: Long, enabled: Boolean,
    onOpen: () -> Unit, onMove: () -> Unit, onRename: () -> Unit, onArchive: () -> Unit, onDelete: () -> Unit) {
    val c = Syncr.colors
    var menu by remember { mutableStateOf(false) }
    val date = relativeDate(note.modifiedAtMs, now)
    val shown = rememberDisplayTitle(note.title)
    val notebook = note.notebook ?: "Inbox"
    val image = rememberThumbnail(thumbnail, c.dark)
    val archiveLabel = if (note.archived) "Unarchive" else "Archive"
    Box {
        Column(Modifier.clip(Radius.card)
            .combinedClickable(enabled = enabled, role = Role.Button, onLongClickLabel = "Note options",
                onLongClick = { menu = true }, onClick = onOpen)
            .semantics(mergeDescendants = true) {
                contentDescription = "$shown. Edited ${date.lowercase()}, in $notebook${if (note.archived) ", archived" else ""}"
                if (enabled) customActions = listOf(
                    CustomAccessibilityAction("Move to notebook") { onMove(); true },
                    CustomAccessibilityAction("Rename") { onRename(); true },
                    CustomAccessibilityAction(archiveLabel) { onArchive(); true },
                    CustomAccessibilityAction("Delete") { onDelete(); true },
                )
            }) {
            Box(Modifier.fillMaxWidth().aspectRatio(CARD_ASPECT).cardSurface(color = c.paper).clip(Radius.card)) {
                if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                else if (thumbnail == null || thumbnail.strokes.isEmpty()) Box(Modifier.fillMaxSize().stippledPaper())
            }
            Text(shown, style = Syncr.type.noteTitle, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Space.m, start = Space.xs, end = Space.xs))
            Text((if (showNotebook) "$date · $notebook" else date).uppercase(), style = Syncr.type.meta, color = c.muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = Space.xs, end = Space.xs, top = 2.dp, bottom = Space.xs))
        }
        SyncrMenu(menu, { menu = false }) {
            MenuItem("Move to notebook…", { menu = false; onMove() }, SyncrIcons.Folder, enabled)
            MenuItem("Rename…", { menu = false; onRename() }, SyncrIcons.Pencil, enabled)
            MenuItem(archiveLabel, { menu = false; onArchive() }, if (note.archived) SyncrIcons.Unarchive else SyncrIcons.Archive, enabled)
            MenuItem("Delete", { menu = false; onDelete() }, SyncrIcons.Trash, enabled, ActionTone.DESTRUCTIVE)
        }
    }
}

/** A quiet stand-in while the library loads for the first time. */
@Composable
private fun PlaceholderCard() {
    val c = Syncr.colors
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "Loading notes" }) {
        Box(Modifier.fillMaxWidth().aspectRatio(CARD_ASPECT).background(c.tint, Radius.card))
        Box(Modifier.padding(top = Space.s, start = Space.xs).width(96.dp).height(12.dp).background(c.tint, Radius.pill))
        Box(Modifier.padding(top = Space.s, start = Space.xs).width(56.dp).height(10.dp).background(c.tint, Radius.pill))
    }
}

/** The design's dashed "New note" edge (`1.5px dashed var(--line-strong)`). */
private fun Modifier.dashedOutline(colour: androidx.compose.ui.graphics.Color) = drawBehind {
    val w = 1.5.dp.toPx()
    drawRoundRect(colour, topLeft = Offset(w / 2, w / 2), size = size.copy(size.width - w, size.height - w),
        cornerRadius = CornerRadius(16.dp.toPx()),
        style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))))
}

private const val THUMB_W = 400
private const val THUMB_H = 500

/** Renders the decoded top-left canvas ink into a small bitmap off the main thread (no FFI here). */
@Composable
private fun rememberThumbnail(thumbnail: Thumbnail?, dark: Boolean): ImageBitmap? {
    return produceState<ImageBitmap?>(null, thumbnail, dark) {
        value = thumbnail?.takeIf { it.strokes.isNotEmpty() }?.let { t ->
            withContext(Dispatchers.Default) { renderThumbnail(t, dark).asImageBitmap() }
        }
    }.value
}

internal fun renderThumbnail(t: Thumbnail, dark: Boolean): Bitmap {
    val bitmap = createBitmap(THUMB_W, THUMB_H)
    val canvas = android.graphics.Canvas(bitmap)
    // Width-fit with a small margin, top-aligned: the top-left of the canvas.
    val margin = THUMB_W * 0.04f
    val scale = (THUMB_W - 2 * margin) / t.width
    canvas.translate(margin, margin)
    canvas.scale(scale, scale)
    // CanvasStrokeRenderer expects the Canvas already transformed; its matrix is for screen-space rendering quality.
    drawThumbnail(canvas, t, dark, scale)
    return bitmap
}
