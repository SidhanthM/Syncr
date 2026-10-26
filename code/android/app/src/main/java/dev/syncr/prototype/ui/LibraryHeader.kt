package dev.syncr.prototype.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.syncr.core.NotebookSummary
import dev.syncr.prototype.notes.LibraryFilter
import dev.syncr.prototype.ui.theme.Space
import dev.syncr.prototype.ui.theme.Syncr

/** "Notes" on the left (design `.lib-title`); Tablet mode and Settings as quiet icon buttons on the right. */
@Composable
fun LibraryTopBar(tabletEnabled: Boolean, onPointer: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = Space.page, end = Space.s, top = Space.s),
        verticalAlignment = Alignment.CenterVertically) {
        Text("Notes", style = Syncr.type.heading, color = Syncr.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() })
        LinkDot(Modifier.padding(end = Space.xs))
        IconAction(SyncrIcons.Tablet, "Tablet mode: use the phone as a pen pad for your Mac", onPointer, enabled = tabletEnabled)
        IconAction(SyncrIcons.Settings, "Settings", onSettings)
    }
}

/**
 * Notebook chips (design `.chips`): All · Inbox · each notebook · +, then the two quieter places, Archived and
 * Recently deleted, after a short rule. Long-press a notebook to rename or delete it. The row scrolls sideways
 * edge to edge.
 */
@Composable
fun NotebookChips(notebooks: List<NotebookSummary>, filter: LibraryFilter, enabled: Boolean,
    onFilter: (LibraryFilter) -> Unit, onNewNotebook: () -> Unit, onRenameNotebook: (String) -> Unit,
    onDeleteNotebook: (String) -> Unit) {
    val c = Syncr.colors
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.page)
        .padding(top = Space.xs, bottom = Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
        Chip("All", filter == LibraryFilter.All) { onFilter(LibraryFilter.All) }
        Chip("Inbox", filter == LibraryFilter.Inbox) { onFilter(LibraryFilter.Inbox) }
        notebooks.forEach { notebook ->
            var menu by remember { mutableStateOf(false) }
            Box {
                Chip(notebook.name, filter == LibraryFilter.Notebook(notebook.name), onLongClickLabel = "Rename or delete",
                    onLongClick = { menu = true }) { onFilter(LibraryFilter.Notebook(notebook.name)) }
                DropdownMenu(menu, { menu = false }, containerColor = c.chrome) {
                    DropdownMenuItem({ Text("Rename…") }, enabled = enabled, onClick = { menu = false; onRenameNotebook(notebook.name) },
                        leadingIcon = { SyncrIcon(SyncrIcons.Pencil, if (enabled) c.ink else c.faint, size = 20.dp) })
                    DropdownMenuItem({ Text("Delete…", color = if (enabled) c.destructive else c.faint) }, enabled = enabled,
                        onClick = { menu = false; onDeleteNotebook(notebook.name) },
                        leadingIcon = { SyncrIcon(SyncrIcons.Trash, if (enabled) c.destructive else c.faint, size = 20.dp) })
                }
            }
        }
        Chip("", false, icon = SyncrIcons.Plus, description = "New notebook", enabled = enabled, onClick = onNewNotebook)
        Box(Modifier.width(1.dp).height(20.dp).background(c.lineStrong))
        Chip("Archived", filter == LibraryFilter.Archived, icon = SyncrIcons.Archive, quiet = true) { onFilter(LibraryFilter.Archived) }
        Chip("Recently deleted", filter == LibraryFilter.Deleted, icon = SyncrIcons.Trash, quiet = true) { onFilter(LibraryFilter.Deleted) }
    }
}
