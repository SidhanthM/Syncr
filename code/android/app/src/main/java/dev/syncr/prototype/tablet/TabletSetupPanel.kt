package dev.syncr.prototype.tablet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.syncr.prototype.ui.Hairline
import dev.syncr.prototype.ui.MenuRow
import dev.syncr.prototype.ui.SectionLabel
import dev.syncr.prototype.ui.SettingsSlider
import dev.syncr.prototype.ui.SyncDialogSystemBars
import dev.syncr.prototype.ui.theme.Syncr
import java.util.Locale
import kotlin.math.roundToInt

/** Everything the bubble, caption and setup sheet show; owned by the activity. */
data class TabletUiState(
    val mode: MappingMode,
    val screen: MacScreen,
    val calibrated: Boolean,
    val host: String?,
    val status: String,
    val needsPermission: Boolean,
    /** The actual-size window's physical size by the phone's reported DPI, in mm. */
    val padWidthMm: Double = 0.0,
    val padHeightMm: Double = 0.0,
    /** The active area in pad (window) pixels; the bubble docks to its right. */
    val area: PadRect? = null,
    val trailMs: Long = TabletSettings.TRAIL_DEFAULT_MS,
    /** Something the user must fix before the link can work (shown amber); null when there is none. */
    val problem: String? = null,
    /** A Mac was chosen before, so the bubble offers to reconnect to it. */
    val knownHost: Boolean = false,
) {
    val link: LinkState get() = LinkStatus.state(host, problem)
    val caption: String get() = LinkStatus.padCaption(host, problem)
}

class TabletSetupActions(
    val close: () -> Unit,
    val setMode: (MappingMode) -> Unit,
    val setScreen: (MacScreen) -> Unit,
    val setCalibrated: (Boolean) -> Unit,
    val setTrail: (Long) -> Unit,
    val bluetoothSettings: () -> Unit,
    val selectMac: () -> Unit,
    val requestPermission: () -> Unit,
    val leave: () -> Unit,
)

/** Tablet settings, reached from the bubble. Styled like the app's other sheets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabletSetupPanel(state: TabletUiState, actions: TabletSetupActions) {
    val c = Syncr.colors
    ModalBottomSheet(actions.close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.chrome, contentColor = c.ink) {
        SyncDialogSystemBars(c.dark)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Tablet mode", style = Syncr.type.heading, color = c.ink, modifier = Modifier.padding(bottom = 4.dp))
            Text("Look at the Mac, write on the phone. Nothing is stored.", style = Syncr.type.caption, color = c.muted)

            SectionLabel("Link")
            Column(Modifier.semantics { selectableGroup() }) {
                Choice("Pointer · Bluetooth", "The phone is a pen-shaped mouse for any Mac app.", selected = true, enabled = true) {}
                Choice("Stream · Wi-Fi", "Needs the Mac app, coming with Mac sync.", selected = false, enabled = false) {}
            }

            Hairline(Modifier.padding(vertical = 12.dp))
            SectionLabel("Mapping")
            Column(Modifier.semantics { selectableGroup() }) {
                Choice("Actual size", "1 cm on the phone moves the cursor 1 cm. Two fingers move the window.",
                    selected = state.mode == MappingMode.ACTUAL_SIZE, enabled = true) { actions.setMode(MappingMode.ACTUAL_SIZE) }
                Choice("Whole screen", "The pad covers the whole Mac screen, scaled evenly. Less precise.",
                    selected = state.mode == MappingMode.WHOLE_SCREEN, enabled = true) { actions.setMode(MappingMode.WHOLE_SCREEN) }
            }

            SectionLabel("Mac screen")
            ScreenSizeFields(state.screen, actions.setScreen)
            Text("A 13-inch MacBook Air is about 290 × 189 mm. Measure the lit area, not the bezel.",
                style = Syncr.type.caption, color = c.muted)
            if (state.mode == MappingMode.ACTUAL_SIZE) {
                Text("Pad window: ${mm(state.padWidthMm)} × ${mm(state.padHeightMm)} mm, by the phone's reported DPI.",
                    style = Syncr.type.caption, color = c.muted, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { actions.setCalibrated(!state.calibrated) },
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(state.calibrated, onCheckedChange = null)
                    Text("Checked with a ruler: a 5 cm line on the phone is 5 cm on the Mac.",
                        style = Syncr.type.caption, color = c.ink)
                }
            }

            var trail by remember(state.trailMs) { mutableFloatStateOf(state.trailMs.toFloat()) }
            val span = TabletSettings.TRAIL_MAX_MS - TabletSettings.TRAIL_MIN_MS
            SettingsSlider("Trail length", (trail - TabletSettings.TRAIL_MIN_MS) / span, "Short", "Long",
                valueText = "${trail.roundToInt()} ms", onFinished = { actions.setTrail(trail.roundToInt().toLong()) }) { v ->
                trail = TabletSettings.TRAIL_MIN_MS + v * span
            }

            Hairline(Modifier.padding(vertical = 12.dp))
            SectionLabel("Bluetooth")
            Text(state.problem ?: if (state.host != null) "Connected to ${state.host}." else "Not connected.",
                style = Syncr.type.title, color = if (state.problem != null) c.attention else c.ink)
            Text(state.status, style = Syncr.type.caption, color = c.muted)
            if (state.needsPermission) MenuRow("Allow Bluetooth access", null, onClick = actions.requestPermission)
            MenuRow("Select paired Mac", "Pair the phone and the Mac in both devices' Bluetooth settings first.", onClick = actions.selectMac)
            MenuRow("Bluetooth settings", null, onClick = actions.bluetoothSettings)

            Hairline(Modifier.padding(vertical = 12.dp))
            MenuRow("Leave Tablet mode", null, onClick = actions.leave)
        }
    }
}

@Composable
private fun Choice(title: String, detail: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    val c = Syncr.colors
    Row(
        Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = Syncr.type.body, color = if (enabled) c.ink else c.muted)
            Text(detail, style = Syncr.type.caption, color = c.muted)
        }
        RadioButton(selected, onClick = null, enabled = enabled)
    }
}

/** Width and height in mm; commits only plausible sizes. Shared with the app's Settings sheet. */
@Composable
fun ScreenSizeFields(screen: MacScreen, onChange: (MacScreen) -> Unit) {
    var w by remember(screen) { mutableStateOf(mm(screen.widthMm)) }
    var h by remember(screen) { mutableStateOf(mm(screen.heightMm)) }
    fun commit(nw: String, nh: String) {
        val wv = nw.toDoubleOrNull(); val hv = nh.toDoubleOrNull()
        if (wv != null && hv != null && wv in 50.0..2000.0 && hv in 50.0..2000.0) onChange(MacScreen(wv, hv))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(w, { w = it; commit(it, h) }, Modifier.weight(1f), singleLine = true,
            label = { Text("Width mm") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        OutlinedTextField(h, { h = it; commit(w, it) }, Modifier.weight(1f), singleLine = true,
            label = { Text("Height mm") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
    }
    if (screen != MacScreen.DEFAULT) {
        TextButton(onClick = { onChange(MacScreen.DEFAULT) }) { Text("Reset to MacBook Air 13\" (290 × 189)", color = Syncr.colors.ink) }
    }
}

private fun mm(v: Double): String =
    if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.ROOT, "%.1f", v)
