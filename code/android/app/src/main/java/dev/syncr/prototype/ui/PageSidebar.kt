package dev.syncr.prototype.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import dev.syncr.core.PageView
import dev.syncr.prototype.notes.NewNoteChoice
import dev.syncr.prototype.notes.NewNoteSizes
import dev.syncr.prototype.notes.NotesState
import dev.syncr.prototype.notes.PageOrder
import dev.syncr.prototype.notes.PagePaper
import dev.syncr.prototype.notes.PageTurn
import dev.syncr.prototype.notes.SheetMapping
import dev.syncr.prototype.notes.CanvasRect
import dev.syncr.prototype.notes.buildThumbnail
import dev.syncr.prototype.notes.drawThumbnail
import dev.syncr.prototype.ui.theme.Level
import dev.syncr.prototype.ui.theme.Radius
import dev.syncr.prototype.ui.theme.raised
import androidx.compose.foundation.gestures.scrollBy
import dev.syncr.prototype.ui.theme.Space
import dev.syncr.prototype.ui.theme.Syncr
import dev.syncr.prototype.ui.theme.TouchTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What the sidebar asks of the note; each is a page command on the repository's worker. */
class PageSidebarActions(
    val onClose: () -> Unit,
    val onGoTo: (String) -> Unit,
    val onAdd: (after: String) -> Unit,
    val onDuplicate: (List<String>) -> Unit,
    val onRotate: (List<String>, PageTurn) -> Unit,
    val onSize: (sheetId: String, width: Float, height: Float) -> Unit,
    val onDelete: (List<String>) -> Unit,
    val onMove: (List<String>, after: String?) -> Unit,
)

private val SIDEBAR_WIDTH = 224.dp
private val THUMB_WIDTH = 150.dp
private val THUMB_GAP = 10.dp
private val EDGE_ZONE = 56.dp

/**
 * The page sidebar (xnotes' side panel): 224 dp wide, 150 dp thumbnails at each page's own shape, 10 dp apart, opened scrolled
 * to the current page. Tap goes to a page. A page's menu has Add page, Duplicate, Rotate…, Page size… and Delete. Long-press
 * selects a page and, still held, drags it (with the other selected pages) to a new place, a line showing where it will drop
 * and the list scrolling within 56 dp of an edge. With pages selected a bar offers Select all and Delete.
 */
