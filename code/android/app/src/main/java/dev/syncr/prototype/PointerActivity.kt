package dev.syncr.prototype

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.syncr.prototype.link.android.LinkController
import dev.syncr.prototype.link.android.LinkUi
import dev.syncr.prototype.link.CanvasRect
import dev.syncr.prototype.link.PadWish
import dev.syncr.prototype.notes.brushWidth
import dev.syncr.prototype.notes.inkColour
import dev.syncr.prototype.ui.linkSummary
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import dev.syncr.prototype.tablet.StreamAction
import dev.syncr.prototype.tablet.StreamPadStatus
import dev.syncr.prototype.tablet.TabletMacLinkSettings
import dev.syncr.prototype.tablet.defaultTabletLink
import dev.syncr.prototype.tablet.streamPadState
import dev.syncr.prototype.tablet.LinkStatus
import dev.syncr.prototype.tablet.MacScreen
import dev.syncr.prototype.tablet.MappingMode
import dev.syncr.prototype.tablet.TabletLink
import dev.syncr.prototype.tablet.TabletMapping
import dev.syncr.prototype.tablet.TabletPadView
import dev.syncr.prototype.tablet.TabletBubble
import dev.syncr.prototype.tablet.TabletBubbleActions
import dev.syncr.prototype.tablet.MacPickerDialog
import dev.syncr.prototype.tablet.TabletSettings
import dev.syncr.prototype.tablet.TabletSetupActions
import dev.syncr.prototype.tablet.TabletSetupPanel
import dev.syncr.prototype.tablet.TabletUiState
import dev.syncr.prototype.ui.theme.SyncrTheme

/**
 * Tablet mode (design §8.2, §8.7). Pointer link: the phone is a Bluetooth HID absolute mouse; the Mac owns
 * the canvas and nothing is stored. Stream link: the pad writes into the note open in the Syncr Mac app over the
 * Wi-Fi link (wifi-link-v0 §3.5); the stroke is saved there, never on the phone.
 * The class keeps its old name so existing entry points and the manifest entry keep working.
 */
class PointerActivity : ComponentActivity() {
    private lateinit var controller: HidController
    private lateinit var settings: TabletSettings
    private lateinit var pad: TabletPadView
    private var ui by mutableStateOf(
        TabletUiState(MappingMode.ACTUAL_SIZE, MacScreen.DEFAULT, false, null,
            "Pair this phone with your Mac in Bluetooth settings on both, then choose your Mac.", needsPermission = false)
    )
    private var panelOpen by mutableStateOf(false)
    private var bubbleOpen by mutableStateOf(false)
    /** Paired devices offered in the "Choose your Mac" dialog; null while it is closed. */
    private var macChoices by mutableStateOf<List<BluetoothDevice>?>(null)
    private var autoConnectTried = false
    private var pointerHost: String? = null
    private var pointerStatus = "Choose your paired Mac to use the Bluetooth pointer."
    private lateinit var link: LinkController
    private var linkUi by mutableStateOf(LinkUi())
    private var activeLink by mutableStateOf(TabletLink.POINTER)
    private var sessionLink: TabletLink? = null
    private var macLinkOpen by mutableStateOf(false)
    private var padAspect = 0.0
    /** Local: the frame width of last time is asked for once per connection, when the Mac first says where the frame is. */
    private var frameRestored = false
    private val streaming get() = activeLink == TabletLink.STREAM
    private val streamState get() = streamPadState(linkUi.enabled, linkUi.pairedMac != null,
        linkUi.status.phase, linkUi.status.macName ?: linkUi.pairedMac, linkUi.target)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = TabletSettings(this)
        link = LinkController.get(this)
        linkUi = link.state.value
        activeLink = defaultTabletLink(settings.link, linkUi.enabled, linkUi.pairedMac != null)
        link.bind(this)
        sessionLink = savedInstanceState?.getString("session_link")?.let { name -> TabletLink.entries.firstOrNull { it.name == name } }
        sessionLink?.let { activeLink = it }
        window.setBackgroundDrawable(Color.BLACK.toDrawable())
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        controller = HidController(this) { message ->
            Log.i("SyncrHid", message)
            runOnUiThread { onStatus(message) }
        }
        ui = ui.copy(mode = settings.mode, screen = settings.screen, calibrated = settings.calibrated,
            needsPermission = !hasBluetoothPermission(), trailMs = settings.trailMs, knownHost = settings.hostAddress != null,
            linkChoice = activeLink)

