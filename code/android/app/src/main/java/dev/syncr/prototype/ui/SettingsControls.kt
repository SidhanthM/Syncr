package dev.syncr.prototype.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.syncr.prototype.ui.theme.Space
import dev.syncr.prototype.ui.theme.Syncr
import dev.syncr.prototype.ui.theme.TouchTarget
import kotlin.math.roundToInt

/** A titled group of radio options, announced as one group. */
@Composable
fun <T> SettingsChoices(title: String?, options: List<T>, selected: T, label: (T) -> String, detail: (T) -> String? = { null },
    enabled: (T) -> Boolean = { true }, onSelect: (T) -> Unit) {
    if (title != null) Label(title, Modifier.padding(top = Space.m))
    Column(Modifier.selectableGroup()) {
        options.forEach { option -> SettingsOption(label(option), option == selected, detail(option), enabled(option)) { onSelect(option) } }
    }
}

/** One radio row; the whole row selects. */
@Composable
fun SettingsOption(label: String, selected: Boolean, detail: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Syncr.colors
    Row(Modifier.fillMaxWidth().heightIn(min = TouchTarget)
        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = Space.m)) {
            Text(label, style = Syncr.type.body, color = if (enabled) c.ink else c.faint)
            if (detail != null) Text(detail, style = Syncr.type.caption, color = if (enabled) c.muted else c.faint)
        }
        RadioButton(selected = selected, onClick = null, enabled = enabled,
            colors = RadioButtonDefaults.colors(selectedColor = c.ink, unselectedColor = c.muted, disabledSelectedColor = c.faint,
                disabledUnselectedColor = c.faint))
    }
}

/** A labelled on/off row; the whole row toggles, with a 48dp minimum height. */
@Composable
fun SettingsSwitch(label: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Syncr.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange)
        .padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = Space.m)) {
            Text(label, style = Syncr.type.body, color = c.ink)
            if (detail != null) Text(detail, style = Syncr.type.caption, color = c.muted)
        }
        Switch(checked, onCheckedChange = null, colors = SwitchDefaults.colors(
            checkedThumbColor = c.chrome, checkedTrackColor = c.ink, checkedBorderColor = c.ink,
            uncheckedThumbColor = c.muted, uncheckedTrackColor = c.tint, uncheckedBorderColor = c.lineStrong))
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
        Slider(value.coerceIn(0f, 1f), onChange, onValueChangeFinished = onFinished,
            colors = SliderDefaults.colors(thumbColor = c.ink, activeTrackColor = c.ink, inactiveTrackColor = c.lineStrong,
                activeTickColor = c.ink, inactiveTickColor = c.lineStrong),
            modifier = Modifier.semantics { contentDescription = label; stateDescription = valueText ?: "${(value * 100).roundToInt()} percent" })
        Row {
            Text(low, style = Syncr.type.caption, color = c.muted, modifier = Modifier.weight(1f))
            Text(high, style = Syncr.type.caption, color = c.muted)
        }
    }
}
