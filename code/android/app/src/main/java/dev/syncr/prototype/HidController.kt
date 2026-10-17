package dev.syncr.prototype

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Android HID Device profile; no companion/server or Samsung-private API. */
class HidController(context: Context, private val status: (String) -> Unit) {
    private val appContext = context.applicationContext
    private val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val handler = Handler(Looper.getMainLooper())
    private var hid: BluetoothHidDevice? = null
    private var registered = false
    private var starting = false
    private var connected: BluetoothDevice? = null
    private var pending: BluetoothDevice? = null
    private var lastReport: ByteArray? = null
    private var firstReportLogged = false
    private var closed = false

    fun available(): Boolean = Build.VERSION.SDK_INT >= 28 && adapter != null
    fun enabled(): Boolean = adapter?.isEnabled == true

    fun bonded(): List<BluetoothDevice> = adapter?.bondedDevices?.sortedBy { it.name ?: it.address } ?: emptyList()

    fun start() {
        if (closed || registered || starting) return
        if (hid != null) { register(hid!!); return }
        if (!available()) { status("Bluetooth HID Device requires Android 9+ and a supported Bluetooth adapter"); return }
        if (!enabled()) { status("Enable Bluetooth first"); return }
        status("Opening Bluetooth HID profile…")
        starting = true
        val ok = adapter!!.getProfileProxy(appContext, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (closed) { adapter.closeProfileProxy(profile, proxy); return }
                val device = proxy as? BluetoothHidDevice
                if (device == null) { starting = false; status("HID Device profile unavailable on this phone"); return }
                hid = device
                register(device)
            }
            override fun onServiceDisconnected(profile: Int) {
                hid = null; registered = false; starting = false; connected = null; lastReport = null; firstReportLogged = false
                if (!closed) status("Bluetooth HID service disconnected")
            }
        }, BluetoothProfile.HID_DEVICE)
        if (!ok) { starting = false; status("Could not open HID Device profile") }
    }

    private fun register(device: BluetoothHidDevice) {
        starting = true
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "Syncr Drawing Controller", "Absolute pointer experiment", "Syncr",
            BluetoothHidDevice.SUBCLASS1_MOUSE, AbsoluteMouse.descriptor
        )
        if (!device.registerApp(sdp, null, null, { task -> handler.post(task) }, callback)) {
            starting = false
            status("HID registration rejected; this device may not support HID Device mode")
        }
    }

    fun connect(device: BluetoothDevice) {
        pending = device
        if (!registered) { status("Registering controller; waiting to connect…"); start(); return }
        status("Connecting to ${device.name ?: device.address}…")
        if (hid?.connect(device) != true) status("Connection request failed. Pair from system Bluetooth settings first.")
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
            if (closed) return
            registered = isRegistered
            starting = false
            if (!isRegistered) {
                connected = null; lastReport = null
                status("HID registration lost. Reopen the app to retry.")
                return
            }
            status("Bluetooth controller ready. Select a paired Mac.")
            pending?.let { target -> pending = null; connect(target) }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            if (closed) return
            if (state == BluetoothProfile.STATE_CONNECTED) {
                connected = device
                firstReportLogged = false
                status("Connected to ${device.name ?: device.address}. Draw on the pad.")
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (connected == device) { connected = null; lastReport = null; firstReportLogged = false }
                status("Disconnected. Select the paired Mac to reconnect.")
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val report = lastReport
            if (type == BluetoothHidDevice.REPORT_TYPE_INPUT && id.toInt() == 0 && report != null) {
                hid?.replyReport(device, type, id, report)
            } else hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
        }
    }

    fun send(x: Float, y: Float, width: Float, height: Float, pressed: Boolean) {
        val target = connected ?: return
        val bytes = AbsoluteMouse.report(x, y, width, height, pressed)
        if (hid?.sendReport(target, 0, bytes) == true) {
            lastReport = bytes
            if (!firstReportLogged) {
                firstReportLogged = true
                Log.i("SyncrHid", "First absolute report queued: x=$x y=$y pressed=$pressed (Mac cursor interpretation unverified)")
            }
        } else status("HID report failed; check connection")
    }

    fun close() {
        if (closed) return
        closed = true
        connected?.let { target ->
            // Never leave the mouse button held when leaving the activity.
            lastReport?.copyOf()?.also { it[0] = 0 }?.let { hid?.sendReport(target, 0, it) }
        }
        hid?.let { device ->
            if (registered) device.unregisterApp()
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, device)
        }
        connected = null; hid = null; registered = false; starting = false
    }
}
