package com.antigravity.wifimapper.collectors

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import com.antigravity.wifimapper.model.WifiRttEvent
import com.antigravity.wifimapper.sync.PoseRingBuffer
import kotlinx.coroutines.*
import java.util.concurrent.Executors

/**
 * Executes Wi-Fi Round-Trip Time (RTT) ranging via IEEE 802.11mc / 802.11az Fine Timing Measurement (FTM)
 * with Android 12+ (API 31+) one-sided fallback support.
 */
class WifiRttRanger(
    private val context: Context,
    private val poseRingBuffer: PoseRingBuffer,
    private val onRttCollected: (WifiRttEvent) -> Unit
) {
    private val rttManager = if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)) {
        context.getSystemService(Context.WIFI_RTT_RANGING_SERVICE) as? WifiRttManager
    } else null

    private val rttExecutor = Executors.newSingleThreadExecutor()
    private var rangingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val isRttSupported: Boolean
        get() = rttManager != null && (rttManager.isAvailable)

    fun startRangingLoop(
        targetScanResultsProvider: () -> List<ScanResult>,
        intervalMs: Long = 500L
    ) {
        if (!isRttSupported) return

        rangingJob = scope.launch {
            while (isActive) {
                val targets = targetScanResultsProvider()
                if (targets.isNotEmpty()) {
                    triggerRanging(targets)
                }
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        rangingJob?.cancel()
    }

    private fun triggerRanging(scanResults: List<ScanResult>) {
        val manager = rttManager ?: return
        if (!manager.isAvailable) return

        val requestBuilder = RangingRequest.Builder()
        var addedCount = 0

        // 1. Add standard 802.11mc responders
        val mcResponders = scanResults.filter { it.is80211mcResponder }.take(RangingRequest.getMaxPeers())
        if (mcResponders.isNotEmpty()) {
            requestBuilder.addAccessPoints(mcResponders)
            addedCount += mcResponders.size
        }

        // 2. On Android 12+ (API 31+), add non-802.11mc APs for one-sided RTT if slots remain
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && addedCount < RangingRequest.getMaxPeers()) {
            val nonMcAPs = scanResults
                .filter { !it.is80211mcResponder }
                .take(RangingRequest.getMaxPeers() - addedCount)
            if (nonMcAPs.isNotEmpty()) {
                try {
                    requestBuilder.addNon80211mcCapableAccessPoints(nonMcAPs)
                    addedCount += nonMcAPs.size
                } catch (_: Exception) {}
            }
        }

        if (addedCount == 0) return

        try {
            manager.startRanging(
                requestBuilder.build(),
                rttExecutor,
                object : RangingResultCallback() {
                    override fun onRangingFailure(code: Int) {
                        // Channel busy or unassociated
                    }

                    override fun onRangingResults(results: List<RangingResult>) {
                        for (res in results) {
                            if (res.status == RangingResult.STATUS_SUCCESS) {
                                val hwTimestampNs = res.rangingTimestampMillis * 1_000_000L
                                val pose = poseRingBuffer.interpolateAt(hwTimestampNs)

                                val modeStr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && res.is80211mcMeasurement) {
                                    "TWO_SIDED_80211MC"
                                } else {
                                    "ONE_SIDED_RTT"
                                }

                                val event = WifiRttEvent(
                                    timestampNs = hwTimestampNs,
                                    bssid = res.macAddress?.toString() ?: "unknown",
                                    mode = modeStr,
                                    distanceMm = res.distanceMm,
                                    stdDevMm = res.distanceStdDevMm,
                                    rssiDbm = res.rssi,
                                    attempted = res.numAttemptedMeasurements,
                                    successful = res.numSuccessfulMeasurements,
                                    pose = pose
                                )
                                onRttCollected(event)
                            }
                        }
                    }
                }
            )
        } catch (e: SecurityException) {
            // Permission revoked
        }
    }
}
