package dev.syncr.prototype.tablet

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.syncr.prototype.ui.MenuRow
import dev.syncr.prototype.ui.SectionLabel
import dev.syncr.prototype.ui.SettingsSlider
import dev.syncr.prototype.ui.theme.Syncr
import java.util.Locale
import kotlin.math.roundToInt

/** Shared by the Tablet sheet and app Settings. SettingsSlider draws Syncr's own rail and thumb. */
@Composable
fun TrackpadControls(value: TrackpadTuning, onChange: (TrackpadTuning) -> Unit) {
    var draft by remember(value) { mutableStateOf(value.normalized()) }
    val c = Syncr.colors
    SectionLabel("Trackpad response")
    SettingsSlider("Pointer speed", TrackpadTuning.POINTER_SPEED.fraction(draft.pointerSpeed), "Slow", "Fast",
        valueText = multiplier(draft.pointerSpeed), onFinished = { onChange(draft) }) { fraction ->
        draft = draft.copy(pointerSpeed = TrackpadTuning.POINTER_SPEED.at(fraction))
    }
    SettingsSlider("Pointer acceleration", TrackpadTuning.POINTER_ACCELERATION.fraction(draft.pointerAcceleration), "Off", "Strong",
        valueText = percent(draft.pointerAcceleration), onFinished = { onChange(draft) }) { fraction ->
        draft = draft.copy(pointerAcceleration = TrackpadTuning.POINTER_ACCELERATION.at(fraction))
    }
    SettingsSlider("Scroll speed", TrackpadTuning.SCROLL_SPEED.fraction(draft.scrollSpeed), "Slow", "Fast",
        valueText = multiplier(draft.scrollSpeed), onFinished = { onChange(draft) }) { fraction ->
        draft = draft.copy(scrollSpeed = TrackpadTuning.SCROLL_SPEED.at(fraction))
    }
    SettingsSlider("Scroll acceleration", TrackpadTuning.SCROLL_ACCELERATION.fraction(draft.scrollAcceleration), "Off", "Strong",
        valueText = percent(draft.scrollAcceleration), onFinished = { onChange(draft) }) { fraction ->
        draft = draft.copy(scrollAcceleration = TrackpadTuning.SCROLL_ACCELERATION.at(fraction))
    }
    Text("Acceleration adds speed to faster swipes. Set it to 0% for a constant response.",
        style = Syncr.type.caption, color = c.muted)
    MenuRow("Reset Trackpad response", null, enabled = draft != TrackpadTuning()) {
        draft = TrackpadTuning(); onChange(draft)
    }
}

private fun multiplier(value: Float) = String.format(Locale.getDefault(), "%.1f×", value)
private fun percent(value: Float) = "${(value * 100).roundToInt()}%"
