# Weight API integration

Open **Apps → your weight app → App settings** (gear icon). Set an HTTP or
HTTPS URL, optionally set a Bearer token, enable automatic uploads, and save.
HTTPS is recommended; HTTP is supported for local servers.
Use **Copy AI API prompt** on the settings page to copy a self-contained prompt
for an AI assistant to generate a compatible backend with persistent storage,
deduplication, authentication, and trend queries. It uses sample values and does
not include your configured URL or token.

Tap **Scan QR to import** to scan this link format:

```text
btdebug://YOUR_TOKEN@https://your-server.com/api/weights
```

Percent-encode special characters in the token (for example `@` becomes `%40`);
keep the complete API URL and its query string unchanged. Without a token use
`btdebug://@https://your-server.com/api/weights`. Plain HTTP/HTTPS URL QR codes
are also supported. Review the confirmation, tap **Import**, then **Save
settings**. Import only fills the form and leaves auto-upload unchanged. A
URL-only QR clears the draft token. The scanner uses Google Play services and
may need a connection to download its module on first use. Generate QR codes
locally on an authenticated server setup page; avoid external QR services when
including a token. The copied AI prompt includes QR generation instructions.

Settings are saved separately for each weight app. Automatic uploads are off
by default. The server URL and token remain on the phone.

When an open weight app saves a stable reading, BTDebug saves it locally first,
queues it persistently, and sends one JSON request per reading:

```http
POST /api/weights
Content-Type: application/json; charset=utf-8
Accept: application/json
Authorization: Bearer YOUR_TOKEN
Idempotency-Key: 62af7a06-b66f-4208-9e48-f552ffe2881e:1791275400000
```

The Authorization header is omitted when no token is configured.

```json
{
  "event_id": "62af7a06-b66f-4208-9e48-f552ffe2881e:1791275400000",
  "app_id": "62af7a06-b66f-4208-9e48-f552ffe2881e",
  "app_name": "OKOK Weight Scale",
  "weight_kg": 72.5,
  "measured_at": "2026-10-06T08:30:00Z",
  "device_address": "AA:BB:CC:DD:EE:FF"
}
```

- `weight_kg` is always kilograms, regardless of the display unit.
- `measured_at` is the phone's measurement time as an ISO 8601 UTC timestamp.
- `device_address` is the observed BLE address; it can change for devices using
  private addresses. Records saved before this feature have a null address.
- Use `app_id` to identify a dashboard and `event_id` as a unique database key.
  A retry can repeat a request if the server accepted it but its response was
  lost. Return a successful response for an event already stored.

Any HTTP 2xx response acknowledges a saved record; the response body is ignored.
The weight dashboard shows upload status and the queued count. A success toast
reports the number of acknowledged readings after an upload batch; failed or
empty batches do not show a success toast. Partial success also shows how many
readings remain queued.
Redirects are not followed. Other HTTP statuses and network failures retain the
record in the queue. Requests use a 10-second connection timeout and a 15-second
read timeout.

**Retry queued uploads** retries failed records. **Upload unsent history** also
queues older local records, skipping records already acknowledged by this phone.
Manual uploads are available even when automatic uploads are disabled.
Changing the server URL does not reset the acknowledged history.

Pending records survive app restart. With automatic uploads enabled, opening the
weight app or saving a new stable reading retries the queue. Uploads run in the
app process; there is no background scheduler. BLE measurements require keeping
BTDebug in the foreground. Deleting a local record removes its queued upload,
but cannot undo an in-flight request or delete a record already on the server.

To keep trends, the server should validate and save each event before returning
2xx, store `weight_kg` and `measured_at`, and deduplicate by `event_id`.
