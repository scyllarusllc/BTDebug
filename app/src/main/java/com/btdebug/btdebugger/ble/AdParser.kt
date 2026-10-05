package com.btdebug.btdebugger.ble

class AdStructure(val type: Int, val data: ByteArray)

/** A decoded BLE advertising payload (legacy or extended, advertisement and scan-response merged). */
class ParsedAd(val raw: ByteArray, val structures: List<AdStructure>) {
    private fun first(type: Int): ByteArray? = structures.firstOrNull { it.type == type }?.data

    val flags: Int? get() = first(0x01)?.takeIf { it.isNotEmpty() }?.u8(0)
    val localName: String?
        get() = (first(0x09) ?: first(0x08))?.let { String(it, Charsets.UTF_8) }?.trim { it <= ' ' }?.takeIf { it.isNotEmpty() }
    val txPower: Int? get() = first(0x0A)?.takeIf { it.isNotEmpty() }?.get(0)?.toInt()
    val appearance: Int? get() = first(0x19)?.takeIf { it.size >= 2 }?.u16le(0)

    val serviceUuids: List<String> by lazy {
        buildList {
            for (s in structures) when (s.type) {
                0x02, 0x03 -> for (i in 0 until s.data.size / 2) add(Uuids.fromShort(s.data.u16le(i * 2)))
                0x04, 0x05 -> for (i in 0 until s.data.size / 4) add(Uuids.fromShort32(s.data.u32le(i * 4)))
                0x06, 0x07 -> for (i in 0 until s.data.size / 16) add(Uuids.from128(s.data.copyOfRange(i * 16, i * 16 + 16)))
            }
        }
    }

    /** Company id → payload (without the company id). Later entries win if repeated. */
    val manufacturer: Map<Int, ByteArray> by lazy {
        structures.filter { it.type == 0xFF && it.data.size >= 2 }
            .associate { it.data.u16le(0) to it.data.copyOfRange(2, it.data.size) }
    }

    /** Service UUID → service data payload. */
    val serviceData: Map<String, ByteArray> by lazy {
        buildMap {
            for (s in structures) when (s.type) {
                0x16 -> if (s.data.size >= 2) put(Uuids.fromShort(s.data.u16le(0)), s.data.copyOfRange(2, s.data.size))
                0x20 -> if (s.data.size >= 4) put(Uuids.fromShort32(s.data.u32le(0)), s.data.copyOfRange(4, s.data.size))
                0x21 -> if (s.data.size >= 16) put(Uuids.from128(s.data.copyOfRange(0, 16)), s.data.copyOfRange(16, s.data.size))
            }
        }
    }

    val isEmpty: Boolean get() = structures.isEmpty()
}

object AdParser {
    val EMPTY = ParsedAd(ByteArray(0), emptyList())

    fun parse(bytes: ByteArray?): ParsedAd {
        if (bytes == null || bytes.isEmpty()) return EMPTY
        val list = ArrayList<AdStructure>()
        var i = 0
        while (i < bytes.size) {
            val len = bytes.u8(i)
            if (len == 0 || i + 1 + len > bytes.size) break
            list += AdStructure(bytes.u8(i + 1), bytes.copyOfRange(i + 2, i + 1 + len))
            i += len + 1
        }
        // Android pads the record with zeros; keep only the meaningful bytes.
        return ParsedAd(bytes.copyOf(i), list)
    }

    fun typeName(type: Int): String = when (type) {
        0x01 -> "Flags"
        0x02 -> "Incomplete 16-bit UUIDs"
        0x03 -> "Complete 16-bit UUIDs"
        0x04 -> "Incomplete 32-bit UUIDs"
        0x05 -> "Complete 32-bit UUIDs"
        0x06 -> "Incomplete 128-bit UUIDs"
        0x07 -> "Complete 128-bit UUIDs"
        0x08 -> "Shortened name"
        0x09 -> "Complete name"
        0x0A -> "TX power"
        0x12 -> "Peripheral conn. interval"
        0x14 -> "Solicited 16-bit UUIDs"
        0x16 -> "Service data (16-bit)"
        0x19 -> "Appearance"
        0x20 -> "Service data (32-bit)"
        0x21 -> "Service data (128-bit)"
        0x24 -> "URI"
        0x2A -> "Mesh message"
        0x3D -> "3D information"
        0xFF -> "Manufacturer data"
        else -> "AD type 0x%02X".format(type)
    }

    fun flagsText(flags: Int): String = buildList {
        if (flags and 0x01 != 0) add("LE limited discoverable")
        if (flags and 0x02 != 0) add("LE general discoverable")
        if (flags and 0x04 != 0) add("BR/EDR not supported")
        if (flags and 0x08 != 0) add("LE+BR/EDR controller")
        if (flags and 0x10 != 0) add("LE+BR/EDR host")
    }.joinToString(", ").ifEmpty { "none" }
}
