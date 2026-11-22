package dev.syncr.prototype.link

import dev.syncr.core.Brush
import dev.syncr.core.BrushFamily
import dev.syncr.core.BrushSize
import dev.syncr.core.CoordinateSpace
import dev.syncr.core.InkColour
import dev.syncr.core.SampleChannels
import dev.syncr.core.encodeSamples
import dev.syncr.core.newId
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.sin

/**
 * The fault scenarios of part K (`code/shared/link-v0/fault-table.sh` and `join-test.sh` run them): the phone's real link
 * client, a host JVM process, talks to the real Mac app through a [FaultProxy] on loopback. This class starts, stops and
 * restarts the Mac itself (it must be able to kill it), reads its log with the time each line arrived, and prints
 *
 *   FAULT <row> | notice phone=<ms|-> mac=<ms|-> | welcomed=<ms after the path returned|kept> | lost: ...
 *
 * for the fault table of K.0, and `FAULT PASS/FAIL <name>` for the checks (K.1 to K.4). Times are loopback times with the
 * 1-minute load printed beside them: they say how fast the link's own logic reacts, never how a real Wi-Fi path behaves.
 */
class FaultTest(private val o: Options, private val print: (String) -> Unit) {
    class Options(
        val macBin: File,
        val macLibrary: File,
        val macPort: Int,
        val fixture: File,
        val work: File,
        /** Scenario names to run (prefix match); empty: the default set. */
        val only: List<String> = emptyList(),
        val soakSeconds: Int = 90,
        val cuts: Int = 200,
        val timeoutMs: Long = 60_000,
        /** Turns the table's rows into checks (K.1 to K.5). Off for the "before" table, which only measures. */
        val check: Boolean = true,
    )

    private class Fail(message: String) : Exception(message)

    private val phoneId = "5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5d"
    private val log = CopyOnWriteArrayList<Pair<Long, String>>()
    private val logFile = File(o.work, "mac.log")
    private val controlFile = File(o.work, "control")
    private var mac: Process? = null
    @Volatile private var tailing = true
    private lateinit var proxy: FaultProxy
    private lateinit var lib: Probe.Library
    private lateinit var storeFile: File
    private var failed = 0
    private val rows = ArrayList<String>()

    private fun now() = System.nanoTime() / 1_000_000
    private fun load() = try { Runtime.getRuntime().exec(arrayOf("sysctl", "-n", "vm.loadavg")).inputStream.bufferedReader().readText().trim().removeSurrounding("{ ", " }").substringBefore(' ') } catch (_: Exception) { "?" }

    // ------------------------------------------------------------------ the Mac

    private fun startMac() {
        // The shell writes its own PID and then becomes the Mac app (exec), so the PID is the app's: this JVM's Process cannot say.
        val env = ProcessBuilder("/bin/sh", "-c", "echo \$\$ > '${pidFile.absolutePath}'; exec '${o.macBin.absolutePath}'").redirectErrorStream(true)
        env.environment().apply {
            put("SYNCR_MAC_LIBRARY_FOLDER", o.macLibrary.absolutePath)
            put("SYNCR_MAC_DEBUG_REPLAY", "1")
            put("SYNCR_MAC_DEBUG_CONTROL", controlFile.absolutePath)
            put("SYNCR_MAC_LINK_PORT", o.macPort.toString())
            put("SYNCR_MAC_LINK_LOOPBACK", "1")
        }
        env.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        val from = log.size
        mac = env.start()
        awaitLog(from, "link: listening port=${o.macPort}", 30_000)
        awaitLog(from, "link: control ready", 30_000)
    }

    private val pidFile = File(o.work, "mac.pid")
    private fun macPid() = pidFile.readText().trim().toLong()
    private fun signal(sig: String) { Runtime.getRuntime().exec(arrayOf("kill", "-$sig", macPid().toString())).waitFor() }

    /** Kills the Mac outright (no goodbye, no FIN from the app: the kernel closes its sockets). */
    private fun killMac() { signal("KILL"); mac?.waitFor(); mac = null }

    private fun tail() {
        var pos = 0L
        var partial = ""
        while (tailing) {
            try {
                if (logFile.exists() && logFile.length() > pos) {
                    RandomAccessFile(logFile, "r").use { f ->
                        f.seek(pos)
                        val bytes = ByteArray((logFile.length() - pos).toInt())
                        f.readFully(bytes)
                        pos += bytes.size
                        val text = partial + String(bytes)
                        val parts = text.split("\n")
                        partial = parts.last()
                        val t = now()
                        for (line in parts.dropLast(1)) log += t to line
                    }
                }
            } catch (_: IOException) { }
            Thread.sleep(5)
        }
    }

    private fun mark() = log.size
    private fun since(from: Int) = log.drop(from)
    private fun control(command: String) { controlFile.appendText(command + "\n") }

    private fun poll(ms: Long = o.timeoutMs, every: Long = 10, until: () -> Boolean): Boolean {
        val end = now() + ms
        while (true) {
            if (until()) return true
            if (now() > end) return false
            Thread.sleep(every)
        }
    }

    /** The time the first line after [from] matching [re] arrived, or a failure. */
    private fun awaitLog(from: Int, re: String, ms: Long = o.timeoutMs): Long = awaitLogLine(from, re, ms).first

    private fun awaitLogLine(from: Int, re: String, ms: Long = o.timeoutMs): Pair<Long, String> {
        val rx = Regex(re)
        var found: Pair<Long, String>? = null
        if (!poll(ms) { found = log.drop(from).firstOrNull { rx.containsMatchIn(it.second) }; found != null }) throw Fail("no Mac log line /$re/ within ${ms / 1000}s")
        return found!!
    }

    private fun firstAfter(from: Int, re: String): Long? { val rx = Regex(re); return log.drop(from).firstOrNull { rx.containsMatchIn(it.second) }?.first }
    private fun count(from: Int, re: String): Int { val rx = Regex(re); return log.drop(from).count { rx.containsMatchIn(it.second) } }

    private class Report(val raw: String) {
        private val f = Regex("""([a-z][a-z-]*)=(\S+)""").findAll(raw.removePrefix("link: report ")).associate { it.groupValues[1] to it.groupValues[2] }
        fun str(k: String) = f[k] ?: ""
        fun int(k: String) = f[k]?.toIntOrNull() ?: -1
        val shown get() = str("shown")
        val strokes get() = int("strokes")
        val connected get() = int("connected")
        val wet get() = int("wet")
        val loading get() = str("loading") == "true"
        val notes: List<String> get() = if (str("notes") == "-") emptyList() else str("notes").split(",").map { it.substringBefore("@") }
        override fun toString() = raw
    }