        pad = TabletPadView(
            this,
            // HID coordinates go through AbsoluteMouse unchanged: u = X / 32767 maps back to exactly X.
            send = { x, y, pressed ->
                controller.send(x.toFloat(), y.toFloat(), TabletMapping.HID_MAX.toFloat(), TabletMapping.HID_MAX.toFloat(), pressed)
            },
            onWindowMoved = { ox, oy -> settings.offsetXMm = ox; settings.offsetYMm = oy },
            onMapperChanged = { m ->
                ui = ui.copy(padWidthMm = m.windowWidthMm, padHeightMm = m.windowHeightMm, area = m.activeArea)
            },
        )
        pad.configure(settings.mode, settings.screen, settings.offsetXMm, settings.offsetYMm)
        pad.setTrailMs(settings.trailMs)
        pad.setStream(if (streaming) link.streamPen else null)
        pad.onCaptionClick = { applyLink(if (streaming) TabletLink.POINTER else TabletLink.STREAM) }
        pad.setLocal(settings.streamLocal)
        pad.setPictureLarge(settings.pictureLarge)
        pad.setInk(inkColour(link.penBrush, dark = true), brushWidth(link.penBrush))
        pad.setTarget(linkUi.target)
        pad.onPictureLarge = { settings.pictureLarge = it }
        pad.onFrameRequest = { frame -> link.requestFrame(frame); settings.frameWidth = frame.w.toFloat() }
        pad.onPadShape = { aspect -> if (aspect != padAspect) { padAspect = aspect; pushPadWish() } }
        ui = ui.copy(streamLocal = settings.streamLocal)
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
                    val choice = sessionLink ?: defaultTabletLink(settings.link, next.enabled, next.pairedMac != null)
                    if (choice != activeLink) switchLink(choice)
                    refreshCaption()
                }
            }
        }

        setContent {
            SyncrTheme(dark = true) {
                Box(Modifier.fillMaxSize()) {
                    AndroidView(factory = { pad }, modifier = Modifier.fillMaxSize())
                    TabletBubble(ui, bubbleOpen, bubbleActions)
                    if (streaming && !panelOpen && !bubbleOpen && !macLinkOpen) {
                        StreamPadStatus(streamState, Modifier.align(Alignment.TopCenter).padding(top = 8.dp), ::streamAction)
                    }
                    if (panelOpen) TabletSetupPanel(ui, setupActions)
                    if (macLinkOpen) TabletMacLinkSettings { macLinkOpen = false; syncPadInput(); hideSystemBars() }
                    macChoices?.let { devices ->
                        MacPickerDialog(devices.map { deviceName(it) },
                            onPick = { index -> closeMacPicker(); settings.hostAddress = devices[index].address; controller.connect(devices[index]) },
                            onDismiss = { closeMacPicker() })
                    }
                }
            }
        }

        if (!controller.available()) {
            onStatus("This phone can't act as a Bluetooth pen. It needs Android 9 or later with Bluetooth HID support.")
        } else if (!streaming) requestBluetoothAccess()
        if (settings.hostAddress == null && !streaming) openPanel()
    }

    private val setupActions = TabletSetupActions(
        close = { closePanel() },
        setMode = { mode ->
            settings.mode = mode
            ui = ui.copy(mode = mode)
            pad.configure(mode, settings.screen, settings.offsetXMm, settings.offsetYMm)
            refreshCaption()
        },
        setScreen = { screen ->
            if (screen != settings.screen) {
                settings.screen = screen
                settings.calibrated = false // A new size has not been checked.
                ui = ui.copy(screen = screen, calibrated = false)
                pad.configure(settings.mode, screen, settings.offsetXMm, settings.offsetYMm)
                refreshCaption()
            }
        },
        setCalibrated = { checked ->
            settings.calibrated = checked
            ui = ui.copy(calibrated = checked)
            refreshCaption()
        },
        setTrail = { ms ->
            settings.trailMs = ms
            ui = ui.copy(trailMs = settings.trailMs)
            pad.setTrailMs(settings.trailMs)
        },
        bluetoothSettings = { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
        selectMac = { selectHost() },
        requestPermission = { requestBluetoothAccess() },
        leave = { finish() },
        setLink = { choice -> applyLink(choice) },
        enableMacLink = { link.setEnabled(true) },
        macLinkSettings = { panelOpen = false; macLinkOpen = true; syncPadInput() },
    )

    /** Pointer (Bluetooth mouse) or Stream (Wi-Fi into the Mac's note). A stroke in progress is released first. */
    private fun applyLink(choice: TabletLink) {
        settings.link = choice // Even selecting the current automatic choice makes it explicit.
        sessionLink = null
        switchLink(choice)
    }

    private fun switchLink(choice: TabletLink) {
        if (choice != activeLink) {
            pad.releaseAll()
            activeLink = choice
            ui = ui.copy(host = if (streaming) null else pointerHost, linkChoice = choice,
                status = if (streaming) ui.status else pointerStatus)
            pad.setStream(if (streaming) link.streamPen else null)
            pushPadWish()
            link.padOpened(streaming && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            if (!streaming && controller.available()) {
                requestBluetoothAccess()
                if (hasBluetoothPermission() && controller.enabled()) autoConnect()
            }
        }
        refreshCaption()
    }

    /** What the pad asks of the Mac: its mapping, pictures of what it covers, and its shape. Nothing while not streaming. */
    private fun pushPadWish() {
        link.setPadWish(if (streaming && padAspect > 0) PadWish(settings.streamLocal, pics = true, aspect = padAspect) else null)
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
            StreamAction.POINTER -> {
                sessionLink = TabletLink.POINTER // Recovery is only for this pad session, never the saved preference.
                switchLink(TabletLink.POINTER)
            }
        }
    }

    /** Bubble taps are finger input on Compose, never pad input; opening it also releases a held button. */
    private val bubbleActions = TabletBubbleActions(
        open = { bubbleOpen = true; syncPadInput() },
        close = { bubbleOpen = false; syncPadInput() },
        toggleMapping = {
            setupActions.setMode(if (settings.mode == MappingMode.ACTUAL_SIZE) MappingMode.WHOLE_SCREEN else MappingMode.ACTUAL_SIZE)
        },
        toggleLocal = {
            settings.streamLocal = !settings.streamLocal
            ui = ui.copy(streamLocal = settings.streamLocal)
            frameRestored = false
            pad.setLocal(settings.streamLocal)
            pushPadWish()
        },
        recentre = { pad.recentre() },
        selectMac = { bubbleOpen = false; syncPadInput(); reconnectOrSelect() },
        settings = { bubbleOpen = false; openPanel() },
        leave = { finish() },
    )

    private fun syncPadInput() {
        pad.inputEnabled = !bubbleOpen && !panelOpen && !macLinkOpen && macChoices == null // Disabling releases a held button first.
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

    private fun onStatus(message: String) {
        pointerHost = LinkStatus.connectedHost(message, pointerHost)
        pointerStatus = message
        if (streaming) return // Keep Bluetooth status for a later switch, but do not show it as the Stream link.
        ui = ui.copy(status = message, host = pointerHost)
        refreshCaption()
        if (message.startsWith("Bluetooth controller ready")) autoConnect()
    }

    private fun refreshCaption() {
        if (streaming) { refreshStreamCaption(); return }
        ui = ui.copy(problem = problem(), knownHost = settings.hostAddress != null, linkChoice = activeLink)
        // The spoken description keeps the mapping and calibration detail the visible line leaves out.
        pad.setCaption(ui.caption, ui.link, LinkStatus.caption(ui.host, ui.mode, ui.calibrated))
    }

    /** Stream: the Mac link's state and whether the Mac has a note this pad can write into. */
    private fun refreshStreamCaption() {
        val mac = linkUi.status.macName.takeIf { linkUi.connected }
        val problem = streamState.problem
        ui = ui.copy(host = mac, problem = problem, linkChoice = activeLink, streamEnabled = linkUi.enabled,
            streamSummary = streamState.message, status = linkSummary(linkUi))
        pad.setCaption(ui.caption, ui.link, LinkStatus.captionStream(mac, problem))
    }

    /** What stops the link working, in a few words for the caption; null when nothing does. */
    private fun problem(): String? = when {
        !::controller.isInitialized -> null
        !controller.available() -> "Bluetooth pen not supported"
        !hasBluetoothPermission() -> "Needs Bluetooth permission"
        !controller.enabled() -> "Bluetooth is off"
        else -> null
    }

    /** Reconnects to the Mac chosen last time if it is still paired; otherwise asks which Mac. */
    private fun reconnectOrSelect() {
        val address = settings.hostAddress
        if (ui.host == null && address != null && hasBluetoothPermission() && controller.enabled()) {
            controller.bonded().firstOrNull { it.address == address }?.let { controller.connect(it); return }
        }
        selectHost()
    }

    private fun hasBluetoothPermission() = Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun requestBluetoothAccess() {
        if (!hasBluetoothPermission()) permissionRequest.launch(Manifest.permission.BLUETOOTH_CONNECT)
        else controller.start()
    }

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        ui = ui.copy(needsPermission = !hasBluetoothPermission())
        refreshCaption()
        if (granted) controller.start()
        else onStatus("Syncr needs Bluetooth permission to connect to your Mac.")
    }

    /** Reconnects to the Mac chosen last time, once per launch, when the HID app is registered. */
    private fun autoConnect() {
        if (autoConnectTried || ui.host != null) return
        autoConnectTried = true
        val address = settings.hostAddress ?: return
        controller.bonded().firstOrNull { it.address == address }?.let { controller.connect(it) }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        ui = ui.copy(needsPermission = !hasBluetoothPermission())
        refreshCaption()
        if (!streaming && controller.available() && hasBluetoothPermission()) controller.start()
        pad.setInk(inkColour(link.penBrush, dark = true), brushWidth(link.penBrush))
        pushPadWish()
        link.padOpened(streaming) // Stream: the Mac creates a note for the pad if none is open
    }

    override fun onPause() {
        pad.releaseAll() // Never leave the mouse button held, or a stream stroke open.
        link.padOpened(false)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) { if (!panelOpen) hideSystemBars() } else pad.releaseAll() // Never leave the button held.
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun selectHost() {
        if (!hasBluetoothPermission()) { requestBluetoothAccess(); return }
        if (!controller.enabled()) { onStatus("Turn on Bluetooth in system settings first."); return }
        val devices = controller.bonded()
        if (devices.isEmpty()) {
            onStatus("No paired devices yet. Pair your Mac in Bluetooth settings on both devices first.")
            return
        }
        macChoices = devices
        syncPadInput()
    }

    private fun closeMacPicker() {
        macChoices = null
        syncPadInput()
        hideSystemBars()
    }

    /** A paired device's name; the address when the name can't be read. Reading it is guarded by the permission check. */
    @SuppressLint("MissingPermission")
    private fun deviceName(device: BluetoothDevice): String =
        (if (hasBluetoothPermission()) runCatching { device.name }.getOrNull() else null) ?: device.address

    override fun onSaveInstanceState(outState: Bundle) {
        sessionLink?.let { outState.putString("session_link", it.name) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::pad.isInitialized) pad.releaseAll()
        if (::controller.isInitialized) controller.close()
        super.onDestroy()
    }
}
