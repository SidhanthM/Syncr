package dev.syncr.prototype.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.syncr.core.Brush
import dev.syncr.core.BrushFamily
import dev.syncr.core.BrushSize
import dev.syncr.core.InkColour
import dev.syncr.core.newId
import dev.syncr.prototype.notes.InkPreview
import dev.syncr.prototype.notes.NoteCanvasView
import dev.syncr.prototype.notes.NotesState
import dev.syncr.prototype.notes.TapCommand
import dev.syncr.prototype.notes.inkColour
import dev.syncr.prototype.notes.shouldAutoAddPage
import dev.syncr.prototype.ui.theme.Syncr

/** Tool choice and floating-chrome visibility, hoisted so it survives leaving and reopening notes. */
data class ToolState(
    val brush: Brush = Brush(BrushFamily.PEN, BrushSize.MEDIUM, InkColour.INK),
    val eraser: Boolean = false,
    val lasso: Boolean = false,
    val barOpen: Boolean = false,
    val optionsOpen: Boolean = false,
)

/**
 * The note: all pages stacked vertically. At rest the chrome is a quiet "Title · Notebook" header,
 * the page indicator and the tool dot; a banner only when something needs attention, and the
 * selection bar only while strokes are selected.
 */
@Composable
fun NoteScreen(vm: NotesViewModel, state: NotesState, dark: Boolean, tools: ToolState,
    onTools: (ToolState) -> Unit, onPaper: (NoteCanvasView) -> Unit, onUndo: () -> Unit, onRedo: () -> Unit, onFit: () -> Unit,
    onMore: () -> Unit, banner: @Composable () -> Unit) {
    val repo = vm.repository
    val snapshot = state.snapshot ?: return
    val c = Syncr.colors
    val status = saveStatus(state)
    val canUndo = snapshot.undoTarget != null && !state.unsaved && !state.busy
    val canRedo = snapshot.redoTarget != null && !state.unsaved && !state.busy
    val pen = !tools.eraser && !tools.lasso && tools.brush.family == BrushFamily.PEN
    val highlighter = !tools.eraser && !tools.lasso && tools.brush.family == BrushFamily.HIGHLIGHTER
    var view by remember { mutableStateOf<NoteCanvasView?>(null) }
    var page by remember(snapshot.noteId) { mutableStateOf(1 to snapshot.sheets.size) }
    var selected by remember(snapshot.noteId) { mutableIntStateOf(0) }
    val context = LocalContext.current
    val gestures by remember(context) { AppPreferencesStore.state(context) }.collectAsState()
    Box(Modifier.fillMaxSize().background(c.desk)) {
        AndroidView(factory = { context -> NoteCanvasView(context).also { view = it; onPaper(it) } }, modifier = Modifier.fillMaxSize(),
            update = { canvas ->
                canvas.brush = tools.brush; canvas.eraser = tools.eraser; canvas.lasso = tools.lasso
                canvas.sideButtonErases = gestures.sideButton == SideButtonAction.ERASE
                canvas.doubleTapFit = gestures.doubleTapFit; canvas.palmMs = gestures.palmDelay.millis
                canvas.onError = repo::message
                canvas.onStroke = { sheetId, channels, usedBrush ->
                    val preview = InkPreview(newId(), sheetId, channels, usedBrush)
                    if (repo.stageStroke(preview)) {
                        // Auto-add (owner decision 6): writing low on the last page queues exactly one new page.
                        repo.state.value.snapshot?.let { current ->
                            val index = current.sheets.indexOfFirst { it.id == sheetId }
                            if (shouldAutoAddPage(index, current.sheets.size, channels.y.max(), current.pageHeight, repo.addSheetPending))
                                repo.stageAddSheet()
                        }
                        vm.run { repo.retry() }; preview
                    } else null
                }
                canvas.onMarks = { edit -> repo.stageMarks(edit).also { staged -> if (staged) vm.run { repo.retry() } } }
                canvas.onAddPage = { if (repo.stageAddSheet()) vm.run { repo.retry() } }
                canvas.onTap = { command ->
                    when (command) {
                        TapCommand.UNDO -> if (gestures.twoFingerUndo) { canvas.deselect(); vm.run { repo.history(false) } }
                        TapCommand.REDO -> { canvas.deselect(); vm.run { repo.history(true) } }
                        TapCommand.FIT -> Unit // already handled by the view
                    }
                }
                canvas.onViewport = { current, total -> page = current to total }
                canvas.onSelection = { selected = it }
                canvas.onTouchStart = { if (tools.barOpen) onTools(tools.copy(barOpen = false, optionsOpen = false)) }
                canvas.update(state, dark)
            })

        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val header = listOf(snapshot.title, snapshot.notebook ?: "Inbox").joinToString(" · ")
            Text(header, style = Syncr.type.label, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { contentDescription = "${snapshot.title}, in ${snapshot.notebook ?: "Inbox"}" })
            banner()
            if (selected > 0) SelectionBar(selected,
                onColour = { colour -> view?.restyleSelection(colour) },
                onDelete = { view?.deleteSelection() },
                onDeselect = { view?.deselect() },
                modifier = Modifier.align(Alignment.CenterHorizontally))
        }

        // Choosing a tool folds the bar back into the bubble; tapping the active tool (or a long press) opens its options.
        fun choose(next: ToolState) = onTools(next.copy(barOpen = false, optionsOpen = false))
        fun options(family: BrushFamily) = onTools(tools.copy(eraser = false, lasso = false,
            brush = tools.brush.copy(family = family), optionsOpen = true))
        val pip = if (tools.eraser || tools.lasso) null else Color(inkColour(tools.brush, c.dark))
        FloatingChrome(open = tools.barOpen, optionsOpen = tools.optionsOpen && !tools.eraser && !tools.lasso,
            description = bubbleDescription(tools.brush, tools.eraser, tools.lasso, status), stateDescription = status.headline,
            onOpen = { onTools(tools.copy(barOpen = true, optionsOpen = false)) },
            ring = { BubbleRing(status.ring.kind(), pip) },
            glyph = { ToolGlyph(tools.brush, tools.eraser, tools.lasso) },
            bar = { drag, release -> NoteToolBar(tools.brush, tools.eraser, eraserEnabled = !state.unsaved,
                canUndo = canUndo, canRedo = canRedo,
                onPen = { if (pen) onTools(tools.copy(optionsOpen = !tools.optionsOpen))
                    else choose(tools.copy(eraser = false, lasso = false, brush = tools.brush.copy(family = BrushFamily.PEN))) },
                onHighlighter = { if (highlighter) onTools(tools.copy(optionsOpen = !tools.optionsOpen))
                    else choose(tools.copy(eraser = false, lasso = false, brush = tools.brush.copy(family = BrushFamily.HIGHLIGHTER))) },
                onEraser = { choose(tools.copy(eraser = true, lasso = false)) },
                onUndo = onUndo, onRedo = onRedo, onFit = onFit, onMore = onMore,
                lasso = tools.lasso, onLasso = { choose(tools.copy(lasso = true, eraser = false)) },
                onPenOptions = { options(BrushFamily.PEN) }, onHighlighterOptions = { options(BrushFamily.HIGHLIGHTER) },
                onDrag = drag, onRelease = release) },
            options = { ToolOptions(tools.brush,
                onSize = { onTools(tools.copy(brush = tools.brush.copy(size = it))) },
                onColour = { onTools(tools.copy(brush = tools.brush.copy(colour = it))) }) },
            indicator = {
                val (current, total) = page.first.coerceIn(1, maxOf(1, snapshot.sheets.size)) to snapshot.sheets.size
                Box(Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clickable(role = Role.Button, onClick = onMore)
                    .semantics { contentDescription = "Page $current of $total. Opens More" },
                    contentAlignment = Alignment.CenterStart) {
                    Text("$current / $total", style = Syncr.type.pageIndicator, color = c.muted, modifier = Modifier.padding(start = 4.dp))
                }
            })
    }
}

