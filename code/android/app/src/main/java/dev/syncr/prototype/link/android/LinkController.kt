package dev.syncr.prototype.link.android

import android.app.Application
import android.annotation.SuppressLint
import android.os.PowerManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.syncr.core.Brush
import dev.syncr.core.BrushFamily
import dev.syncr.core.BrushSize
import dev.syncr.core.InkColour
import dev.syncr.core.encodeSamples
import dev.syncr.core.newId
import dev.syncr.prototype.link.CanvasMirror
import dev.syncr.prototype.link.CanvasRect
import dev.syncr.prototype.link.PadWish
import dev.syncr.prototype.link.Wire
import dev.syncr.prototype.link.ClientConfig
import dev.syncr.prototype.link.ClientListener
import dev.syncr.prototype.link.Clock
import dev.syncr.prototype.link.CorePreviewOut
import dev.syncr.prototype.link.Endpoint
import dev.syncr.prototype.link.IdPool
import dev.syncr.prototype.link.InkMirror
import dev.syncr.prototype.link.LinkClient
import dev.syncr.prototype.link.LinkPolicy
import dev.syncr.prototype.link.CopyOutcome
import dev.syncr.prototype.link.NoteDigest
import dev.syncr.prototype.link.NoteMeta
import dev.syncr.prototype.link.NoteSource
import dev.syncr.prototype.link.PenFeed
import dev.syncr.prototype.link.StrokeKeeper
import dev.syncr.prototype.link.PenDiagnostics
import dev.syncr.prototype.link.radioLine
import dev.syncr.prototype.link.Phase
import dev.syncr.prototype.link.SessionStatus
import dev.syncr.prototype.link.StreamPen
import dev.syncr.prototype.link.StreamTarget
import dev.syncr.prototype.link.PointerInput
import dev.syncr.prototype.link.PointerStatus
import dev.syncr.prototype.notes.InkCodec
import dev.syncr.prototype.notes.InkPreferencesStore
import dev.syncr.prototype.notes.NotesRepository
import dev.syncr.prototype.notes.NotesState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** What Settings and the connection indicator show. */
data class LinkUi(
    val enabled: Boolean = false,
    val status: SessionStatus = SessionStatus(),
    val found: List<FoundMac> = emptyList(),
    /** The paired Mac's name, or null when this phone is not paired. */
    val pairedMac: String? = null,
    val target: StreamTarget = StreamTarget(),
    val pointer: PointerStatus = PointerStatus(),
) {
    val connected get() = status.phase == Phase.CONNECTED
}

/** A decoded picture of what the pad covers: the canvas [region] it shows, drawn at target [rev] with [saved] pad strokes in it. */
class PadPicture(val rev: Long, val saved: Long, val region: CanvasRect, val bitmap: android.graphics.Bitmap, val kind: Int = 1)

/**
 * The app's one link to the Mac (wifi-link-v0). Owns the client, discovery and the Wi-Fi lock, and lives while the app is
 * in the foreground and the link is enabled: [bind] counts started activities, so going from the notes to the pad does not
 * drop the connection. Off by default; nothing touches the network until the user turns it on in Settings.
 */
class LinkController private constructor(private val app: Application) {
    private val prefs = app.getSharedPreferences("syncr-link", Context.MODE_PRIVATE)
    private val store = PrefsPairingStore(prefs)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ui = MutableStateFlow(LinkUi(enabled = prefs.getBoolean(ENABLED, LinkPolicy.ENABLED_BY_DEFAULT), pairedMac = store.pairing()?.macName))
    val state: StateFlow<LinkUi> = ui.asStateFlow()

    /** The pen thread's ring and stroke IDs. Created once; the pen only ever touches these. */
    val feed = PenFeed()
    private val ids = IdPool(::newId)
    private val out by lazy { CorePreviewOut() }

    /** Pad strokes the Mac has not confirmed yet: they outlive the client, which is remade whenever the app returns to the front. */
    private val keeper = StrokeKeeper()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** For the note canvas (wifi-link-v0 §3.4). */
    val mirror: InkMirror = CanvasMirror(feed, ids)

