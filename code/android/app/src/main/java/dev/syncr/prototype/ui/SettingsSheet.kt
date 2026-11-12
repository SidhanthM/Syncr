package dev.syncr.prototype.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.syncr.prototype.notes.InkPreferencesStore
import dev.syncr.prototype.notes.PenLook
import dev.syncr.prototype.tablet.TabletSettings
import dev.syncr.prototype.ui.theme.Space
import dev.syncr.prototype.ui.theme.Syncr
import kotlin.math.roundToInt

enum class Appearance { SYSTEM, LIGHT, DARK;
    fun isDark(systemDark: Boolean) = when (this) { SYSTEM -> systemDark; LIGHT -> false; DARK -> true }
    companion object { fun fromStored(value: String?) = entries.firstOrNull { it.name == value } ?: SYSTEM }
}

/** Preferences change only local presentation and input; they never rewrite a note or its ink. */
@Composable
fun SettingsSheet(appearance: Appearance, location: String, onAppearance: (Appearance) -> Unit, onDismiss: () -> Unit) {
    val c = Syncr.colors
    val context = LocalContext.current
    val ink by remember(context) { InkPreferencesStore.state(context) }.collectAsState()
    val app by remember(context) { AppPreferencesStore.state(context) }.collectAsState()
    val tablet = remember(context) { TabletSettings(context) }
    var trail by remember { mutableFloatStateOf(tablet.trailMs.toFloat()) }
    fun app(change: (AppPreferences) -> AppPreferences) = AppPreferencesStore.update(context, change)
    fun ink(change: (dev.syncr.prototype.notes.InkPreferences) -> dev.syncr.prototype.notes.InkPreferences) =
        InkPreferencesStore.update(context, change)

    SyncrSheet(onDismiss, title = "Settings") {
        SectionLabel("Appearance")
        SettingsChoices(null, Appearance.entries, appearance, label = {
            when (it) { Appearance.SYSTEM -> "Match the system"; Appearance.LIGHT -> "Light"; Appearance.DARK -> "Dark" }
        }, onSelect = onAppearance)
        Text("Tablet mode always uses its black pen pad.", style = Syncr.type.caption, color = c.muted)

        Hairline(Modifier.padding(top = Space.l))
        SectionLabel("Ink")
        Text("How strokes look on this phone. Your saved ink doesn't change.", style = Syncr.type.caption, color = c.muted)
        SettingsChoices("Pen look", PenLook.entries, ink.penLook, label = {
            when (it) { PenLook.FOUNTAIN -> "Fountain"; PenLook.BALLPOINT -> "Ballpoint"; PenLook.FINELINER -> "Fineliner" }
        }) { look -> ink { it.copy(penLook = look) } }
        SettingsSlider("Pressure", ink.pressure, "Even", "Expressive") { v -> ink { it.copy(pressure = v) } }
        SettingsSlider("Smoothing", ink.smoothing, "Raw", "Smooth") { v -> ink { it.copy(smoothing = v) } }
        SettingsSwitch("Tapered ends", "Strokes thin out where they start and finish.", ink.taper) { on -> ink { it.copy(taper = on) } }
        SettingsSwitch("Predict pen motion", "Draws a short guess ahead of the pen to cut lag. The guess is never saved.", ink.predictPenMotion) { on -> ink { it.copy(predictPenMotion = on) } }

        Hairline(Modifier.padding(top = Space.l))
        SectionLabel("Pen and gestures")
        SettingsChoices("Side button", listOf(SideButtonAction.ERASE, SideButtonAction.OFF), app.sideButton, label = {
            when (it) { SideButtonAction.ERASE -> "Hold to erase"; SideButtonAction.OFF -> "Off" }
        }) { choice -> app { it.copy(sideButton = choice) } }
        SettingsSwitch("Two-finger tap to undo", null, app.twoFingerUndo) { on -> app { it.copy(twoFingerUndo = on) } }
        SettingsSwitch("Double-tap to fit", "Double-tap empty paper to fit the page.", app.doubleTapFit) { on -> app { it.copy(doubleTapFit = on) } }
        SettingsChoices("Palm rejection", PalmDelay.entries, app.palmDelay, label = {
            when (it) { PalmDelay.SHORT -> "Short"; PalmDelay.NORMAL -> "Normal"; PalmDelay.LONG -> "Long" }
        }, detail = { "Ignores fingers for ${it.millis} ms after the pen lifts" }) { delay -> app { it.copy(palmDelay = delay) } }

        Hairline(Modifier.padding(top = Space.l))
        SectionLabel("Tool bubble")
        SettingsSwitch("Left-handed", "The bubble starts in the bottom-left corner.", app.leftHanded) { on -> app { it.copy(leftHanded = on) } }
        MenuRow("Reset bubble position", if (app.bubbleX.isNaN()) "It's in its usual corner" else "Move it back to its usual corner",
            enabled = !app.bubbleX.isNaN()) { AppPreferencesStore.resetBubble(context) }

        Hairline(Modifier.padding(top = Space.l))
        SectionLabel("Tablet mode")
        SettingsSlider("Trail length", (trail - TabletSettings.TRAIL_MIN_MS) / (TabletSettings.TRAIL_MAX_MS - TabletSettings.TRAIL_MIN_MS),
            "Short", "Long", valueText = "${trail.roundToInt()} ms",
            onFinished = { tablet.trailMs = trail.roundToInt().toLong() }) { v ->
            trail = TabletSettings.TRAIL_MIN_MS + v * (TabletSettings.TRAIL_MAX_MS - TabletSettings.TRAIL_MIN_MS)
        }

        Hairline(Modifier.padding(top = Space.l))
        MacLinkSection()

        Hairline(Modifier.padding(top = Space.l))
        SectionLabel("About")
        val version = remember(context) {
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown"
        }
        Text("Syncr $version", style = Syncr.type.title, color = c.ink)
        Text("Notes are saved on this phone, in:", style = Syncr.type.caption, color = c.muted, modifier = Modifier.padding(top = Space.s))
        SelectionContainer { Text(location, style = Syncr.type.caption, color = c.muted) }
    }
}
