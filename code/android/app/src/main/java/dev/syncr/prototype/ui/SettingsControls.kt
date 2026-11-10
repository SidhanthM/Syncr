package dev.syncr.prototype.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.syncr.prototype.ui.theme.Radius
import dev.syncr.prototype.ui.theme.Space
import dev.syncr.prototype.ui.theme.Syncr
import dev.syncr.prototype.ui.theme.TouchTarget
import kotlin.math.roundToInt

/** A titled group of selectable rows, announced as one group. */
@Composable
fun <T> SettingsChoices(title: String?, options: List<T>, selected: T, label: (T) -> String, detail: (T) -> String? = { null },
    enabled: (T) -> Boolean = { true }, onSelect: (T) -> Unit) {
    if (title != null) Label(title, Modifier.padding(top = Space.m))
    Column(Modifier.selectableGroup()) {
        options.forEach { option -> SettingsOption(label(option), option == selected, detail(option), enabled(option)) { onSelect(option) } }
    }
}

/** One selectable row; the whole row selects and the choice is marked by a small ink check at the right. */
@Composable
fun SettingsOption(label: String, selected: Boolean, detail: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Syncr.colors
    Row(Modifier.fillMaxWidth().heightIn(min = TouchTarget).clip(Radius.s)
        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = Space.m)) {
            Text(label, style = Syncr.type.body, color = if (enabled) c.ink else c.faint)
            if (detail != null) Text(detail, style = Syncr.type.caption, color = if (enabled) c.muted else c.faint)
        }
        if (selected) SyncrIcon(SyncrIcons.Check, if (enabled) c.ink else c.faint, size = 20.dp)
        else androidx.compose.foundation.layout.Spacer(Modifier.size(20.dp))
    }
}

/** A labelled on/off row; the whole row toggles, with a 48dp minimum height. */
@Composable
fun SettingsSwitch(label: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Syncr.colors
    Row(Modifier.fillMaxWidth().heightIn(min = TouchTarget).clip(Radius.s).toggleable(checked, role = Role.Switch, onValueChange = onChange)
        .padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = Space.m)) {
            Text(label, style = Syncr.type.body, color = c.ink)
            if (detail != null) Text(detail, style = Syncr.type.caption, color = c.muted)
        }
        SyncrSwitch(checked)
    }
}

/** A 0..1 slider with words at each end. [onFinished] runs once the finger lifts. */
@Composable
fun SettingsSlider(label: String, value: Float, low: String, high: String, valueText: String? = null,
    onFinished: (() -> Unit)? = null, onChange: (Float) -> Unit) {
    val c = Syncr.colors
    Column(Modifier.fillMaxWidth().padding(top = Space.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = Syncr.type.body, color = c.ink, modifier = Modifier.weight(1f))
            if (valueText != null) Text(valueText, style = Syncr.type.caption, color = c.muted)
        }
        SyncrSlider(value, onChange, label, valueText ?: "${(value.coerceIn(0f, 1f) * 100).roundToInt()} percent", onFinished = onFinished)
        Row {
            Text(low, style = Syncr.type.caption, color = c.muted, modifier = Modifier.weight(1f))
            Text(high, style = Syncr.type.caption, color = c.muted)
        }
    }
}
