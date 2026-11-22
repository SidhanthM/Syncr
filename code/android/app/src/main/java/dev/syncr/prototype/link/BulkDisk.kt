package dev.syncr.prototype.link

import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * The disk reads and writes of note transfers: chunks read from a staged export, chunks written to a staged import. The app
 * uses the disk as it is; tests slow it down to prove no pen frame or pointer packet waits for it (remaining-v1 L.5).
 */
interface BulkDisk {
    fun read(file: RandomAccessFile, buffer: ByteArray, offset: Int, length: Int): Int = file.read(buffer, offset, length)
    fun write(out: OutputStream, data: ByteArray) = out.write(data)

    companion object {
        val REAL = object : BulkDisk {}
    }
}
