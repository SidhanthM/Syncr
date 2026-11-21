package dev.syncr.prototype.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.syncr.prototype.companion.SubscriptionClient
import dev.syncr.prototype.link.android.LinkController
import dev.syncr.prototype.notes.InkPreferences
import dev.syncr.prototype.notes.InkPreferencesStore
import dev.syncr.prototype.tablet.TabletSettings
import dev.syncr.prototype.tablet.TrackpadControls
import dev.syncr.prototype.ui.theme.Motion
import dev.syncr.prototype.ui.theme.Radius
import dev.syncr.prototype.ui.theme.Space
import dev.syncr.prototype.ui.theme.Syncr
import dev.syncr.prototype.ui.theme.TouchTarget
import kotlin.math.roundToInt

enum class Appearance { SYSTEM, LIGHT, DARK;
    fun isDark(systemDark: Boolean) = when (this) { SYSTEM -> systemDark; LIGHT -> false; DARK -> true }
    companion object { fun fromStored(value: String?) = entries.firstOrNull { it.name == value } ?: SYSTEM }
}

/** The groups of Settings: one short list, and a page for each, inside the same sheet. */
internal enum class SettingsPage(val title: String) {
    APPEARANCE("Appearance"), INK("Ink"), PEN("Pen and gestures"), BUBBLE("Tool bubble"),
    COMPANION("Companion"), MAC("Mac link"), TABLET("Tablet mode"), ABOUT("About"),
}

/**
 * Settings: a short list of groups, each with its current value on the right. A group opens as a page inside the
 * sheet, sliding in from the right on [Motion.shape]; Back returns. Preferences change only local presentation and
 * input; they never rewrite a note or its ink.
 */
@Composable
fun SettingsSheet(appearance: Appearance, location: String, onAppearance: (Appearance) -> Unit, onDismiss: () -> Unit) {
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    val reduced = Syncr.reducedMotion
    SyncrSheet(onDismiss) {
        BackHandler(page != null) { page = null }
        AnimatedContent(page, transitionSpec = {
            val back = targetState == null
            ContentTransform(Motion.pageIn(reduced, back), Motion.pageOut(reduced, back), sizeTransform = SizeTransform(clip = false) { _, _ -> Motion.shape() })
        }, label = "settings page") { current ->
            Column(Modifier.fillMaxWidth()) {
                if (current == null) SettingsList(appearance) { page = it }
                else SettingsPageBody(current, appearance, location, onAppearance) { page = null }
            }
        }
    }
}

@Composable
private fun SettingsList(appearance: Appearance, open: (SettingsPage) -> Unit) {
    val c = Syncr.colors
    val context = LocalContext.current
    val ink by remember(context) { InkPreferencesStore.state(context) }.collectAsState()
    val app by remember(context) { AppPreferencesStore.state(context) }.collectAsState()
    val link by remember(context) { LinkController.get(context).state }.collectAsState()
    val tablet = remember(context) { TabletSettings(context) }
    val version = remember(context) { appVersion(context) }
    Text("Settings", style = Syncr.type.subheading, color = c.ink, modifier = Modifier.semantics { heading() }.padding(bottom = Space.s))
    SettingsEntry(SettingsPage.APPEARANCE, appearanceName(appearance), open)
    SettingsEntry(SettingsPage.INK, listOfNotNull(if (ink.taper) "Tapered" else null, if (ink.predictPenMotion) "Predicted" else null)
        .joinToString(", ").ifEmpty { "Plain" }, open)
    SettingsEntry(SettingsPage.PEN, if (app.sideButton == SideButtonAction.ERASE) "Side button erases" else "Side button off", open)
    SettingsEntry(SettingsPage.BUBBLE, if (app.leftHanded) "Left-handed" else "Right-handed", open)
    SettingsEntry(SettingsPage.COMPANION, remember(context) { SubscriptionClient.get(context).model?.name } ?: "Not set up", open)
    SettingsEntry(SettingsPage.MAC, linkSummary(link), open)
    SettingsEntry(SettingsPage.TABLET, "${tablet.trailMs} ms trail", open)
    SettingsEntry(SettingsPage.ABOUT, version, open)
}

/** One row of the list: the group's name, its value, and a chevron. */
@Composable
private fun SettingsEntry(page: SettingsPage, value: String, open: (SettingsPage) -> Unit) {
    val c = Syncr.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(Radius.s)
        .clickable(role = Role.Button) { open(page) }.padding(vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically) {
        Text(page.title, style = Syncr.type.body, color = c.ink, modifier = Modifier.weight(1f))
        Text(value, style = Syncr.type.caption, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Space.s).widthIn(max = 190.dp))
        SyncrIcon(SyncrIcons.Chevron, c.faint, size = 18.dp)
    }
}

