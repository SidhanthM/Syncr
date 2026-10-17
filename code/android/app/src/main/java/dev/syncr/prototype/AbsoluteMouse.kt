package dev.syncr.prototype

import kotlin.math.roundToInt

/** Experimental HID absolute mouse: three buttons, one padding byte, unsigned X/Y (0..32767). */
object AbsoluteMouse {
    private const val MAX = 32767

    // Generic Desktop / Mouse, pointer, three buttons, two ABSOLUTE 16-bit coordinates.
    // Not a relative mouse. Whether a particular macOS version maps this to its cursor
    // must be verified on the paired Mac; building an APK cannot establish that.
    val descriptor = byteArrayOf(
        0x05, 0x01, 0x09, 0x02, 0xA1.toByte(), 0x01,
        0x09, 0x01, 0xA1.toByte(), 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x03,
        0x15, 0x00, 0x25, 0x01, 0x95.toByte(), 0x03, 0x75, 0x01, 0x81.toByte(), 0x02,
        0x95.toByte(), 0x01, 0x75, 0x05, 0x81.toByte(), 0x03,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31,
        0x16, 0x00, 0x00, 0x26, 0xFF.toByte(), 0x7F,
        0x75, 0x10, 0x95.toByte(), 0x02, 0x81.toByte(), 0x02,
        0xC0.toByte(), 0xC0.toByte()
    )

    fun report(x: Float, y: Float, width: Float, height: Float, pressed: Boolean): ByteArray {
        require(width > 0f && height > 0f && x.isFinite() && y.isFinite())
        val px = ((x / width).coerceIn(0f, 1f) * MAX).roundToInt()
        val py = ((y / height).coerceIn(0f, 1f) * MAX).roundToInt()
        return byteArrayOf(
            if (pressed) 1 else 0,
            px.toByte(), (px shr 8).toByte(),
            py.toByte(), (py shr 8).toByte()
        )
    }
}
