package dev.syncr.example
fun main(args: Array<String>) {
    val snapshot = createAddSnapshotReopen(args.single())
    check(snapshot.localSequence == 5uL)
    println("PASS: pre-amendment native library wrote sequence-5 legacy note ${snapshot.noteId}")
}
