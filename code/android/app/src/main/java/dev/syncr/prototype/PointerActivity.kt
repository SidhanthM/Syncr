package dev.syncr.prototype

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.syncr.prototype.link.CanvasRect
import dev.syncr.prototype.link.PadWish
import dev.syncr.prototype.link.android.LinkController
import dev.syncr.prototype.link.android.LinkUi
import dev.syncr.prototype.notes.brushWidth
import dev.syncr.prototype.notes.inkColour
import dev.syncr.prototype.tablet.LinkStatus
import dev.syncr.prototype.tablet.StreamAction
import dev.syncr.prototype.tablet.StreamPadStatus
import dev.syncr.prototype.tablet.TabletBubble
import dev.syncr.prototype.tablet.TabletBubbleActions
import dev.syncr.prototype.tablet.TabletMacLinkSettings
import dev.syncr.prototype.tablet.TabletPadView
import dev.syncr.prototype.tablet.TabletSettings
import dev.syncr.prototype.tablet.TabletSetupActions
import dev.syncr.prototype.tablet.TabletSetupPanel
import dev.syncr.prototype.tablet.TabletUiState
import dev.syncr.prototype.tablet.streamPadState
import dev.syncr.prototype.ui.linkSummary
import dev.syncr.prototype.ui.theme.SyncrTheme
import kotlinx.coroutines.launch

/**
 * Tablet mode (design §8.2, §8.7): the pad writes into the note open in the Syncr Mac app over the Wi-Fi link
 * (wifi-link-v0 §3.5); the stroke is saved there, never on the phone.
 * The class keeps its old name so existing entry points and the manifest entry keep working.
 */
class PointerActivity : ComponentActivity() {
    private lateinit var settings: TabletSettings
    private lateinit var pad: TabletPadView
    private var ui by mutableStateOf(TabletUiState())
    private var panelOpen by mutableStateOf(false)
    private var bubbleOpen by mutableStateOf(false)
    private lateinit var link: LinkController
    private var linkUi by mutableStateOf(LinkUi())
    private var macLinkOpen by mutableStateOf(false)
    private var padAspect = 0.0
    /** Local: the frame width of last time is asked for once per connection, when the Mac first says where the frame is. */
    private var frameRestored = false
    private val streamState get() = streamPadState(linkUi.enabled, linkUi.pairedMac != null,
        linkUi.status.phase, linkUi.status.macName ?: linkUi.pairedMac, linkUi.target)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = TabletSettings(this)
        link = LinkController.get(this)
        linkUi = link.state.value
        link.bind(this)
        window.setBackgroundDrawable(Color.BLACK.toDrawable())
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        ui = ui.copy(trailMs = settings.trailMs, streamLocal = settings.streamLocal)

