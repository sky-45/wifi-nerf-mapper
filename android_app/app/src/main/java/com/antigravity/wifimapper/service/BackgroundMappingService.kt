package com.antigravity.wifimapper.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.ScanResult
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.antigravity.wifimapper.cloud.CloudBatchUploader
import com.antigravity.wifimapper.collectors.ConnectedLinkProber
import com.antigravity.wifimapper.collectors.InertialPdrTracker
import com.antigravity.wifimapper.collectors.WifiMultiBandScanner
import com.antigravity.wifimapper.collectors.WifiRttRanger
import com.antigravity.wifimapper.model.LiveMappingTelemetry
import com.antigravity.wifimapper.model.Pose6DoF
import com.antigravity.wifimapper.sync.PoseRingBuffer
import com.antigravity.wifimapper.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Android ForegroundService that runs all sensors continuously with screen off
 * under PARTIAL_WAKE_LOCK and streams telemetry to the cloud REST endpoint.
 */
class BackgroundMappingService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val poseRingBuffer = PoseRingBuffer(capacity = 2000)

    private lateinit var pdrTracker: InertialPdrTracker
    private lateinit var wifiScanner: WifiMultiBandScanner
    private lateinit var linkProber: ConnectedLinkProber
    private lateinit var rttRanger: WifiRttRanger
    private lateinit var cloudUploader: CloudBatchUploader

    private var latestScanResults = listOf<ScanResult>()

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WifiMapper::WakeLock").apply {
            acquire(60 * 60 * 1000L) // 1 hr max safe lease
        }

        cloudUploader = CloudBatchUploader(applicationContext)
        cloudUploader.start()

        pdrTracker = InertialPdrTracker(applicationContext, poseRingBuffer)
        pdrTracker.onStepDetectedListener = { steps, strideM, pose ->
            _telemetryState.value = _telemetryState.value.copy(
                totalSteps = steps,
                totalDistanceM = pdrTracker.totalDistanceM.value,
                currentPose = pose
            )
        }
        pdrTracker.start()

        wifiScanner = WifiMultiBandScanner(applicationContext, poseRingBuffer) { scanBatch ->
            cloudUploader.enqueueEvent(scanBatch)
            _telemetryState.value = _telemetryState.value.copy(
                visibleApCount = scanBatch.scans.size,
                lastExcessWallLossDb = scanBatch.multibandPairs.firstOrNull()?.excessWallLossDb
            )
        }
        wifiScanner.start(intervalMs = 1500L)

        linkProber = ConnectedLinkProber(applicationContext, poseRingBuffer) { probeEvent ->
            cloudUploader.enqueueEvent(probeEvent)
        }
        linkProber.start(intervalMs = 100L)

        rttRanger = WifiRttRanger(applicationContext, poseRingBuffer) { rttEvent ->
            cloudUploader.enqueueEvent(rttEvent)
            _telemetryState.value = _telemetryState.value.copy(
                rttAnchorCount = _telemetryState.value.rttAnchorCount + 1
            )
        }
        rttRanger.startRangingLoop({ latestScanResults }, intervalMs = 500L)

        cloudUploader.onUploadTelemetryUpdate = { batches, bytes, pending ->
            _telemetryState.value = _telemetryState.value.copy(
                uploadedBatches = batches,
                totalUploadedBytesCompressed = bytes,
                pendingUploads = pending
            )
        }

        _telemetryState.value = _telemetryState.value.copy(isRunning = true)
    }

    override fun onDestroy() {
        super.onDestroy()
        wifiScanner.stop()
        linkProber.stop()
        rttRanger.stop()
        pdrTracker.stop()
        cloudUploader.stop()

        wakeLock?.let {
            if (it.isHeld) it.release()
        }

        _telemetryState.value = _telemetryState.value.copy(isRunning = false)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundNotification() {
        val channelId = "wifi_mapping_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Wi-Fi Mapping Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Wi-Fi Mapping Active")
            .setContentText("Collecting background multi-band Wi-Fi and PDR telemetry")
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1001,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(1001, notification)
        }
    }

    companion object {
        private val _telemetryState = MutableStateFlow(LiveMappingTelemetry())
        val telemetryState: StateFlow<LiveMappingTelemetry> = _telemetryState
    }
}
