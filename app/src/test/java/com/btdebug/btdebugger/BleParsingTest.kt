package com.btdebug.btdebugger

import com.btdebug.btdebugger.ble.AdParser
import com.btdebug.btdebugger.ble.DeviceKind
import com.btdebug.btdebugger.ble.Decoders
import com.btdebug.btdebugger.ble.Uuids
import com.btdebug.btdebugger.ble.parseHex
import com.btdebug.btdebugger.ble.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleParsingTest {
    // Captured from a real OKOK/Chipsea scale (F8:8F:C8:18:97:C0) showing 60.75 kg.
    private val okok = "10 FF C0 0D 17 BB 17 70 0A 01 25 F8 8F C8 18 97 C0".parseHex()!!

    @Test fun parsesHexVariants() {
        assertEquals("0A 1B", "0a:1b".parseHex()!!.toHex())
        assertEquals("0A 1B", "0x0A1B".parseHex()!!.toHex())
        assertNull("0A1".parseHex())
        assertNull("zz".parseHex())
    }

    @Test fun splitsAdStructuresAndTrimsPadding() {
        val ad = AdParser.parse(okok + ByteArray(20))
        assertEquals(1, ad.structures.size)
        assertEquals(okok.size, ad.raw.size)
        assertEquals(0x0DC0, ad.manufacturer.keys.single())
    }

    @Test fun decodesOkokScaleWeight() {
        val ad = AdParser.parse(okok)
        val d = Decoders.decode(ad, "F8:8F:C8:18:97:C0").single()
        assertEquals(60.75, d.weightKg!!, 0.0001)
        assertEquals(DeviceKind.SCALE, d.kind)
    }

    @Test fun identifiesMicrosoftCdp() {
        val raw = "1E FF 06 00 01 09 20 22 49 85 ED 0B 55 12 67 B7 55 F3 34 6A 52 07 4C B1 1C C3 CF 75 63 91 7B".parseHex()!!
        val d = Decoders.decode(AdParser.parse(raw), "14:C0:5E:CB:5E:F0")
        assertEquals(DeviceKind.COMPUTER, d.first().kind)
    }

    @Test fun decodesIBeacon() {
        val raw = "02 01 06 1A FF 4C 00 02 15 FD A5 06 93 A4 E2 4F B1 AF CF C6 EB 07 64 78 25 27 11 4E 20 C5".parseHex()!!
        val d = Decoders.decode(AdParser.parse(raw), "AA:BB:CC:DD:EE:FF").first { it.protocol == "iBeacon" }
        assertTrue(d.fields.contains("Major" to "10001"))
        assertTrue(d.fields.contains("Minor" to "20000"))
    }

    @Test fun decodesEddystoneUrl() {
        // flags, 16-bit service list (FEAA), service data: URL frame, tx -20, https://, "goo" + ".com"
        val raw = "02 01 06 03 03 AA FE 0A 16 AA FE 10 EC 03 67 6F 6F 07".parseHex()!!
        val d = Decoders.decode(AdParser.parse(raw), "AA:BB:CC:DD:EE:FF").single()
        assertEquals("Eddystone-URL", d.protocol)
        assertEquals("https://goo.com", d.summary)
    }

    @Test fun formatsUuids() {
        assertEquals("0x180F", Uuids.short("0000180f-0000-1000-8000-00805f9b34fb"))
        assertEquals("Battery", Uuids.serviceName("0000180f-0000-1000-8000-00805f9b34fb"))
        assertNotNull(Uuids.describeValue("00002a19-0000-1000-8000-00805f9b34fb", byteArrayOf(87)))
        assertEquals("87 %", Uuids.describeValue("00002a19-0000-1000-8000-00805f9b34fb", byteArrayOf(87)))
    }
}