        pad = TabletPadView(this, onMapperChanged = { m -> ui = ui.copy(area = m.activeArea) })
        pad.setTrailMs(settings.trailMs)
        pad.setStream(link.streamPen)
        pad.setLocal(settings.streamLocal)
        pad.setPictureLarge(settings.pictureLarge)
        pad.setInk(inkColour(link.penBrush, dark = true), brushWidth(link.penBrush))
        pad.setTarget(linkUi.target)
        pad.onPictureLarge = { settings.pictureLarge = it }
        pad.onFrameRequest = { frame -> link.requestFrame(frame); settings.frameWidth = frame.w.toFloat() }
        pad.onPadShape = { aspect -> if (aspect != padAspect) { padAspect = aspect; pushPadWish() } }
        refreshCaption()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { link.pictures.collect { pad.setPicture(it) } }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                link.state.collect { next ->
                    if (next.connected && !linkUi.connected) { pad.linkReset(); frameRestored = false }
                    linkUi = next
                    pad.setTarget(next.target)
                    restoreFrameWidth(next.target.frame)
                    refreshCaption()
                }
            }
        }

        setContent {
            SyncrTheme(dark = true) {
                Box(Modifier.fillMaxSize()) {
                    AndroidView(factory = { pad }, modifier = Modifier.fillMaxSize())
                    TabletBubble(ui, bubbleOpen, bubbleActions)
                    if (!panelOpen && !bubbleOpen && !macLinkOpen) {
                        StreamPadStatus(streamState, Modifier.align(Alignment.TopCenter).padding(top = 8.dp), ::streamAction)
                    }
                    if (panelOpen) TabletSetupPanel(ui, setupActions)
                    if (macLinkOpen) TabletMacLinkSettings { macLinkOpen = false; syncPadInput(); hideSystemBars() }
                }
            }
        }
    }

    private val setupActions = TabletSetupActions(
        close = { closePanel() },
        setTrail = { ms ->
            settings.trailMs = ms
            ui = ui.copy(trailMs = settings.trailMs)
            pad.setTrailMs(settings.trailMs)
        },
        setLocal = { local -> setLocal(local) },
        leave = { finish() },
        enableMacLink = { link.setEnabled(true) },
        macLinkSettings = { panelOpen = false; macLinkOpen = true; syncPadInput() },
    )

    private fun setLocal(local: Boolean) {
        if (local == settings.streamLocal) return
        settings.streamLocal = local
        ui = ui.copy(streamLocal = local)
        frameRestored = false
        pad.setLocal(local)
        pushPadWish()
    }

    /** What the pad asks of the Mac: pictures of what it covers, and its shape. Nothing before the shape is known. */
    private fun pushPadWish() {
        link.setPadWish(if (padAspect > 0) PadWish(settings.streamLocal, pics = true, aspect = padAspect) else null)
    }

    /** The Mac starts a new frame at half its view; ask once for the width used last time, around the same centre. */
    private fun restoreFrameWidth(frame: CanvasRect?) {
        if (frame == null || frameRestored || !settings.streamLocal) return
        frameRestored = true
        val w = settings.frameWidth.toDouble()
        if (w < 1 || kotlin.math.abs(w - frame.w) < 1) return
        val h = w * frame.h / frame.w
        link.requestFrame(CanvasRect(frame.x + (frame.w - w) / 2, frame.y + (frame.h - h) / 2, w, h))
    }

    private fun streamAction(action: StreamAction) {
        when (action) {
            StreamAction.ENABLE -> link.setEnabled(true)
            StreamAction.PAIR -> { macLinkOpen = true; syncPadInput() }
        }
    }

    /** Bubble taps are finger input on Compose, never pad input; opening it also drops a stroke in progress. */
    private val bubbleActions = TabletBubbleActions(
        open = { bubbleOpen = true; syncPadInput() },
        close = { bubbleOpen = false; syncPadInput() },
        toggleLocal = { setLocal(!settings.streamLocal) },
        settings = { bubbleOpen = false; openPanel() },
        leave = { finish() },
    )

    private fun syncPadInput() {
        pad.inputEnabled = !bubbleOpen && !panelOpen && !macLinkOpen // Disabling drops a stroke in progress first.
    }

    private fun openPanel() {
        panelOpen = true
        syncPadInput()
    }

    private fun closePanel() {
        panelOpen = false
        syncPadInput()
        hideSystemBars()
    }

    /** The Mac link's state and whether the Mac has a note this pad can write into. */
    private fun refreshCaption() {
        val mac = linkUi.status.macName.takeIf { linkUi.connected }
        val problem = streamState.problem
        ui = ui.copy(host = mac, problem = problem, streamEnabled = linkUi.enabled, streamSummary = streamState.message)
        pad.setCaption(ui.caption, ui.link, LinkStatus.caption(mac, problem))
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        refreshCaption()
        pad.setInk(inkColour(link.penBrush, dark = true), brushWidth(link.penBrush))
        pushPadWish()
        link.padOpened(true) // The Mac creates a note for the pad if none is open
    }

    override fun onPause() {
        pad.releaseAll() // Never leave a stream stroke open.
        link.padOpened(false)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) { if (!panelOpen) hideSystemBars() } else pad.releaseAll()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onDestroy() {
        if (::pad.isInitialized) pad.releaseAll()
        super.onDestroy()
    }
}