    /** For the Tablet pad in Stream (§3.5). */
    private val penDiagnostics = PenDiagnostics()
    val streamPen = StreamPen(feed, ids, { target }, { penBrush }, penDiagnostics::offer) {
        when {
            !ui.value.enabled -> "Mac link is off"
            ui.value.status.phase == Phase.NEEDS_CODE -> "Mac needs pairing code"
            ui.value.pairedMac == null -> "Mac is not paired"
            else -> "Mac is disconnected"
        }
    }

    init {
        scope.launch {
            while (isActive) {
                penDiagnostics.drain { Log.i(TAG, it) }
                delay(100)
            }
        }
    }

    @Volatile private var target = StreamTarget()

    /** The newest picture of what the pad covers; null with no connection or before the first arrives. */
    private val picture = MutableStateFlow<PadPicture?>(null)
    val pictures: StateFlow<PadPicture?> = picture.asStateFlow()
    private val newestPicture = java.util.concurrent.atomic.AtomicReference<ByteArray?>()
    private val decoder = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "syncr-pad-picture").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    @Volatile private var padWish: PadWish? = null
    /** The pen frame the Mac last reported while the pad was open: a client made later (the app returned to the front) asks for it again. */
    @Volatile private var padFrame: CanvasRect? = null

    /** The tool-bar pen the phone last used in a note (family, size, ink); Stream strokes are written with it. */
    @Volatile private var chosenBrush: Brush = readBrush()

    /** [chosenBrush] as a stroke is stored: with the pen look and feel in the current ink preferences. */
    val penBrush: Brush get() = InkPreferencesStore.state(app).value.drawn(chosenBrush)

    private var client: LinkClient? = null
    private var foreground = 0
    @Volatile private var wifiLock: WifiManager.WifiLock? = null
    /** Created only when the link first runs: with the link off, nothing here ever touches NsdManager. */
    private var finder: NsdFinder? = null
    private var found: List<FoundMac> = emptyList()

    @Volatile private var latest: Projection = Projection(emptyList(), null)
    private var repo: NotesRepository? = null
    private var notesJob: Job? = null
    @Volatile private var padOn = false
    @Volatile private var pointerOn = false

    private data class Projection(val notes: List<NoteMeta>, val open: String?)

    /** Counts this activity while it is started. Call from onCreate. */
    fun bind(owner: LifecycleOwner) {
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { foreground++; reconcile() }
            override fun onStop(owner: LifecycleOwner) { foreground--; reconcile() }
        })
    }

    fun setEnabled(on: Boolean) {
        prefs.edit { putBoolean(ENABLED, on) }
        ui.update { it.copy(enabled = on) }
        reconcile()
    }

    /** Connect to a Mac chosen from the list. */
    fun connect(mac: FoundMac) = client?.connect(mac.endpoint)

    /** Connect to a typed `host:port`. Returns an error to show, or null. */
    fun connectManual(text: String): String? {
        val trimmed = text.trim()
        val colon = trimmed.lastIndexOf(':')
        val host = if (colon > 0) trimmed.substring(0, colon).trim().removePrefix("[").removeSuffix("]") else ""
        val port = if (colon > 0) trimmed.substring(colon + 1).trim().toIntOrNull() else null
        if (host.isEmpty() || port == null || port !in 1..65535) return "Type the Mac's address as host:port, for example 192.168.1.20:5050."
        val c = client ?: return "Turn the Mac link on first."
        c.connect(Endpoint(host, port))
        return null
    }

    fun submitCode(code: String) = client?.submitCode(code)

    /** Deletes this phone's pairing. The Mac keeps its own record until it forgets this phone there. */
    fun forget() {
        client?.forget() ?: store.forget()
        target = StreamTarget(); picture.value = null
        ui.update { it.copy(pairedMac = null, status = SessionStatus(), target = target) }
    }

    /** The pad opened or closed in Stream. */
    fun padOpened(on: Boolean) {
        padOn = on
        if (!on) padFrame = null
        client?.setPad(on)
    }

    fun pointerOpened(on: Boolean) { pointerOn = on; client?.setPointer(on) }
    fun pointerInput(input: PointerInput) { client?.pointerInput(input) }

    /** How the pad maps and whether it wants pictures (rework-v1 §V); null when the pad is not asking. */
    fun setPadWish(wish: PadWish?) {
        padWish = wish
        client?.setPadWish(wish)
    }

    /** A finger gesture on the pad moved or resized the pen frame. */
    fun requestFrame(rect: CanvasRect) { client?.requestFrame(rect) }

    /** Read on the pad: a finger or the pen moved or pinched the Mac's view. */
    fun requestScroll(dx: Double, dy: Double, zoom: Double) { client?.requestScroll(dx, dy, zoom) }

    /** Reader thread: keep only the newest picture and decode it off every thread the pen depends on. */
    private fun receivePicture(message: ByteArray) {
        if (newestPicture.getAndSet(message) != null) return // a decode is already queued and will take this one
        decoder.execute {
            val bytes = newestPicture.getAndSet(null) ?: return@execute
            val header = Wire.parsePicture(bytes) ?: return@execute
            val bitmap = runCatching {
                android.graphics.BitmapFactory.decodeByteArray(bytes, Wire.PICTURE_HEADER, bytes.size - Wire.PICTURE_HEADER)
            }.getOrNull() ?: return@execute
            if (ui.value.connected) picture.value = PadPicture(header.rev, header.saved, header.region, bitmap, header.kind)
        }
    }

    /** Remember the pen the notes are using, for Stream. */
    fun usePen(brush: Brush) {
        if (brush == chosenBrush) return
        chosenBrush = brush
        prefs.edit {
            putString(BRUSH_FAMILY, brush.family.name)
            putString(BRUSH_SIZE, brush.size.name)
            putString(BRUSH_COLOUR, brush.colour.name)
            putString(BRUSH_CUSTOM, brush.customInk?.let { InkCodec.encode(it) })
        }
    }

    /** Sync and mirror work from the notes in [repo]. Pass null when the notes screen goes away. */
    fun attachNotes(repo: NotesRepository?) {
        notesJob?.cancel()
        this.repo = repo
        if (repo == null) return
        notesJob = scope.launch {
            repo.state.map(::project).distinctUntilChanged().collect { p ->
                latest = p
                client?.let { it.notesChanged(p.notes, p.open); it.setViewing(p.open) }
            }
        }
    }

    private fun project(state: NotesState): Projection {
        val notes = LinkedHashMap<String, NoteMeta>()
        state.notes.forEach { notes[it.id] = NoteMeta(it.id, it.localSequence.toLong(), it.title) }
        // The open note's saved sequence comes from its snapshot: the library list is only re-read on close.
        state.snapshot?.let { notes[it.noteId] = NoteMeta(it.noteId, it.localSequence.toLong(), it.title) }
        return Projection(notes.values.toList(), state.snapshot?.noteId)
    }

    // ---- Lifecycle ----

    @Synchronized
    private fun reconcile() {
        if (LinkPolicy.shouldRun(ui.value.enabled, foreground)) {
            val f = finder ?: NsdFinder(app.getSystemService(NsdManager::class.java)) { found ->
                ui.update { it.copy(found = found) }
                // The paired Mac showed up (it restarted, or Wi-Fi is back): connect now, not at the next retry.
                val paired = store.pairing()
                if (paired != null && !ui.value.connected && found.any { it.macId == paired.macId }) client?.kick()
            }.also { finder = it }
            f.start()
            if (client == null) startClient()
        } else {
            stopClient()
            finder?.stop()
        }
        updateWifiLock()
        watchNetwork(client != null)
    }

    /**
     * Wi-Fi coming back, the address or the route changing: the client connects at once instead of waiting out its retry delay, and
     * a connection that is up is asked to prove it still works. Only while the link runs; no permission beyond ACCESS_NETWORK_STATE.
     */
    @Synchronized
    private fun watchNetwork(on: Boolean) {
        val manager = app.getSystemService(ConnectivityManager::class.java) ?: return
        if (!on) {
            networkCallback?.let { runCatching { manager.unregisterNetworkCallback(it) } }
            networkCallback = null
            return
        }
        if (networkCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { Log.i(TAG, "network available"); client?.networkChanged() }
            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
                Log.i(TAG, "network link properties changed"); client?.networkChanged()
            }
        }
        networkCallback = runCatching { manager.registerDefaultNetworkCallback(callback); callback }.getOrNull()
    }

    private fun startClient() {
        val dir = File(app.cacheDir, "link-out").also { it.deleteRecursively() }
        val source = object : NoteSource {
            override fun list() = latest.notes
            override fun exportCopy(id: String, dest: File): Long? {
                val repo = repo ?: return null
                dest.delete()
                return runBlocking { repo.exportCopy(id, dest.absolutePath) }
            }
            override fun digests(ids: List<String>): Map<String, NoteDigest> {
                val repo = repo ?: return emptyMap()
                return runBlocking { repo.syncDigests(ids) }.mapValues { NoteDigest(it.value.digest, it.value.operationCount.toLong()) }
            }
            override fun heads(id: String): Map<String, String>? = repo?.let { runBlocking { it.syncHeads(id) } }
            override fun editing(id: String): Boolean = repo?.state?.value?.let { it.unsaved && it.snapshot?.noteId == id } == true
            override fun exportDelta(id: String, base: Map<String, String>, dest: File): Long? {
                val repo = repo ?: return null
                dest.delete()
                return runBlocking { repo.exportDelta(id, base, dest.absolutePath) }
            }
            override fun applyDelta(id: String, file: File): CopyOutcome {
                val repo = repo ?: return CopyOutcome.Refused("notes are not open")
                return try {
                    val r = runBlocking { repo.applyDelta(id, file.absolutePath) }
                    CopyOutcome.Applied(r.seq, NoteDigest(r.digest, r.operationCount), r.how, r.rejected)
                } catch (e: Exception) {
                    CopyOutcome.Refused((e.message ?: e.javaClass.simpleName).take(150))
                }
            }
            override fun trash(): List<String> = repo?.let { runBlocking { it.trashIds() } } ?: emptyList()
            override fun applyCopy(id: String, file: File): CopyOutcome {
                val repo = repo ?: return CopyOutcome.Refused("notes are not open")
                return try {
                    val r = runBlocking { repo.applyCopy(id, file.absolutePath) }
                    CopyOutcome.Applied(r.seq, NoteDigest(r.digest, r.operationCount), r.how, r.rejected)
                } catch (e: Exception) {
                    CopyOutcome.Refused((e.message ?: e.javaClass.simpleName).take(150))
                }
            }
        }
        val config = ClientConfig(
            device = deviceName(), store = store, source = source, clock = Clock.SYSTEM, tempDir = dir, out = out, feed = feed,
            encodeSamples = ::encodeSamples, log = { Log.i(TAG, it) }, ids = ids, latencyContext = ::radioContext, keeper = keeper, transport = TunedTcp,
        )
        lateinit var self: LinkClient
        val listener = object : ClientListener {
            override fun onStatus(status: SessionStatus) {
                if (client !== self) return // a client that was stopped
                // A Mac that confirms strokes lets the pad go on through a drop: its target stays, so ink drawn meanwhile is kept.
                if (status.phase != Phase.CONNECTED && !keeper.peerKeeps) { target = StreamTarget(); picture.value = null }
                ui.update { it.copy(status = status, pairedMac = store.pairing()?.macName, target = target,
                    pointer = if (status.phase == Phase.CONNECTED) it.pointer else PointerStatus()) }
                updateWifiLock()
            }
            override fun onTarget(target: StreamTarget) {
                if (padOn) target.frame?.let { padFrame = it }
                this@LinkController.target = target
                ui.update { it.copy(target = target) }
            }
            override fun onPointer(status: PointerStatus) {
                if (client === self) ui.update { it.copy(pointer = status) }
            }
            override fun onConnected(endpoint: Endpoint) = store.saveEndpoint(endpoint.host, endpoint.port)
            override fun onPicture(message: ByteArray) = receivePicture(message)
        }
        val c = LinkClient(config, ::endpointFor, listener)
        self = c
        client = c
        c.notesChanged(latest.notes, latest.open)
        c.setViewing(latest.open)
        c.setPad(padOn)
        if (padOn) c.resumeFrame(padFrame)
        c.setPadWish(padWish)
        c.setPointer(pointerOn)
        c.start()
    }

    private fun stopClient() {
        val c = client ?: return
        client = null
        c.stop()
        target = StreamTarget()
        picture.value = null
        ui.update { it.copy(status = SessionStatus(), found = emptyList(), target = target, pointer = PointerStatus()) }
    }

    /** Where the paired Mac is: seen by discovery, or the address of the last good connection. */
    private fun endpointFor(pairing: dev.syncr.prototype.link.Pairing): Endpoint? {
        ui.value.found.firstOrNull { it.macId == pairing.macId }?.let { return it.endpoint }
        return store.lastEndpoint()?.let { (host, port) -> Endpoint(host, port, pairing.macName, pairing.macId) }
    }

    /** The low-latency Wi-Fi lock is held only while a session is connected and the app is in front. */
    @Synchronized
    private fun updateWifiLock() {
        val want = LinkPolicy.wantsWifiLock(foreground, ui.value.connected)
        if (want && wifiLock == null) {
            val wifi = app.applicationContext.getSystemService(WifiManager::class.java) ?: return
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = try {
                wifi.createWifiLock(mode, "syncr-link").apply { setReferenceCounted(false); acquire() }.also {
                    Log.i(TAG, "wifi lock acquired held=${it.isHeld} requested=$wifiMode low-latency-effect=unobservable(public API reports isHeld only)")
                }
            } catch (e: RuntimeException) {
                Log.i(TAG, "wifi lock acquire failed held=false requested=$wifiMode reason=${e.javaClass.simpleName}")
                null
            }
        } else if (!want) {
            wifiLock?.let { lock ->
                try {
                    if (lock.isHeld) lock.release()
                    Log.i(TAG, "wifi lock released held=${lock.isHeld} requested=$wifiMode")
                } catch (e: RuntimeException) {
                    Log.i(TAG, "wifi lock release failed reason=${e.javaClass.simpleName}")
                }
            }
            wifiLock = null
        }
    }

    private val wifiMode get() = if (Build.VERSION.SDK_INT >= 29) "WIFI_MODE_FULL_LOW_LATENCY" else "WIFI_MODE_FULL_HIGH_PERF"

    /** ACCESS_WIFI_STATE exposes radio metrics, not location identifiers. Never requests a runtime permission. */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun radioContext(): String {
        val info = runCatching { app.getSystemService(WifiManager::class.java)?.connectionInfo }.getOrNull()
        val saver = runCatching { app.getSystemService(PowerManager::class.java)?.isPowerSaveMode }.getOrNull()
        return radioLine(runCatching { wifiLock?.isHeld == true }.getOrDefault(false), wifiMode,
            info?.linkSpeed?.takeIf { it > 0 }, info?.frequency?.takeIf { it > 0 },
            info?.rssi?.takeIf { it in -126..-1 }, saver)
    }

    private fun deviceName(): String =
        (Settings.Global.getString(app.contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: Build.MODEL).take(64)

    private fun readBrush(): Brush {
        fun <T : Enum<T>> pick(key: String, all: Array<T>, fallback: T) = all.firstOrNull { it.name == prefs.getString(key, null) } ?: fallback
        return Brush(
            pick(BRUSH_FAMILY, BrushFamily.entries.toTypedArray(), BrushFamily.PEN),
            pick(BRUSH_SIZE, BrushSize.entries.toTypedArray(), BrushSize.MEDIUM),
            pick(BRUSH_COLOUR, InkColour.entries.toTypedArray(), InkColour.INK),
            customInk = prefs.getString(BRUSH_CUSTOM, null)?.let { InkCodec.decodeOklch(it) },
        )
    }

    companion object {
        const val TAG = "SyncrLink"
        private const val ENABLED = "enabled"
        private const val BRUSH_FAMILY = "brush_family"
        private const val BRUSH_SIZE = "brush_size"
        private const val BRUSH_COLOUR = "brush_colour"
        private const val BRUSH_CUSTOM = "brush_custom_ink"

        @Volatile private var instance: LinkController? = null

        fun get(context: Context): LinkController = instance ?: synchronized(this) {
            instance ?: LinkController(context.applicationContext as Application).also { instance = it }
        }
    }
}