@Composable
fun PageSidebar(state: NotesState, current: String?, dark: Boolean, actions: PageSidebarActions) {
    val c = Syncr.colors
    val snapshot = state.snapshot ?: return
    val pages = snapshot.pages.filter { !it.tombstone }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<PageDialog?>(null) }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = pages.indexOfFirst { it.sheetId == current }.coerceAtLeast(0))
    val density = LocalDensity.current
    // Drag: where the held pointer is in the list, and where the page would drop (an index in [pages], 0..size).
    var dragging by remember { mutableStateOf(false) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    var dropAt by remember { mutableStateOf<Int?>(null) }
    var lastLongPress by remember { mutableStateOf(0L) }
    val currentPages by rememberUpdatedState(pages)
    val currentSelected by rememberUpdatedState(selected)

    // Pages that were deleted or reordered away from a selection drop out of it.
    LaunchedEffect(pages.map { it.sheetId }) { selected = selected.filter { id -> pages.any { it.sheetId == id } }.toSet() }

    fun dropIndexFor(y: Float): Int {
        val items = list.layoutInfo.visibleItemsInfo
        val hit = items.firstOrNull { y < it.offset + it.size / 2f } ?: return currentPages.size
        return hit.index
    }

    // Edge auto-scroll while dragging: up to 22 dp per frame within 56 dp of an edge.
    LaunchedEffect(dragging) {
        while (dragging) {
            val zone = with(density) { EDGE_ZONE.toPx() }; val top = list.layoutInfo.viewportStartOffset.toFloat(); val bottom = list.layoutInfo.viewportEndOffset.toFloat()
            val step = with(density) { 22.dp.toPx() }
            val dy = when {
                pointerY < top + zone -> -step * ((top + zone - pointerY) / zone).coerceIn(0f, 1f)
                pointerY > bottom - zone -> step * ((pointerY - (bottom - zone)) / zone).coerceIn(0f, 1f)
                else -> 0f
            }
            if (dy != 0f) { list.scrollBy(dy); dropAt = dropIndexFor(pointerY) }
            delay(16)
        }
    }

    Box(Modifier.fillMaxSize()) {
        // The scrim: a tap outside closes.
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.32f))
            .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { actions.onClose() }
            .semantics { contentDescription = "Close pages" })
        Column(Modifier.align(Alignment.CenterStart).width(SIDEBAR_WIDTH).fillMaxHeight()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))
            .raised(Level.TWO, Radius.card).clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { }) {
            if (selected.isEmpty()) Row(Modifier.fillMaxWidth().height(52.dp).padding(start = Space.l, end = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                Text("Pages", style = Syncr.type.subheading, color = c.ink, modifier = Modifier.weight(1f))
                Text("${pages.size}", style = Syncr.type.caption, color = c.muted)
                IconAction(SyncrIcons.Close, "Close pages", actions.onClose)
            } else Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                IconAction(SyncrIcons.Close, "Clear selection", { selected = emptySet() })
                Text("${selected.size}", style = Syncr.type.label, color = c.ink, modifier = Modifier.weight(1f))
                TextAction("Select all", { selected = pages.map { it.sheetId }.toSet() })
                IconAction(SyncrIcons.Trash, "Delete selected pages", { actions.onDelete(pages.filter { it.sheetId in selected }.map { it.sheetId }); selected = emptySet() }, tint = c.destructive)
            }
            Hairline()
            LazyColumn(Modifier.fillMaxWidth().weight(1f)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        if (System.currentTimeMillis() - lastLongPress < 600) return@detectTapGestures
                        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { offset.y >= it.offset && offset.y < it.offset + it.size } ?: return@detectTapGestures
                        val page = currentPages.getOrNull(item.index) ?: return@detectTapGestures
                        if (currentSelected.isNotEmpty()) selected = if (page.sheetId in currentSelected) currentSelected - page.sheetId else currentSelected + page.sheetId
                        else { actions.onGoTo(page.sheetId); actions.onClose() }
                    }
                }
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            val item = list.layoutInfo.visibleItemsInfo.firstOrNull { offset.y >= it.offset && offset.y < it.offset + it.size }
                            val page = item?.let { currentPages.getOrNull(it.index) } ?: return@detectDragGesturesAfterLongPress
                            lastLongPress = System.currentTimeMillis()
                            if (page.sheetId !in currentSelected) selected = currentSelected + page.sheetId
                            dragging = true; pointerY = offset.y; dropAt = dropIndexFor(offset.y)
                        },
                        onDrag = { change, _ -> change.consume(); pointerY = change.position.y; dropAt = dropIndexFor(pointerY) },
                        onDragEnd = {
                            val target = dropAt
                            dragging = false; dropAt = null
                            if (target != null) {
                                val ids = currentSelected
                                // The page the picked pages follow: the nearest unpicked page above the drop line.
                                val after = currentPages.take(target).lastOrNull { it.sheetId !in ids }?.sheetId
                                val inOrder = currentPages.filter { it.sheetId in ids }.map { it.sheetId }
                                val order = currentPages.map { it.sheetId }
                                if (inOrder.isNotEmpty() && PageOrder.reorder(order, ids, after) != order) actions.onMove(inOrder, after)
                            }
                        },
                        onDragCancel = { dragging = false; dropAt = null },
                    )
                }, state = list, userScrollEnabled = !dragging, // a held page is dragged, not scrolled past
                contentPadding = androidx.compose.foundation.layout.PaddingValues(Space.s),
                verticalArrangement = Arrangement.spacedBy(THUMB_GAP), horizontalAlignment = Alignment.CenterHorizontally) {
                itemsIndexed(pages, key = { _, p -> p.sheetId }) { index, page ->
                    PageCell(page, index + 1, state, dark, isCurrent = page.sheetId == current, isSelected = page.sheetId in selected,
                        dropLineAbove = dragging && dropAt == index, dropLineBelow = dragging && dropAt == pages.size && index == pages.lastIndex,
                        menuOpen = menuFor == page.sheetId, onMenu = { menuFor = if (it) page.sheetId else null }) { choice ->
                        menuFor = null
                        when (choice) {
                            PageChoice.ADD -> actions.onAdd(page.sheetId)
                            PageChoice.DUPLICATE -> actions.onDuplicate(listOf(page.sheetId))
                            PageChoice.ROTATE -> dialog = PageDialog.Rotate(page.sheetId)
                            PageChoice.SIZE -> dialog = PageDialog.Size(page)
                            PageChoice.DELETE -> actions.onDelete(listOf(page.sheetId))
                        }
                    }
                }
            }
        }
    }
    when (val d = dialog) {
        null -> Unit
        is PageDialog.Rotate -> SyncrDialog({ dialog = null }, title = "Rotate page", confirm = { TextAction("Cancel", { dialog = null }) }) {
            Column {
                MenuRow("90° clockwise", icon = null) { dialog = null; actions.onRotate(listOf(d.sheetId), PageTurn.CLOCKWISE) }
                MenuRow("90° anticlockwise", icon = null) { dialog = null; actions.onRotate(listOf(d.sheetId), PageTurn.COUNTER_CLOCKWISE) }
                MenuRow("180°", icon = null) { dialog = null; actions.onRotate(listOf(d.sheetId), PageTurn.HALF) }
                DialogDetail("Ink and shapes turn with the page; text boxes stay upright.")
            }
        }
        is PageDialog.Size -> PageSizeDialog(d.page, onDismiss = { dialog = null }) { w, h -> dialog = null; actions.onSize(d.page.sheetId, w, h) }
    }
}

