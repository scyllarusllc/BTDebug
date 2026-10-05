@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.btdebug.btdebugger.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.btdebug.btdebugger.ApiSyncState
import com.btdebug.btdebugger.AppViewModel
import com.btdebug.btdebugger.data.ApiSettings
import com.btdebug.btdebugger.data.WeightApi
import com.btdebug.btdebugger.data.ApiImportConfiguration
import com.btdebug.btdebugger.data.ApiQrImport
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.common.MlKitException

@Composable
fun AppSettingsScreen(vm: AppViewModel, id: String) {
    val context = LocalContext.current
    val settingsMap by vm.apiSettings.collectAsStateWithLifecycle()
    val syncMap by vm.apiSync.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val saved = settingsMap[id] ?: ApiSettings()
    val sync = syncMap[id] ?: ApiSyncState()
    var url by rememberSaveable(id) { mutableStateOf(saved.url) }
    // Keep the token out of saved-instance-state bundles.
    var token by remember(id) { mutableStateOf(saved.token) }
    var enabled by rememberSaveable(id) { mutableStateOf(saved.enabled) }
    var scanningQr by remember { mutableStateOf(false) }
    var qrError by remember { mutableStateOf<String?>(null) }
    var imported by remember { mutableStateOf<ApiImportConfiguration?>(null) }
    val qrScanner = remember(context) {
        GmsBarcodeScanning.getClient(context, GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build())
    }
    val draft = ApiSettings(url.trim(), token.trim(), enabled)
    val changed = draft != saved
    val validation = if (draft.url.isNotEmpty() || enabled) WeightApi.validationError(draft.url, draft.token) else null

    Scaffold(topBar = {
        TopAppBar(title = { Text("App settings") }, navigationIcon = {
            IconButton({ vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        })
    }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(apps.firstOrNull { it.id == id }?.name ?: "Weight app", style = MaterialTheme.typography.titleLarge)
            Text("Save stable weight readings to your server. Local history is always kept.")
            OutlinedButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("BTDebug server API prompt", WeightApi.serverPrompt))
                Toast.makeText(context, "AI prompt copied", Toast.LENGTH_SHORT).show()
            }) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Text("Copy AI API prompt", Modifier.padding(start = 8.dp))
            }
            Text("Paste into your AI assistant to generate a compatible API with weight storage and trend queries. The prompt contains examples, without your URL or token.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                scanningQr = true
                qrError = null
                try {
                    qrScanner.startScan()
                        .addOnSuccessListener { barcode ->
                            scanningQr = false
                            val result = runCatching { ApiQrImport.parse(barcode.rawValue.orEmpty()) }
                            imported = result.getOrNull()
                            qrError = result.exceptionOrNull()?.message
                        }
                        .addOnCanceledListener { scanningQr = false }
                        .addOnFailureListener { error ->
                            scanningQr = false
                            qrError = when ((error as? MlKitException)?.errorCode) {
                                MlKitException.CODE_SCANNER_CANCELLED, MlKitException.CANCELLED -> null
                                MlKitException.CODE_SCANNER_UNAVAILABLE -> "Scanner module is downloading. Check your connection and try again shortly."
                                MlKitException.CODE_SCANNER_CAMERA_PERMISSION_NOT_GRANTED -> "Allow camera access for Google Play services, then retry."
                                MlKitException.CODE_SCANNER_GOOGLE_PLAY_SERVICES_VERSION_TOO_OLD -> "Update Google Play services to use the QR scanner."
                                else -> "Could not scan the QR code. Retry or enter the URL manually."
                            }
                        }
                } catch (_: Exception) {
                    scanningQr = false
                    qrError = "Could not open the QR scanner. You can enter the URL manually."
                }
            }, enabled = !scanningQr && !sync.busy) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Text(if (scanningQr) "Opening scanner…" else "Scan QR to import", Modifier.padding(start = 8.dp))
            }
            Text("Scan btdebug://<token>@<API URL> or a plain API URL. Review the import, then save settings.", style = MaterialTheme.typography.bodySmall)
            qrError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(
                url, { url = it }, label = { Text("API URL") },
                placeholder = { Text("https://your-server.com/api/weights") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                isError = validation != null,
            )
            validation?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (draft.url.startsWith("http://")) Text("HTTP sends weight and token without encryption. Use HTTPS for a public server.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                token, { token = it }, label = { Text("Bearer token (optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Auto-upload stable readings", modifier = Modifier.weight(1f))
                Switch(enabled, { enabled = it })
            }
            Button({ vm.saveApiSettings(id, draft) }, enabled = changed && validation == null && !sync.busy) { Text("Save settings") }
            Text("Upload status", style = MaterialTheme.typography.titleMedium)
            Text(sync.message)
            Text("${sync.pending} reading(s) queued")
            val canSend = !changed && !sync.busy && WeightApi.validationError(saved.url, saved.token) == null
            OutlinedButton({ vm.retryUploads(id) }, enabled = canSend && sync.pending > 0) { Text("Retry queued uploads") }
            OutlinedButton({ vm.syncHistory(id) }, enabled = canSend) { Text("Upload unsent history") }
            Text("Failed uploads stay queued after restart. Retry here, or reopen this weight app with auto-upload enabled. Uploads run while BTDebug is open.", style = MaterialTheme.typography.bodySmall)
            Text("API request", style = MaterialTheme.typography.titleMedium)
            Text("POST · application/json · success: HTTP 2xx", style = MaterialTheme.typography.bodySmall)
            Text("""{
  "event_id": "app-id:timestamp-ms",
  "app_id": "app-id",
  "app_name": "Weight Scale",
  "weight_kg": 72.5,
  "measured_at": "2026-10-06T08:30:00Z",
  "device_address": "AA:BB:CC:DD:EE:FF"
}""", fontFamily = Mono, style = MaterialTheme.typography.bodySmall)
            Text("Your server should deduplicate event_id (also sent as Idempotency-Key). Older local readings may have a null device_address.", style = MaterialTheme.typography.bodySmall)
        }
    }
    imported?.let { configuration ->
        AlertDialog(
            onDismissRequest = { imported = null },
            title = { Text("Import API settings?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(configuration.url)
                    Text(if (configuration.token.isEmpty()) "No token in QR; the current token will be cleared." else "Bearer token included (hidden).")
                    Text("This replaces the URL and token in the form. Review them and tap Save settings to apply. The auto-upload switch is unchanged.")
                }
            },
            confirmButton = { TextButton({
                url = configuration.url
                token = configuration.token
                imported = null
                Toast.makeText(context, "Imported. Tap Save settings to apply.", Toast.LENGTH_SHORT).show()
            }) { Text("Import") } },
            dismissButton = { TextButton({ imported = null }) { Text("Cancel") } },
        )
    }
}
