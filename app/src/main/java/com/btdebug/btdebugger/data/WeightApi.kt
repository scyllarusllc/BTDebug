package com.btdebug.btdebugger.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.Instant

object WeightApi {
    val serverPrompt = """
        Build a runnable server API for my BTDebug Android weight scale app so I can store readings and view my weight trends.
        Use the existing project's backend framework and database if present. If starting from scratch, use FastAPI with SQLite, persistent database storage, and dependency/setup instructions.

        The Android client sends one reading per request to an API URL I configure:
        POST /api/weights
        Content-Type: application/json; charset=utf-8
        Accept: application/json
        Authorization: Bearer <configured-token> (omitted if no token is configured)
        Idempotency-Key: <app_id>:<measurement-timestamp-in-milliseconds>

        Example JSON:
        {
          "event_id": "example-app-id:1791275400000",
          "app_id": "example-app-id",
          "app_name": "Weight Scale",
          "weight_kg": 72.5,
          "measured_at": "2026-10-06T08:30:00Z",
          "device_address": "AA:BB:CC:DD:EE:FF"
        }

        Compatibility requirements:
        - Keep these field names. Weight is always kilograms, even when the app displays pounds.
        - measured_at is an ISO 8601 UTC measurement timestamp from the phone, not the upload time. Historical uploads may arrive out of order.
        - device_address can be null for older records and can change for devices using private BLE addresses. Identify the dashboard using app_id, not the BLE address.
        - event_id is the stable identifier for a reading and matches the Idempotency-Key header. Enforce uniqueness atomically in the database. Retries may repeat the same event if the previous response was lost.
        - Return HTTP 201 or 200 only after committing a valid reading. Return HTTP 200 for an identical event already saved; do not use HTTP 409 for an identical retry. Reject conflicting payloads for an existing event_id without overwriting it.
        - The client treats any HTTP 2xx as acknowledged and ignores the response body. Non-2xx or network failures keep the reading queued for retry. Do not acknowledge failed validation or storage with 2xx.
        - Do not redirect POST requests; the client does not follow redirects. Respond promptly (client connection timeout 10 seconds, read timeout 15 seconds).

        Implementation requirements:
        - Validate required fields, a finite positive weight, and a timezone-aware timestamp. Preserve measurement precision and normalize timestamps to UTC.
        - Support a Bearer token from an environment variable, returning 401 for missing/invalid credentials when authentication is enabled. Use authentication by default for public deployment; never embed secrets in source code or logs.
        - Persist readings across restarts with a database schema/migration and an index on app_id and measured_at.
        - Add an authenticated GET /api/weights endpoint with app_id, optional from/to timestamps, and bounded pagination to return readings in measurement-time order for trends.
        - Include runnable code, dependencies, startup commands, environment-variable examples, database setup, and deployment guidance for HTTPS.
        - Include curl examples and tests for saving a reading, querying trends, duplicate retries, conflicting duplicates, null device_address, invalid input, and authentication failures.
        - Finish by telling me the exact POST URL and which token to enter in BTDebug's App settings. Use placeholder secrets in examples.
        - Provide an authenticated setup page that generates a QR code locally (not through an external QR service) for BTDebug's "Scan QR to import" button. The QR text format must be btdebug://<percent-encoded-token>@<complete-HTTP-or-HTTPS-POST-URL>, for example btdebug://YOUR_TOKEN@https://your-server.com/api/weights. Percent-encode the token with RFC 3986 encoding (including @, %, /, ?, #, and spaces); keep the API URL unchanged, including its scheme and query string. Without a token use btdebug://@https://your-server.com/api/weights. Protect this page and QR because they may contain credentials. A plain HTTP/HTTPS URL QR is also supported, but imports without a token.
    """.trimIndent()

    fun validationError(url: String, token: String): String? {
        val uri = runCatching { URI(url) }.getOrNull()
        return when {
            uri == null || uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank() -> "Enter a valid HTTP or HTTPS URL."
            uri.rawUserInfo != null || uri.rawFragment != null -> "Use a URL without embedded credentials or a fragment."
            token.contains('\r') || token.contains('\n') -> "Token must be a single line."
            else -> null
        }
    }

    fun payload(app: WeightApp, reading: Reading): String = JSONObject()
        .put("event_id", "${app.id}:${reading.time}")
        .put("app_id", app.id).put("app_name", app.name)
        .put("weight_kg", reading.kg)
        .put("measured_at", Instant.ofEpochMilli(reading.time).toString())
        .put("device_address", reading.deviceAddress ?: JSONObject.NULL)
        .toString()

    fun post(settings: ApiSettings, app: WeightApp, reading: Reading) {
        validationError(settings.url, settings.token)?.let { error(it) }
        val connection = URL(settings.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Idempotency-Key", "${app.id}:${reading.time}")
            if (settings.token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer ${settings.token}")
            connection.outputStream.use { it.write(payload(app, reading).toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) error("Server returned HTTP $status. Record kept for retry.")
        } finally {
            connection.disconnect()
        }
    }
}
