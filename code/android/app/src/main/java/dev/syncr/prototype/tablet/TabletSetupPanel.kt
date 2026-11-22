package dev.syncr.prototype.tablet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import dev.syncr.prototype.ui.theme.Space
import dev.syncr.prototype.ui.theme.Syncr
import kotlin.math.roundToInt

/** Everything the bubble, caption and setup sheet show; owned by the activity. */
data class TabletUiState(
    /** The connected Mac's name; null when not connected. */
    val host: String? = null,
    /** The active area in pad (window) pixels; the bubble docks to its right. */
    val area: PadRect? = null,
    val trailMs: Long = TabletSettings.TRAIL_DEFAULT_MS,
    /** Something the user must fix before the pad can write (shown amber); null when there is none. */
    val problem: String? = null,
    /** Whether the Mac link is switched on, and one line about it. */
    val streamEnabled: Boolean = false,
    val streamSummary: String = "",
    /** The pad writes inside a pen frame on the Mac's page (Local) rather than over the whole view. */
    val streamLocal: Boolean = true,
    /** The pad reads the Mac's view (scroll, zoom) instead of writing into it. */
    val streamRead: Boolean = false,
    val trackpad: Boolean = false,
    val orientation: TabletOrientation = TabletOrientation.LANDSCAPE,
    val tuning: TrackpadTuning = TrackpadTuning(),
) {
    val link: LinkState get() = LinkStatus.state(host != null, problem)
    val caption: String get() = LinkStatus.padCaption(host, problem)
}

class TabletSetupActions(
    val close: () -> Unit,
    val setTrail: (Long) -> Unit,
    val setLocal: (Boolean) -> Unit,
    val setTrackpad: (Boolean) -> Unit,
    val setOrientation: (TabletOrientation) -> Unit,
    val setTuning: (TrackpadTuning) -> Unit,
    val leave: () -> Unit,
    val enableMacLink: () -> Unit,
    val macLinkSettings: () -> Unit,
)

/** Tablet settings, reached from the bubble. The same sheet and controls as the app's Settings. */
@Composable
fun TabletSetupPanel(state: TabletUiState, actions: TabletSetupActions) {
    val c = Syncr.colors
    SyncrSheet(actions.close, title = "Tablet mode") {
        SettingsChoices("Input", listOf(false, true), state.trackpad,
            label = { if (it) "Trackpad" else "Pen" },
            detail = { if (it) "One finger moves the Mac cursor; tap clicks. Two fingers scroll or tap to right-click. Double tap and hold to drag. No S Pen needed."
                else "Write into the Mac's open note with the S Pen." },
            onSelect = actions.setTrackpad)
        SettingsChoices("Orientation", TabletOrientation.entries, state.orientation,
            label = { when (it) { TabletOrientation.LANDSCAPE -> "Landscape"; TabletOrientation.PORTRAIT -> "Portrait"; TabletOrientation.AUTO -> "Auto" } },
            detail = { when (it) { TabletOrientation.LANDSCAPE -> "Horizontal pad"; TabletOrientation.PORTRAIT -> "Vertical pad"; TabletOrientation.AUTO -> "Follow the device orientation" } },
            onSelect = actions.setOrientation)

        SectionLabel("Connection")
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.problem != null) {
                SyncrIcon(SyncrIcons.Attention, c.attention, size = 20.dp)
                Spacer(Modifier.width(Space.s))
            }
            Text(state.problem ?: if (state.host != null) "Connected to ${state.host}" else "Not connected",
                style = Syncr.type.title, color = c.ink)
        }
        if (state.streamSummary != state.problem) Text(state.streamSummary, style = Syncr.type.caption, color = c.muted)
        if (!state.streamEnabled) MenuRow("Turn on Mac link", "Then pair the Mac in Mac link settings.", onClick = actions.enableMacLink)
        MenuRow("Mac link settings", "Pair, reconnect or forget the Mac.", onClick = actions.macLinkSettings)

        Hairline(Modifier.padding(top = Space.l))
        if (state.trackpad) TrackpadControls(state.tuning, actions.setTuning)
        if (!state.trackpad) SettingsChoices("View", listOf(true, false), state.streamLocal,
            label = { if (it) "Local" else "Whole view" },
            detail = { if (it) "A frame on the Mac's page. Two fingers move it, a pinch resizes it." else "The pad covers the Mac's whole view." },
            onSelect = actions.setLocal)
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