@Composable
private fun SettingsPageBody(page: SettingsPage, appearance: Appearance, location: String, onAppearance: (Appearance) -> Unit, back: () -> Unit) {
    val c = Syncr.colors
    val context = LocalContext.current
    val ink by remember(context) { InkPreferencesStore.state(context) }.collectAsState()
    val app by remember(context) { AppPreferencesStore.state(context) }.collectAsState()
    val tablet = remember(context) { TabletSettings(context) }
    var trail by remember { mutableFloatStateOf(tablet.trailMs.toFloat()) }
    var local by remember { mutableStateOf(tablet.streamLocal) }
    var tuning by remember { mutableStateOf(tablet.trackpadTuning) }
    fun app(change: (AppPreferences) -> AppPreferences) = AppPreferencesStore.update(context, change)
    fun ink(change: (InkPreferences) -> InkPreferences) = InkPreferencesStore.update(context, change)

    Row(Modifier.fillMaxWidth().heightIn(min = TouchTarget), verticalAlignment = Alignment.CenterVertically) {
        IconAction(SyncrIcons.Back, "Back to Settings", back)
        Text(page.title, style = Syncr.type.subheading, color = c.ink, modifier = Modifier.semantics { heading() }.padding(start = Space.xs))
    }
    when (page) {
        SettingsPage.APPEARANCE -> {
            SettingsChoices(null, Appearance.entries, appearance, label = { appearanceName(it) }, onSelect = onAppearance)
            Text("Tablet mode always uses its black pen pad.", style = Syncr.type.caption, color = c.muted)
        }
        SettingsPage.INK -> {
            Text("How strokes look on this phone. Your saved ink doesn't change.", style = Syncr.type.caption, color = c.muted)
            SettingsSlider("Pressure", ink.pressure, "Even", "Expressive") { v -> ink { it.copy(pressure = v) } }
            SettingsSlider("Smoothing", ink.smoothing, "Raw", "Smooth") { v -> ink { it.copy(smoothing = v) } }
            SettingsSwitch("Tapered ends", null, ink.taper) { on -> ink { it.copy(taper = on) } }
            SettingsSwitch("Predict pen motion", "The guess ahead of the pen is never saved.", ink.predictPenMotion) { on -> ink { it.copy(predictPenMotion = on) } }
            SettingsSwitch("Direct ink (experimental)", "Draws the pen's stroke straight onto the screen. Falls back on its own where the phone can't.", ink.frontBufferInk) { on -> ink { it.copy(frontBufferInk = on) } }
        }
        SettingsPage.PEN -> {
            SettingsChoices("Side button", listOf(SideButtonAction.ERASE, SideButtonAction.OFF), app.sideButton, label = {
                when (it) { SideButtonAction.ERASE -> "Hold to erase"; SideButtonAction.OFF -> "Off" }
            }) { choice -> app { it.copy(sideButton = choice) } }
            SettingsSwitch("Two-finger tap to undo", null, app.twoFingerUndo) { on -> app { it.copy(twoFingerUndo = on) } }
            SettingsSwitch("Double-tap to fit", null, app.doubleTapFit) { on -> app { it.copy(doubleTapFit = on) } }
            SettingsChoices("Palm rejection", PalmDelay.entries, app.palmDelay, label = {
                when (it) { PalmDelay.SHORT -> "Short"; PalmDelay.NORMAL -> "Normal"; PalmDelay.LONG -> "Long" }
            }, detail = { "Ignores fingers for ${it.millis} ms after the pen lifts" }) { delay -> app { it.copy(palmDelay = delay) } }
        }
        SettingsPage.BUBBLE -> {
            SettingsSwitch("Left-handed", "The bubble starts in the bottom-left corner.", app.leftHanded) { on -> app { it.copy(leftHanded = on) } }
            MenuRow("Reset position", if (app.bubbleX.isNaN()) "It's in its usual corner" else null,
                enabled = !app.bubbleX.isNaN()) { AppPreferencesStore.resetBubble(context) }
        }
        SettingsPage.COMPANION -> CompanionAccount()
        SettingsPage.MAC -> MacLinkSection()
        SettingsPage.TABLET -> {
            TrackpadControls(tuning) { next ->
                tablet.trackpadTuning = next
                tuning = tablet.trackpadTuning
            }
            SectionLabel("Pen view")
            SettingsChoices("Starts as", listOf(true, false), local, label = { if (it) "Local" else "Whole view" },
                detail = { if (it) "A frame on the Mac's page that you move with two fingers." else "The pad covers the Mac's whole view." }) { next ->
                local = next; tablet.streamLocal = next
            }
            SettingsSlider("Trail length", (trail - TabletSettings.TRAIL_MIN_MS) / (TabletSettings.TRAIL_MAX_MS - TabletSettings.TRAIL_MIN_MS),
                "Short", "Long", valueText = "${trail.roundToInt()} ms",
                onFinished = { tablet.trailMs = trail.roundToInt().toLong() }) { v ->
                trail = TabletSettings.TRAIL_MIN_MS + v * (TabletSettings.TRAIL_MAX_MS - TabletSettings.TRAIL_MIN_MS)
            }
        }
        SettingsPage.ABOUT -> {
            Text("Syncr ${remember(context) { appVersion(context) }}", style = Syncr.type.title, color = c.ink, modifier = Modifier.padding(top = Space.s))
            Text("Notes are saved on this phone, in:", style = Syncr.type.caption, color = c.muted, modifier = Modifier.padding(top = Space.s))
            SelectionContainer { Text(location, style = Syncr.type.caption, color = c.muted) }
        }
    }
}

private fun appearanceName(a: Appearance) = when (a) { Appearance.SYSTEM -> "System"; Appearance.LIGHT -> "Light"; Appearance.DARK -> "Dark" }

private fun appVersion(context: android.content.Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown"
