package com.shopcallback.tracker.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val REQUIRED_PERMISSIONS = buildList {
    add(Manifest.permission.READ_CALL_LOG)
    add(Manifest.permission.READ_PHONE_STATE)
    add(Manifest.permission.READ_CONTACTS)
    add(Manifest.permission.CALL_PHONE)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}

fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean {
    val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

@Composable
fun OnboardingScreen(onAllGranted: () -> Unit) {
    val context = LocalContext.current
    var permissionsGranted by remember {
        mutableStateOf(
            REQUIRED_PERMISSIONS.all {
                androidx.core.content.ContextCompat.checkSelfPermission(context, it) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        )
    }
    var batteryExempted by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    var permissionsPermanentlyDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
        if (!permissionsGranted) {
            val activity = context as? Activity
            permissionsPermanentlyDenied = activity != null && results
                .filterValues { granted -> !granted }
                .keys
                .any { permission -> !activity.shouldShowRequestPermissionRationale(permission) }
        }
    }

    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        batteryExempted = isIgnoringBatteryOptimizations(context)
    }

    LaunchedEffect(permissionsGranted, batteryExempted) {
        if (permissionsGranted && batteryExempted) {
            onAllGranted()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Callback Tracker needs a few permissions to watch for missed calls.", style = MaterialTheme.typography.bodyLarge)

        if (!permissionsGranted) {
            Button(onClick = { permissionLauncher.launch(REQUIRED_PERMISSIONS.toTypedArray()) }) {
                Text("Grant call & contacts permissions")
            }

            if (permissionsPermanentlyDenied) {
                Text("Android will no longer show the permission dialog. Enable the permissions manually in app settings, then come back.")
                Button(onClick = {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    )
                    context.startActivity(intent)
                }) {
                    Text("Open App Settings")
                }
            }
        }

        if (permissionsGranted && !batteryExempted) {
            Text("One more step: allow this app to run in the background without battery restrictions.")
            Button(onClick = {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                )
                batteryLauncher.launch(intent)
            }) {
                Text("Disable battery optimization")
            }
        }
    }
}
