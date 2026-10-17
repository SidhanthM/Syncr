package dev.syncr.prototype.ui

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What holding the pen's side button does while writing. */
enum class SideButtonAction { ERASE, OFF }

/** How long finger touches are ignored after the pen lifts (design §8.4, palm rule v1). */
enum class PalmDelay(val millis: Long) { SHORT(150), NORMAL(300), LONG(500) }

/**
 * Local pen, gesture and tool-bubble preferences. Like [dev.syncr.prototype.notes.InkPreferences] they
 * change only how this phone behaves, never a note. [bubbleX]/[bubbleY] are the dragged bubble's centre as
 * fractions of the safe viewport; NaN means "not moved yet", which puts it in the handed default corner.
 */
data class AppPreferences(
    val sideButton: SideButtonAction = SideButtonAction.ERASE,
    val twoFingerUndo: Boolean = true,
    val doubleTapFit: Boolean = true,
    val palmDelay: PalmDelay = PalmDelay.NORMAL,
    val leftHanded: Boolean = false,
    val bubbleX: Float = Float.NaN,
    val bubbleY: Float = Float.NaN,
)

/** The few reads and writes the store needs, so it is unit-tested without Android. */
interface PreferenceValues {
    fun string(key: String): String?
    fun boolean(key: String, default: Boolean): Boolean
    fun float(key: String, default: Float): Float
}

interface PreferenceWriter {
    fun putString(key: String, value: String)
    fun putBoolean(key: String, value: Boolean)
    fun putFloat(key: String, value: Float)
    fun remove(key: String)
}

/** Process-wide store over the shared "syncr-settings" preferences, observable by views and Compose. */
object AppPreferencesStore {
    private const val FILE = "syncr-settings"
    // Bubble keys predate this store (FloatingChrome); keep them so a dragged position survives the update.
    private const val BUBBLE_X = "bubble-x"
    private const val BUBBLE_Y = "bubble-y"
    private var flow: MutableStateFlow<AppPreferences>? = null

    fun state(context: Context): StateFlow<AppPreferences> = synchronized(this) {
        flow ?: MutableStateFlow(read(values(prefs(context)))).also { flow = it }
    }.asStateFlow()

    fun update(context: Context, change: (AppPreferences) -> AppPreferences) {
        val next = change(state(context).value)
        val editor = prefs(context).edit()
        write(next, object : PreferenceWriter {
            override fun putString(key: String, value: String) { editor.putString(key, value) }
            override fun putBoolean(key: String, value: Boolean) { editor.putBoolean(key, value) }
            override fun putFloat(key: String, value: Float) { editor.putFloat(key, value) }
            override fun remove(key: String) { editor.remove(key) }
        })
        editor.apply()
        synchronized(this) { flow!!.value = next }
    }

    /** Back to the handed default corner. */
    fun resetBubble(context: Context) = update(context) { it.copy(bubbleX = Float.NaN, bubbleY = Float.NaN) }

    fun read(values: PreferenceValues): AppPreferences {
        val d = AppPreferences()
        return AppPreferences(
            sideButton = SideButtonAction.entries.firstOrNull { it.name == values.string("pen-side-button") } ?: d.sideButton,
            twoFingerUndo = values.boolean("gesture-two-finger-undo", d.twoFingerUndo),
            doubleTapFit = values.boolean("gesture-double-tap-fit", d.doubleTapFit),
            palmDelay = PalmDelay.entries.firstOrNull { it.name == values.string("pen-palm-delay") } ?: d.palmDelay,
            leftHanded = values.boolean("bubble-left-handed", d.leftHanded),
            bubbleX = values.float(BUBBLE_X, Float.NaN).fraction(),
            bubbleY = values.float(BUBBLE_Y, Float.NaN).fraction(),
        )
    }

    fun write(p: AppPreferences, out: PreferenceWriter) {
        out.putString("pen-side-button", p.sideButton.name)
        out.putBoolean("gesture-two-finger-undo", p.twoFingerUndo)
        out.putBoolean("gesture-double-tap-fit", p.doubleTapFit)
        out.putString("pen-palm-delay", p.palmDelay.name)
        out.putBoolean("bubble-left-handed", p.leftHanded)
        val x = p.bubbleX.fraction(); val y = p.bubbleY.fraction()
        if (x.isNaN() || y.isNaN()) { out.remove(BUBBLE_X); out.remove(BUBBLE_Y) }
        else { out.putFloat(BUBBLE_X, x); out.putFloat(BUBBLE_Y, y) }
    }

    /** A stored fraction outside 0..1 (or not finite) is treated as "not moved". */
    private fun Float.fraction() = if (isFinite() && this in 0f..1f) this else Float.NaN

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun values(prefs: SharedPreferences) = object : PreferenceValues {
        override fun string(key: String) = prefs.getString(key, null)
        override fun boolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
        override fun float(key: String, default: Float) = prefs.getFloat(key, default)
    }
}