    private fun report(): Report {
        val m = mark()
        control("report")
        return Report(awaitLogLine(m, "^link: report ", 15_000).second)
    }

    private class MacStroke(val id: String, val x: Int, val y: Int)

    /** The Mac's live strokes in [note], each with its first stored sample. */
    private fun macStrokes(note: String): List<MacStroke> {
        val m = mark()
        control("sync-strokes $note")
        val line = awaitLogLine(m, "^link: sync-strokes note=$note", 45_000).second
        return line.substringAfter("ids=").split(",").filter { it.isNotBlank() }.map {
            val p = it.split(":"); MacStroke(p[0], p.getOrNull(1)?.toIntOrNull() ?: 0, p.getOrNull(2)?.toIntOrNull() ?: 0)
        }
    }

    private fun macDigest(id: String): String? {
        val m = mark()
        control("report")
        val line = awaitLogLine(m, "^link: sync-digests", 15_000).second
        return line.removePrefix("link: sync-digests").trim().split(",").filter { it.isNotBlank() }.map { it.split(":") }.firstOrNull { it[0] == id }?.let { it[1] }
    }

    // ------------------------------------------------------------------ the phone

    private inner class Phone(val store: Probe.FileStore, val version: Int = 1) {
        @Volatile var status = SessionStatus()
        @Volatile var target = StreamTarget()
        /** Every status with the time it arrived. */
        val statuses = CopyOnWriteArrayList<Pair<Long, SessionStatus>>()
        val logs = CopyOnWriteArrayList<Pair<Long, String>>()
        /** When each connection reached `welcome`, as the client saw it (not as the status shown to the owner, which is calmed). */
        val connects = CopyOnWriteArrayList<Long>()
        val feed = PenFeed()
        val ids = IdPool(::newId)
        val client: LinkClient
        val outDir = File(o.work, "out-${System.nanoTime()}")

        init {
            val config = ClientConfig("Syncr fault test", store, lib, Clock.SYSTEM, outDir, CorePreviewOut(), feed,
                ::encodeSamples, { logs += now() to it }, ids = ids, linkVersion = version, transport = Transport.TCP)
            client = LinkClient(config, { p -> Endpoint("127.0.0.1", proxy.port, p.macName, p.macId) }, object : ClientListener {
                override fun onStatus(status: SessionStatus) { this@Phone.status = status; statuses += now() to status }
                override fun onTarget(target: StreamTarget) { this@Phone.target = target }
                override fun onConnected(endpoint: Endpoint) { connects += now() }
            })
        }

        fun start() { client.notesChanged(lib.list(), null); client.start() }
        fun stop() = client.stop(wait = true)
        fun connected() = status.phase == Phase.CONNECTED
        /**
         * When the client first let go of a connection after [t]: its own `disconnect` line, or a status that is not CONNECTED (the
         * client before the calmed status had no such line). Null when it never did.
         */
        fun noticeAfter(t: Long): Long? = listOfNotNull(
            logs.firstOrNull { it.first >= t && it.second.startsWith("disconnect cause=") }?.first,
            statuses.firstOrNull { it.first >= t && it.second.phase != Phase.CONNECTED }?.first,
        ).minOrNull()
        fun connectAfter(t: Long): Long? = connects.firstOrNull { it > t }
    }

    private lateinit var phone: Phone
    private lateinit var note1: String
    private lateinit var note2: String
    private val sessions = ArrayList<Phone>()

    private fun brush() = Brush(BrushFamily.PEN, BrushSize.MEDIUM, InkColour.INK)

    // The two client calls below are new with part K; reached by name so this harness also runs against the client before K (the "before" table).
    private fun networkChanged(p: Phone) { try { p.client.javaClass.getMethod("networkChanged").invoke(p.client) } catch (_: ReflectiveOperationException) { } }
    private fun resumeFrame(p: Phone, frame: CanvasRect?) { try { p.client.javaClass.getMethod("resumeFrame", CanvasRect::class.java).invoke(p.client, frame) } catch (_: ReflectiveOperationException) { } }
    private fun keptStrokes(p: Phone): Int = try { p.client.javaClass.getMethod("getKeptStrokes").invoke(p.client) as Int } catch (_: ReflectiveOperationException) { -1 }

    // ------------------------------------------------------------------ pen

    /** A pad stroke of [samples] samples at 240 Hz in canvas space (Local mode): down now, up when the samples are played. */
    private inner class Stroke(val p: Phone, val samples: Int, val y: Float, val x0: Float = 600f) {
        @Volatile var started = false
        @Volatile var finished = false
        @Volatile var ended = false
        val pen = StreamPen(p.feed, p.ids, { p.target }, { brush() }, skipped = { why -> skipReasons += why })
        val skipReasons = CopyOnWriteArrayList<String>()

        fun play(onStart: () -> Unit = {}) {
            val start = System.nanoTime()
            var i = 0
            while (i < samples) {
                val batchEnd = minOf(i + 4, samples)
                val due = start + (batchEnd - 1) * 1_000_000_000L / 240
                while (System.nanoTime() < due) Thread.sleep(0, 200_000)
                while (i < batchEnd) {
                    val u = i / (samples - 1f)
                    val x = x0 + 1800f * u; val yy = y + 120f * sin(u * 2 * PI).toFloat(); val pr = 0.25f + 0.6f * sin(u * PI).toFloat()
                    val t = (start + i * 1_000_000_000L / 240) / 1_000_000
                    if (!started && i == 0) { started = pen.down(x, yy, pr, 0f, 0f, t, false, false, CoordinateSpace.CANVAS); onStart() } else if (started) pen.move(x, yy, pr, 0f, 0f, t)
                    i++
                }
                if (started) pen.flush()
            }
            if (started) ended = pen.up()
            finished = true
        }

        fun async(onStart: () -> Unit = {}): Thread = Thread({ play(onStart) }, "fault-pen").apply { isDaemon = true; start() }
    }

    // ------------------------------------------------------------------ setup

