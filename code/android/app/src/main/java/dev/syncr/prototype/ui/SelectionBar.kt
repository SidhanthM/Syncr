package dev.syncr.prototype.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.syncr.core.Brush
import dev.syncr.core.BrushFamily
import dev.syncr.core.BrushSize
import dev.syncr.core.InkColour
import dev.syncr.prototype.notes.inkColour
import dev.syncr.prototype.ui.theme.Space
import dev.syncr.prototype.ui.theme.Syncr
import dev.syncr.prototype.ui.theme.TouchTarget
import dev.syncr.prototype.ui.theme.floatingSurface

/**
 * The selection bar (design "Selection"): the five colours to recolour, the bin to delete, and an explicit
 * close to deselect, on the same floating surface as the tool bar.
 */
@Composable
fun SelectionBar(count: Int, onColour: (InkColour) -> Unit, onDelete: () -> Unit, onDeselect: () -> Unit,
    modifier: Modifier = Modifier) {
    val c = Syncr.colors
    Row(modifier.floatingSurface().horizontalScroll(rememberScrollState()).padding(horizontal = Space.xs)
        .semantics { paneTitle = if (count == 1) "1 stroke selected" else "$count strokes selected" },
        verticalAlignment = Alignment.CenterVertically) {
        InkColour.entries.forEach { colour ->
            Box(Modifier.size(TouchTarget).clickable(role = Role.Button) { onColour(colour) }
                .semantics { contentDescription = "Make selection ${colour.label().lowercase()}" }, contentAlignment = Alignment.Center) {
                ColourDot(Color(inkColour(Brush(BrushFamily.PEN, BrushSize.MEDIUM, colour), c.dark)), selected = false,
                    modifier = Modifier.size(26.dp))
            }
        }
        Box(Modifier.padding(horizontal = Space.xs).size(1.dp, 24.dp).background(c.lineStrong))
        IconAction(SyncrIcons.Bin, "Delete selection", onDelete)
        IconAction(SyncrIcons.Close, "Deselect", onDeselect)
    }
}
