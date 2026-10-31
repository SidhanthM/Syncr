package dev.syncr.prototype.tablet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.syncr.prototype.ui.DialogDetail
import dev.syncr.prototype.ui.SyncrDialog
import dev.syncr.prototype.ui.SyncrIcon
import dev.syncr.prototype.ui.SyncrIcons
import dev.syncr.prototype.ui.TextAction
import dev.syncr.prototype.ui.theme.Space
import dev.syncr.prototype.ui.theme.Syncr
import dev.syncr.prototype.ui.theme.TouchTarget

/** "Choose your Mac": the phone's paired devices, one row each. The activity disables pad input while it shows. */
@Composable
fun MacPickerDialog(names: List<String>, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val c = Syncr.colors
    SyncrDialog(onDismiss, title = "Choose your Mac", confirm = { TextAction("Cancel", onDismiss) }) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            DialogDetail("Paired with this phone", Modifier.padding(bottom = Space.s))
            names.forEachIndexed { index, name ->
                Row(Modifier.fillMaxWidth().heightIn(min = TouchTarget).clickable(role = Role.Button) { onPick(index) }
                    .padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
                    SyncrIcon(SyncrIcons.Laptop, c.muted, size = 20.dp)
                    Text(name, style = Syncr.type.body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = Space.m))
                }
            }
        }
    }
}
