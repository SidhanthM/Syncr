package dev.syncr.prototype.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.syncr.core.Brush
import dev.syncr.core.BrushFamily
import dev.syncr.core.BrushSize
import dev.syncr.core.InkColour
import dev.syncr.core.NoteSummary
import dev.syncr.core.NotebookSummary
import dev.syncr.prototype.notes.LibraryFilter
import dev.syncr.prototype.notes.NotesState
import dev.syncr.prototype.tablet.TrackpadControls
import dev.syncr.prototype.tablet.TrackpadTuning
import dev.syncr.prototype.ui.theme.Space
import dev.syncr.prototype.ui.theme.Syncr
import dev.syncr.prototype.ui.theme.SyncrTheme

/* Previews of the main pieces, each in light and dark. Sample content mirrors the design's mocks. */

@Preview(name = "Light", showBackground = true, widthDp = 360)
@Preview(name = "Dark", showBackground = true, widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
private annotation class ThemePreviews

@Composable
private fun PreviewSurface(content: @Composable () -> Unit) {
    SyncrTheme(dark = isPreviewDark()) {
        Box(Modifier.background(Syncr.colors.desk).padding(Space.l)) { content() }
    }
}

private const val NOW = 1_794_407_520_000L
private val sampleNotes = listOf(
    NoteSummary("01M75GBNR0AAAAAAAAAAAAAAAA", "Lagrangians", 1u, "Physics", NOW - 86_400_000, NOW - 600_000),
    NoteSummary("01M75GBNR0BBBBBBBBBBBBBBBB", "2026-11-10 14.32", 1u, null, NOW - 7_200_000, NOW - 7_200_000),
    NoteSummary("01M75GBNR0CCCCCCCCCCCCCCCC", "Groceries, and a very long title that has to ellipsize", 1u, "Journal",
        NOW - 9 * 86_400_000L, NOW - 9 * 86_400_000L),
)
private val sampleState = NotesState(notes = sampleNotes,
    notebooks = listOf(NotebookSummary("Physics", 1u), NotebookSummary("Journal", 1u)))
private val noActions = LibraryActions({}, {}, {}, {}, {}, {}, { _, _ -> }, {}, { _, _ -> }, { _, _ -> }, { _, _ -> },
    {}, {}, {}, {}, {})

@ThemePreviews
@Composable
private fun LibraryPreview() = SyncrTheme(dark = isPreviewDark()) {
    Box(Modifier.size(360.dp, 640.dp)) { LibraryScreen(sampleState, LibraryFilter.All, ready = true, actions = noActions, banner = {}) }
}

@ThemePreviews
@Composable
private fun EmptyLibraryPreview() = SyncrTheme(dark = isPreviewDark()) {
    Box(Modifier.size(360.dp, 480.dp)) { LibraryScreen(NotesState(), LibraryFilter.All, ready = true, actions = noActions, banner = {}) }
}

@ThemePreviews
@Composable
private fun EmptyArchivedPreview() = SyncrTheme(dark = isPreviewDark()) {
    Box(Modifier.size(360.dp, 480.dp)) { LibraryScreen(sampleState, LibraryFilter.Archived, ready = true, actions = noActions, banner = {}) }
}

@ThemePreviews
@Composable
private fun NoteCardPreview() = PreviewSurface {
    Box(Modifier.width(160.dp)) {
        NoteCard(sampleNotes[2], null, showNotebook = true, now = NOW, enabled = true, {}, {}, {}, {}, {})
    }
}

@ThemePreviews
@Composable
private fun BannerPreview() = PreviewSurface {
    val pending = NotesState(error = "StorageFull: disk", recoveryExported = false,
        creation = dev.syncr.core.CreateNoteRequest("a", "b", "c", "t", null))
    StatusBanner(pending, {}, {}, {}, {})
}

@ThemePreviews
@Composable
private fun RingsPreview() = PreviewSurface {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.l)) {
        RingKind.entries.forEach { kind ->
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(BubbleDisc).background(Syncr.colors.surface2, androidx.compose.foundation.shape.CircleShape))
                BubbleRing(kind, if (kind == RingKind.GREY) Syncr.colors.accent else null)
                PenGlyph()
            }
        }
    }
}

@ThemePreviews
@Composable
private fun ToolBarPreview() = SyncrTheme(dark = isPreviewDark()) {
    Box(Modifier.size(360.dp, 260.dp).background(Syncr.colors.paper)) {
        MorphingBubble(expanded = true, center = Offset(60f, 400f), description = "", stateDescription = "", onOpen = {},
            ring = {}, glyph = { PenGlyph() }, optionsOpen = true,
            bar = { NoteToolBar(Brush(BrushFamily.PEN, BrushSize.MEDIUM, InkColour.INK), eraser = false, typing = false,
                canUndo = true, canRedo = false, penHead = Head.FOUNTAIN, onPen = {}, onHighlighter = {}, onEraser = {}, onUndo = {}, onRedo = {},
                onMore = {}, lasso = false, onLasso = {}, onTyping = {}, onPenOptions = {}, onHighlighterOptions = {},
                onDrag = {}, onRelease = {}) },
            options = { ToolOptions(Brush(BrushFamily.PEN, BrushSize.MEDIUM, InkColour.INK), {}, {}) })
    }
}

@ThemePreviews
@Composable
private fun SelectionBarPreview() = PreviewSurface { SelectionBar(3, {}, {}, {}) }

@ThemePreviews
@Composable
private fun GuardDialogPreview() = PreviewSurface {
    Column { GuardDialog(NotesState(error = "StorageFull: disk"), {}, {}, {}, {}, {}) }
}

@ThemePreviews
@Composable
private fun TrackpadControlsPreview() = PreviewSurface {
    Column { TrackpadControls(TrackpadTuning()) {} }
}

@ThemePreviews
@Composable
private fun IconsPreview() = PreviewSurface {
    val icons = listOf(SyncrIcons.Pen, SyncrIcons.Highlighter, SyncrIcons.Eraser, SyncrIcons.Lasso, SyncrIcons.Bin,
        SyncrIcons.Undo, SyncrIcons.Redo, SyncrIcons.Fit, SyncrIcons.More, SyncrIcons.Settings, SyncrIcons.Tablet,
        SyncrIcons.Folder, SyncrIcons.Archive, SyncrIcons.Unarchive, SyncrIcons.Restore, SyncrIcons.Plus,
        SyncrIcons.Close, SyncrIcons.Check, SyncrIcons.Attention, SyncrIcons.Export, SyncrIcons.Laptop, SyncrIcons.Grip)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        icons.forEach { SyncrIcon(it, Syncr.colors.ink) }
    }
}

@Composable
private fun isPreviewDark() = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
