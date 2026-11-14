package dev.syncr.prototype.link

import dev.syncr.core.Brush
import dev.syncr.core.BrushFamily
import dev.syncr.core.BrushSize
import dev.syncr.core.Command
import dev.syncr.core.CommandBody
import dev.syncr.core.InkColour
import dev.syncr.core.MarkMutation
import dev.syncr.core.SampleChannels
import dev.syncr.core.encodeSamples
import dev.syncr.core.newId
import dev.syncr.prototype.notes.GeneratedCoreAdapter
import dev.syncr.prototype.notes.LibraryAccess
import java.io.File
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.sin

/**
 * The phone side of the link as a plain JVM process, for testing against a real Mac app without a phone. It pairs, sends
 * the manifest of a fixture library (a temp copy, never the original), serves `want`ed notes with the core's real
 * `export_copy`, and can play a scripted mirrored stroke and a scripted Tablet Stream stroke at realistic timing (240 Hz
 * samples, one frame per 4-sample batch). Every result is one greppable line starting `PROBE`; the last is `PROBE done ok`
 * or `PROBE FAIL ...`. Needs the host core library (`-PsyncrNativeHost=true`).
 */
class Probe(private val o: Options, private val print: (String) -> Unit) {
    class Options(
        val endpoint: Endpoint,
        /** The four digits the Mac shows; null reads them from stdin. */
        val code: String?,
        val library: File,
        val mirror: Boolean = true,
        val stream: Boolean = true,
        val timeoutMs: Long = 60_000,
        /** Keep the pairing here between runs (a Properties file); null keeps it in memory. */
        val storeFile: File? = null,
        val readCode: () -> String? = { readLine() },
    )

    internal class FileStore(private val file: File?, private val id: String) : PairingStore {
        private var pairing: Pairing? = null

        init {
            if (file != null && file.isFile) {
                val p = Properties().also { f -> file.inputStream().use { f.load(it) } }
                val mac = p.getProperty("mac_id"); val token = p.getProperty("token")
                if (mac != null && token != null) pairing = Pairing(mac, p.getProperty("mac_name") ?: "Mac", token)
            }
        }

        override fun phoneId() = id
        override fun pairing() = pairing
        override fun save(pairing: Pairing) {
            this.pairing = pairing
            file?.let { f ->
                Properties().apply { setProperty("mac_id", pairing.macId); setProperty("mac_name", pairing.macName); setProperty("token", pairing.token) }
                    .also { p -> f.outputStream().use { p.store(it, "syncr probe pairing") } }
            }
        }
        override fun forget() { pairing = null; file?.delete() }
    }

    internal class Library(val root: File) : NoteSource {
        private val access: LibraryAccess = GeneratedCoreAdapter().open(root.absolutePath)
        val sheets = HashMap<String, String>()

        @Synchronized override fun list() = access.listNotes().map { NoteMeta(it.id, it.localSequence.toLong(), it.title) }

        @Synchronized override fun exportCopy(id: String, dest: File): Long? {
            dest.delete()
            val note = access.openNote(id)
            return try { note.export(dest.absolutePath).exportedLocalSequence.toLong() } finally { note.closeDurably() }
        }

        // ---- link v1: what the phone's app does through its repository, done directly on the core for the host test ----

        @Synchronized override fun digests(ids: List<String>): Map<String, NoteDigest> = ids.mapNotNull { id ->
            runCatching { access.noteSyncDigest(id) }.getOrNull()?.let { id to NoteDigest(it.digest, it.operationCount.toLong()) }
        }.toMap()

        @Synchronized override fun trash(): List<String> = access.listDeletedNotes().map { it.id }

        // ---- delta sync, straight on the core like the app's repository does it ----

        @Synchronized override fun heads(id: String): Map<String, String>? =
            runCatching { access.noteSyncHeads(id) }.getOrNull()?.associate { it.authorId to it.newestId }

        @Synchronized override fun exportDelta(id: String, base: Map<String, String>, dest: File): Long? {
            dest.delete()
            val note = access.openNote(id)
            return try {
                val receipt = note.exportDelta(base.map { dev.syncr.core.AuthorHead(it.key, it.value) }, dest.absolutePath)
                if (receipt.anchored) receipt.exportedLocalSequence.toLong() else null
            } finally { note.closeDurably() }
        }

