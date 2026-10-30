package dev.syncr.prototype.link

import dev.syncr.core.Brush
import dev.syncr.core.BrushFamily
import dev.syncr.core.BrushSize
import dev.syncr.core.InkColour
import dev.syncr.core.SampleChannels
import dev.syncr.core.encodeSamples
import dev.syncr.core.newId
import dev.syncr.prototype.notes.GeneratedCoreAdapter
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.sin

/**
 * The joined test: scripted phone scenarios (the phone's real link client, a host JVM process, the core's real `export_copy`)
 * against the real Mac app over loopback. The Mac was started with `SYNCR_MAC_DEBUG_REPLAY=1` and
 * `SYNCR_MAC_DEBUG_CONTROL=<file>`; this reads its stderr log and sends it commands (see the Mac's `DebugControl.swift`:
 * `report`, `open`, `view`, ...). Every step prints `JOIN PASS <name>` or `JOIN FAIL <name>: <why>`; the run is green only
 * if none failed. Assertions use the Mac's own state (the `report` line), never sleeps; the only waits that are windows
 * rather than conditions are the ones that prove something did *not* happen, and they say so.
 */
class JoinTest(private val o: Options, private val print: (String) -> Unit) {
    class Options(
        val host: String,
        val port: Int,
        val macLog: File,
        val macControl: File,
        val macLibrary: File,
        val fixture: File,
        val timeoutMs: Long = 40_000,
    )

    private class Fail(message: String) : Exception(message)

    private val endpoint = Endpoint(o.host, o.port)
    private val phoneId = "5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c"
    private val work = Files.createTempDirectory("syncr-join-").toFile()
    private var failed = 0
    private lateinit var lib: Probe.Library
    private lateinit var storeFile: File
    private val sessions = ArrayList<Session>()

    // ------------------------------------------------------------------ the Mac, as seen from here

    private fun macLines(): List<String> = try { o.macLog.readLines() } catch (_: IOException) { emptyList() }
    private fun mark() = macLines().size
    private fun macSince(from: Int) = macLines().drop(from)

    private fun poll(ms: Long = o.timeoutMs, every: Long = 25, until: () -> Boolean): Boolean {
        val end = System.nanoTime() + ms * 1_000_000
        while (true) {
            if (until()) return true
            if (System.nanoTime() > end) return false
            Thread.sleep(every)
        }
    }

    /** The first Mac log line after [from] that matches [re]; throws if none arrives in time. */
    private fun awaitLog(from: Int, re: String, ms: Long = o.timeoutMs): String {
        val rx = Regex(re)
        var found: String? = null
        if (!poll(ms) { found = macSince(from).firstOrNull { rx.containsMatchIn(it) }; found != null }) throw Fail("no Mac log line /$re/ within ${ms / 1000}s")
        return found!!
    }

    private fun macHas(from: Int, re: String) = macSince(from).any { Regex(re).containsMatchIn(it) }

    private fun control(command: String) { o.macControl.appendText(command + "\n") }

    private class Report(val raw: String) {
        private val f = Regex("""([a-z][a-z-]*)=(\S+)""").findAll(raw.removePrefix("link: report ")).associate { it.groupValues[1] to it.groupValues[2] }
        fun str(k: String) = f[k] ?: ""
        fun int(k: String) = f[k]?.toIntOrNull() ?: -1
        val shown get() = str("shown")
        val strokes get() = int("strokes")
        val wet get() = int("wet")
        val loading get() = str("loading") == "true"
        val seq get() = str("seq").toLongOrNull() ?: -1
        val pixels get() = int("ink-pixels")
        val connected get() = int("connected")
        val notes: Map<String, Pair<Long, Boolean>> get() =
            if (str("notes") == "-") emptyMap() else str("notes").split(",").associate {
                val (id, rest) = it.split("@"); id to (rest.substringBefore(":").toLong() to it.endsWith(":mac"))
            }
        fun lastStroke(): List<Float>? = str("last-stroke").takeIf { it != "none" }?.split(",")?.map { it.toFloat() }
        override fun toString() = raw
    }

    private fun report(): Report {
        val m = mark()
        control("report")
        return Report(awaitLog(m, "^link: report ", 10_000))
    }

