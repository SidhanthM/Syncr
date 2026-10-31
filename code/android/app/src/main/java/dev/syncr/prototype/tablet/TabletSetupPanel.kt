package dev.syncr.prototype.tablet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.syncr.prototype.ui.ActionTone
import dev.syncr.prototype.ui.Hairline
import dev.syncr.prototype.ui.MenuRow
import dev.syncr.prototype.ui.SectionLabel
import dev.syncr.prototype.ui.SettingsChoices
import dev.syncr.prototype.ui.SettingsSlider
import dev.syncr.prototype.ui.SyncrIcon
import dev.syncr.prototype.ui.SyncrIcons
import dev.syncr.prototype.ui.SyncrSheet
import dev.syncr.prototype.ui.TextAction
import dev.syncr.prototype.ui.theme.Space
import dev.syncr.prototype.ui.theme.Syncr
import dev.syncr.prototype.ui.theme.TouchTarget
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
    /** Which link the pad writes over. */
    val linkChoice: TabletLink = TabletLink.POINTER,
    /** Stream: whether the Mac link is switched on, and one line about it. */
    val streamEnabled: Boolean = false,
    val streamSummary: String = "",
) {
    val link: LinkState get() = if (linkChoice == TabletLink.STREAM) LinkStatus.streamState(host != null, problem) else LinkStatus.state(host, problem)
    val caption: String get() = if (linkChoice == TabletLink.STREAM) LinkStatus.padCaptionStream(host, problem) else LinkStatus.padCaption(host, problem)
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
    val setLink: (TabletLink) -> Unit = {},
    val enableMacLink: () -> Unit = {},
    val macLinkSettings: () -> Unit = {},
)

/** Tablet settings, reached from the bubble. The same sheet and controls as the app's Settings. */
@Composable
fun TabletSetupPanel(state: TabletUiState, actions: TabletSetupActions) {
    val c = Syncr.colors
    SyncrSheet(actions.close, title = "Tablet mode") {
        val stream = state.linkChoice == TabletLink.STREAM
        Text(if (stream) "Look at the Mac, write on the phone. The stroke is saved in the Mac's note, not on the phone."
            else "Look at the Mac, write on the phone. Nothing is stored.", style = Syncr.type.caption, color = c.muted)

        // The link decides what Connection shows, so it comes first.
        SettingsChoices("Link", TabletLink.entries, state.linkChoice,
            label = { if (it == TabletLink.POINTER) "Pointer · Bluetooth" else "Stream · Wi-Fi" },
            detail = { if (it == TabletLink.POINTER) "The phone is a pen-shaped mouse for any Mac app." else "Write into the note open in the Syncr Mac app. The stroke is saved there." },
            onSelect = actions.setLink)

        SectionLabel("Connection")
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.problem != null) {
                SyncrIcon(SyncrIcons.Attention, c.attention, size = 20.dp)
                Spacer(Modifier.width(Space.s))
            }
            Text(state.problem ?: if (state.host != null) "Connected to ${state.host}" else "Not connected",
                style = Syncr.type.title, color = c.ink)
        }
        if (stream) {
            if (state.streamSummary != state.problem) Text(state.streamSummary, style = Syncr.type.caption, color = c.muted)
            if (!state.streamEnabled) MenuRow("Turn on Mac link", "Then pair the Mac in Mac link settings.", onClick = actions.enableMacLink)
            MenuRow("Mac link settings", "Pair, reconnect or forget the Mac.", onClick = actions.macLinkSettings)
        } else {
            Text(state.status, style = Syncr.type.caption, color = c.muted)
            if (state.needsPermission) MenuRow("Allow Bluetooth access", null, onClick = actions.requestPermission)
            MenuRow(if (state.knownHost && state.host == null) "Reconnect or choose a Mac" else "Choose your Mac",
                "Pair the phone and the Mac in both devices' Bluetooth settings first.", onClick = actions.selectMac)
            MenuRow("Bluetooth settings", null, onClick = actions.bluetoothSettings)
        }

        Hairline(Modifier.padding(top = Space.l))

        if (!stream) {
            SettingsChoices("Mapping", listOf(MappingMode.ACTUAL_SIZE, MappingMode.WHOLE_SCREEN), state.mode,
                label = { if (it == MappingMode.ACTUAL_SIZE) "Actual size" else "Whole screen" },
                detail = { if (it == MappingMode.ACTUAL_SIZE) "1 cm on the phone moves the cursor 1 cm. Two fingers move the window."
                    else "The pad covers the whole Mac screen, scaled evenly. Less precise." },
                onSelect = actions.setMode)

            SectionLabel("Mac screen")
            ScreenSizeFields(state.screen, actions.setScreen)
            Text("A 13-inch MacBook Air is about 290 × 189 mm. Measure the lit area, not the bezel.",
                style = Syncr.type.caption, color = c.muted, modifier = Modifier.padding(top = Space.xs))
            if (state.mode == MappingMode.ACTUAL_SIZE) {
                Text("Pad window: ${mm(state.padWidthMm)} × ${mm(state.padHeightMm)} mm, by the phone's reported DPI.",
                    style = Syncr.type.caption, color = c.muted, modifier = Modifier.padding(top = Space.s))
                Row(Modifier.fillMaxWidth().heightIn(min = TouchTarget)
                    .toggleable(state.calibrated, role = Role.Checkbox, onValueChange = actions.setCalibrated),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(state.calibrated, onCheckedChange = null,
                        colors = CheckboxDefaults.colors(checkedColor = c.ink, uncheckedColor = c.muted, checkmarkColor = c.chrome))
                    Text("Checked with a ruler: a 5 cm line on the phone is 5 cm on the Mac.",
                        style = Syncr.type.caption, color = c.ink, modifier = Modifier.padding(start = Space.m))
                }
            }

        }
        var trail by remember(state.trailMs) { mutableFloatStateOf(state.trailMs.toFloat()) }
        val span = TabletSettings.TRAIL_MAX_MS - TabletSettings.TRAIL_MIN_MS
        SettingsSlider("Trail length", (trail - TabletSettings.TRAIL_MIN_MS) / span, "Short", "Long",
            valueText = "${trail.roundToInt()} ms", onFinished = { actions.setTrail(trail.roundToInt().toLong()) }) { v ->
            trail = TabletSettings.TRAIL_MIN_MS + v * span
        }

        Hairline(Modifier.padding(vertical = Space.l))
        MenuRow("Leave Tablet mode", null, tone = ActionTone.DEFAULT, onClick = actions.leave)
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
    Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(w, { w = it; commit(it, h) }, Modifier.weight(1f), singleLine = true,
            label = { Text("Width (mm)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next))
        OutlinedTextField(h, { h = it; commit(w, it) }, Modifier.weight(1f), singleLine = true,
            label = { Text("Height (mm)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done))
    }
    if (screen != MacScreen.DEFAULT) TextAction("Reset to MacBook Air 13″ (290 × 189)", { onChange(MacScreen.DEFAULT) })
}

private fun mm(v: Double): String =
    if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.ROOT, "%.1f", v)
