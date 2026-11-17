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
        private val read = AtomicLong()
        @Volatile private var cutAt = Long.MAX_VALUE
        @Volatile private var readCutAt = Long.MAX_VALUE
        fun armIn(bytes: Long) { cutAt = written.get() + bytes }
        /** Cuts after this many more bytes have been read (to land in the middle of a file the Mac sends). */
        fun armReadIn(bytes: Long) { readCutAt = read.get() + bytes }
        fun cutNow() { try { close() } catch (_: IOException) { } }
        override fun getInputStream(): java.io.InputStream {
            val inner = super.getInputStream()
            return object : java.io.InputStream() {
                override fun read(): Int = inner.read().also { if (it >= 0) count(1) }
                override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) count(it) }
                private fun count(n: Int) { if (read.addAndGet(n.toLong()) >= readCutAt) { cutNow(); throw IOException("cut on cue") } }
            }
        }
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
    private inner class Session(val store: Probe.FileStore, val version: Int = 0) {
        @Volatile var status = SessionStatus()
        @Volatile var target = StreamTarget()
        val phases = CopyOnWriteArrayList<Phase>()
        val statuses = CopyOnWriteArrayList<SessionStatus>()
        val wanted = CopyOnWriteArrayList<String>()
        val delivered = CopyOnWriteArrayList<Pair<String, Long>>()
        val logs = CopyOnWriteArrayList<String>()
        /** Pictures of what the pad covers: header, size of the whole message, whether the image starts as a JPEG does. */
        val pictures = CopyOnWriteArrayList<Triple<Wire.PictureHeader, Int, Boolean>>()
        val transport = CuttingTransport()
        val outDir = File(work, "out-${sessions.size}")
        val feed = PenFeed()
        val ids = IdPool(::newId)
        @Volatile var cutOnWant: Long = -1
        val client: LinkClient

        init {
            val config = ClientConfig("Syncr join test", store, lib, Clock.SYSTEM, outDir, CorePreviewOut(), feed,
                ::encodeSamples, { logs += it }, transport = transport, ids = ids, linkVersion = version)
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
                override fun onPicture(message: ByteArray) {
                    Wire.parsePicture(message)?.let {
                        pictures += Triple(it, message.size, message[Wire.PICTURE_HEADER] == 0xff.toByte() && message[Wire.PICTURE_HEADER + 1] == 0xd8.toByte())
                    }
                }
            })
            sessions += this
        }

        fun start() { client.notesChanged(lib.list(), null); client.start() }
        fun stop() = client.stop(wait = true)

        fun awaitPhase(p: Phase, ms: Long = o.timeoutMs) {
            if (!poll(ms) { status.phase == p }) throw Fail("phone stayed in ${status.phase} (${status.message}), wanted $p")
        }
    }

    /** The Mac's operation-set digests (first 12 hex characters) and operation counts, by note. */
    private fun macDigests(): Map<String, Pair<String, Long>> {
        val m = mark()
        control("report")
        val line = awaitLog(m, "^link: sync-digests", 10_000)
        return line.removePrefix("link: sync-digests").trim().split(",").filter { it.isNotBlank() }.associate {
            val (id, d, n) = it.split(":"); id to (d to n.toLong())
        }
    }

    /** Waits until the phone's and the Mac's copies of [id] hold the same set of operations. */
    private fun awaitEqual(id: String, ms: Long = o.timeoutMs) {
        var last = ""
        if (!poll(ms, every = 100) {
                val phone = lib.digest(id); val mac = macDigests()[id]
                last = "phone ${phone.digest.take(12)}:${phone.operationCount} mac ${mac?.first}:${mac?.second}"
                mac != null && mac.first == phone.digest.take(12) && mac.second == phone.operationCount.toLong()
            }) throw Fail("the phone and the Mac never agreed on note $id ($last)")
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


    // ------------------------------------------------------------------ typed text, keystroke to visible (latency-v1 T.1)

    private fun pct(v: List<Long>, p: Double): Long = if (v.isEmpty()) -1 else v.sorted()[minOf(v.size - 1, (v.size * p).toInt())]
    private fun summary(v: List<Long>) = "p50=${pct(v, 0.5)} p95=${pct(v, 0.95)} max=${v.maxOrNull() ?: -1} ms n=${v.size}"

    /** The Mac types [n] letters [gapMs] apart through its editor; per letter, ms until the phone's library holds it. */
    private fun macToPhone(note: String, n: Int, gapMs: Int): List<Long> {
        val base = lib.documentTextOf(note).length
        lib.arrivals.clear(); lib.watch = note
        val m = mark()
        control("type-burst $n $gapMs")
        awaitLog(m, "ack type-burst", n * gapMs + 10_000L)
        val typed = macSince(m).mapNotNull { Regex("""link: typed i=(\d+) epoch=(\d+)""").find(it)?.destructured?.let { (i, e) -> i.toInt() to e.toLong() } }.toMap()
        check(typed.size == n) { "the Mac typed ${typed.size} of $n letters" }
        check(poll(10_000, 5) { lib.arrivals.any { it.second >= base + n } }) { "the last Mac letter never reached the phone (library text ${lib.arrivals.lastOrNull()}, wanted ${base + n})" }
        lib.watch = null
        return (1..n).map { i -> lib.arrivals.first { it.second >= base + i }.first - typed.getValue(i) }
    }

    /** How the phone's editor commits typed text: the delay before the next save, from the clock, the first unsaved keystroke and the last save. */
    private fun interface TextCommit { fun delayMs(now: Long, dirtySince: Long, lastCommit: Long): Long }

    /** The phone types [n] letters [gapMs] apart and commits them as its editor would; per letter, ms until the Mac's editor shows it. */
    private fun phoneToMac(note: String, n: Int, gapMs: Int, commit: TextCommit): List<Long> {
        val base = lib.documentTextOf(note).length
        val m = mark()
        val keys = LongArray(n)
        val clock = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        val lock = Any()
        var text = lib.documentTextOf(note)
        var pending: java.util.concurrent.ScheduledFuture<*>? = null
        var dirtySince = 0L; var lastCommit = 0L
        fun commitNow() {
            val t = synchronized(lock) { dirtySince = 0; lastCommit = System.currentTimeMillis(); text }
            lib.typeText(note, t); current.client.notesChanged(lib.list(), note)
        }
        val start = System.nanoTime()
        for (i in 1..n) {
            val due = start + (i - 1) * gapMs * 1_000_000L
            while (System.nanoTime() < due) Thread.sleep(0, 200_000)
            val now = System.currentTimeMillis()
            val (since, last) = synchronized(lock) { text += "p"; keys[i - 1] = now; if (dirtySince == 0L) dirtySince = now; dirtySince to lastCommit }
            pending?.cancel(false)
            pending = clock.schedule({ try { commitNow() } catch (e: Exception) { print("JOIN info commit failed: $e") } }, commit.delayMs(now, since, last), java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        val settled = poll(15_000, 5) { macSince(m).any { l -> Regex("""link: editor-text len=(\d+)""").find(l)?.groupValues?.get(1)?.toInt()?.let { it >= base + n } == true } }
        clock.shutdownNow()
        check(settled) { "the last phone letter never showed in the Mac's editor (${macSince(m).filter { it.contains("editor-text") }.takeLast(2)}, wanted length ${base + n})" }
        val seen = macSince(m).mapNotNull { Regex("""link: editor-text len=(\d+) epoch=(\d+)""").find(it)?.destructured?.let { (l, e) -> l.toInt() to e.toLong() } }
        return (1..n).map { i -> seen.first { it.first >= base + i }.second - keys[i - 1] }
    }

    private lateinit var current: Session

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
                // The Mac follows the phone to the note it views, and a phone note is writable on the Mac now (link v1), so the
                // pad would write into it. This step is about the Mac having no note open: close every tab first.
                control("sync-close-tabs")
                awaitReport("the Mac still shows a note after its tabs were closed") { it.shown == "none" }
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
                // Ended previews of the mirrored strokes above wait up to 10 s for a copy that never comes; start from a clean layer.
                awaitReport("an earlier preview never expired") { it.wet == 0 }
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


            // ---------------------------------------------------------------- link v1: two-way sync (sync-v1.md)
            s.stop()
            var cur = Session(newStore(), 1)
            var macNoteId = ""
            fun connectV1() {
                cur = Session(newStore(), 1)
                cur.start(); cur.client.connect(endpoint)
                cur.awaitPhase(Phase.CONNECTED)
            }
            step("j0 a v1 phone connects: the Mac sends its notes list, the Mac-made note installs on the phone, every digest agrees") {
                macNoteId = report().notes.entries.first { it.value.second }.key
                cur.start(); cur.client.connect(endpoint)
                cur.awaitPhase(Phase.CONNECTED)
                check(poll { lib.hasNote(macNoteId) }) { "the Mac-made note $macNoteId never reached the phone" }
                for (id in listOf(note1, note2, macNoteId)) awaitEqual(id)
                print("JOIN info j0 phone holds ${lib.list().size} notes; Mac-made note ${macNoteId} has ${lib.liveStrokes(macNoteId).size} strokes")
            }
            step("j1 a phone note is adopted, then typed into on the Mac: the phone receives the text operation") {
                control("sync-text $note1 hello")
                check(poll { lib.snapshot(note1).textBlocks.any { !it.tombstone && it.text.contains("hello") } }) { "the typed text never reached the phone" }
                awaitEqual(note1)
            }
            step("j2 a Mac note with text, ink and a margin note appears on the phone with all of it") {
                control("sync-text $macNoteId note")
                check(poll { lib.snapshot(macNoteId).textBlocks.any { !it.tombstone } }) { "the Mac's text block never reached the phone" }
                control("sync-margin $macNoteId")
                check(poll { lib.snapshot(macNoteId).companionItems.any { it.body is dev.syncr.core.CompanionBody.MarginNote } }) { "the margin note never reached the phone" }
                val strokes = lib.liveStrokes(macNoteId).size
                check(strokes >= 3) { "the phone holds $strokes of the Mac note's strokes" }
                awaitEqual(macNoteId)
            }
            step("j3 both sides change different strokes while disconnected; after reconnect the digests are equal and both moves are in") {
                cur.stop()
                val strokes = lib.liveStrokes(note1)
                check(strokes.size >= 2) { "note1 has ${strokes.size} strokes" }
                val first = strokes.first().id; val last = strokes.last().id
                val m = mark()
                lib.moveStroke(note1, first, 11.0)
                control("sync-move $note1 last 22")
                awaitLog(m, "ack sync-move $note1")
                val conflictsBefore = report().int("conflicts")
                connectV1()
                awaitEqual(note1)
                val snap = lib.snapshot(note1)
                check(snap.strokes.first { it.id == first }.transform.tx == 11.0) { "the phone's own move was lost" }
                check(snap.strokes.first { it.id == last }.transform.tx == 22.0) { "the Mac's move did not reach the phone" }
                check(report().int("conflicts") == conflictsBefore) { "a conflict was recorded for changes that do not overlap" }
            }
            step("j4 the same stroke is moved on both sides: the Mac keeps its move, a conflict file appears, the phone rebases with a backup") {
                cur.stop()
                val first = lib.liveStrokes(note1).first().id
                val phoneOp = lib.moveStroke(note1, first, 7.0)
                val m = mark()
                control("sync-move $note1 first 100")
                awaitLog(m, "ack sync-move $note1")
                connectV1()
                awaitLog(m, """link: sync-conflict note=$note1 rejected=1""")
                val file = File(o.macLibrary, ".mac/sync-conflicts/$note1/$phoneOp.json")
                check(poll { file.isFile }) { "no conflict file at $file" }
                val text = file.readText()
                check(text.contains("stale base") && text.contains("\"canonical_hex\"") && text.contains("\"payload_json\"") && text.contains("\"author_id\"")) { "the conflict file lacks fields: ${text.take(200)}" }
                check(poll { lib.rebaseBackups(lib.root, note1).isNotEmpty() }) { "the phone wrote no backup, so it did not rebase" }
                awaitEqual(note1)
                check(lib.snapshot(note1).strokes.first { it.id == first }.transform.tx == 100.0) { "the Mac's version did not win on the phone" }
                check(macHas(m, "sync-conflict")) { "the Mac did not log the conflict" }
                print("JOIN info j4 conflict file ${file.name} (${text.length} bytes), phone backups ${lib.rebaseBackups(lib.root, note1).map { it.name }}")
            }
            step("j5a a connection cut while the Mac sends a big note changes nothing on the phone; the transfer completes after reconnect") {
                val before = lib.digest(note2)
                val tmp = cur.outDir
                cur.transport.current!!.armReadIn(150_000)
                val m = mark()
                control("sync-bulk $note2 6")
                // The cut is seen on the Mac (the phone may already be reconnected by the time this thread looks at its own status).
                awaitLog(m, "link: disconnected phone=")
                check(lib.digest(note2) == before) { "the phone's note changed during a cut transfer" }
                check(tmp.listFiles().orEmpty().none { it.name.startsWith("in-") }) { "a partial file stayed on the phone: ${tmp.list()?.toList()}" }
                cur.awaitPhase(Phase.CONNECTED)
                awaitEqual(note2)
                check(lib.digest(note2) != before) { "the big note never arrived" }
                check(tmp.listFiles().orEmpty().none { it.name.startsWith("in-") }) { "a staging file stayed after the transfer" }
            }
            step("j5b a connection cut while the phone sends a big note changes nothing on the Mac; the transfer completes after reconnect") {
                repeat(6) { k ->
                    val pts = List(8_000) { i ->
                        val u = i / 7_999f
                        Triple(300f + 4200f * u, 400f + 300f * k + 120f * sin(u * 60f * PI.toFloat()), 0.5f + 0.3f * sin(u * 9f))
                    }
                    lib.addStroke(note1, newId(), channels(pts), brush())
                }
                val m = mark()
                val before = macDigests()[note1]!!
                cur.cutOnWant = 150_000
                cur.client.notesChanged(lib.list(), null)
                val aborted = awaitLog(m, """link: transfer-aborted id=$note1 received=(\d+) of=(\d+) partial-file-removed=true""")
                print("JOIN info j5b $aborted")
                check(macDigests()[note1] == before || macDigests()[note1]!!.second >= before.second) { "the Mac's note went backwards" }
                cur.awaitPhase(Phase.CONNECTED)
                awaitEqual(note1)
                check(File(o.macLibrary, ".incoming-link").listFiles().orEmpty().isEmpty()) { "Mac staging not empty" }
            }
            step("j6 200 randomised connect and cut cycles: every operation is applied once, nothing is lost, both sides converge") {
                cur.stop()
                awaitEqual(note1)
                val base = lib.digest(note1).operationCount.toLong()
                val rnd = java.util.Random(20261116)
                var phoneAdds = 0; var macAdds = 0
                val started = System.nanoTime()
                repeat(200) { n ->
                    val c = Session(newStore(), 1)
                    c.start(); c.client.connect(endpoint)
                    check(poll(15_000, 5) { c.status.phase == Phase.CONNECTED }) { "cycle $n: no connection (${c.status})" }
                    val roll = rnd.nextInt(4)
                    if (roll == 0 || roll == 3) { lib.addStroke(note1, newId(), channels(List(20) { curve(it, 20) }), brush()); phoneAdds++; c.client.notesChanged(lib.list(), null) }
                    if (roll == 1 || roll == 3) { control("sync-stroke $note1 ${rnd.nextInt(3000)}"); macAdds++ }
                    Thread.sleep(rnd.nextInt(150).toLong())
                    if (rnd.nextBoolean()) c.transport.current?.cutNow()
                    c.stop()
                }
                print("JOIN info j6 200 cycles in ${(System.nanoTime() - started) / 1_000_000_000}s: phone added $phoneAdds strokes, Mac $macAdds")
                connectV1()
                cur.client.notesChanged(lib.list(), null)
                val expect = base + phoneAdds + macAdds
                var last = ""
                check(poll(90_000, every = 250) {
                    val mac = macDigests()[note1]; val phone = lib.digest(note1)
                    last = "phone ${phone.digest.take(12)}:${phone.operationCount} mac ${mac?.first}:${mac?.second} expected $expect ops"
                    mac != null && mac.first == phone.digest.take(12) && mac.second == expect && phone.operationCount.toLong() == expect
                }) { "no convergence: $last" }
                val ids = lib.snapshot(note1).strokes.map { it.id }
                check(ids.size == ids.toSet().size) { "a stroke appears twice on the phone" }
                print("JOIN info j6 converged: $last")
            }
            step("j7 a note in the phone's Recently deleted is neither wanted from nor pushed to the phone") {
                cur.stop()
                lib.deleteNote(note2)
                val m = mark()
                connectV1()
                control("sync-stroke $note2 300")
                awaitLog(m, "ack sync-stroke $note2")
                // A window: nothing is meant to follow, so give an announcement and a transfer time to show up.
                Thread.sleep(2_000)
                check(!lib.hasNote(note2)) { "the deleted note came back to the phone" }
                check(!macHas(m, "link: announce ids=.*$note2")) { "the Mac announced a note the phone deleted" }
                check(cur.logs.none { it.contains("copy-") && it.contains(note2) }) { "the phone took a copy of its deleted note" }
            }

            step("t typed text reaches the other device fast: keystroke to visible, both ways, during a burst and after the last key") {
                current = cur
                val baseline = System.getenv("SYNCR_JOIN_TEXT_BASELINE") == "1"
                val mode = if (baseline) "baseline" else "throttled"
                macNote(note1)
                // The first letters make the document block on the Mac and let it reach the phone, so both sides hold the same text.
                control("insert \"seed \"")
                check(poll { lib.documentTextOf(note1).contains("seed") }) { "the Mac's first words never reached the phone" }
                Thread.sleep(1_500)
                val commit = if (baseline) TextCommit { _, _, _ -> 600 } else TextCommit { now, since, last -> dev.syncr.prototype.ui.TypingThrottle.delayMs(now, since, last) }
                val macDuring = ArrayList<Long>(); val macLast = ArrayList<Long>(); val macFirst = ArrayList<Long>()
                val phoneDuring = ArrayList<Long>(); val phoneLast = ArrayList<Long>(); val phoneFirst = ArrayList<Long>()
                repeat(8) {
                    val a = macToPhone(note1, 15, 100)
                    macDuring += a; macLast += a.last(); macFirst += a.first()
                    Thread.sleep(1_500)
                    val b = phoneToMac(note1, 15, 100, commit)
                    phoneDuring += b; phoneLast += b.last(); phoneFirst += b.first()
                    Thread.sleep(1_500)
                }
                print("JOIN info t mode=$mode Mac to phone, every keystroke: ${summary(macDuring)}")
                print("JOIN info t mode=$mode Mac to phone, first key of a burst: ${summary(macFirst)}")
                print("JOIN info t mode=$mode Mac to phone, last key of a burst: ${summary(macLast)}")
                print("JOIN info t mode=$mode phone to Mac, every keystroke: ${summary(phoneDuring)}")
                print("JOIN info t mode=$mode phone to Mac, first key of a burst: ${summary(phoneFirst)}")
                print("JOIN info t mode=$mode phone to Mac, last key of a burst: ${summary(phoneLast)}")
                awaitEqual(note1)
                // The targets of latency-v1 T.6; reported always, enforced when SYNCR_JOIN_TEXT_ENFORCE=1.
                if (System.getenv("SYNCR_JOIN_TEXT_ENFORCE") == "1") {
                    check(pct(macDuring, 0.95) <= 300 && pct(phoneDuring, 0.95) <= 300) { "during typing, p95 over 300 ms: Mac to phone ${pct(macDuring, 0.95)}, phone to Mac ${pct(phoneDuring, 0.95)}" }
                    check(pct(macLast, 0.95) <= 250 && pct(phoneLast, 0.95) <= 250) { "after the last key, p95 over 250 ms: Mac to phone ${pct(macLast, 0.95)}, phone to Mac ${pct(phoneLast, 0.95)}" }
                }
            }


            // ---------------------------------------------------------------- the pad's Local mode (rework-v1 §V)
            step("p1 Local mode: the Mac answers padmode with a pen frame of the pad's shape inside its view") {
                macNote(macNoteId)
                control("view 0 0 1.5")
                cur.client.setPad(true)
                cur.client.setPadWish(PadWish(local = true, pics = true, aspect = 2.0))
                check(poll { cur.target.usable && cur.target.frame != null && cur.target.view?.x == 0.0 }) { "no pen frame in the target, or the view never settled: ${cur.target}" }
                val f = cur.target.frame!!; val v = cur.target.view!!
                print("JOIN info p1 frame=$f view=$v page=${cur.target.pageHeight}")
                check(Math.abs(f.w / f.h - 2.0) < 0.01) { "the frame is not the pad's shape: $f" }
                check(f.x >= maxOf(0.0, v.x) - 0.5 && f.right <= v.right + 0.5 && f.y >= v.y - 0.5 && f.bottom <= v.bottom + 0.5) { "the frame $f is outside the view $v" }
            }
            step("p2 Local mode: a frame request is answered, and a stroke in canvas units lands inside the frame at the mapped place") {
                val v = cur.target.view!!
                val want = CanvasRect(maxOf(0.0, v.x) + 300, v.y + 200, 1000.0, 500.0)
                cur.client.requestFrame(want)
                fun near(a: CanvasRect, b: CanvasRect) = Math.abs(a.x - b.x) < 1 && Math.abs(a.y - b.y) < 1 && Math.abs(a.w - b.w) < 1 && Math.abs(a.h - b.h) < 1
                check(poll { cur.target.frame?.let { near(it, want) } == true }) { "asked for $want, the Mac says ${cur.target.frame}" }
                val f = cur.target.frame!!
                val before = report().strokes
                val m = mark()
                val area = dev.syncr.prototype.tablet.PadRect(0.0, 0.0, 2000.0, 1000.0)
                val pen = StreamPen(cur.feed, cur.ids, { cur.target }, { brush(colour = InkColour.INK) })
                var started = false
                play(120) { k, t, last ->
                    val u = k / 119.0
                    val (x, y) = FrameMapping.toCanvas(area, f, 200 + 1600 * u, 200 + 600 * u)
                    if (!started) started = pen.down(x, y, 0.6f, 0f, 0f, t, false, false, dev.syncr.core.CoordinateSpace.CANVAS) else pen.move(x, y, 0.6f, 0f, 0f, t)
                    if (last && started) pen.flush()
                    // The Mac scrolls while the pen is down: the stroke keeps the frame it began with.
                    if (k == 60) control("view 0 40 1.5")
                }
                check(started) { "the pad could not start a stroke: ${pen.problem()}" }
                check(pen.up()) { "the stroke was cancelled by the Mac's scroll" }
                awaitLog(m, "link: stroke-saved note=${cur.target.noteId} stroke=")
                val r = awaitReport("the Local stroke never showed") { it.strokes == before + 1 && it.wet == 0 }
                val b = r.lastStroke()!!
                val wantBounds = listOf(f.x + 0.1 * f.w, f.y + 0.2 * f.h, f.x + 0.9 * f.w, f.y + 0.8 * f.h).map { it.toFloat() }
                val off = b.zip(wantBounds).map { (a, w) -> Math.abs(a - w) }
                print("JOIN info p2 stroke bounds=${b.joinToString(",")} expected~${wantBounds.joinToString(",")} off=${off.joinToString(",") { "%.1f".format(it) }}")
                check(off.all { it <= 40f }) { "the stroke's canvas bounds $b are not near $wantBounds" }
                control("view 0 0 1.5")
            }
            step("p3 pictures: one shows the frame with the saved stroke counted; none is sent while nothing changes") {
                check(poll { cur.target.view?.y == 0.0 }) { "the view did not return: ${cur.target.view}" }
                val f = cur.target.frame!!
                check(poll(8_000) { cur.pictures.lastOrNull()?.let { (h, _, _) -> h.saved >= 1 && Math.abs(h.region.x - f.x) < 1 && Math.abs(h.region.y - f.y) < 1 && Math.abs(h.region.w - f.w) < 1 } == true }) {
                    "no picture of the frame $f with the stroke in it; got ${cur.pictures.map { "${it.first.region} saved=${it.first.saved}" }}"
                }
                val (h, size, jpeg) = cur.pictures.last()
                print("JOIN info p3 pictures=${cur.pictures.size} last=${size} bytes rev=${h.rev} saved=${h.saved} region=${h.region}")
                check(jpeg && size <= Wire.MAX_MESSAGE) { "the picture is not a JPEG in one message (size $size)" }
                Thread.sleep(600)
                val n = cur.pictures.size
                Thread.sleep(1_500)
                check(cur.pictures.size == n) { "pictures kept arriving with nothing changing: $n -> ${cur.pictures.size}" }
            }
            step("p4 a frame asked for below the view makes the Mac's view follow; the frame stays in sight") {
                val v = cur.target.view!!; val f = cur.target.frame!!
                val n = cur.pictures.size
                cur.client.requestFrame(CanvasRect(f.x, v.bottom + 200, f.w, f.h))
                check(poll { cur.target.frame != f }) { "the Mac did not answer the request" }
                Thread.sleep(300)
                val t = cur.target; val nf = t.frame!!; val nv = t.view!!
                print("JOIN info p4 view ${v.y} -> ${nv.y}, frame ${f.y} -> ${nf.y}")
                check(nf.y >= nv.y - 0.5 && nf.bottom <= nv.bottom + 0.5 && nf.y > f.y) { "frame $nf is not inside the view $nv, or did not move down" }
                check(poll(5_000) { cur.pictures.size > n }) { "no new picture after the frame moved" }
            }
            step("p5 whole view again: the frame goes; the second path was offered and the connection lost or doubled nothing") {
                cur.client.setPadWish(PadWish(local = false, pics = true, aspect = 2.0))
                check(poll { cur.target.frame == null && cur.target.view != null }) { "the frame stayed after padmode local=false: ${cur.target}" }
                cur.client.setPad(false)
                check(cur.logs.any { it.startsWith("second path on") }) { "the phone was never offered the datagram path; logs: ${cur.logs.filter { it.contains("path") }}" }
                val m = mark()
                cur.stop()
                val line = awaitLog(m, "link: second-path frames=")
                print("JOIN info p5 mac: $line; phone: ${cur.logs.lastOrNull { it.startsWith("second path closed") }}")
                control("view 0 0 1")
            }

            step("k rehearsal: the phone fixtures (two canvas notes, one pre-canvas v1 note) are adopted, edited on both sides and synced; every stroke is compared by count, ID and sample bytes") {
                cur.stop()
                val repo = File("../../core")
                val extra = listOf(File(repo, "fixtures/legacy-v1/note-v1.syncr")).filter { it.isFile }
                extra.forEach { it.copyTo(File(lib.root, it.name), overwrite = true) }
                val ids = lib.list().map { it.id }.filter { it != macNoteId }
                val before = ids.associateWith { lib.strokeBytes(it) }
                print("JOIN rehearsal notes=${ids.size} strokes-before=${before.values.sumOf { it.size }} per-note=${before.map { "${it.key.takeLast(6)}:${it.value.size}" }}")
                connectV1()
                // adopt every note, then edit on both sides while connected and while apart
                for (id in ids) check(poll { macDigests()[id] != null }) { "note $id was never adopted by the Mac" }
                for (id in ids) awaitEqual(id)
                cur.stop()
                for (id in ids) {
                    lib.addStroke(id, newId(), channels(List(30) { curve(it, 30) }), brush())
                    control("sync-stroke $id 2500")
                    if (lib.liveStrokes(id).size >= 2) lib.moveStroke(id, lib.liveStrokes(id).first().id, 5.0)
                }
                connectV1()
                cur.client.notesChanged(lib.list(), null)
                for (id in ids) awaitEqual(id)
                // compare against the Mac's own file: a copy of it (the Mac holds the writer lock on the library)
                var total = 0
                for (id in ids) {
                    val dir = Files.createTempDirectory(work.toPath(), "macview").toFile()
                    File(o.macLibrary, "$id.syncr").copyTo(File(dir, "$id.syncr"))
                    val mac = Probe.Library(dir).also { it.root }
                    val macBytes = mac.strokeBytes(id); val phoneBytes = lib.strokeBytes(id)
                    mac.close()
                    check(macBytes.keys == phoneBytes.keys) { "note $id: stroke IDs differ (Mac ${macBytes.size}, phone ${phoneBytes.size})" }
                    for ((k, v) in phoneBytes) check(v.contentEquals(macBytes[k])) { "note $id stroke $k: sample bytes differ" }
                    for ((k, v) in before[id]!!) check(phoneBytes[k]?.contentEquals(v) == true && macBytes[k]?.contentEquals(v) == true) { "note $id: original stroke $k changed or lost" }
                    total += phoneBytes.size
                    print("JOIN rehearsal note=${id.takeLast(8)} strokes before=${before[id]!!.size} after=${phoneBytes.size} ids-equal=true bytes-equal=true originals-intact=true")
                }
                print("JOIN rehearsal ok: ${ids.size} notes, $total strokes compared, ${before.values.sumOf { it.size }} originals intact on both sides")
            }

            step("i latency lines print on both sides") {
                val m = mark()
                cur.stop()
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
