package dev.syncr.prototype.link

import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """usage: linkProbe HOST:PORT [--code 1234] [--library DIR] [--store FILE] [--timeout SECONDS] [--no-mirror] [--no-stream]
  HOST:PORT   where the Mac app listens (the Phone link port shown in its window)
  --code      the four digits the Mac shows; without it the probe asks on stdin
  --library   fixture library folder to copy and serve (default ../../core/fixtures/canvas-v2)
  --store     keep the pairing in this file, so a second run reconnects without a code
  --timeout   seconds to wait for each step (default 60)

usage: linkProbe HOST:PORT --join --mac-log FILE --mac-control FILE --mac-library DIR [--library DIR]
  The joined test (code/shared/link-v0/join-test.sh runs it): scripted phone scenarios against the real Mac app, which was
  started with SYNCR_MAC_DEBUG_REPLAY=1 and SYNCR_MAC_DEBUG_CONTROL=FILE and logs to --mac-log. Prints JOIN PASS/FAIL lines."""

/** `./gradlew -q --console=plain :app:linkProbe --args="127.0.0.1:5050 --code 1234"`; prints `PROBE ...` lines, exit code 0 on success. */
fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] in listOf("-h", "--help") || !args[0].contains(':')) { println(USAGE); exitProcess(2) }
    val (host, port) = args[0].let { it.substringBeforeLast(':') to (it.substringAfterLast(':').toIntOrNull() ?: run { println(USAGE); exitProcess(2) }) }
    fun option(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    if ("--faults" in args) {
        fun need(name: String) = option(name) ?: run { println("missing $name"); exitProcess(2) }
        val fault = FaultTest(FaultTest.Options(File(need("--mac-bin")), File(need("--mac-library")), port, File(option("--library") ?: "../../core/fixtures/canvas-v2").canonicalFile,
            File(need("--work")), only = option("--only")?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
            soakSeconds = option("--soak")?.toIntOrNull() ?: 90, cuts = option("--cuts")?.toIntOrNull() ?: 200, check = "--no-check" !in args), ::println)
        exitProcess(if (fault.run()) 0 else 1)
    }
    if ("--join" in args) {
        fun need(name: String) = option(name) ?: run { println("missing $name\n$USAGE"); exitProcess(2) }
        val join = JoinTest(JoinTest.Options(host, port, File(need("--mac-log")), File(need("--mac-control")), File(need("--mac-library")),
            File(option("--library") ?: "../../core/fixtures/canvas-v2").canonicalFile), ::println)
        exitProcess(if (join.run()) 0 else 1)
    }
    val options = Probe.Options(
        endpoint = Endpoint(host, port),
        code = option("--code"),
        library = File(option("--library") ?: "../../core/fixtures/canvas-v2").canonicalFile,
        mirror = "--no-mirror" !in args,
        stream = "--no-stream" !in args,
        timeoutMs = (option("--timeout")?.toLongOrNull() ?: 60) * 1000,
        storeFile = option("--store")?.let(::File),
    )
    val ok = Probe(options) { println(it) }.run()
    exitProcess(if (ok) 0 else 1)
}
