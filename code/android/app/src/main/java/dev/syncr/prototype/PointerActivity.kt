package dev.syncr.prototype

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.syncr.prototype.tablet.LinkStatus
import dev.syncr.prototype.tablet.MacScreen
import dev.syncr.prototype.tablet.MappingMode
import dev.syncr.prototype.tablet.TabletLink
import dev.syncr.prototype.tablet.TabletMapping
import dev.syncr.prototype.tablet.TabletPadView
import dev.syncr.prototype.tablet.TabletBubble
import dev.syncr.prototype.tablet.TabletBubbleActions
import dev.syncr.prototype.tablet.TabletSettings
import dev.syncr.prototype.tablet.TabletSetupActions
import dev.syncr.prototype.tablet.TabletSetupPanel
import dev.syncr.prototype.tablet.TabletUiState
import dev.syncr.prototype.ui.theme.SyncrTheme

/**
 * Tablet mode (design §8.2, §8.7). Pointer link: the phone is a Bluetooth HID absolute mouse; the Mac owns
 * the canvas and nothing is stored. Stream (Wi-Fi into a Syncr note) waits for the Mac app.
 * The class keeps its old name so existing entry points and the manifest entry keep working.
 */
class PointerActivity : ComponentActivity() {
    private lateinit var controller: HidController
    private lateinit var settings: TabletSettings
    private lateinit var pad: TabletPadView
    private var ui by mutableStateOf(
        TabletUiState(MappingMode.ACTUAL_SIZE, MacScreen.DEFAULT, false, null,
            "Pair the phone in Android and macOS Bluetooth settings, then select your Mac.", needsPermission = false)
    )
    private var panelOpen by mutableStateOf(false)
    private var bubbleOpen by mutableStateOf(false)
    private var autoConnectTried = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = TabletSettings(this)
        settings.link = TabletLink.POINTER // The only link that exists today.
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
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
            needsPermission = !hasBluetoothPermission(), trailMs = settings.trailMs, knownHost = settings.hostAddress != null)

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
        refreshCaption()

        setContent {
            SyncrTheme(dark = true) {
                Box(Modifier.fillMaxSize()) {
                    AndroidView(factory = { pad }, modifier = Modifier.fillMaxSize())
                    TabletBubble(ui, bubbleOpen, bubbleActions)
                    if (panelOpen) TabletSetupPanel(ui, setupActions)
                }
            }
        }

        if (!controller.available()) {
            onStatus("This phone does not expose Android's Bluetooth HID Device profile (Android 9+ required).")
        } else requestBluetoothAccess()
        if (settings.hostAddress == null) openPanel()
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
    )

    /** Bubble taps are finger input on Compose, never pad input; opening it also releases a held button. */
    private val bubbleActions = TabletBubbleActions(
        open = { bubbleOpen = true; syncPadInput() },
        close = { bubbleOpen = false; syncPadInput() },
        toggleMapping = {
            setupActions.setMode(if (settings.mode == MappingMode.ACTUAL_SIZE) MappingMode.WHOLE_SCREEN else MappingMode.ACTUAL_SIZE)
        },
        recentre = { pad.recentre() },
        selectMac = { bubbleOpen = false; syncPadInput(); reconnectOrSelect() },
        settings = { bubbleOpen = false; openPanel() },
        leave = { finish() },
    )

    private fun syncPadInput() {
        pad.inputEnabled = !bubbleOpen && !panelOpen // Disabling releases a held button first.
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
        val host = LinkStatus.connectedHost(message, ui.host)
        ui = ui.copy(status = message, host = host)
        refreshCaption()
        if (message.startsWith("Bluetooth controller ready")) autoConnect()
    }

    private fun refreshCaption() {
        ui = ui.copy(problem = problem(), knownHost = settings.hostAddress != null)
        // The spoken description keeps the mapping and calibration detail the visible line leaves out.
        pad.setCaption(ui.caption, ui.link, LinkStatus.caption(ui.host, ui.mode, ui.calibrated))
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
        else onStatus("Bluetooth permission is needed to pair and send pointer input.")
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
        if (controller.available() && hasBluetoothPermission()) controller.start()
    }

    override fun onPause() {
        pad.releaseAll() // Never leave the mouse button held.
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
            onStatus("No paired devices. Pair your Mac in Bluetooth settings on both devices first.")
            return
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Choose your paired Mac")
            .setItems(devices.map { it.name ?: it.address }.toTypedArray()) { _, index ->
                settings.hostAddress = devices[index].address
                controller.connect(devices[index])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        if (::pad.isInitialized) pad.releaseAll()
        if (::controller.isInitialized) controller.close()
        super.onDestroy()
    }
}
