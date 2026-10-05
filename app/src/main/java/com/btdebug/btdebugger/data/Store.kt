package com.btdebug.btdebugger.data

import android.content.Context
import com.btdebug.btdebugger.ble.DeviceKind
import org.json.JSONArray
import org.json.JSONObject

data class FilterSpec(
    val connectableOnly: Boolean = false,
    val namedOnly: Boolean = false,
    val decodedOnly: Boolean = false,
    /** Hide devices weaker than this. -100 means "no limit". */
    val minRssi: Int = -100,
    val kinds: Set<DeviceKind> = emptySet(),
) {
    val isActive: Boolean get() = this != FilterSpec()
}

data class FilterPreset(val name: String, val spec: FilterSpec)
data class WeightApp(val id: String, val name: String, val created: Long, val useLb: Boolean = false)
data class Reading(val time: Long, val kg: Double, val deviceAddress: String? = null)
data class ApiSettings(val url: String = "", val token: String = "", val enabled: Boolean = false)

/** Small JSON-in-SharedPreferences store for local history and upload state. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("btdebug", Context.MODE_PRIVATE)

    fun loadPresets(): List<FilterPreset> = runCatching {
        val a = JSONArray(prefs.getString("presets", "[]"))
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            FilterPreset(o.getString("name"), FilterSpec(
                o.optBoolean("connectable"), o.optBoolean("named"), o.optBoolean("decoded"), o.optInt("minRssi", -100),
                o.optJSONArray("kinds")?.let { k -> List(k.length()) { runCatching { DeviceKind.valueOf(k.getString(it)) }.getOrNull() }.filterNotNull().toSet() }.orEmpty(),
            ))
        }
    }.getOrDefault(emptyList())

    fun savePresets(list: List<FilterPreset>) {
        val a = JSONArray()
        list.forEach { p ->
            a.put(JSONObject().put("name", p.name).put("connectable", p.spec.connectableOnly).put("named", p.spec.namedOnly)
                .put("decoded", p.spec.decodedOnly).put("minRssi", p.spec.minRssi)
                .put("kinds", JSONArray(p.spec.kinds.map { it.name })))
        }
        prefs.edit().putString("presets", a.toString()).apply()
    }

    fun loadApps(): List<WeightApp> = runCatching {
        val a = JSONArray(prefs.getString("apps", "[]"))
        List(a.length()) { i -> a.getJSONObject(i).let { WeightApp(it.getString("id"), it.getString("name"), it.getLong("created"), it.optBoolean("lb")) } }
    }.getOrDefault(emptyList())

    fun saveApps(list: List<WeightApp>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("created", it.created).put("lb", it.useLb)) }
        prefs.edit().putString("apps", a.toString()).apply()
    }

    fun loadReadings(appId: String): List<Reading> = runCatching {
        val a = JSONArray(prefs.getString("readings_$appId", "[]"))
        List(a.length()) { i -> a.getJSONObject(i).let { Reading(it.getLong("t"), it.getDouble("kg"), it.optString("device").takeIf { address -> address.isNotEmpty() }) } }
    }.getOrDefault(emptyList())

    fun saveReadings(appId: String, list: List<Reading>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("t", it.time).put("kg", it.kg).put("device", it.deviceAddress)) }
        prefs.edit().putString("readings_$appId", a.toString()).apply()
    }

    fun deleteReadings(appId: String) { prefs.edit().remove("readings_$appId").apply() }

    fun loadApiSettings(appId: String): ApiSettings = runCatching {
        val o = JSONObject(prefs.getString("api_$appId", "{}")!!)
        ApiSettings(o.optString("url"), o.optString("token"), o.optBoolean("enabled"))
    }.getOrDefault(ApiSettings())

    fun saveApiSettings(appId: String, settings: ApiSettings) {
        prefs.edit().putString("api_$appId", JSONObject().put("url", settings.url)
            .put("token", settings.token).put("enabled", settings.enabled).toString()).apply()
    }

    @Synchronized fun pendingUploads(appId: String): List<Reading> = runCatching {
        val a = JSONArray(prefs.getString("pending_$appId", "[]"))
        List(a.length()) { i -> a.getJSONObject(i).let {
            Reading(it.getLong("t"), it.getDouble("kg"), it.optString("device").takeIf(String::isNotEmpty))
        } }
    }.getOrDefault(emptyList())

    @Synchronized fun enqueueUpload(appId: String, reading: Reading) {
        if (reading.time in uploadedTimes(appId) || pendingUploads(appId).any { it.time == reading.time }) return
        savePending(appId, pendingUploads(appId) + reading)
    }

    @Synchronized fun completeUpload(appId: String, time: Long) {
        prefs.edit().putString("uploaded_$appId", JSONArray((uploadedTimes(appId) + time).toList()).toString()).commit()
        savePending(appId, pendingUploads(appId).filterNot { it.time == time })
    }

    @Synchronized fun removePendingUpload(appId: String, time: Long) {
        savePending(appId, pendingUploads(appId).filterNot { it.time == time })
    }

    private fun uploadedTimes(appId: String): Set<Long> = runCatching {
        val a = JSONArray(prefs.getString("uploaded_$appId", "[]"))
        List(a.length()) { a.getLong(it) }.toSet()
    }.getOrDefault(emptySet())

    private fun savePending(appId: String, readings: List<Reading>) {
        val a = JSONArray()
        readings.forEach { a.put(JSONObject().put("t", it.time).put("kg", it.kg).put("device", it.deviceAddress)) }
        prefs.edit().putString("pending_$appId", a.toString()).commit()
    }

    @Synchronized fun deleteApiData(appId: String) {
        prefs.edit().remove("api_$appId").remove("pending_$appId").remove("uploaded_$appId").apply()
    }
}