private sealed interface PageDialog {
    data class Rotate(val sheetId: String) : PageDialog
    data class Size(val page: PageView) : PageDialog
}

private enum class PageChoice { ADD, DUPLICATE, ROTATE, SIZE, DELETE }

/** One page: its thumbnail with the number, a ring when it is the current page, a tick when picked, and the menu button. */
@Composable
private fun PageCell(page: PageView, number: Int, state: NotesState, dark: Boolean, isCurrent: Boolean, isSelected: Boolean,
    dropLineAbove: Boolean, dropLineBelow: Boolean, menuOpen: Boolean, onMenu: (Boolean) -> Unit, onChoice: (PageChoice) -> Unit) {
    val c = Syncr.colors
    val image = rememberPageThumb(page, state, dark)
    Column(Modifier.width(THUMB_WIDTH).semantics {
        contentDescription = "Page $number${if (isCurrent) ", current" else ""}${if (isSelected) ", selected" else ""}"
        this.selected = isSelected
    }.drawWithContent {
        drawContent()
        val line = androidx.compose.ui.graphics.Color(0xffd39999)
        if (dropLineAbove) drawLine(line, Offset(0f, -5.dp.toPx()), Offset(size.width, -5.dp.toPx()), 3.dp.toPx())
        if (dropLineBelow) drawLine(line, Offset(0f, size.height + 5.dp.toPx()), Offset(size.width, size.height + 5.dp.toPx()), 3.dp.toPx())
    }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().aspectRatio((page.width / page.height).coerceIn(0.2f, 5f)).clip(Radius.s).background(c.paper)
            .border(if (isCurrent || isSelected) 2.dp else 1.dp, if (isSelected) c.accent else if (isCurrent) c.ink else c.dots, Radius.s)) {
            image?.let { Image(it, null, Modifier.fillMaxSize()) }
            if (isSelected) Box(Modifier.align(Alignment.TopStart).padding(6.dp).size(20.dp).background(c.accent, CircleShape), contentAlignment = Alignment.Center) {
                SyncrIcon(SyncrIcons.Check, c.surface1, size = 14.dp)
            }
            Box(Modifier.align(Alignment.TopEnd)) {
                Box(Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button) { onMenu(true) }.semantics { contentDescription = "Page $number options" },
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.size(22.dp).background(c.surface1.copy(alpha = 0.85f), CircleShape), contentAlignment = Alignment.Center) {
                        SyncrIcon(SyncrIcons.More, c.muted, size = 16.dp)
                    }
                }
                SyncrMenu(menuOpen, { onMenu(false) }) {
                    MenuItem("Add page", { onChoice(PageChoice.ADD) }, SyncrIcons.Plus)
                    MenuItem("Duplicate", { onChoice(PageChoice.DUPLICATE) }, SyncrIcons.NotePage)
                    MenuItem("Rotate…", { onChoice(PageChoice.ROTATE) }, SyncrIcons.Fit)
                    MenuItem("Page size…", { onChoice(PageChoice.SIZE) }, SyncrIcons.Settings)
                    MenuItem("Delete", { onChoice(PageChoice.DELETE) }, SyncrIcons.Trash, tone = ActionTone.DESTRUCTIVE)
                }
            }
        }
        Text("$number", style = Syncr.type.meta, color = if (isCurrent) c.ink else c.muted, modifier = Modifier.padding(top = 4.dp))
    }
}

