package dev.syncr.prototype.ui

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.syncr.core.PageFormat
import dev.syncr.prototype.notes.InkPreferencesStore
import dev.syncr.prototype.notes.PenLook
import dev.syncr.prototype.tablet.MappingMode
import dev.syncr.prototype.tablet.ScreenSizeFields
import dev.syncr.prototype.tablet.TabletSettings
import dev.syncr.prototype.ui.theme.Syncr
import kotlin.math.roundToInt

enum class Appearance { SYSTEM, LIGHT, DARK;
    fun isDark(systemDark: Boolean) = when (this) { SYSTEM -> systemDark; LIGHT -> false; DARK -> true }
    companion object { fun fromStored(value: String?) = entries.firstOrNull { it.name == value } ?: SYSTEM }
}

/** Preferences change only local presentation and input; they never rewrite a note or its ink. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(appearance: Appearance, defaultFormat: PageFormat, location: String, onAppearance: (Appearance) -> Unit,
    onDefaultFormat: (PageFormat) -> Unit, onDismiss: () -> Unit) {
    val c = Syncr.colors
    val context = LocalContext.current
    val ink by remember(context) { InkPreferencesStore.state(context) }.collectAsState()
    val app by remember(context) { AppPreferencesStore.state(context) }.collectAsState()
    val tablet = remember(context) { TabletSettings(context) }
    var mapping by remember { mutableStateOf(tablet.mode) }
    var screen by remember { mutableStateOf(tablet.screen) }
    var trail by remember { mutableFloatStateOf(tablet.trailMs.toFloat()) }
    fun app(change: (AppPreferences) -> AppPreferences) = AppPreferencesStore.update(context, change)
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.chrome, contentColor = c.ink) {
        SyncDialogSystemBars(c.dark)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Settings", style = Syncr.type.heading, color = c.ink, modifier = Modifier.padding(bottom = 12.dp))
            SectionLabel("Appearance")
            Text("Tablet mode keeps its high-contrast pen pad.", style = Syncr.type.caption, color = c.muted)
            Column(Modifier.semantics { selectableGroup() }) {
                Appearance.entries.forEach { choice ->
                    SettingsOption(when (choice) { Appearance.SYSTEM -> "Follow system"; Appearance.LIGHT -> "Light"; Appearance.DARK -> "Dark" },
                        choice == appearance) { onAppearance(choice) }
                }
            }
            Hairline(Modifier.padding(vertical = 12.dp))
            SectionLabel("New notes")
            Text("Default page shape. Existing notes are unchanged.", style = Syncr.type.caption, color = c.muted)
            Column(Modifier.semantics { selectableGroup() }) {
                listOf(PageFormat.PHONE, PageFormat.SHEET16X10).forEach { format ->
                    SettingsOption(format.label(), format == defaultFormat) { onDefaultFormat(format) }
                }
            }

            Hairline(Modifier.padding(vertical = 12.dp))
            SectionLabel("Ink")
            Text("How strokes are drawn on this phone. Stored ink is unchanged.", style = Syncr.type.caption, color = c.muted)
            Label("Pen look", Modifier.padding(top = 12.dp))
            Column(Modifier.semantics { selectableGroup() }) {
                PenLook.entries.forEach { look ->
                    SettingsOption(when (look) { PenLook.FOUNTAIN -> "Fountain"; PenLook.BALLPOINT -> "Ballpoint"; PenLook.FINELINER -> "Fineliner" },
                        look == ink.penLook) { InkPreferencesStore.update(context) { it.copy(penLook = look) } }
                }
            }
            SettingsSlider("Pressure sensitivity", ink.pressure, "Even", "Expressive") { v ->
                InkPreferencesStore.update(context) { it.copy(pressure = v) }
            }
            SettingsSlider("Smoothing", ink.smoothing, "Raw", "Smooth") { v ->
                InkPreferencesStore.update(context) { it.copy(smoothing = v) }
            }
            SettingsSwitch("Tapered ends", "Strokes thin out where they start and finish.", ink.taper) { on ->
                InkPreferencesStore.update(context) { it.copy(taper = on) }
            }

            Hairline(Modifier.padding(vertical = 12.dp))
            SectionLabel("Pen & gestures")
            Label("Side button", Modifier.padding(top = 12.dp))
            Column(Modifier.semantics { selectableGroup() }) {
                SettingsOption("Hold to erase", app.sideButton == SideButtonAction.ERASE) { app { it.copy(sideButton = SideButtonAction.ERASE) } }
                SettingsOption("Off", app.sideButton == SideButtonAction.OFF) { app { it.copy(sideButton = SideButtonAction.OFF) } }
            }
            SettingsSwitch("Two-finger tap to undo", null, app.twoFingerUndo) { on -> app { it.copy(twoFingerUndo = on) } }
            SettingsSwitch("Double-tap to fit", "Double-tap empty paper to fit the page.", app.doubleTapFit) { on -> app { it.copy(doubleTapFit = on) } }
            Label("Palm rejection delay", Modifier.padding(top = 12.dp))
            Text("How long finger touches are ignored after the pen lifts.", style = Syncr.type.caption, color = c.muted)
            Column(Modifier.semantics { selectableGroup() }) {
                PalmDelay.entries.forEach { delay ->
                    SettingsOption("${when (delay) { PalmDelay.SHORT -> "Short"; PalmDelay.NORMAL -> "Normal"; PalmDelay.LONG -> "Long" }} · ${delay.millis} ms",
                        delay == app.palmDelay) { app { it.copy(palmDelay = delay) } }
                }
            }

            Hairline(Modifier.padding(vertical = 12.dp))
            SectionLabel("Tool bubble")
            SettingsSwitch("Left-handed", "The bubble starts in the bottom-left corner, the page number in the bottom-right.",
                app.leftHanded) { on -> app { it.copy(leftHanded = on) } }
            MenuRow("Reset bubble position", if (app.bubbleX.isNaN()) "In its default corner" else "Back to the default corner",
                enabled = !app.bubbleX.isNaN()) { AppPreferencesStore.resetBubble(context) }

            Hairline(Modifier.padding(vertical = 12.dp))
            SectionLabel("Tablet mode")
            Label("Mapping", Modifier.padding(top = 4.dp))
            Column(Modifier.semantics { selectableGroup() }) {
                SettingsOption("Actual size", mapping == MappingMode.ACTUAL_SIZE) { mapping = MappingMode.ACTUAL_SIZE; tablet.mode = mapping }
                SettingsOption("Whole screen", mapping == MappingMode.WHOLE_SCREEN) { mapping = MappingMode.WHOLE_SCREEN; tablet.mode = mapping }
            }
            Label("Mac screen size", Modifier.padding(top = 12.dp, bottom = 4.dp))
            ScreenSizeFields(screen) { next ->
                if (next != tablet.screen) { tablet.screen = next; tablet.calibrated = false } // A new size has not been checked.
                screen = next
            }
            SettingsSlider("Trail length", (trail - TabletSettings.TRAIL_MIN_MS) / (TabletSettings.TRAIL_MAX_MS - TabletSettings.TRAIL_MIN_MS),
                "Short", "Long", valueText = "${trail.roundToInt()} ms",
                onFinished = { tablet.trailMs = trail.roundToInt().toLong() }) { v ->
                trail = TabletSettings.TRAIL_MIN_MS + v * (TabletSettings.TRAIL_MAX_MS - TabletSettings.TRAIL_MIN_MS)
            }

            Hairline(Modifier.padding(vertical = 12.dp))
            SectionLabel("About")
            val version = remember(context) {
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown"
            }
            Text("Syncr $version", style = Syncr.type.title, color = c.ink)
            Text("Saved on this phone", style = Syncr.type.body, color = c.ink, modifier = Modifier.padding(top = 8.dp))
            SelectionContainer { Text(location, style = Syncr.type.caption, color = c.muted) }
            var licences by remember { mutableStateOf(false) }
            MenuRow("Licences", if (licences) "Hide" else "Doto typeface, SIL Open Font License 1.1") { licences = !licences }
            if (licences) {
                val text = remember(context) {
                    runCatching { context.assets.open("licenses/Doto-OFL.txt").bufferedReader().use { it.readText() } }
                        .getOrDefault("Doto is licensed under the SIL Open Font License 1.1.")
                }
                SelectionContainer { Text(text, style = Syncr.type.caption, color = c.muted) }
            }
        }
    }
}

@Composable
fun SyncDialogSystemBars(dark: Boolean) {
    val view = LocalView.current
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
}

@Composable
fun SettingsOption(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Syncr.colors
    Row(Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Syncr.type.body, color = c.ink, modifier = Modifier.weight(1f))
        RadioButton(selected = selected, onClick = null)
    }
}

/** A labelled on/off row; the whole row toggles, with a 48dp minimum height. */
@Composable
fun SettingsSwitch(label: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Syncr.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = Syncr.type.body, color = c.ink)
            if (detail != null) Text(detail, style = Syncr.type.caption, color = c.muted)
        }
        Switch(checked, onCheckedChange = null, colors = SwitchDefaults.colors(
            checkedThumbColor = c.chrome, checkedTrackColor = c.ink, uncheckedThumbColor = c.muted,
            uncheckedTrackColor = c.chrome, uncheckedBorderColor = c.muted))
    }
}

/** A 0..1 slider with words at each end. [onFinished] runs once the finger lifts. */
@Composable
fun SettingsSlider(label: String, value: Float, low: String, high: String, valueText: String? = null,
    onFinished: (() -> Unit)? = null, onChange: (Float) -> Unit) {
    val c = Syncr.colors
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = Syncr.type.body, color = c.ink, modifier = Modifier.weight(1f))
            if (valueText != null) Text(valueText, style = Syncr.type.caption, color = c.muted)
        }
        Slider(value.coerceIn(0f, 1f), onChange, onValueChangeFinished = onFinished,
            colors = SliderDefaults.colors(thumbColor = c.ink, activeTrackColor = c.ink, inactiveTrackColor = c.dots),
            modifier = Modifier.semantics { contentDescription = label; stateDescription = valueText ?: "${(value * 100).roundToInt()} percent" })
        Row {
            Text(low, style = Syncr.type.caption, color = c.muted, modifier = Modifier.weight(1f))
            Text(high, style = Syncr.type.caption, color = c.muted)
        }
    }
}
