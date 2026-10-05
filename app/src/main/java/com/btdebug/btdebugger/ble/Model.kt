package com.btdebug.btdebugger.ble

enum class DeviceKind(val label: String) {
    PHONE("Phone"), COMPUTER("Computer"), WEARABLE("Wearable"), AUDIO("Audio"), SCALE("Scale"),
    BEACON("Beacon"), TRACKER("Tracker"), SENSOR("Sensor"), APPLE("Apple device"), OTHER("Other")
}

/** One structured interpretation of an advertisement (OKOK scale, iBeacon, Eddystone...). */
data class Decoded(
    val protocol: String,
    val summary: String,
    val fields: List<Pair<String, String>> = emptyList(),
    val weightKg: Double? = null,
    val kind: DeviceKind? = null,
    val identified: String? = null,
)

class AdPacket(
    val address: String,
    val name: String?,
    val rssi: Int,
    val connectable: Boolean,
    val time: Long,
    val raw: ByteArray,
)

class ScanDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val connectable: Boolean,
    val firstSeen: Long,
    val lastSeen: Long,
    val packets: Int,
    val ad: ParsedAd,
    val decoded: List<Decoded>,
    val kind: DeviceKind,
    val identified: String?,
) {
    val isScale: Boolean get() = decoded.any { it.weightKg != null }
    val displayName: String get() = name ?: "Unknown device"
}

fun classify(ad: ParsedAd, decoded: List<Decoded>, name: String?): DeviceKind {
    decoded.firstNotNullOfOrNull { it.kind }?.let { return it }
    ad.appearance?.let { a ->
        when (Appearance.category(a)) {
            1 -> return DeviceKind.PHONE
            2 -> return DeviceKind.COMPUTER
            3 -> return DeviceKind.WEARABLE
            8, 9 -> return DeviceKind.TRACKER
            12, 13, 14, 16, 17, 18, 49 -> return DeviceKind.SENSOR
            50 -> return DeviceKind.SCALE
        }
    }
    val n = name?.lowercase().orEmpty()
    when {
        "iphone" in n || "pixel" in n || "galaxy" in n -> return DeviceKind.PHONE
        "macbook" in n || "ipad" in n || "laptop" in n -> return DeviceKind.COMPUTER
        "watch" in n || "band" in n -> return DeviceKind.WEARABLE
        "buds" in n || "airpods" in n || "headphone" in n || "speaker" in n -> return DeviceKind.AUDIO
    }
    val svc = ad.serviceUuids.map { Uuids.short(it) }
    when {
        "0x181D" in svc || "0x181B" in svc -> return DeviceKind.SCALE
        "0x180D" in svc -> return DeviceKind.WEARABLE
        "0x181A" in svc || "0x1809" in svc -> return DeviceKind.SENSOR
    }
    if (ad.manufacturer.containsKey(0x004C)) return DeviceKind.APPLE
    if (ad.manufacturer.containsKey(0x0006)) return DeviceKind.COMPUTER
    return DeviceKind.OTHER
}