/** A page drawn small: paper, template and ink, rebuilt only when its look or ink changes, off the main thread. */
@Composable
private fun rememberPageThumb(page: PageView, state: NotesState, dark: Boolean): androidx.compose.ui.graphics.ImageBitmap? {
    val snapshot = state.snapshot ?: return null
    val widthPx = with(LocalDensity.current) { THUMB_WIDTH.roundToPx() }
    val ink = snapshot.strokes.filter { it.sheetId == page.sheetId && !it.tombstone }
    val signature = listOf(page.sheetId, page.width, page.height, page.head.operationId, page.styleHead.operationId, page.resolvedStyle, dark, widthPx,
        ink.map { it.head.operationId }, snapshot.pageStyle?.head?.operationId)
    return produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, signature) {
        val samples = state.samples
        // Ink follows the paper: a custom paper colour decides light or dark ink, else the theme.
        val inkDark = page.resolvedStyle.paper?.let { (0.299f * it.r.toInt() + 0.587f * it.g.toInt() + 0.114f * it.b.toInt()) / 255f < 0.5f } ?: dark
        value = withContext(Dispatchers.Default) { renderPage(page, buildThumbnail(snapshot, samples::get, onlySheet = page.sheetId), dark, inkDark, widthPx).asImageBitmap() }
    }.value
}

internal fun renderPage(page: PageView, ink: dev.syncr.prototype.notes.Thumbnail, dark: Boolean, inkDark: Boolean, widthPx: Int): Bitmap {
    val heightPx = (widthPx * page.height / page.width).toInt().coerceAtLeast(1)
    val bitmap = createBitmap(widthPx, heightPx)
    val canvas = android.graphics.Canvas(bitmap)
    val scale = widthPx / page.width
    PagePaper().draw(canvas, SheetMapping(scale, 0f, 0f), CanvasRect(0f, 0f, page.width, page.height), page.resolvedStyle, dark, widthPx, heightPx, 0f, edges = false)
    // The ink renderer expects a canvas that already maps page units to pixels (as the library cards draw it).
    canvas.scale(scale, scale)
    drawThumbnail(canvas, ink, inkDark, scale)
    return bitmap
}

/** "Page size…": the size and orientation chooser for one page; it changes the size only, never scaling or moving what is on the page. */
@Composable
private fun PageSizeDialog(page: PageView, onDismiss: () -> Unit, onApply: (Float, Float) -> Unit) {
    var choice by remember(page.sheetId) { mutableStateOf(choiceFor(page)) }
    val problem = choice.problem()
    SyncrDialog(onDismiss, title = "Page size",
        confirm = { TextAction("Apply", { choice.layout()?.let { onApply(it.width, it.height) } }, enabled = problem == null) },
        dismiss = { TextAction("Cancel", onDismiss) }) {
        Column { PageSizeFields(choice, problem) { choice = it } }
    }
}