    private fun setup() {
        o.work.mkdirs()
        val root = File(o.work, "library").also { it.mkdirs() }
        o.fixture.listFiles()!!.filter { it.extension == "syncr" || it.name == "library.sqlite3" }.forEach { it.copyTo(File(root, it.name), overwrite = true) }
        lib = Probe.Library(root)
        storeFile = File(o.work, "pairing.properties")
        o.macLibrary.mkdirs()
        controlFile.writeText("")
        Thread(::tail, "fault-tail").apply { isDaemon = true; start() }
        startMac()
        proxy = FaultProxy("127.0.0.1", o.macPort)
        val notes = lib.list().sortedBy { it.id }
        check(notes.size >= 2) { "the fixture library needs two notes" }
        note1 = notes[0].id; note2 = notes[1].id
        phone = Phone(Probe.FileStore(storeFile, phoneId)); sessions += phone
        val m = mark()
        phone.start()
        phone.client.connect(Endpoint("127.0.0.1", proxy.port)) // not paired yet: it is told where the Mac is
        poll { phone.status.phase == Phase.NEEDS_CODE || phone.status.phase == Phase.CONNECTED }
        val code = Regex("""pairing-code (\d{4})""").find(awaitLogLine(m, "link: pairing-code", 20_000).second)!!.groupValues[1]
        phone.client.submitCode(code)
        check(poll(20_000) { phone.connected() }) { "the phone never connected (${phone.status})" }
        // The Mac wants both fixture notes, then the phone is in step.
        check(poll(40_000) { report().notes.containsAll(listOf(note1, note2)) }) { "the Mac never installed the fixture notes" }
        control("open $note1")
        check(poll(20_000) { report().let { it.shown == note1 && !it.loading } }) { "the Mac did not open $note1" }
        arm()
    }

    private fun check(ok: Boolean, why: () -> String) { if (!ok) throw Fail(why()) }

    /** The pad is open in Local mode with a known frame; the Mac shows [note1]. */
    private fun arm(p: Phone = phone) {
        p.client.setPad(true)
        p.client.setPadWish(PadWish(local = true, pics = false, aspect = 1.6))
        check(poll(15_000) { p.target.usable && p.target.frame != null }) { "no pad target with a frame (${p.target})" }
        // A small frame left of centre: the Mac's own default is centred across the view, so a frame that came back is not luck.
        p.client.requestFrame(CanvasRect(300.0, 950.0, 800.0, 500.0))
        check(poll(15_000) { p.target.frame?.let { it.w == 800.0 } == true }) { "the Mac did not take the frame (${p.target.frame})" }
    }

    /** The pen frame as it was just before a fault; the Mac clamps and moves frames, so "the same" is compared with that, not with what was asked for. */
    private var frameBefore: CanvasRect? = null

    private fun frameKept(p: Phone): String {
        val f = p.target.frame ?: return "none"
        val was = frameBefore ?: return "same"
        return if (f.w == was.w && f.h == was.h && f.x == was.x && f.y == was.y) "same" else "reset"
    }

    /** Everything is up again: connected, one live session on the Mac, the pad has a target. */
    private fun settle(p: Phone = phone, ms: Long = 60_000): Boolean = poll(ms) { p.connected() && report().connected == 1 && p.target.usable } && run { Thread.sleep(300); true }

    // ------------------------------------------------------------------ the table

    internal class Row(val name: String) {
        var phoneNoticeMs: Long? = null
        var macNoticeMs: Long? = null
        var welcomedMs: Long? = null
        var kept = false
        var stroke = "n/a"
        var sync = "n/a"
        var pad = "n/a"
        var frame = "n/a"
        /** Whole copies of the note the phone changed that crossed after the fault (a delta should do). */
        var whole = -1
        var extra = ""
    }

    private fun fmt(v: Long?) = v?.toString() ?: "-"

    private fun emit(r: Row) {
        val line = "FAULT %-34s | notice phone=%s mac=%s ms | welcomed=%s | lost: stroke=%s sync=%s pad=%s frame=%s pointer=n/a | whole-copies=%s%s | load=%s"
            .format(r.name, fmt(r.phoneNoticeMs), fmt(r.macNoticeMs), if (r.kept) "kept" else fmt(r.welcomedMs) + " ms", r.stroke, r.sync, r.pad, r.frame,
                if (r.whole < 0) "n/a" else r.whole.toString(), if (r.extra.isEmpty()) "" else " (${r.extra})", load())
        rows += line; print(line)
    }

    private fun enabled(name: String) = o.only.isEmpty() || o.only.any { name.startsWith(it) }

    /** A long run that only happens when asked for by name. */
    private fun explicit(name: String) = o.only.any { name.startsWith(it) }

    /** The Mac let go of a session: the line the session itself writes (after K), or the controller's older one (before K; absent when the session was replaced). */
    private val MAC_LET_GO = "^link: (disconnected phone=|disconnect cause=)"

