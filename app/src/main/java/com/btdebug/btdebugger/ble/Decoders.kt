package com.btdebug.btdebugger.ble

/** Broadcast decoders. Each returns zero or more interpretations of the advertisement. */
object Decoders {
    private val companies = mapOf(
        0x0006 to "Microsoft", 0x004C to "Apple", 0x0075 to "Samsung", 0x00E0 to "Google",
        0x0059 to "Nordic Semiconductor", 0x0087 to "Garmin", 0x00D2 to "Dialog Semiconductor",
        0x0157 to "Zepp / Huami", 0x038F to "Xiaomi", 0x02E5 to "Espressif", 0x0499 to "Ruuvi",
        0x0131 to "Cypress", 0x000D to "Texas Instruments", 0x0046 to "Mediatek", 0x0171 to "Amazon",
        0x0822 to "Adafruit", 0x0DC0 to "OKOK / Chipsea scale",
    )

    fun companyName(id: Int): String = companies[id] ?: "unknown company"

    fun decode(ad: ParsedAd, address: String): List<Decoded> = buildList {
        val mac = macToBytes(address)
        for ((id, d) in ad.manufacturer) {
            okok(id, d, mac)?.let { add(it) }
            if (id == 0x004C) appleDecoder(d)?.let { add(it) }
            if (id == 0x0006 && d.isNotEmpty() && d[0].toInt() == 0x01) {
                add(Decoded("Microsoft CDP", "Windows / Microsoft Connected Devices Platform beacon",
                    kind = DeviceKind.COMPUTER, identified = "Windows device"))
            }
        }
        for ((uuid, d) in ad.serviceData) {
            when (Uuids.short(uuid)) {
                "0xFEAA" -> eddystone(d)?.let { add(it) }
                "0xFE95" -> add(Decoded("Xiaomi MiBeacon", "Service data ${d.toHex()}", kind = DeviceKind.SENSOR, identified = "Xiaomi device"))
                "0xFE2C" -> add(Decoded("Google Fast Pair", "Model/anti-spoof payload ${d.toHex()}", kind = DeviceKind.AUDIO))
                "0xFD6F" -> add(Decoded("Exposure Notification", "Rolling proximity identifier ${d.toHex("")}"))
                "0xFEED" -> add(Decoded("Tile", "Tile tracker beacon", kind = DeviceKind.TRACKER, identified = "Tile tracker"))
                "0x181D" -> add(Decoded("Weight Scale service data", d.toHex()))
            }
        }
    }

    // OKOK / Chipsea broadcast scales: manufacturer data = weight (centi-kg, big-endian) + status + MAC.
    // Real captures: 17 BB … 0A 01 25 + MAC with weight 0x17BB = 6075 → 60.75 kg.
    private fun okok(companyId: Int, d: ByteArray, mac: ByteArray?): Decoded? {
        if (d.size < 7) return null
        val macTail = mac != null && d.size >= 6 && d.copyOfRange(d.size - 6, d.size).contentEquals(mac)
        if (!macTail && companyId != 0x0DC0) return null
        val centi = d.u16be(0)
        val kg = centi / 100.0
        if (kg <= 0.0 || kg > 250.0) return null
        return Decoded(
            protocol = "OKOK / Chipsea scale",
            summary = "%.2f kg".format(kg),
            fields = listOf(
                "Weight" to "%.2f kg (%.2f lb)".format(kg, kg * 2.2046226218),
                "Raw weight" to "0x%04X (%d)".format(centi, centi),
                "Impedance / aux" to if (d.size >= 4) "0x%04X".format(d.u16be(2)) else "n/a",
                "Status bytes" to d.copyOfRange(4, minOf(d.size, 7)).toHex(),
            ),
            weightKg = kg,
            kind = DeviceKind.SCALE,
            identified = "OKOK weight scale",
        )
    }

    private fun appleDecoder(d: ByteArray): Decoded? {
        if (d.size < 2) return null
        val type = d.u8(0)
        if (type == 0x02 && d.size >= 23 && d.u8(1) == 0x15) {
            val uuid = Uuids.from128(d.copyOfRange(2, 18).reversedArray())
            return Decoded(
                "iBeacon", "major ${d.u16be(18)} · minor ${d.u16be(20)}",
                listOf("UUID" to uuid, "Major" to d.u16be(18).toString(), "Minor" to d.u16be(20).toString(),
                    "Measured power @1m" to "${d[22].toInt()} dBm"),
                kind = DeviceKind.BEACON, identified = "iBeacon",
            )
        }
        val label = when (type) {
            0x05 -> "AirDrop"; 0x07 -> "Proximity Pairing (AirPods)"; 0x09 -> "AirPlay target"
            0x0C -> "Handoff"; 0x0F -> "Nearby Action"; 0x10 -> "Nearby Info"; 0x12 -> "Find My"
            0x16 -> "Nearby (Continuity)"; else -> "Continuity 0x%02X".format(type)
        }
        val kind = if (type == 0x07) DeviceKind.AUDIO else if (type == 0x12) DeviceKind.TRACKER else DeviceKind.APPLE
        return Decoded("Apple Continuity", label, listOf("Message type" to "0x%02X".format(type), "Payload" to d.toHex()),
            kind = kind, identified = if (type == 0x07) "AirPods / Beats" else "Apple device · $label")
    }

    private fun eddystone(d: ByteArray): Decoded? {
        if (d.isEmpty()) return null
        return when (d.u8(0)) {
            0x00 -> if (d.size >= 18) Decoded("Eddystone-UID", "namespace ${d.copyOfRange(2, 12).toHex("")}",
                listOf("Tx power @0m" to "${d[1].toInt()} dBm", "Namespace" to d.copyOfRange(2, 12).toHex(""),
                    "Instance" to d.copyOfRange(12, 18).toHex("")), kind = DeviceKind.BEACON, identified = "Eddystone beacon") else null
            0x10 -> if (d.size >= 3) {
                val schemes = arrayOf("http://www.", "https://www.", "http://", "https://")
                val exp = arrayOf(".com/", ".org/", ".edu/", ".net/", ".info/", ".biz/", ".gov/", ".com", ".org", ".edu", ".net", ".info", ".biz", ".gov")
                val url = buildString {
                    append(schemes.getOrElse(d.u8(2)) { "" })
                    for (i in 3 until d.size) { val b = d.u8(i); if (b < exp.size) append(exp[b]) else append(b.toChar()) }
                }
                Decoded("Eddystone-URL", url, listOf("URL" to url, "Tx power @0m" to "${d[1].toInt()} dBm"),
                    kind = DeviceKind.BEACON, identified = "Eddystone beacon")
            } else null
            0x20 -> if (d.size >= 14) {
                val temp = d.u16be(4).toShort().toInt()
                Decoded("Eddystone-TLM", "battery ${d.u16be(2)} mV",
                    listOf("Battery" to "${d.u16be(2)} mV",
                        "Temperature" to if (temp == -0x8000) "n/a" else "%.2f °C".format(temp / 256.0),
                        "Advertisement count" to d.u32be(6).toString(),
                        "Uptime" to "%.1f s".format(d.u32be(10) / 10.0)), kind = DeviceKind.BEACON)
            } else null
            else -> null
        }
    }
}
