package dev.syncr.prototype.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import dev.syncr.prototype.ui.theme.Level
import dev.syncr.prototype.ui.theme.raised
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.syncr.prototype.editor.SlashCommand
import dev.syncr.prototype.ui.theme.Motion
import dev.syncr.prototype.ui.theme.Space
import dev.syncr.prototype.ui.theme.Syncr
import dev.syncr.prototype.ui.theme.TouchTarget

/** One row of the panel: an icon glyph, a title, and at the right the name as typed (`/h1`) or a note's detail. */
internal data class PanelRow(val icon: String, val title: String, val detail: String, val group: String = "")

/** The slash menu's rows for [commands], with the group of each. */
internal fun slashRows(commands: List<SlashCommand>) = commands.map { PanelRow(it.icon, it.title, "/${it.name}", it.group) }

internal const val PANEL_ROWS = 5
private val ROW_HEIGHT = 44.dp

/**
 * The slash menu and `[[` completion (§3.2): full width above the toolbar, drawn at screen scale with the app's own
 * tokens, five rows high and scrolling, the selected row kept in view. A group label heads each group when [grouped].
 */
@Composable
internal fun EditorPanel(open: Boolean, rows: List<PanelRow>, selected: Int, grouped: Boolean, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Syncr.colors
    val reduced = Syncr.reducedMotion
    val pick by rememberUpdatedState(onPick)
    AnimatedVisibility(open && rows.isNotEmpty(), modifier,
        enter = Motion.menuIn(reduced), exit = Motion.menuOut(reduced)) {
        val state = rememberLazyListState()
        // The list holds the labels too; find the row's place among them.
        val place = remember(rows, grouped) { rows.indices.map { i -> if (grouped) i + rows.take(i + 1).map { it.group }.distinct().size else i } }
        LaunchedEffect(selected, rows) { place.getOrNull(selected)?.let { state.animateScrollToItem((it - PANEL_ROWS + 1).coerceAtLeast(0).coerceAtMost(it)) } }
        val shape = RoundedCornerShape(10.dp)
        LazyColumn(Modifier.padding(horizontal = Space.s, vertical = Space.xs).fillMaxWidth()
            .heightIn(max = ROW_HEIGHT * PANEL_ROWS).raised(Level.THREE, shape).clip(shape).border(1.dp, c.dots, shape),
            state = state) {
            rows.forEachIndexed { i, row ->
                if (grouped && (i == 0 || rows[i - 1].group != row.group)) item(key = "g" + row.group) {
                    Text(row.group.uppercase(), style = Syncr.type.caption.copy(fontWeight = FontWeight.SemiBold), color = c.faint,
                        modifier = Modifier.fillMaxWidth().padding(start = Space.l, top = Space.s, bottom = Space.xs))
                }
                item(key = "r$i") {
                    Row(Modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT).background(if (i == selected) c.selectedFill else Color.Transparent)
                        .clickable(role = Role.Button) { pick(i) }.padding(horizontal = Space.l)
                        .semantics { contentDescription = row.title + ", " + row.detail; this.selected = i == selected },
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) {
                            Text(row.icon, style = Syncr.type.label, color = c.muted, maxLines = 1)
                        }
                        Text(row.title, style = Syncr.type.body, color = c.ink, maxLines = 1, modifier = Modifier.weight(1f))
                        Text(row.detail, style = Syncr.type.caption.copy(fontFamily = FontFamily.Monospace), color = c.faint, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** One button of the toolbar. [on] marks a format that holds at the caret. */
internal class ToolButton(val icon: ImageVector, val label: String, val enabled: Boolean = true, val on: Boolean = false, val art: PenArt? = null,
    val onClick: () -> Unit)

/** The editing toolbar above the keyboard: one row that scrolls, its last button (Hide keyboard) always in reach (§5.3). */
@Composable
internal fun EditorToolbar(buttons: List<ToolButton>, modifier: Modifier = Modifier) {
    val c = Syncr.colors
    @Composable fun button(b: ToolButton) {
        Box(Modifier.size(TouchTarget).padding(3.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (b.on) c.selectedFill else Color.Transparent)
            .clickable(enabled = b.enabled, role = Role.Button, onClick = b.onClick)
            .semantics { contentDescription = b.label; selected = b.on }, contentAlignment = Alignment.Center) {
            if (b.art != null) PenAction(b.art, if (b.enabled) c.ink else c.faint, size = 26.dp)
            else SyncrIcon(b.icon, if (b.enabled) c.ink else c.faint, size = 22.dp)
        }
    }
    Row(modifier.fillMaxWidth().raised(Level.TWO, RectangleShape).border(1.dp, c.dots).padding(horizontal = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) { buttons.dropLast(1).forEach { button(it) } }
        buttons.lastOrNull()?.let { button(it) }
    }
}