    private fun awaitReport(why: String, ms: Long = o.timeoutMs, cond: (Report) -> Boolean): Report {
        var last: Report? = null
        if (!poll(ms, every = 50) { last = report(); cond(last!!) }) throw Fail("$why; last Mac report: $last")
        return last!!
    }

    private fun macNote(id: String): Report {
        control("open $id")
        return awaitReport("the Mac did not show note $id") { it.shown == id && !it.loading }
    }

    // ------------------------------------------------------------------ the phone

    /** A socket that can be cut on cue: now, or after so many more bytes written (to land in the middle of a file transfer). */
    private class CuttingSocket : Socket() {
        private val written = AtomicLong()
        @Volatile private var cutAt = Long.MAX_VALUE
        fun armIn(bytes: Long) { cutAt = written.get() + bytes }
        fun cutNow() { try { close() } catch (_: IOException) { } }
        override fun getOutputStream(): OutputStream {
            val inner = super.getOutputStream()
            return object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (written.get() >= cutAt) { cutNow(); throw IOException("cut on cue") }
                    inner.write(b, off, len)
                    written.addAndGet(len.toLong())
                }
                override fun flush() = inner.flush()
            }
        }
    }

    private class CuttingTransport : Transport {
        @Volatile var current: CuttingSocket? = null
        override fun connect(endpoint: Endpoint, timeoutMs: Int): Socket = CuttingSocket().also {
            it.tcpNoDelay = true
            it.connect(InetSocketAddress(endpoint.host, endpoint.port), timeoutMs)
            current = it
        }
    }

    /** One link client with everything the scenarios want to see of it. */
    private inner class Session(val store: Probe.FileStore) {
        @Volatile var status = SessionStatus()
        @Volatile var target = StreamTarget()
        val phases = CopyOnWriteArrayList<Phase>()
        val statuses = CopyOnWriteArrayList<SessionStatus>()
        val wanted = CopyOnWriteArrayList<String>()
        val delivered = CopyOnWriteArrayList<Pair<String, Long>>()
        val logs = CopyOnWriteArrayList<String>()
        val transport = CuttingTransport()
        val feed = PenFeed()
        val ids = IdPool(::newId)
        @Volatile var cutOnWant: Long = -1
        val client: LinkClient

        init {
            val config = ClientConfig("Syncr join test", store, lib, Clock.SYSTEM, File(work, "out-${sessions.size}"), CorePreviewOut(), feed,
                ::encodeSamples, { logs += it }, transport = transport, ids = ids)
            client = LinkClient(config, { p -> endpoint.copy(name = p.macName, macId = p.macId) }, object : ClientListener {
                override fun onStatus(status: SessionStatus) {
                    this@Session.status = status; statuses += status
                    if (phases.lastOrNull() != status.phase) phases += status.phase
                }
                override fun onTarget(target: StreamTarget) { this@Session.target = target }
                override fun onWanted(ids: List<String>) {
                    wanted += ids
                    if (cutOnWant >= 0) { transport.current?.armIn(cutOnWant); cutOnWant = -1 }
                }
                override fun onDelivered(id: String, seq: Long) { delivered += id to seq }
            })
            sessions += this
        }

        fun start() { client.notesChanged(lib.list(), null); client.start() }
        fun stop() = client.stop(wait = true)

        fun awaitPhase(p: Phase, ms: Long = o.timeoutMs) {
            if (!poll(ms) { status.phase == p }) throw Fail("phone stayed in ${status.phase} (${status.message}), wanted $p")
        }
    }

    private fun newStore() = Probe.FileStore(storeFile, phoneId)

    private fun brush(family: BrushFamily = BrushFamily.PEN, colour: InkColour = InkColour.BLUE) = Brush(family, BrushSize.MEDIUM, colour)

    /** Plays [count] samples at 240 Hz in real time, 4 to a batch (the S Pen's 60 batches a second). */
    private fun play(count: Int, from: Int = 0, total: Int = count, sample: (i: Int, timeMs: Long, endOfBatch: Boolean) -> Unit) {
        val start = System.nanoTime()
        var i = 0
        while (i < count) {
            val batchEnd = minOf(i + 4, count)
            val due = start + (batchEnd - 1) * 1_000_000_000L / 240
            while (System.nanoTime() < due) Thread.sleep(0, 200_000)
            while (i < batchEnd) { sample(from + i, (start + i * 1_000_000_000L / 240) / 1_000_000, i == batchEnd - 1); i++ }
        }
    }

    private fun channels(points: List<Triple<Float, Float, Float>>) = SampleChannels(
        points.map { it.first }, points.map { it.second }, points.map { it.third },
        points.indices.map { (it * 1000L / 240).toUInt() }, null, null)

    private fun curve(i: Int, n: Int = 240): Triple<Float, Float, Float> {
        val u = i / (n - 1f)
        return Triple(600f + 1800f * u, 1000f + 250f * sin(u * 2 * PI).toFloat(), 0.2f + 0.7f * sin(u * PI).toFloat())
    }

    // ------------------------------------------------------------------ the script

    private fun step(name: String, body: () -> Unit) {
        try { body(); print("JOIN PASS $name") }
        catch (f: Fail) { failed++; print("JOIN FAIL $name: ${f.message}") }
        catch (e: Exception) { failed++; print("JOIN FAIL $name: ${e.javaClass.simpleName}: ${e.message}") }
    }

    private fun check(ok: Boolean, why: () -> String) { if (!ok) throw Fail(why()) }

    fun run(): Boolean {
        val root = File(work, "library").also { it.mkdirs() }
        o.fixture.listFiles()!!.filter { it.extension == "syncr" || it.name == "library.sqlite3" }.forEach { it.copyTo(File(root, it.name)) }
        lib = Probe.Library(root)
        storeFile = File(work, "pairing.properties")
        try {
            val notes = lib.list().sortedBy { it.id }
            check(notes.size >= 2) { "the fixture library needs two notes" }
            val note1 = notes[0].id
            val note2 = notes[1].id
            val seq1 = notes[0].seq
            val seq2 = notes[1].seq
            val sheet1 = lib.sheetOf(note1)
            print("JOIN library notes=${notes.joinToString(",") { "${it.id}@${it.seq}" }}")
            var s = Session(newStore())
            var code = ""

            step("a1 first pairing: the Mac shows four digits, a wrong code is refused with tries left") {
                val m = mark()
                s.start(); s.client.connect(endpoint)
                s.awaitPhase(Phase.NEEDS_CODE)
                val line = awaitLog(m, """link: pairing-code (\d{4}) phone="Syncr join test"""")
                code = Regex("""pairing-code (\d{4})""").find(line)!!.groupValues[1]
                val wrong = "%04d".format((code.toInt() + 1) % 10_000)
                s.client.submitCode(wrong)
                check(poll { s.status.triesLeft == 4 }) { "no 'denied left=4' after a wrong code (status ${s.status})" }
                check(s.status.phase == Phase.NEEDS_CODE) { "left NEEDS_CODE after a wrong code" }
                check(!macHas(m, "link: paired ")) { "the Mac paired on a wrong code" }
            }
            step("a2 first pairing: the right code pairs, the phone stores the token, the Mac connects it") {
                val m = mark()
                s.client.submitCode(code)
                s.awaitPhase(Phase.CONNECTED)
                awaitLog(m, """link: paired phone="Syncr join test" id=$phoneId""")
                awaitLog(m, """link: connected phone="Syncr join test" id=$phoneId epoch=""")
                check(s.store.pairing() != null) { "the phone did not store the pairing" }
                check(Probe.FileStore(storeFile, phoneId).pairing() != null) { "the pairing did not reach the phone's store file" }
            }
            step("c1 initial sync: both fixture notes install on the Mac at the phone's sequence") {
                for (n in notes) awaitLog(0, """link: note-installed id=${n.id} seq=${n.seq}$""")
                check(s.wanted.toSet() == notes.map { it.id }.toSet()) { "the Mac wanted ${s.wanted}" }
                check(poll { s.delivered.size >= notes.size }) { "the phone saw ${s.delivered.size} have messages" }
                val r = report()
                for (n in notes) check(r.notes[n.id]?.first == n.seq && r.notes[n.id]?.second == false) { "Mac library ${r.str("notes")} does not hold ${n.id}@${n.seq} as a phone copy" }
            }
            step("c2 the synced notes render on the Mac with every stroke") {
                for (n in notes) {
                    val expect = lib.strokeCount(n.id)
                    val r = macNote(n.id)
                    check(r.strokes == expect) { "note ${n.id}: the Mac renders ${r.strokes} strokes, the phone has $expect" }
                    check(expect > 0) { "fixture note ${n.id} has no strokes" }
                    val b = r.lastStroke()!!
                    control("view 0 ${maxOf(0f, b[1] - 100)} 1")
                    val seen = awaitReport("note ${n.id} drew no pixels") { it.pixels > 0 && it.str("view").startsWith("0.0,") }
                    print("JOIN info c2 note=${n.id} strokes=${r.strokes} ink-pixels=${seen.pixels} seq=${r.seq}")
                }
            }

            step("b reconnect with the stored token: no code is asked, nothing is re-sent") {
                s.stop()
                val m = mark()
                s = Session(newStore())
                s.start(); s.client.connect(endpoint)
                s.awaitPhase(Phase.CONNECTED)
                awaitLog(m, """link: connected phone="Syncr join test"""")
                check(Phase.NEEDS_CODE !in s.phases) { "the phone asked for a code again (phases ${s.phases})" }
                check(!macHas(m, "link: pairing-code")) { "the Mac asked for a code on a stored token" }
                // A window, not a condition: nothing is meant to follow, so wait long enough for a `want` to have arrived.
                Thread.sleep(1_500)
                check(s.wanted.isEmpty()) { "the Mac asked for ${s.wanted} although it holds them" }
                check(!macHas(m, "note-installed")) { "a note was installed again" }
            }

            step("d changed note re-syncs (new sequence, one more stroke); the unchanged note is not re-sent") {
                s.wanted.clear()
                val before = report().notes
                val m = mark()
                lib.addStroke(note1, newId(), channels(List(240) { curve(it) }), brush(colour = InkColour.INK))
                val now = lib.list()
                val newSeq = now.first { it.id == note1 }.seq
                check(newSeq > seq1) { "the phone's sequence did not rise ($seq1 to $newSeq)" }
                s.client.notesChanged(now, null)
                awaitLog(m, """link: note-installed id=$note1 seq=$newSeq$""")
                check(poll { s.delivered.any { it.first == note1 && it.second == newSeq } }) { "the phone never saw have for $note1@$newSeq" }
                check(s.wanted == listOf(note1)) { "the Mac wanted ${s.wanted}, expected only $note1" }
                check(!macHas(m, "note-installed id=$note2")) { "the unchanged note was installed again" }
                check(before[note2]?.first == seq2 && report().notes[note2]?.first == seq2) { "the unchanged note's copy moved" }
                val r = macNote(note1)
                check(r.seq == newSeq) { "the Mac's copy is at ${r.seq}, expected $newSeq" }
                check(r.strokes == lib.strokeCount(note1)) { "the Mac has ${r.strokes} strokes, the phone ${lib.strokeCount(note1)}" }
            }

            step("e mirror: a previewed stroke, then its saved copy lands: ink is neither lost nor doubled") {
                val m = mark()
                s.client.setViewing(note1)
                awaitLog(m, "link: viewing id=$note1")
                val base = awaitReport("the Mac did not follow the phone to $note1") { it.shown == note1 && !it.loading && it.wet == 0 }
                val n = base.strokes
                val mirror = CanvasMirror(s.feed, s.ids)
                val id = mirror.begin(note1, sheet1, brush(), 1f, 0f, 0f, hasTilt = false, hasOrientation = false)
                    ?: throw Fail("the link refused to start a mirrored stroke (${s.status})")
                val points = List(240) { curve(it) }
                play(240) { i, t, last -> val p = points[i]; mirror.sample(p.first, p.second, p.third, 0f, 0f, t); if (last) mirror.flush() }
                mirror.end()
                awaitReport("the live preview never reached the Mac's wet layer") { it.wet == 1 && it.strokes == n }
                val line = s.logs.size
                // The phone saves the stroke under the ID it previewed, and the note's copy follows 1.5 s later (note still open).
                lib.addStroke(note1, id, channels(points), brush())
                val newSeq = lib.list().first { it.id == note1 }.seq
                val installMark = mark()
                s.client.notesChanged(lib.list(), note1)
                // Sample the Mac through the landing: every report must show the stroke exactly once, as wet ink or as saved ink.
                var landed: Report? = null
                val ok = poll {
                    val r = report()
                    check(!(r.strokes == n && r.wet == 0)) { "ink lost: $r" }
                    check(!(r.strokes == n + 1 && r.wet > 0)) { "ink doubled: $r" }
                    if (r.strokes == n + 1 && r.wet == 0 && r.seq == newSeq && !r.loading) { landed = r; true } else false
                }
                check(ok) { "the new copy never replaced the preview; last $landed" }
                awaitLog(installMark, """note-installed id=$note1 seq=$newSeq$""")
                print("JOIN info e strokes ${n} -> ${landed!!.strokes}, wet ${landed!!.wet}")
                s.client.notesChanged(lib.list(), null)
                s.client.setViewing(null)
            }

            step("f1 Tablet Stream: pad on with no Mac note open creates one and offers it as the target") {
                val m = mark()
                val before = report()
                check(before.notes.values.none { it.second }) { "a Mac-created note already exists" }
                s.client.setPad(true)
                awaitLog(m, "link: pad on=true")
                check(poll { s.target.usable }) { "the Mac gave no writable target (${s.target})" }
                val r = awaitReport("the Mac did not show its new note") { it.shown == s.target.noteId && !it.loading }
                check(r.notes[s.target.noteId]?.second == true) { "the target is not a Mac-created note: ${r.str("notes")}" }
                check(r.notes.values.count { it.second } == 1) { "pad on created ${r.notes.values.count { it.second }} notes" }
            }
            step("f2 Tablet Stream: a pen stroke and a highlighter stroke are saved in the Mac note") {
                val target = s.target
                for ((i, family) in listOf(BrushFamily.PEN, BrushFamily.HIGHLIGHTER).withIndex()) {
                    val m = mark()
                    val pen = StreamPen(s.feed, s.ids, { s.target }, { brush(family, InkColour.INK) })
                    var started = false
                    play(240) { k, t, last ->
                        val u = k / 239f
                        val x = 600f + 3600f * u; val y = 800f + 600f * i + 500f * sin(u * 3 * PI).toFloat(); val p = 0.25f + 0.6f * sin(u * PI).toFloat()
                        if (!started) started = pen.down(x, y, p, 0f, 0f, t, false, false) else pen.move(x, y, p, 0f, 0f, t)
                        if (last && started) pen.flush()
                    }
                    check(started) { "the pad could not start a stroke: ${pen.problem()}" }
                    pen.up()
                    awaitLog(m, "link: stroke-saved note=${target.noteId} stroke=")
                    awaitReport("$family: expected ${i + 1} saved strokes and no wet ink") { it.shown == target.noteId && it.strokes == i + 1 && it.wet == 0 }
                }
            }
            step("f3 Tablet Stream: a cancelled stroke leaves nothing") {
                val target = s.target
                val m = mark()
                val pen = StreamPen(s.feed, s.ids, { s.target }, { brush(colour = InkColour.INK) })
                var started = false
                play(80) { k, t, last ->
                    val x = 700f + 20f * k; val y = 2400f
                    if (!started) started = pen.down(x, y, 0.5f, 0f, 0f, t, false, false) else pen.move(x, y, 0.5f, 0f, 0f, t)
                    if (last) pen.flush()
                }
                check(started) { "the pad could not start a stroke: ${pen.problem()}" }
                awaitReport("the pad stroke was never drawn live") { it.wet == 1 }
                pen.cancel()
                awaitReport("the cancelled stroke stayed on the Mac's wet layer") { it.wet == 0 }
                // A window: nothing is meant to follow the cancel, so give a stray commit time to show up.
                Thread.sleep(1_000)
                val r = report()
                check(r.strokes == 2 && r.wet == 0 && r.shown == target.noteId) { "after a cancel the Mac shows $r" }
                check(!macHas(m, "stroke-saved")) { "a cancelled stroke was saved" }
            }
            step("f4 Tablet Stream: with the Mac view scrolled and zoomed the stroke lands at the right canvas place") {
                val target = s.target
                val rev = target.rev
                control("view 1200 800 2")
                check(poll { s.target.rev > rev }) { "the Mac sent no new target revision after the view changed (rev ${s.target.rev})" }
                val m = mark()
                val pen = StreamPen(s.feed, s.ids, { s.target }, { brush(colour = InkColour.INK) })
                var started = false
                play(120) { k, t, last ->
                    val u = k / 119f
                    val x = 800f + 2800f * u; val y = 600f + 1800f * u
                    if (!started) started = pen.down(x, y, 0.6f, 0f, 0f, t, false, false) else pen.move(x, y, 0.6f, 0f, 0f, t)
                    if (last && started) pen.flush()
                }
                check(started) { "the pad could not start a stroke: ${pen.problem()}" }
                pen.up()
                awaitLog(m, "link: stroke-saved note=${target.noteId} stroke=")
                val r = awaitReport("the third stroke never showed") { it.strokes == 3 && it.wet == 0 }
                val b = r.lastStroke()!!
                // view-relative (x, y) over zoom 2 from (1200, 800): x 1600..3000, y 1100..2000, plus the pen's width.
                val want = listOf(1600f, 1100f, 3000f, 2000f)
                val off = b.zip(want).map { (a, w) -> Math.abs(a - w) }
                print("JOIN info f4 stroke bounds=${b.joinToString(",")} expected~${want.joinToString(",")} off=${off.joinToString(",") { "%.1f".format(it) }}")
                check(off.all { it <= 40f }) { "the stroke's canvas bounds ${b} are not near $want" }
                control("view 0 0 1")
            }
            step("f5 Tablet Stream: pad off, and pad on again with a Mac note open creates no second note") {
                val m = mark()
                s.client.setPad(false)
                awaitLog(m, "link: pad on=false")
                s.client.setPad(true)
                awaitLog(m, "link: pad on=true")
                val r = report()
                check(r.notes.values.count { it.second } == 1) { "the Mac now has ${r.notes.values.count { it.second }} Mac-created notes" }
                s.client.setPad(false)
            }

            step("g1 connection dropped mid-file-transfer: no partial note, the transfer completes after reconnect") {
                // Make note 2 large: six long, smooth strokes of 8,000 samples each (a hand-written page of dense ink).
                repeat(6) { k ->
                    val pts = List(8_000) { i ->
                        val u = i / 7_999f
                        Triple(300f + 4200f * u, 400f + 300f * k + 120f * sin(u * 60f * PI.toFloat()), 0.5f + 0.3f * sin(u * 9f))
                    }
                    lib.addStroke(note2, newId(), channels(pts), brush())
                }
                val now = lib.list()
                val newSeq = now.first { it.id == note2 }.seq
                val size = File(work, "probe-size.syncr").also { f -> lib.exportCopy(note2, f) }.let { val n = it.length(); it.delete(); n }
                print("JOIN info g1 note2 export=$size bytes seq $seq2 -> $newSeq")
                check(size > 400_000) { "the big note is only $size bytes, too small to cut mid-transfer" }
                val m = mark()
                s.wanted.clear(); s.delivered.clear()
                s.cutOnWant = 150_000
                val connections = macSince(0).count { it.contains("link: connected phone=") }
                s.client.notesChanged(now, null)
                val aborted = awaitLog(m, """link: transfer-aborted id=$note2 received=(\d+) of=(\d+) partial-file-removed=""")
                print("JOIN info g1 $aborted")
                check(aborted.endsWith("partial-file-removed=true")) { "the Mac kept the partial file: $aborted" }
                val staging = File(o.macLibrary, ".incoming-link")
                // Between the abort and the next connection nothing may be installed (what follows is the retry, after reconnect).
                val afterAbort = macSince(m).dropWhile { !it.contains("transfer-aborted id=$note2") }.drop(1)
                check(afterAbort.takeWhile { !it.contains("link: connected phone=") }.none { it.contains("note-installed id=$note2") }) { "the note was installed from a cut transfer" }
                // The phone reconnects by itself (1 s backoff) and offers the note again.
                awaitLog(m, """link: note-installed id=$note2 seq=$newSeq$""")
                check(macSince(0).count { it.contains("link: connected phone=") } > connections) { "no second connection was made" }
                s.awaitPhase(Phase.CONNECTED)
                check(poll { s.delivered.any { it.first == note2 && it.second == newSeq } }) { "the phone never saw have for $note2@$newSeq" }
                val r = macNote(note2)
                check(r.seq == newSeq && r.strokes == lib.strokeCount(note2)) { "after the retry the Mac has seq ${r.seq}, ${r.strokes} strokes; phone ${lib.strokeCount(note2)}" }
                check(staging.listFiles().orEmpty().isEmpty()) { "staging not empty after completion" }
            }

            step("g2 connection dropped mid-mirrored-stroke: no ghost ink, the link recovers and mirrors again") {
                control("open $note1")
                awaitReport("note1 shown") { it.shown == note1 && !it.loading }
                s.client.setViewing(note1)
                val m = mark()
                val mirror = CanvasMirror(s.feed, s.ids)
                mirror.begin(note1, sheet1, brush(), 1f, 0f, 0f, false, false) ?: throw Fail("no mirrored stroke")
                val points = List(240) { curve(it) }
                val socket = s.transport.current!!
                play(240) { i, t, last ->
                    val p = points[i]; mirror.sample(p.first, p.second, p.third, 0f, 0f, t)
                    if (last) mirror.flush()
                    if (i == 99) { check(poll(5_000) { report().wet == 1 }) { "the half stroke never showed on the Mac" }; socket.cutNow() }
                }
                mirror.end()
                awaitLog(m, "link: disconnected phone=")
                awaitReport("ghost ink stayed on the Mac after the drop") { it.wet == 0 }
                awaitLog(m, "link: connected phone=")
                s.awaitPhase(Phase.CONNECTED)
                val after = awaitReport("the Mac shows ink after the reconnect") { it.connected == 1 && it.shown == note1 && !it.loading }
                // A window: the stroke's remaining frames went to a connection that never saw its Begin; none may be drawn.
                Thread.sleep(1_000)
                val r = report()
                check(r.wet == 0 && r.strokes == after.strokes) { "after the reconnect the Mac shows wet=${r.wet} strokes=${r.strokes} (before: ${after.strokes})" }
                // Recovery: a new mirrored stroke is drawn live again.
                val id = mirror.begin(note1, sheet1, brush(colour = InkColour.INK), 1f, 0f, 0f, false, false) ?: throw Fail("no stroke after reconnect")
                play(60) { i, t, last -> val p = curve(i); mirror.sample(p.first, p.second + 400, p.third, 0f, 0f, t); if (last) mirror.flush() }
                mirror.end()
                awaitReport("a mirrored stroke ($id) did not draw after the reconnect") { it.wet == 1 }
                s.client.setViewing(null)
            }

            step("g3 connection dropped mid-Stream-stroke: nothing partial is saved, the pad works after reconnect") {
                s.client.setPad(true)
                check(poll { s.target.usable }) { "no target" }
                val target = s.target
                val base = awaitReport("the Mac note") { it.shown == target.noteId && !it.loading }
                val m = mark()
                val socket = s.transport.current!!
                val pen = StreamPen(s.feed, s.ids, { s.target }, { brush(colour = InkColour.INK) })
                var started = false
                play(160) { k, t, last ->
                    val x = 700f + 20f * k; val y = 2700f
                    if (!started) started = pen.down(x, y, 0.5f, 0f, 0f, t, false, false) else pen.move(x, y, 0.5f, 0f, 0f, t)
                    if (last) pen.flush()
                    if (k == 79) { check(poll(5_000) { report().wet == 1 }) { "the half pad stroke never showed" }; socket.cutNow() }
                }
                // The pen lifts after the link is already gone; the stroke must not be half-saved.
                pen.up()
                awaitLog(m, "link: disconnected phone=")
                awaitReport("ghost ink stayed on the Mac after the drop") { it.wet == 0 }
                awaitLog(m, "link: connected phone=")
                s.awaitPhase(Phase.CONNECTED)
                check(poll { s.target.usable && s.target.noteId == target.noteId }) { "no target after reconnect (${s.target})" }
                awaitReport("the Mac shows ink after the reconnect") { it.connected == 1 && it.wet == 0 }
                Thread.sleep(1_000) // a window: whatever the phone resends after the reconnect has had time to arrive
                val r = report()
                print("JOIN info g3 strokes before=${base.strokes} after=${r.strokes} wet=${r.wet} saved-lines=${macSince(m).count { it.contains("stroke-saved") }}")
                check(r.wet == 0) { "ghost ink: $r" }
                check(r.strokes == base.strokes) { "the Mac saved ${r.strokes - base.strokes} stroke(s) of a stroke whose connection died" }
                // Recovery: a whole stroke works on the new connection.
                val m2 = mark()
                val pen2 = StreamPen(s.feed, s.ids, { s.target }, { brush(colour = InkColour.INK) })
                var up = false
                play(60) { k, t, last ->
                    if (!up) up = pen2.down(900f + 20f * k, 2800f, 0.5f, 0f, 0f, t, false, false) else pen2.move(900f + 20f * k, 2800f, 0.5f, 0f, 0f, t)
                    if (last && up) pen2.flush()
                }
                check(up) { "the pad cannot start a stroke after the reconnect: ${pen2.problem()}" }
                pen2.up()
                awaitLog(m2, "link: stroke-saved note=${target.noteId} stroke=")
                s.client.setPad(false)
            }

            step("h1 Mac forgets the phone: the phone learns, and reconnecting asks for a code again") {
                val m = mark()
                control("forget-phones")
                check(poll { s.store.pairing() == null }) { "the phone kept its token after the Mac forgot it" }
                check(poll { s.status.phase == Phase.IDLE }) { "the phone is in ${s.status.phase} (${s.status.message})" }
                check(Probe.FileStore(storeFile, phoneId).pairing() == null) { "the phone's store file still holds a pairing" }
                s.client.connect(endpoint)
                s.awaitPhase(Phase.NEEDS_CODE)
                val c = Regex("""pairing-code (\d{4})""").find(awaitLog(m, "link: pairing-code "))!!.groupValues[1]
                s.client.submitCode(c)
                s.awaitPhase(Phase.CONNECTED)
                check(s.store.pairing() != null) { "no new pairing stored" }
            }
            step("h2 phone forgets the Mac: reconnecting asks for a code again") {
                val m = mark()
                s.client.forget()
                check(s.store.pairing() == null) { "the pairing survived forget" }
                s.client.connect(endpoint)
                s.awaitPhase(Phase.NEEDS_CODE)
                val c = Regex("""pairing-code (\d{4})""").find(awaitLog(m, "link: pairing-code "))!!.groupValues[1]
                s.client.submitCode(c)
                s.awaitPhase(Phase.CONNECTED)
                awaitLog(m, "link: paired phone=")
            }

            step("i latency lines print on both sides") {
                val m = mark()
                s.stop()
                awaitLog(0, """link: latency \[[a-z ]+\] phone-send-to-mac-presented p50=[\d.]+ p95=[\d.]+ max=[\d.]+ ms n=\d+ rtt-last=[\d.]+ ms rtt-best=[\d.]+ ms \(""")
                awaitLog(m, "link: disconnected phone=")
                val phone = sessions.flatMap { it.logs }.filter { it.startsWith("latency ") }
                check(phone.any { it.contains("round-trip") && it.contains("pen-event-to-socket-write") && it.contains("excludes:") }) { "no phone latency line; logs: ${sessions.flatMap { it.logs }.take(5)}" }
                print("JOIN info i phone: ${phone.last()}")
                print("JOIN info i mac: ${macLines().last { it.startsWith("link: latency") }}")
                val tokens = sessions.mapNotNull { it.store.pairing()?.token } + (runCatching { Probe.FileStore(storeFile, phoneId).pairing()?.token }.getOrNull()?.let { listOf(it) } ?: emptyList())
                check(tokens.none { t -> macLines().any { it.contains(t) } }) { "the Mac log contains a pairing token" }
            }
        } finally {
            sessions.forEach { try { it.stop() } catch (_: Exception) { } }
            try { lib.close() } catch (_: Exception) { }
            work.deleteRecursively()
        }
        print(if (failed == 0) "JOIN done ok" else "JOIN FAIL $failed step(s) failed")
        return failed == 0
    }
}