    /**
     * One fault. [inject] starts it (returns when it is in place), [restore] puts the path back (a pure heal, or the Mac
     * started again). A pad stroke is drawn across the injection and a change is made on the phone during the fault; after
     * the path is back, the row says how long the link took to be welcomed and whether anything was lost.
     */
    private fun scenario(
        name: String, outageMs: Long, inject: () -> Unit, restore: () -> Unit, waitMs: Long = 90_000,
        /** The phone is told the network changed once the path is back, as Android's callback does for a Wi-Fi that returned. */
        kick: Boolean = false,
        /** How long after the path is back to wait for a connection that is going to be noticed as dead (the old limits were 12 and 20 s). */
        noticeWindowMs: Long = 8_000,
        /** What the row must show (K.1 to K.5); null when it does. Only run with [Options.check]. */
        expect: (Row) -> String? = { null },
    ) {
        if (!enabled(name)) return
        val row = Row(name)
        try {
            check(settle()) { "not settled before the fault (${phone.status})" }
            val baseStrokes = macStrokes(note1).size
            frameBefore = phone.target.frame
            val m = mark()
            val before = lib.digest(note2).digest.take(12)
            val stroke = Stroke(phone, samples = 200, y = 900f + (rows.size * 40))
            val pen = stroke.async()
            Thread.sleep(300)
            val faultAt = now()
            inject()
            // Change something on the phone during the fault: it must reach the Mac once the path is back.
            lib.addStroke(note2, newId(), channels(), brush())
            phone.client.notesChanged(lib.list(), null)
            val wantDigest = lib.digest(note2).digest.take(12)
            check(wantDigest != before) { "the phone's change did not change its digest" }
            Thread.sleep(maxOf(0, outageMs - (now() - faultAt)))
            val healAt = now()
            restore()
            if (kick) networkChanged(phone)
            // The phone's side: when it let go of the connection (never, if it was kept) and when it was welcomed again after that.
            var notice: Long? = null
            var welcomedAt: Long? = null
            val giveUp = now() + waitMs
            while (now() < giveUp) {
                notice = phone.noticeAfter(faultAt)
                if (notice != null) { welcomedAt = phone.connectAfter(notice); if (welcomedAt != null) break }
                else if (now() > healAt + noticeWindowMs) break // nothing noticed this long after the path was back: the connection was kept
                Thread.sleep(10)
            }
            row.phoneNoticeMs = notice?.let { it - faultAt }
            row.welcomedMs = welcomedAt?.let { maxOf(0L, it - healAt) }
            // The Mac's: it may have noticed a different way, or later.
            check(poll(30_000) { report().connected == 1 }) { "the Mac never showed the phone connected again" }
            row.macNoticeMs = firstAfter(m, MAC_LET_GO)?.let { it - faultAt }
            row.kept = notice == null && row.macNoticeMs == null
            if (notice == null && row.macNoticeMs != null) row.welcomedMs = firstAfter(m, "^link: connected phone=")?.let { maxOf(0L, it - healAt) }
            pen.join(5_000)
            // Losses: the stroke, the change, the pad and its frame. Each gets time to arrive.
            val strokeIn = poll(25_000, 50) { macStrokes(note1).size > baseStrokes }
            val strokes = macStrokes(note1).map { it.id }
            row.stroke = when {
                !stroke.started -> "refused(${stroke.skipReasons.firstOrNull() ?: "?"})"
                strokeIn && strokes.size == baseStrokes + 1 && strokes.toSet().size == strokes.size -> "kept"
                strokeIn -> "doubled(${strokes.size - baseStrokes})"
                else -> "LOST"
            }
            val t0 = now()
            row.sync = if (poll(30_000, 100) { macDigest(note2) == wantDigest }) "kept(${now() - t0}ms)" else "LOST"
            row.pad = if (firstAfter(m, "link: pad on=true") != null || row.kept) "kept" else "LOST"
            row.frame = frameKept(phone)
            row.whole = count(m, "link: file-in id=$note2 .*delta=false")
            if (o.check) expect(row)?.let { failed++; row.extra += (if (row.extra.isEmpty()) "" else "; ") + "CHECK FAILED: $it" }
        } catch (f: Fail) {
            failed++; row.extra = "FAIL: ${f.message}"
        } catch (e: Exception) {
            failed++; row.extra = "FAIL: ${e.javaClass.simpleName}: ${e.message}"
        }
        emit(row)
        proxy.heal(); proxy.cut()
        try { if (mac == null) startMac() } catch (_: Exception) { }
        settle()
    }

    private fun channels(): SampleChannels {
        val n = 60
        return SampleChannels(List(n) { 100f + 10 * it }, List(n) { 1500f + 20 * sin(it / 6f) }, List(n) { 0.5f }, List(n) { (it * 4).toUInt() }, null, null)
    }

    // ------------------------------------------------------------------ the scenarios

    /** Nothing was lost: the stroke in flight arrived once, the change on the phone reached the Mac, the pad and its frame came back. */
    private fun lossless(r: Row): String? = listOf("stroke" to r.stroke, "sync" to r.sync.substringBefore("("), "pad" to r.pad, "frame" to r.frame)
        .firstOrNull { it.second != "kept" && it.second != "same" }?.let { "${it.first} was ${it.second}" }
        ?: if (r.whole > 0) "the changed note crossed whole ${r.whole} time(s) where a delta would do" else null

    private fun within(what: String, actual: Long?, limit: Long): String? = if (actual == null) "$what: never" else if (actual > limit) "$what took $actual ms, limit $limit ms" else null

