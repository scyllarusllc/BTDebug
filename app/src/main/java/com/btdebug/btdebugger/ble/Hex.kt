package com.btdebug.btdebugger.ble

fun ByteArray.toHex(separator: String = " "): String = joinToString(separator) { "%02X".format(it) }

fun ByteArray.toAscii(): String {
    val chars = CharArray(size) {
        val v = this[it].toInt() and 0xFF
        if (v in 32..126) v.toChar() else '·'
    }
    return String(chars)
}

/** Parses "0A 1B", "0a:1b", "0x0A1B" or "0a1b". Returns null when the text is not valid hex. */
fun String.parseHex(): ByteArray? {
    val clean = trim().removePrefix("0x").filter { !it.isWhitespace() && it != ':' && it != '-' }
    if (clean.isEmpty() || clean.length % 2 != 0) return null
    if (!clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
fun ByteArray.u16le(i: Int): Int = u8(i) or (u8(i + 1) shl 8)
fun ByteArray.u16be(i: Int): Int = (u8(i) shl 8) or u8(i + 1)
fun ByteArray.s16le(i: Int): Int = u16le(i).toShort().toInt()
fun ByteArray.u32le(i: Int): Long = u16le(i).toLong() or (u16le(i + 2).toLong() shl 16)
fun ByteArray.u32be(i: Int): Long = (u16be(i).toLong() shl 16) or u16be(i + 2).toLong()

fun macToBytes(mac: String): ByteArray? =
    runCatching { mac.split(":").map { it.toInt(16).toByte() }.toByteArray() }
        .getOrNull()?.takeIf { it.size == 6 }