        @Synchronized override fun applyDelta(id: String, file: File): CopyOutcome {
            if (access.listNotes().none { it.id == id }) return CopyOutcome.Refused("no such note")
            val note = access.openNote(id)
            val merged = try { note.mergeDelta(file.absolutePath) } finally { note.closeDurably() }
            val d = access.noteSyncDigest(id)
            val seq = access.listNotes().first { it.id == id }.localSequence.toLong()
            val whole = merged.complete && merged.report.rejected.isEmpty()
            return CopyOutcome.Applied(seq, NoteDigest(d.digest, d.operationCount.toLong()), if (whole) "delta" else "delta-short", merged.report.rejected.size)
        }

        @Synchronized override fun applyCopy(id: String, file: File): CopyOutcome {
            val path = file.absolutePath
            val how: String
            var rejected = 0
            if (access.listNotes().none { it.id == id }) {
                access.installCopy(path); how = "installed"
            } else {
                val note = access.openNote(id)
                val report = try { note.mergeCopy(path) } finally { note.closeDurably() }
                if (report.rejected.isEmpty()) how = "merged"
                else { rejected = access.rebaseOntoCopy(id, path).rejected.size; how = "rebased" }
            }
            val d = access.noteSyncDigest(id)
            val seq = access.listNotes().first { it.id == id }.localSequence.toLong()
            return CopyOutcome.Applied(seq, NoteDigest(d.digest, d.operationCount.toLong()), how, rejected)
        }

        /** Every stroke of [id], tombstoned ones included, with its sample bytes. */
        @Synchronized fun strokeBytes(id: String): Map<String, ByteArray> {
            val note = access.openNote(id)
            return try { note.snapshot().strokes.associate { it.id to note.samples(it.id) } } finally { note.closeDurably() }
        }

        @Synchronized fun hasNote(id: String) = access.listNotes().any { it.id == id }
        @Synchronized fun digest(id: String) = access.noteSyncDigest(id)
        @Synchronized fun deleteNote(id: String) = access.deleteNote(id)

        @Synchronized fun liveStrokes(id: String): List<dev.syncr.core.StrokeView> {
            val note = access.openNote(id)
            return try { note.snapshot().strokes.filter { !it.tombstone } } finally { note.closeDurably() }
        }

        @Synchronized fun snapshot(id: String): dev.syncr.core.NoteSnapshot {
            val note = access.openNote(id)
            return try { note.snapshot() } finally { note.closeDurably() }
        }

        /** Moves a stroke right by [tx] (one core command, based on the stroke's current head). Returns the operation ID. */
        @Synchronized fun moveStroke(id: String, mark: String, tx: Double): String {
            val note = access.openNote(id)
            try {
                val s = note.snapshot().strokes.first { it.id == mark }
                val op = newId()
                note.submit(Command(op, listOf(s.head.operationId), CommandBody.Marks(listOf(
                    MarkMutation.TransformStroke(mark, dev.syncr.core.Base.Operation(s.head.operationId), dev.syncr.core.Affine(1.0, 0.0, 0.0, 1.0, tx, 0.0))))))
                return op
            } finally { note.closeDurably() }
        }

        /** The backups the core wrote before rebasing [id], by file name. */
        fun rebaseBackups(root: File, id: String): List<File> =
            File(root, ".backups").listFiles().orEmpty().filter { it.name.startsWith("$id.rebase.") }

        @Synchronized fun sheetOf(id: String): String {
            val note = access.openNote(id)
            return try { note.snapshot().sheets.first().id } finally { note.closeDurably() }
        }

        /** Saves a finished stroke into the note's first sheet, as the phone's editor does: one core command, sequence + 1. */
        @Synchronized fun addStroke(id: String, strokeId: String, channels: SampleChannels, brush: Brush) {
            val note = access.openNote(id)
            try {
                val sheet = note.snapshot().sheets.first()
                note.submit(Command(newId(), listOf(sheet.creationOperationId),
                    CommandBody.Marks(listOf(MarkMutation.AddStroke(strokeId, sheet.id, encodeSamples(channels), brush)))))
            } finally { note.closeDurably() }
        }

