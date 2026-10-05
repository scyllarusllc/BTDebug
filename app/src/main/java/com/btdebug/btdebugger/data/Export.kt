package com.btdebug.btdebugger.data

import com.btdebug.btdebugger.ble.GattEvent
import com.btdebug.btdebugger.ble.toAscii
import com.btdebug.btdebugger.ble.toHex
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AdRecord(
    val time: Long,
    val rssi: Int,
    val raw: ByteArray,
    /** One flag per raw byte: true when it differs from the previous distinct packet. */
    val changed: List<Boolean>,
    val count: Int = 1,
    val summary: String? = null,
)

object Export {
    private val iso get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

    fun json(address: String, name: String?, adv: List<AdRecord>, gatt: List<GattEvent>): String = JSONObject().apply {
        put("device", JSONObject().put("address", address).put("name", name ?: JSONObject.NULL))
        put("exportedAt", iso.format(Date()))
        put("advertisements", JSONArray().also { a ->
            adv.sortedBy { it.time }.forEach {
                a.put(JSONObject().put("time", iso.format(Date(it.time))).put("rssi", it.rssi).put("hex", it.raw.toHex(""))
                    .put("count", it.count).put("decoded", it.summary ?: JSONObject.NULL))
            }
        })
        put("gatt", JSONArray().also { a ->
            gatt.forEach {
                a.put(JSONObject().put("time", iso.format(Date(it.time))).put("kind", it.kind.name).put("uuid", it.uuid ?: JSONObject.NULL)
                    .put("hex", it.value?.toHex("") ?: JSONObject.NULL).put("status", it.status).put("note", it.note ?: JSONObject.NULL))
            }
        })
    }.toString(2)

    fun csv(adv: List<AdRecord>, gatt: List<GattEvent>): String = buildString {
        appendLine("time,source,kind,uuid,rssi,hex,ascii,status,note")
        val rows = adv.map { it.time to "${iso.format(Date(it.time))},adv,advertisement,,${it.rssi},${it.raw.toHex("")},${q(it.raw.toAscii())},,${q(it.summary ?: "")}" } +
            gatt.map { it.time to "${iso.format(Date(it.time))},gatt,${it.kind.name.lowercase()},${it.uuid ?: ""},,${it.value?.toHex("") ?: ""},${q(it.value?.toAscii() ?: "")},${it.status},${q(it.note ?: "")}" }
        rows.sortedBy { it.first }.forEach { appendLine(it.second) }
    }

    private fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
}