    fun run(): Boolean {
        try {
            setup()
            print("FAULT setup ok: Mac pid ${macPid()} port ${o.macPort}, proxy ${proxy.port}, load ${load()}")
            scenario("outage 1s (stall, refuse)", 1_000,
                inject = { proxy.setBoth(FaultProxy.Mode.STALL); proxy.accepting = FaultProxy.Accepting.REFUSE },
                restore = { proxy.heal() },
                expect = { r -> if (!r.kept) "a 1 s outage closed the connection" else lossless(r) })
            scenario("outage 5s (stall, refuse)", 5_000,
                inject = { proxy.setBoth(FaultProxy.Mode.STALL); proxy.accepting = FaultProxy.Accepting.REFUSE },
                restore = { proxy.heal() },
                expect = { r -> lossless(r) ?: within("welcomed again", if (r.kept) 0 else r.welcomedMs, 12_000) })
            scenario("outage 30s (stall, refuse)", 30_000,
                inject = { proxy.setBoth(FaultProxy.Mode.STALL); proxy.accepting = FaultProxy.Accepting.REFUSE },
                restore = { proxy.heal() },
                expect = { r -> lossless(r) ?: within("welcomed again (no network callback: the backoff's longest wait)", r.welcomedMs, 13_000) })
            scenario("outage 30s + network callback", 30_000,
                inject = { proxy.setBoth(FaultProxy.Mode.STALL); proxy.accepting = FaultProxy.Accepting.REFUSE },
                restore = { proxy.heal() }, kick = true,
                expect = { r -> lossless(r) ?: within("welcomed again", r.welcomedMs, 1_500) })
            scenario("blackhole (bytes vanish, no FIN)", 0,
                inject = { proxy.blackhole() },
                restore = { },
                waitMs = 120_000, noticeWindowMs = 45_000,
                expect = { r -> lossless(r) ?: within("phone noticed", r.phoneNoticeMs, 6_000) ?: within("Mac noticed", r.macNoticeMs, 6_500) })
            scenario("cut (FIN)", 300, inject = { proxy.cut() }, restore = { },
                expect = { r -> lossless(r) ?: within("phone noticed", r.phoneNoticeMs, 1_000) ?: within("welcomed again", r.welcomedMs, 1_500) })
            scenario("cut (RST)", 300, inject = { proxy.cut(rst = true) }, restore = { },
                expect = { r -> lossless(r) ?: within("phone noticed", r.phoneNoticeMs, 1_000) ?: within("welcomed again", r.welcomedMs, 1_500) })
            scenario("Mac app restart", 300,
                inject = { killMac() },
                restore = { startMac() },
                expect = { r -> within("welcomed again (no Bonjour on loopback: the backoff alone)", r.welcomedMs, 5_000) })
            scenario("Mac stopped 3 s (SIGSTOP)", 3_000,
                inject = { signal("STOP") },
                restore = { signal("CONT") },
                expect = { r -> if (!r.kept) "a Mac stalled for 3 s was taken for dead" else lossless(r) })
            scenario("phone to Mac stalled 3 s", 3_000,
                inject = { proxy.up.mode = FaultProxy.Mode.STALL },
                restore = { proxy.heal() },
                expect = { r -> if (!r.kept) "a phone stalled for 3 s was taken for dead" else lossless(r) })
            phoneLeavesAndReturns()
            lingeringSession()
            if (enabled("return-times")) returnTimes(24)
            if (enabled("calm-status")) calmStatus()
            if (enabled("stroke-stages")) strokeStages()
            if (enabled("wet-ink")) wetInkAfterCuts(10)
            if (explicit("random-cuts")) randomCuts(o.cuts)
            if (explicit("soak")) soak(o.soakSeconds)
        } catch (f: Fail) {
            failed++; print("FAULT FAIL setup: ${f.message}")
        } catch (e: Exception) {
            failed++; print("FAULT FAIL ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            tailing = false
            try { proxy.close() } catch (_: Exception) { }
            try { sessions.forEach { it.stop() } } catch (_: Exception) { }
            mac?.let { signal("CONT"); it.destroy() }
        }
        print(if (failed == 0) "FAULT TABLE DONE" else "FAULT TABLE DONE with $failed failure(s)")
        return failed == 0
    }

    // ------------------------------------------------------------------ K.2: coming back

    private fun pct(v: List<Long>, p: Double): Long = if (v.isEmpty()) -1 else v.sorted()[minOf(v.size - 1, (v.size * p).toInt())]
    private fun dist(v: List<Long>) = "n=${v.size} p50=${pct(v, 0.5)} p95=${pct(v, 0.95)} max=${v.maxOrNull() ?: -1} ms"

    /** Welcomed again, measured from the moment the path came back, over [n] cuts (FIN and RST) and a few blackholes told by the network callback. */
    private fun returnTimes(n: Int) {
        val name = "return-times"
        try {
            val cuts = ArrayList<Long>()
            repeat(n) { i ->
                check(settle()) { "not settled before cut $i" }
                val at = now()
                proxy.cut(rst = i % 2 == 1)
                val back = poll(20_000, 5) { phone.connectAfter(at) != null }
                check(back) { "cut $i: the phone never reconnected" }
                cuts += phone.connectAfter(at)!! - at
            }
            print("FAULT $name cut to welcomed again: ${dist(cuts)} | load=${load()}")
            if (o.check && pct(cuts, 0.95) > 1_000) { failed++; print("FAULT FAIL $name: p95 ${pct(cuts, 0.95)} ms is over 1000 ms") }
            val holes = ArrayList<Long>(); val notices = ArrayList<Long>()
            repeat(4) { i ->
                check(settle()) { "not settled before blackhole $i" }
                val at = now()
                proxy.blackhole()
                check(poll(30_000, 5) { phone.noticeAfter(at) != null }) { "blackhole $i: the phone never noticed" }
                val noticed = phone.noticeAfter(at)!!
                notices += noticed - at
                // The path is fine for new connections the moment the old one is called dead; the network callback adds nothing here but is exercised.
                networkChanged(phone)
                check(poll(30_000, 5) { phone.connectAfter(noticed) != null }) { "blackhole $i: the phone never reconnected" }
                holes += phone.connectAfter(noticed)!! - noticed
                proxy.heal()
            }
            print("FAULT $name blackhole to noticed: ${dist(notices)}; noticed to welcomed again: ${dist(holes)} | load=${load()}")
            if (o.check && pct(notices, 0.95) > 6_000) { failed++; print("FAULT FAIL $name: blackhole noticed in ${pct(notices, 0.95)} ms, over 6000 ms") }
            if (o.check && pct(holes, 0.95) > 1_000) { failed++; print("FAULT FAIL $name: blackhole return p95 ${pct(holes, 0.95)} ms is over 1000 ms") }
        } catch (f: Fail) { failed++; print("FAULT FAIL $name: ${f.message}") }
        settle()
    }

    /**
     * The phone draws a pad stroke right after a reconnect: its live preview must show on the Mac (the Mac's wet layer) while the pen is
     * still down, every time. A reconnect that comes at once must not leave the Mac in an old epoch, where it would drop every frame.
     */
    private fun wetInkAfterCuts(n: Int) {
        val name = "wet-ink"
        try {
            val seen = ArrayList<Long>()
            repeat(n) { i ->
                check(settle()) { "not settled before cut $i" }
                val at = now()
                proxy.cut(rst = i % 2 == 1)
                check(poll(20_000, 5) { phone.connectAfter(at) != null }) { "cut $i: the phone never reconnected" }
                // At once, without waiting for anything else: the Mac may still be finishing with the old connection.
                val stroke = Stroke(phone, 240, 1000f + i * 30)
                val started = now()
                val pen = stroke.async()
                val shown = poll(6_000, 10) { report().wet >= 1 }
                check(shown) { "cut $i: the live preview of a stroke drawn right after the reconnect never showed on the Mac" }
                seen += now() - started
                pen.join(10_000)
                check(poll(30_000, 20) { keptStrokes(phone) <= 0 }) { "cut $i: the stroke was never confirmed" }
            }
            print("FAULT PASS $name: after $n cuts the live preview showed on the Mac every time (first wet frame after pen-down: ${dist(seen)}) | load=${load()}")
        } catch (f: Fail) { failed++; print("FAULT FAIL $name: ${f.message}") }
        settle()
    }

    // ------------------------------------------------------------------ K.5: calm status

    private fun calmStatus() {
        val name = "calm-status"
        try {
            check(settle()) { "not settled" }
            // A drop that is over within two seconds is invisible to the owner.
            phone.statuses.clear()
            val at = now()
            proxy.cut()
            check(poll(10_000, 5) { phone.connectAfter(at) != null }) { "the phone never reconnected" }
            Thread.sleep(2_500)
            val quick = phone.statuses.map { it.second.phase }
            print("FAULT $name a 0.5 s drop: statuses shown to the owner = $quick | load=${load()}")
            if (o.check && quick.any { it != Phase.CONNECTED }) { failed++; print("FAULT FAIL $name: a drop that healed at once changed what the owner sees ($quick)") }
            // A long one shows "Reconnecting" once, and never flaps faster than once a second.
            check(settle()) { "not settled" }
            phone.statuses.clear()
            val longAt = now()
            proxy.blackhole(); proxy.accepting = FaultProxy.Accepting.REFUSE
            Thread.sleep(9_000)
            proxy.heal(); proxy.cut()
            check(poll(30_000, 10) { phone.statuses.any { it.second.phase == Phase.CONNECTED && it.first > longAt + 8_000 } }) { "the phone never came back" }
            val seen = phone.statuses.toList()
            val summary = seen.joinToString(" ") { "${it.first - longAt}:${it.second.phase}" }
            print("FAULT $name a 9 s outage: ms-after-start:status = $summary | load=${load()}")
            val gaps = seen.zipWithNext().map { it.second.first - it.first.first }
            if (o.check) {
                if (seen.count { it.second.phase == Phase.CONNECTING } > 1) { failed++; print("FAULT FAIL $name: more than one Reconnecting status ($summary)") }
                if (gaps.any { it < 950 }) { failed++; print("FAULT FAIL $name: the status changed twice within a second ($summary)") }
                if (seen.none { it.second.message?.contains("Reconnecting") == true }) { failed++; print("FAULT FAIL $name: no Reconnecting status in a 9 s outage ($summary)") }
            }
        } catch (f: Fail) { failed++; print("FAULT FAIL $name: ${f.message}") }
        settle()
    }

    // ------------------------------------------------------------------ K.4: a stroke survives a drop, once

    /** One stroke of [samples] samples at row [y]; [cutAfterMs] after pen-down [cut] runs. Returns how many strokes the Mac holds at that row afterwards. */
    private fun strokeThroughCut(y: Float, samples: Int, cutAfterMs: Long, cut: () -> Unit): Pair<Stroke, Int> {
        check(settle()) { "not settled" }
        val stroke = Stroke(phone, samples, y)
        val pen = stroke.async { Thread({ if (cutAfterMs >= 0) { Thread.sleep(cutAfterMs); cut() } }, "fault-cut").apply { isDaemon = true; start() } }
        pen.join(20_000)
        return stroke to 0
    }

    /** The Mac's strokes at row [y] (+/- 130 canvas units: the stroke is a gentle wave around it), counted after the link has settled. */
    private fun atRow(y: Int, offset: Int): List<MacStroke> = macStrokes(note1).filter { Math.abs(it.y - (y - offset)) <= 1 }

    private var yOffset: Int? = null

    private fun strokeStages() {
        val name = "stroke-stages"
        try {
            check(settle()) { "not settled" }
            // The offset between canvas units and what the Mac stores for this note and sheet, learnt from one stroke that crossed no fault.
            val probe = Stroke(phone, 120, 1300f); probe.play()
            check(poll(15_000, 50) { macStrokes(note1).any { Math.abs(it.y - 1300) < 400 } }) { "the probe stroke never arrived" }
            val seen = macStrokes(note1).last()
            yOffset = 1300 - seen.y
            val stages = listOf(
                "before the first frame" to { -1L },
                "in the first frames" to { 15L },
                "mid-stroke" to { 400L },
            )
            var row = 1500
            for ((stage, delay) in stages) {
                for (rst in listOf(false, true)) {
                    row += 90
                    val base = macStrokes(note1).size
                    val (stroke, _) = if (stage == "before the first frame") {
                        // The link is cut, and the pen comes down a moment later, while the phone does not know yet.
                        check(settle()) { "not settled" }
                        proxy.cut(rst)
                        val s = Stroke(phone, 200, row.toFloat()); s.play(); s to 0
                    } else strokeThroughCut(row.toFloat(), 200, delay()) { proxy.cut(rst) }
                    check(stroke.started) { "$stage: the pad refused the stroke (${stroke.skipReasons})" }
                    check(poll(30_000, 50) { macStrokes(note1).size > base && (keptStrokes(phone) <= 0) }) { "$stage${if (rst) " (RST)" else ""}: the stroke never arrived or stayed unconfirmed (Mac ${macStrokes(note1).size - base} new, kept ${keptStrokes(phone)})" }
                    Thread.sleep(300)
                    val now = macStrokes(note1)
                    val here = now.filter { Math.abs(it.y - (row - yOffset!!)) <= 1 }
                    check(now.size == base + 1 && here.size == 1) { "$stage${if (rst) " (RST)" else ""}: ${now.size - base} new strokes, ${here.size} at its place (expected exactly one)" }
                    print("FAULT PASS stroke $stage${if (rst) " (RST)" else ""}: once, at the mapped place")
                }
            }
            // After pen-up, before the confirmation: the confirmation is held back, the stroke reaches the Mac, then the link is cut.
            for (rst in listOf(false, true)) {
                row += 90
                check(settle()) { "not settled" }
                val base = macStrokes(note1).size
                proxy.blackhole(up = false, down = true) // the Mac's confirmation never reaches the phone
                val stroke = Stroke(phone, 120, row.toFloat()); stroke.play()
                check(stroke.ended) { "the stroke did not end" }
                check(poll(15_000, 20) { macStrokes(note1).size > base }) { "the stroke never reached the Mac before the cut" }
                proxy.cut(rst); proxy.heal()
                check(poll(30_000, 50) { keptStrokes(phone) <= 0 && phone.connectAfter(now() - 30_000) != null }) { "after pen-up${if (rst) " (RST)" else ""}: the phone never got the confirmation (kept ${keptStrokes(phone)})" }
                Thread.sleep(300)
                val now = macStrokes(note1)
                check(now.size == base + 1) { "after pen-up before the confirmation${if (rst) " (RST)" else ""}: the Mac holds ${now.size - base} new strokes, expected one" }
                print("FAULT PASS stroke after pen-up, before the confirmation${if (rst) " (RST)" else ""}: once")
            }
            // After the confirmation: nothing is sent again.
            row += 90
            check(settle()) { "not settled" }
            val base = macStrokes(note1).size
            val stroke = Stroke(phone, 120, row.toFloat()); stroke.play()
            check(poll(15_000, 20) { macStrokes(note1).size > base && keptStrokes(phone) <= 0 }) { "the stroke was not confirmed" }
            proxy.cut()
            check(poll(15_000, 20) { phone.connectAfter(now() - 15_000) != null && report().connected == 1 }) { "no reconnect" }
            Thread.sleep(800)
            check(macStrokes(note1).size == base + 1) { "a confirmed stroke was sent again and doubled" }
            print("FAULT PASS stroke after the confirmation: not sent again")
        } catch (f: Fail) { failed++; print("FAULT FAIL $name: ${f.message}") }
        settle()
    }

    // ------------------------------------------------------------------ K.4: 200 randomised cuts

    private fun randomCuts(n: Int) {
        val name = "random-cuts"
        try {
            check(settle()) { "not settled" }
            val rng = java.util.Random(20261122)
            val probe = Stroke(phone, 120, 1300f); probe.play()
            check(poll(15_000, 50) { macStrokes(note1).any { Math.abs(it.y - 1300) < 400 } }) { "the probe stroke never arrived" }
            val offset = 1300 - macStrokes(note1).last().y
            val base = macStrokes(note1).size
            val expectedRows = ArrayList<Int>()
            var cancelled = 0
            repeat(n) { i ->
                check(settle()) { "not settled before trial $i" }
                val row = 700 + (i % 60) * 25 + 7 * (i / 60)
                val samples = 40 + rng.nextInt(260)
                val kind = rng.nextInt(4)
                val after = (rng.nextInt(samples * 1000 / 240 + 500) - 60).toLong()
                val stroke = Stroke(phone, samples, row.toFloat())
                val pen = stroke.async {
                    Thread({
                        if (after > 0) Thread.sleep(after)
                        when (kind) {
                            0 -> proxy.cut(false)
                            1 -> proxy.cut(true)
                            2 -> { proxy.blackhole(); Thread.sleep(250 + rng.nextInt(400).toLong()); proxy.cut(); proxy.heal() }
                            else -> { proxy.setBoth(FaultProxy.Mode.STALL); proxy.accepting = FaultProxy.Accepting.REFUSE; Thread.sleep(300 + rng.nextInt(900).toLong()); proxy.cut(); proxy.heal() }
                        }
                    }, "fault-cut").apply { isDaemon = true; start() }
                }
                if (after < 0) { proxy.cut(kind == 1) }
                pen.join(30_000)
                if (stroke.ended) expectedRows += row else cancelled++
                // The next trial waits for the connection and for the stroke to be confirmed, so cuts do not pile onto one another.
                check(poll(40_000, 20) { keptStrokes(phone) <= 0 }) { "trial $i: the stroke was never confirmed (kept ${keptStrokes(phone)})" }
            }
            check(poll(30_000, 50) { keptStrokes(phone) <= 0 }) { "strokes left unconfirmed (${keptStrokes(phone)})" }
            Thread.sleep(500)
            val all = macStrokes(note1)
            val fresh = all.drop(base)
            val ids = all.map { it.id }
            val doubled = ids.size - ids.toSet().size
            val missing = expectedRows.filter { row -> fresh.none { Math.abs(it.y - (row - offset)) <= 1 } }
            val extra = fresh.size - expectedRows.size
            print("FAULT random-cuts: $n trials, ${expectedRows.size} strokes finished, $cancelled cancelled by the app; the Mac holds ${fresh.size} new, $doubled duplicate IDs, ${missing.size} missing, $extra extra | load=${load()}")
            if (doubled != 0 || missing.isNotEmpty() || extra != 0) { failed++; print("FAULT FAIL $name: strokes missing ${missing.take(8)}, extra $extra, duplicate IDs $doubled") }
            else print("FAULT PASS $name: every finished stroke is in the Mac's note exactly once, at the mapped place")
        } catch (f: Fail) { failed++; print("FAULT FAIL $name: ${f.message}") }
        settle()
    }

    // ------------------------------------------------------------------ K.8: soak

    private fun soak(seconds: Int) {
        val name = "soak"
        try {
            check(settle()) { "not settled" }
            val rng = java.util.Random(7)
            val probe = Stroke(phone, 120, 1300f); probe.play()
            check(poll(15_000, 50) { macStrokes(note1).any { Math.abs(it.y - 1300) < 400 } }) { "the probe stroke never arrived" }
            val offset = 1300 - macStrokes(note1).last().y
            val base = macStrokes(note1).size
            control("sync-bulk $note2 5") // one large note on the Mac: it goes to the phone under the faults
            val stop = java.util.concurrent.atomic.AtomicBoolean(false)
            val rows = java.util.concurrent.CopyOnWriteArrayList<Int>()
            val cancelled = java.util.concurrent.atomic.AtomicInteger()
            var row = 650
            // Pad ink, a stroke after a stroke.
            val ink = Thread({
                var i = 0
                while (!stop.get()) {
                    val y = 650 + (i % 100) * 21 + (i / 100) * 3; i++
                    val stroke = Stroke(phone, 50 + rng.nextInt(200), y.toFloat())
                    stroke.play()
                    if (stroke.ended) rows += y else if (stroke.started) cancelled.incrementAndGet()
                    Thread.sleep(80 + rng.nextInt(500).toLong())
                }
            }, "fault-soak-ink").apply { isDaemon = true; start() }
            // Typing on the phone: an edit of note2 every so often.
            var typed = 0
            val typing = Thread({
                while (!stop.get()) {
                    try { lib.typeText(note2, lib.documentTextOf(note2) + "w"); phone.client.notesChanged(lib.list(), null); typed++ } catch (_: Exception) { }
                    Thread.sleep(700 + rng.nextInt(1200).toLong())
                }
            }, "fault-soak-text").apply { isDaemon = true; start() }

            val notice = ArrayList<Long>(); val back = ArrayList<Long>(); var faults = 0; var kept = 0
            val end = now() + seconds * 1000L
            while (now() < end) {
                Thread.sleep(500 + rng.nextInt(3_500).toLong())
                if (now() >= end) break
                val m = mark()
                val at = now()
                val kind = rng.nextInt(7)
                val outage = 300L + rng.nextInt(7_000)
                var healAt = at
                when (kind) {
                    0 -> proxy.cut(false)
                    1 -> proxy.cut(true)
                    2 -> { proxy.blackhole() }
                    3 -> { proxy.setBoth(FaultProxy.Mode.STALL); proxy.accepting = FaultProxy.Accepting.REFUSE; Thread.sleep(outage); proxy.heal(); healAt = now() }
                    4 -> { proxy.up.mode = FaultProxy.Mode.STALL; Thread.sleep(minOf(outage, 3_000)); proxy.heal(); healAt = now() }
                    5 -> { proxy.down.delayMs = 400; proxy.down.bytesPerSec = 40_000; Thread.sleep(outage); proxy.heal(); healAt = now() }
                    else -> { signal("STOP"); Thread.sleep(minOf(outage, 3_000)); signal("CONT"); healAt = now() }
                }
                faults++
                // Wait for the link to be whole again before the next fault.
                val n = phone.noticeAfter(at)
                val deadline = now() + 40_000
                var cn: Long? = null
                while (now() < deadline) {
                    val noticed = phone.noticeAfter(at)
                    if (noticed != null) { cn = phone.connectAfter(noticed); if (cn != null) { notice += noticed - at; back += maxOf(0L, cn - maxOf(healAt, noticed)); break } }
                    else if (now() > healAt + 8_000) { kept++; break }
                    Thread.sleep(10)
                }
                if (n == null && cn == null && now() >= deadline) throw Fail("fault $faults ($kind): the link never came back")
            }
            stop.set(true); ink.join(30_000); typing.join(10_000)
            proxy.heal(); networkChanged(phone)
            check(settle(ms = 90_000)) { "the link never settled after the soak (${phone.status})" }
            check(poll(60_000, 50) { keptStrokes(phone) <= 0 }) { "strokes left unconfirmed after the soak (${keptStrokes(phone)})" }
            Thread.sleep(500)
            val all = macStrokes(note1)
            val fresh = all.drop(base)
            val doubled = all.size - all.map { it.id }.toSet().size
            val missing = rows.filter { y -> fresh.none { Math.abs(it.y - (y - offset)) <= 1 } }
            val extra = fresh.size - rows.size
            // The two libraries agree.
            val agreed = poll(60_000, 250) { macDigest(note1) == lib.digest(note1).digest.take(12) && macDigest(note2) == lib.digest(note2).digest.take(12) }
            val macAlive = mac?.isAlive == true
            val noPanic = since(0).none { it.second.contains("panic") || it.second.contains("Fatal error") } && phone.logs.none { it.second.contains("link session failed") }
            print("FAULT soak ${seconds}s: $faults faults ($kept kept the connection), ${rows.size} strokes drawn ($cancelled cancelled by the app), $typed phone edits; Mac holds ${fresh.size} new strokes, $doubled duplicate IDs, ${missing.size} missing, $extra extra; libraries ${if (agreed) "agree" else "DIFFER"}; Mac ${if (macAlive) "alive" else "DEAD"}, panics ${if (noPanic) "none" else "FOUND"}")
            print("FAULT soak notice (fault to phone noticed): ${dist(notice)}; return (the later of the path being back and the fault being noticed, to welcomed again; no network callback, so a long outage waits out the backoff): ${dist(back)} | load=${load()}")
            if (doubled != 0 || missing.isNotEmpty() || extra != 0 || !agreed || !macAlive || !noPanic) { failed++; print("FAULT FAIL $name: missing ${missing.take(8)}, extra $extra, duplicates $doubled, agreed $agreed, alive $macAlive, no panic $noPanic") }
            else print("FAULT PASS $name: both libraries converged, every stroke once, nothing panicked")
        } catch (f: Fail) { failed++; print("FAULT FAIL $name: ${f.message}") }
        settle()
    }

    /** The app goes to the background (the client stops) and returns: time from start to welcome, and what the pad kept. */
    private fun phoneLeavesAndReturns() {
        val name = "phone leaves and returns (3 s)"
        if (!enabled(name)) return
        val row = Row(name)
        try {
            check(settle()) { "not settled" }
            frameBefore = phone.target.frame
            val m = mark()
            phone.stop()
            row.macNoticeMs = firstAfter(m, "^link: disconnected phone=")?.let { it - now() }
            check(poll(10_000) { report().connected == 0 }) { "the Mac still shows the phone after it left" }
            Thread.sleep(3_000)
            val next = Phone(Probe.FileStore(storeFile, phoneId)); sessions += next
            val returnAt = now()
            // What the app's link controller does when it makes a client again: the pad it had, and the frame it had, then start.
            resumeFrame(next, frameBefore)
            next.client.setPad(true); next.client.setPadWish(PadWish(local = true, pics = false, aspect = 1.6))
            next.start()
            check(poll(60_000) { next.connected() }) { "the returning phone never connected" }
            row.welcomedMs = now() - returnAt
            phone = next
            row.pad = if (poll(10_000) { next.target.usable }) "kept" else "LOST"
            poll(5_000) { next.target.frame != null }
            Thread.sleep(500) // the frame is asked for after the pad mode: give the Mac's answer a moment
            row.frame = frameKept(next)
            row.extra = "welcomed counted from the app's start; the pad and its frame are re-asked by the app's link controller"
        } catch (f: Fail) { failed++; row.extra = "FAIL: ${f.message}" } catch (e: Exception) { failed++; row.extra = "FAIL: ${e.javaClass.simpleName}: ${e.message}" }
        emit(row)
        try { arm() } catch (_: Exception) { }
    }

    /** The phone's old connection is dead but the Mac has not noticed; the same phone connects again. */
    private fun lingeringSession() {
        val name = "same phone again, old session lingers"
        if (!enabled(name)) return
        val row = Row(name)
        try {
            check(settle()) { "not settled" }
            frameBefore = phone.target.frame
            val m = mark()
            // The old connection goes silent in both directions without a FIN: the phone app was killed or its Wi-Fi changed address.
            proxy.blackhole()
            val old = phone
            val killedAt = now()
            old.stop()
            proxy.heal()
            val next = Phone(Probe.FileStore(storeFile, phoneId)); sessions += next
            val startAt = now()
            next.client.setPad(true); next.client.setPadWish(PadWish(local = true, pics = false, aspect = 1.6))
            next.start()
            check(poll(60_000) { next.connected() }) { "the new session never connected" }
            row.welcomedMs = now() - startAt
            val oldGone = poll(60_000) { report().connected == 1 }
            row.macNoticeMs = firstAfter(m, MAC_LET_GO)?.let { it - startAt }
            row.extra = "welcomed counted from the new session's start; Mac drops the old one ${fmt(row.macNoticeMs)} ms after it; old-session ${if (oldGone) "gone" else "STILL THERE"}; killed at -${startAt - killedAt} ms"
            phone = next
            row.pad = if (poll(10_000) { next.target.usable }) "kept" else "LOST"
        } catch (f: Fail) { failed++; row.extra = "FAIL: ${f.message}" } catch (e: Exception) { failed++; row.extra = "FAIL: ${e.javaClass.simpleName}: ${e.message}" }
        emit(row)
        proxy.heal(); proxy.cut()
        try { settle(); arm() } catch (_: Exception) { }
    }
}