        @Synchronized fun strokeCount(id: String): Int {
            val note = access.openNote(id)
            return try { note.snapshot().strokes.count { !it.tombstone } } finally { note.closeDurably() }
        }

        fun close() = access.release()
    }

    private val phase = java.util.concurrent.atomic.AtomicReference(Phase.IDLE)
    @Volatile private var status = SessionStatus()
    @Volatile private var target = StreamTarget()
    private val wanted = CopyOnWriteArrayList<String>()
    private val delivered = CopyOnWriteArrayList<String>()
    @Volatile private var lastSyncEvent = System.nanoTime()

    private fun line(text: String) = print("PROBE $text")

    /** Runs the whole script. True when every step passed. */
    fun run(): Boolean {
        val work = Files.createTempDirectory("syncr-probe-").toFile()
        var library: Library? = null
        var client: LinkClient? = null
        try {
            // A copy: opening a library migrates it, and fixtures are never touched.
            val root = File(work, "library").also { it.mkdirs() }
            o.library.listFiles()!!.filter { it.extension == "syncr" || it.name == "library.sqlite3" }.forEach { it.copyTo(File(root, it.name)) }
            val lib = Library(root).also { library = it }
            val notes = lib.list()
            line("library notes=${notes.size} ${notes.joinToString(" ") { "${it.id}@${it.seq}" }}")
            if (notes.isEmpty()) return fail("library", "the fixture library has no notes")

            val store = FileStore(o.storeFile, "5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c")
            val out = CorePreviewOut()
            val feed = PenFeed()
            val ids = IdPool(::newId)
            val config = ClientConfig("Syncr probe", store, lib, Clock.SYSTEM, File(work, "out"), out, feed, ::encodeSamples,
                { line("log $it") }, ids = ids)
            val c = LinkClient(config, { p -> o.endpoint.copy(name = p.macName, macId = p.macId) }, object : ClientListener {
                override fun onStatus(status: SessionStatus) {
                    if (status != this@Probe.status) line("status phase=${status.phase} mac=${status.macName ?: "-"} tries=${status.triesLeft ?: "-"} message=${status.message ?: "-"}")
                    this@Probe.status = status
                    phase.set(status.phase)
                }
                override fun onTarget(target: StreamTarget) {
                    this@Probe.target = target
                    line("target note=${target.noteId} sheet=${target.sheetId} writable=${target.writable} rev=${target.rev}")
                }
                override fun onWanted(ids: List<String>) { wanted += ids; lastSyncEvent = System.nanoTime(); line("sync want ids=${ids.joinToString(",")}") }
                override fun onDelivered(id: String, seq: Long) { delivered += id; lastSyncEvent = System.nanoTime(); line("sync have id=$id seq=$seq") }
            })
            client = c
            c.notesChanged(notes, null)
            c.start()
            c.connect(o.endpoint)
            val deadline = System.nanoTime() + o.timeoutMs * 1_000_000

            // 1. Pair (or reuse the stored token).
            var submitted = false
            while (phase.get() != Phase.CONNECTED) {
                if (System.nanoTime() > deadline) return fail("pair", "timed out in phase ${phase.get()} (${status.message})")
                if (phase.get() == Phase.NEEDS_CODE && !submitted) {
                    val code = o.code ?: run { line("need-code: type the four digits the Mac shows, then Enter"); o.readCode() }
                    if (code == null) return fail("pair", "no code given")
                    c.submitCode(code.trim()); submitted = true
                }
                if (phase.get() == Phase.NEEDS_CODE && status.triesLeft != null && submitted) submitted = false // a wrong code: ask again
                Thread.sleep(50)
            }
            line("welcome mac=${status.macName} epoch=${c.epoch} paired=${store.pairing() != null}")

            // 2. Note sync: give the Mac a moment to ask, then wait for what it asked for.
            Thread.sleep(1_500)
            while (delivered.size < wanted.size && System.nanoTime() < deadline) Thread.sleep(50)
            if (delivered.size < wanted.size) return fail("sync", "delivered ${delivered.size} of ${wanted.size} wanted notes")
            line("sync wanted=${wanted.size} delivered=${delivered.size}")

            val noteId = notes.first().id
            val sheetId = lib.sheetOf(noteId)

            // 3. Live mirror: a scripted stroke over the first note, Canvas space.
            if (o.mirror) {
                c.setViewing(noteId)
                Thread.sleep(500)
                val brush = Brush(BrushFamily.PEN, BrushSize.MEDIUM, InkColour.BLUE)
                val mirror = CanvasMirror(feed, ids)
                val before = c.framesQueued
                val id = mirror.begin(noteId, sheetId, brush, 1f, 0f, 0f, hasTilt = false, hasOrientation = false)
                    ?: return fail("mirror", "the link refused to start a stroke (not connected?)")
                play(240, 4) { i, t, last ->
                    val u = i / 239f
                    mirror.sample(600f + 1800f * u, 1000f + 250f * sin(u * 2 * PI).toFloat(), 0.2f + 0.7f * sin(u * PI).toFloat(), 0f, 0f, t)
                    if (last) mirror.flush()
                }
                mirror.end()
                Thread.sleep(300)
                line("mirror stroke=$id note=$noteId sheet=$sheetId samples=240 frames=${c.framesQueued - before} space=canvas")
                c.setViewing(null)
            }

            // 4. Tablet Stream: the pad opens, the Mac names its note, a scripted pad stroke goes in view-relative units.
            if (o.stream) {
                c.setPad(true)
                while (!target.usable && System.nanoTime() < deadline) Thread.sleep(50)
                if (!target.usable) return fail("stream", "the Mac gave no writable target (${target})")
                val brush = Brush(BrushFamily.PEN, BrushSize.MEDIUM, InkColour.INK)
                val pen = StreamPen(feed, ids, { target }, { brush })
                val before = c.framesQueued
                val t0 = System.nanoTime() / 1_000_000
                var started = false
                play(240, 4) { i, t, last ->
                    val u = i / 239f
                    val x = 600f + 3600f * u
                    val y = 1500f + 700f * sin(u * 3 * PI).toFloat()
                    val p = 0.25f + 0.6f * sin(u * PI).toFloat()
                    if (!started) { started = pen.down(x, y, p, 0f, 0f, t, false, false); if (!started) return@play } else pen.move(x, y, p, 0f, 0f, t)
                    if (last) pen.flush()
                }
                if (!started) return fail("stream", "the pad could not start a stroke: ${pen.problem()}")
                pen.up()
                Thread.sleep(500)
                line("stream note=${target.noteId} sheet=${target.sheetId} rev=${target.rev} samples=240 frames=${c.framesQueued - before} commit=queued space=view-relative took=${System.nanoTime() / 1_000_000 - t0}ms")
                c.setPad(false)
            }

            // 5. Latency: wait for a couple of pings so the round trip has samples.
            Thread.sleep(4_500)
            line("latency pen-to-socket-write ${c.latencyLog.penToWrite.summary()} | round-trip ${c.latencyLog.roundTrip.summary()} " +
                "| excludes digitiser, display scan-out, and the Mac side (the Mac logs phone-send to Mac-presented)")
            line("done ok")
            return true
        } catch (e: Exception) {
            return fail("exception", "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            client?.stop(wait = true)
            library?.close()
            work.deleteRecursively()
        }
    }

    private fun fail(step: String, why: String): Boolean {
        line("FAIL $step: $why")
        return false
    }

    /**
     * Plays [count] samples at 240 Hz, [perBatch] per input batch (60 batches a second, as the S Pen delivers them), in real time.
     * [sample] gets the sample index, its event time (monotonic ms) and whether it is the last of its batch.
     */
    private fun play(count: Int, perBatch: Int, sample: (index: Int, timeMs: Long, lastInBatch: Boolean) -> Unit) {
        val start = System.nanoTime()
        var i = 0
        while (i < count) {
            val batchEnd = minOf(i + perBatch, count)
            // The batch is delivered when its last sample exists.
            val due = start + (batchEnd - 1) * 1_000_000_000L / 240
            while (System.nanoTime() < due) Thread.sleep(0, 200_000)
            while (i < batchEnd) {
                val t = (start + i * 1_000_000_000L / 240) / 1_000_000
                sample(i, t, i == batchEnd - 1)
                i++
            }
        }
    }
}
