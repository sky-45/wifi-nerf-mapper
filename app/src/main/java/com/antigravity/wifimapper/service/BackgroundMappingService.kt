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
import com.antigravity.wifimapper.R
import com.antigravity.wifimapper.cloud.CloudBatchUploader
import com.antigravity.wifimapper.cloud.CloudConfig
import com.antigravity.wifimapper.cloud.CloudProviderMode
import com.antigravity.wifimapper.cloud.DeviceIdentityManager
import com.antigravity.wifimapper.collectors.ConnectedLinkProber
import com.antigravity.wifimapper.collectors.EnvironmentDwellManager
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
 * under PARTIAL_WAKE_LOCK, orchestrates multi-device fleet identity, 24/7 environment
 * dwell clustering with adaptive duty-cycling, and streams telemetry to cloud storage.
 */
class BackgroundMappingService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val poseRingBuffer = PoseRingBuffer(capacity = 2000)

    private lateinit var deviceIdentityManager: DeviceIdentityManager
    private lateinit var environmentDwellManager: EnvironmentDwellManager

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
            setReferenceCounted(false)
            acquire()
        }

        // Initialize fleet device identity and 24/7 dwell cluster manager
        deviceIdentityManager = DeviceIdentityManager(applicationContext)
        environmentDwellManager = EnvironmentDwellManager()

        // Configure cloud uploader with hardware device ID and dynamic environment state
        val ociParUrl = runCatching {
            val resId = resources.getIdentifier("cloud_upload_url", "string", packageName)
            val custom = if (resId != 0) getString(resId) else ""
            if (custom.isNotBlank() && !custom.contains("EXAMPLE_PAR_TOKEN")) custom else getString(R.string.oci_par_base_url)
        }.getOrDefault(getString(R.string.oci_par_base_url))
        val cloudModeStr = getString(R.string.cloud_provider_mode)
        val cloudMode = if (cloudModeStr.contains("OCI", ignoreCase = true)) {
            CloudProviderMode.OCI_OBJECT_STORAGE
        } else {
            CloudProviderMode.REST_INGESTION
        }
        val authToken = getString(R.string.cloud_auth_token)

        val cloudConfig = CloudConfig(
            mode = cloudMode,
            ociParBaseUrl = ociParUrl,
            authToken = authToken,
            useOciPut = true
        )

        cloudUploader = CloudBatchUploader(
            context = applicationContext,
            config = cloudConfig,
            deviceIdProvider = { deviceIdentityManager.getDeviceId() },
            environmentIdProvider = { environmentDwellManager.currentEnvironmentId },
            dwellStateProvider = { environmentDwellManager.currentDwellState.name }
        )
        cloudUploader.start()

        // Handle adaptive duty-cycling on state changes (e.g. CAMPING vs IN_TRANSIT)
        environmentDwellManager.onDutyCycleChanged = { wifiScanMs, batchUploadMs ->
            wifiScanner.updateInterval(wifiScanMs)
            cloudUploader.updateBatchInterval(batchUploadMs)
            _telemetryState.value = _telemetryState.value.copy(
                environmentId = environmentDwellManager.currentEnvironmentId,
                dwellState = environmentDwellManager.currentDwellState.name
            )
        }

        environmentDwellManager.onDwellStateChanged = { _, newState, envId ->
            _telemetryState.value = _telemetryState.value.copy(
                environmentId = envId,
                dwellState = newState.name
            )
        }

        // High-rate PDR step tracker (100% background inertial dead-reckoning)
        pdrTracker = InertialPdrTracker(applicationContext, poseRingBuffer)
        pdrTracker.onStepDetectedListener = { steps, strideM, pose ->
            val stepEvent = com.antigravity.wifimapper.model.MagBaroPdrEvent(
                timestampNs = System.nanoTime(),
                deviceId = deviceIdentityManager.getDeviceId(),
                environmentId = environmentDwellManager.currentEnvironmentId,
                dwellState = environmentDwellManager.currentDwellState.name,
                pressureHpa = pdrTracker.latestPressureHpa,
                stepDetected = true,
                strideLengthM = strideM,
                isStationary = pose.isStationary,
                pose = pose
            )
            cloudUploader.enqueueHfEvent(stepEvent)

            _telemetryState.value = _telemetryState.value.copy(
                totalSteps = steps,
                totalDistanceM = pdrTracker.totalDistanceM.value,
                currentPose = pose
            )
        }
        pdrTracker.start()

        // Multi-band Wi-Fi scanner connected to dwell clustering -> LF architectural scans
        wifiScanner = WifiMultiBandScanner(applicationContext, poseRingBuffer) { scanBatch ->
            // Update 24/7 environment dwell clustering
            environmentDwellManager.processScanBatch(scanBatch.scans)

            // Enrich batch with device & environment metadata
            val enrichedBatch = scanBatch.copy(
                deviceId = deviceIdentityManager.getDeviceId(),
                environmentId = environmentDwellManager.currentEnvironmentId,
                dwellState = environmentDwellManager.currentDwellState.name
            )
            cloudUploader.enqueueLfEvent(enrichedBatch)

            _telemetryState.value = _telemetryState.value.copy(
                visibleApCount = scanBatch.scans.size,
                lastExcessWallLossDb = scanBatch.multibandPairs.firstOrNull()?.excessWallLossDb,
                environmentId = environmentDwellManager.currentEnvironmentId,
                dwellState = environmentDwellManager.currentDwellState.name
            )
        }
        wifiScanner.start(intervalMs = environmentDwellManager.recommendedWifiScanIntervalMs)

        // Connected AP probing -> HF live telemetry
        linkProber = ConnectedLinkProber(applicationContext, poseRingBuffer) { probeEvent ->
            val enrichedProbe = probeEvent.copy(
                deviceId = deviceIdentityManager.getDeviceId(),
                environmentId = environmentDwellManager.currentEnvironmentId,
                dwellState = environmentDwellManager.currentDwellState.name
            )
            cloudUploader.enqueueHfEvent(enrichedProbe)
        }
        linkProber.start(intervalMs = 100L)

        // 802.11mc FTM RTT Ranger -> LF architectural scans
        rttRanger = WifiRttRanger(applicationContext, poseRingBuffer) { rttEvent ->
            val enrichedRtt = rttEvent.copy(
                deviceId = deviceIdentityManager.getDeviceId(),
                environmentId = environmentDwellManager.currentEnvironmentId,
                dwellState = environmentDwellManager.currentDwellState.name
            )
            cloudUploader.enqueueLfEvent(enrichedRtt)
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

        _telemetryState.value = _telemetryState.value.copy(
            isRunning = true,
            deviceId = deviceIdentityManager.getDeviceId(),
            environmentId = environmentDwellManager.currentEnvironmentId,
            dwellState = environmentDwellManager.currentDwellState.name
        )
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
        val channelId = "wifi_mapping_silent_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            // Delete old channel if it exists so system does not retain old LOW importance
            nm?.deleteNotificationChannel("wifi_mapping_channel")

            val channel = NotificationChannel(
                channelId,
                "Background Service",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Silent background telemetry sync"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            nm?.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Wi-Fi Mapper")
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
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
