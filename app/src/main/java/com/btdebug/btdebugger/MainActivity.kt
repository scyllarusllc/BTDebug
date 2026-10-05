package com.btdebug.btdebugger

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.btdebug.btdebugger.ui.AppsScreens
import com.btdebug.btdebugger.ui.BTDebugTheme
import com.btdebug.btdebugger.ui.DeviceScreen
import com.btdebug.btdebugger.ui.HomeScreen
import com.btdebug.btdebugger.ui.AppSettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { BTDebugTheme { Surface(Modifier.fillMaxSize()) { BTDebugApp(vm) } } }
    }
}

private fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

private fun hasPermissions(ctx: Context) =
    requiredPermissions().all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

@Composable
fun BTDebugApp(vm: AppViewModel) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasPermissions(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = hasPermissions(ctx) }
    if (!granted) {
        PermissionScreen { launcher.launch(requiredPermissions()) }
        return
    }
    val route by vm.route.collectAsStateWithLifecycle()
    BackHandler(enabled = route != Route.Home) { vm.back() }
    // Stop the radio when the app is backgrounded; resume when it returns.
    var wasScanning by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { wasScanning = vm.scanner.scanning.value; vm.scanner.stop() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { if (wasScanning) vm.startScan() }
    when (val r = route) {
        Route.Home -> HomeScreen(vm)
        is Route.Device -> DeviceScreen(vm, r.address)
        is Route.WeightApp -> AppsScreens.WeightAppScreen(vm, r.id)
        is Route.AppSettings -> AppSettingsScreen(vm, r.id)
    }
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("BTDebug", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            "To discover nearby Bluetooth LE devices and talk to them, BTDebug needs the Nearby devices permission" +
                " (and location on Android, which Google requires for raw advertisement data). " +
                "Readings are saved on this phone. You can optionally upload them to your own API from App settings.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGrant) { Text("Grant permissions") }
    }
}