/** Design §8.5: a small bar with the five colours and Delete, plus an explicit deselect. */
@Composable
private fun SelectionBar(count: Int, onColour: (InkColour) -> Unit, onDelete: () -> Unit, onDeselect: () -> Unit,
    modifier: Modifier = Modifier) {
    val c = Syncr.colors
    Row(modifier.shadow(6.dp, RoundedCornerShape(16.dp)).background(c.tool, RoundedCornerShape(16.dp))
        .horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp)
        .semantics { contentDescription = "$count selected" }, verticalAlignment = Alignment.CenterVertically) {
        InkColour.entries.forEach { colour ->
            Box(Modifier.size(44.dp).clickable(role = Role.Button) { onColour(colour) }
                .semantics { contentDescription = "Recolour selection ${colour.label()}" }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(22.dp)) {
                    drawCircle(Color(inkColour(Brush(BrushFamily.PEN, BrushSize.MEDIUM, colour), !c.dark)), 9.dp.toPx())
                }
            }
        }
        Box(Modifier.padding(horizontal = 4.dp).size(1.dp, 24.dp).background(c.onTool.copy(alpha = 0.25f)))
        Box(Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onDelete)
            .semantics { contentDescription = "Delete selection" }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text("Delete", style = Syncr.type.label, color = c.onTool)
        }
        Box(Modifier.size(44.dp).clickable(role = Role.Button, onClick = onDeselect)
            .semantics { contentDescription = "Deselect" }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(14.dp)) {
                val w = 1.8f.dp.toPx()
                drawLine(c.onTool, Offset.Zero, Offset(size.width, size.height), w, StrokeCap.Round)
                drawLine(c.onTool, Offset(size.width, 0f), Offset(0f, size.height), w, StrokeCap.Round)
            }
        }
    }
}
