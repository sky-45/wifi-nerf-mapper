package com.antigravity.wifimapper.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.antigravity.wifimapper.service.BackgroundMappingService

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Automatically start service and request battery exemption once permissions are granted
        ensureBatteryOptimizationExemption()
        if (hasRequiredPermissions() && !BackgroundMappingService.telemetryState.value.isRunning) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, BackgroundMappingService::class.java)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Attempt to disable Wi-Fi scan throttling programmatically if system allows it
        try {
            Settings.Global.putInt(contentResolver, "wifi_scan_throttle_enabled", 0)
        } catch (_: SecurityException) {
            // Normal apps rely on standard scan interval + unthrottled 10Hz connected probe
        }

        if (hasRequiredPermissions()) {
            ensureBatteryOptimizationExemption()
            if (!BackgroundMappingService.telemetryState.value.isRunning) {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, BackgroundMappingService::class.java)
                )
            }
        } else {
            requestRequiredPermissions()
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MappingDashboardScreen(
                        onStartService = {
                            if (hasRequiredPermissions()) {
                                ContextCompat.startForegroundService(
                                    this,
                                    Intent(this, BackgroundMappingService::class.java)
                                )
                            } else {
                                Toast.makeText(
                                    this,
                                    "Please grant location permissions to start mapping",
                                    Toast.LENGTH_SHORT
                                ).show()
                                requestRequiredPermissions()
                            }
                        },
                        onStopService = {
                            stopService(Intent(this, BackgroundMappingService::class.java))
                        }
                    )
                }
            }
        }
    }

    private fun ensureBatteryOptimizationExemption() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } catch (_: Exception) {
            // Ignored if device does not support direct prompt
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // POST_NOTIFICATIONS is omitted intentionally so Android 13/14 suppresses
            // the persistent notification while allowing the foreground service to collect telemetry.
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun hasRequiredPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}

@Composable
fun MappingDashboardScreen(
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    val telemetry by BackgroundMappingService.telemetryState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Wi-Fi Indoor Mapper (Fleet)",
            style = MaterialTheme.typography.headlineSmall
        )

        // Trajectory Canvas View
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1E1E1E)),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val scale = 15f // 15 px per meter

                    // Draw origin
                    drawCircle(Color.Gray, radius = 6f, center = Offset(cx, cy))

                    // Draw current phone PDR position
                    val px = cx + (telemetry.currentPose.x * scale).toFloat()
                    val py = cy - (telemetry.currentPose.y * scale).toFloat()
                    drawCircle(Color.Cyan, radius = 10f, center = Offset(px, py))
                }
                Text(
                    text = "Pos: (%.2fm, %.2fm) | Steps: %d".format(
                        telemetry.currentPose.x,
                        telemetry.currentPose.y,
                        telemetry.totalSteps
                    ),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                )
            }
        }

        // Metrics Grid
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val shortDevice = if (telemetry.deviceId.length > 8) telemetry.deviceId.take(8) + "..." else telemetry.deviceId.ifEmpty { "Pending" }
                Text("Device Fleet ID: $shortDevice")
                Text("Environment: ${telemetry.environmentId} [${telemetry.dwellState}]")
                Text("Walk Distance: %.2f m".format(telemetry.totalDistanceM))
                Text("Visible Wi-Fi APs: ${telemetry.visibleApCount}")
                Text("RTT Anchor Hits: ${telemetry.rttAnchorCount}")
                val gnssText = if (telemetry.latestLat != null && telemetry.latestLon != null) {
                    val accStr = telemetry.latestGnssAccuracyM?.let { " ±%.0fm".format(it) } ?: ""
                    "Earth GNSS: %.5f, %.5f%s".format(telemetry.latestLat, telemetry.latestLon, accStr)
                } else {
                    "Earth GNSS: Acquiring fix..."
                }
                Text(gnssText)
                Text("Cloud Batches: ${telemetry.uploadedBatches} (%.1f KB gz)".format(
                    telemetry.totalUploadedBytesCompressed / 1024.0
                ))
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = {
                if (telemetry.isRunning) onStopService() else onStartService()
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (telemetry.isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text(if (telemetry.isRunning) "STOP MAPPING SERVICE" else "START BACKGROUND SERVICE")
        }
    }
}

