package com.btdebug.btdebugger.ble

object Uuids {
    private const val BASE_SUFFIX = "-0000-1000-8000-00805F9B34FB"

    fun fromShort(v: Int): String = "0000%04X$BASE_SUFFIX".format(v and 0xFFFF)
    fun fromShort32(v: Long): String = "%08X$BASE_SUFFIX".format(v)

    fun from128(le: ByteArray): String {
        val h = le.reversedArray().toHex("")
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }

    /** "0x180F" for Bluetooth-base UUIDs, otherwise the full upper-case UUID. */
    fun short(uuid: String): String {
        val u = uuid.uppercase()
        return if (u.endsWith(BASE_SUFFIX) && u.startsWith("0000")) "0x" + u.substring(4, 8) else u
    }

    private fun id16(uuid: String): Int? {
        val u = uuid.uppercase()
        return if (u.endsWith(BASE_SUFFIX) && u.startsWith("0000")) u.substring(4, 8).toIntOrNull(16) else null
    }

    private val services = mapOf(
        0x1800 to "Generic Access", 0x1801 to "Generic Attribute", 0x1802 to "Immediate Alert",
        0x1803 to "Link Loss", 0x1804 to "Tx Power", 0x1805 to "Current Time", 0x1808 to "Glucose",
        0x1809 to "Health Thermometer", 0x180A to "Device Information", 0x180D to "Heart Rate",
        0x180F to "Battery", 0x1810 to "Blood Pressure", 0x1812 to "Human Interface Device",
        0x1814 to "Running Speed and Cadence", 0x1816 to "Cycling Speed and Cadence",
        0x1818 to "Cycling Power", 0x1819 to "Location and Navigation", 0x181A to "Environmental Sensing",
        0x181B to "Body Composition", 0x181C to "User Data", 0x181D to "Weight Scale",
        0x181F to "Continuous Glucose Monitoring", 0x1822 to "Pulse Oximeter",
        0xFE95 to "Xiaomi", 0xFEAA to "Eddystone", 0xFE2C to "Google Fast Pair",
        0xFD6F to "Exposure Notification", 0xFEED to "Tile", 0xFE9F to "Google",
    )

    private val characteristics = mapOf(
        0x2A00 to "Device Name", 0x2A01 to "Appearance", 0x2A02 to "Peripheral Privacy Flag",
        0x2A04 to "Peripheral Preferred Connection Parameters", 0x2A05 to "Service Changed",
        0x2A07 to "Tx Power Level", 0x2A19 to "Battery Level", 0x2A23 to "System ID",
        0x2A24 to "Model Number", 0x2A25 to "Serial Number", 0x2A26 to "Firmware Revision",
        0x2A27 to "Hardware Revision", 0x2A28 to "Software Revision", 0x2A29 to "Manufacturer Name",
        0x2A2B to "Current Time", 0x2A37 to "Heart Rate Measurement", 0x2A38 to "Body Sensor Location",
        0x2A39 to "Heart Rate Control Point", 0x2A1C to "Temperature Measurement",
        0x2A1E to "Intermediate Temperature", 0x2A35 to "Blood Pressure Measurement",
        0x2A4A to "HID Information", 0x2A4B to "Report Map", 0x2A4C to "HID Control Point",
        0x2A4D to "Report", 0x2A50 to "PnP ID", 0x2A6D to "Pressure", 0x2A6E to "Temperature",
        0x2A6F to "Humidity", 0x2A9D to "Weight Measurement", 0x2A9E to "Weight Scale Feature",
        0x2A9C to "Body Composition Measurement", 0x2A5B to "CSC Measurement",
    )

    private val vendor = mapOf(
        "6E400001-B5A3-F393-E0A9-E50E24DCCA9E" to "Nordic UART Service",
        "6E400002-B5A3-F393-E0A9-E50E24DCCA9E" to "Nordic UART RX (write)",
        "6E400003-B5A3-F393-E0A9-E50E24DCCA9E" to "Nordic UART TX (notify)",
        "0000FFE0-0000-1000-8000-00805F9B34FB" to "HM-10 / generic serial service",
        "0000FFE1-0000-1000-8000-00805F9B34FB" to "HM-10 / generic serial data",
    )

    fun serviceName(uuid: String): String? = vendor[uuid.uppercase()] ?: id16(uuid)?.let { services[it] }
    fun characteristicName(uuid: String): String? = vendor[uuid.uppercase()] ?: id16(uuid)?.let { characteristics[it] }

    /** Human-readable interpretation of well-known characteristic values, or null. */
    fun describeValue(uuid: String, v: ByteArray): String? {
        if (v.isEmpty()) return null
        return when (id16(uuid)) {
            0x2A00, 0x2A24, 0x2A25, 0x2A26, 0x2A27, 0x2A28, 0x2A29 -> "\"" + String(v, Charsets.UTF_8).trimEnd('\u0000') + "\""
            0x2A19 -> "${v.u8(0)} %"
            0x2A01 -> if (v.size >= 2) Appearance.name(v.u16le(0)) else null
            0x2A38 -> listOf("Other", "Chest", "Wrist", "Finger", "Hand", "Ear lobe", "Foot").getOrNull(v.u8(0))
            0x2A37 -> {
                val wide = v.u8(0) and 1 != 0
                if (wide && v.size >= 3) "${v.u16le(1)} bpm" else if (v.size >= 2) "${v.u8(1)} bpm" else null
            }
            0x2A6E -> if (v.size >= 2) "%.2f °C".format(v.s16le(0) / 100.0) else null
            0x2A6F -> if (v.size >= 2) "%.2f %%".format(v.u16le(0) / 100.0) else null
            0x2A6D -> if (v.size >= 4) "%.1f Pa".format(v.u32le(0) / 10.0) else null
            0x2A07 -> "${v[0].toInt()} dBm"
            else -> null
        }
    }
}

object Appearance {
    private val categories = mapOf(
        0 to "Unknown", 1 to "Phone", 2 to "Computer", 3 to "Watch", 4 to "Clock", 5 to "Display",
        6 to "Remote control", 7 to "Eye glasses", 8 to "Tag", 9 to "Keyring", 10 to "Media player",
        11 to "Barcode scanner", 12 to "Thermometer", 13 to "Heart rate sensor", 14 to "Blood pressure",
        15 to "HID", 16 to "Glucose meter", 17 to "Running/walking sensor", 18 to "Cycling",
        49 to "Pulse oximeter", 50 to "Weight scale", 51 to "Personal mobility", 52 to "Continuous glucose monitor",
        53 to "Insulin pump", 81 to "Outdoor sports activity",
    )

    fun category(value: Int): Int = value shr 6
    fun name(value: Int): String =
        "0x%04X · %s".format(value, categories[category(value)] ?: "category ${category(value)}")
}
