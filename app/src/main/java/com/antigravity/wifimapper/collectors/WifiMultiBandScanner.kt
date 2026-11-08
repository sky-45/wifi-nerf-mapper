package com.antigravity.wifimapper.collectors

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.SystemClock
import com.antigravity.wifimapper.model.MultiBandPair
import com.antigravity.wifimapper.model.WifiScanBatchEvent
import com.antigravity.wifimapper.model.WifiScanItem
import com.antigravity.wifimapper.sync.PoseRingBuffer
import kotlinx.coroutines.*
import kotlin.math.log10

/**
 * Sweeps Wi-Fi channels, filters stale scan cache, groups colocated 2.4/5/6 GHz radios,
 * and retro-interpolates poses at ScanResult.timestamp.
 */
class WifiMultiBandScanner(
    private val context: Context,
    private val poseRingBuffer: PoseRingBuffer,
    private val onScanBatchCollected: (WifiScanBatchEvent) -> Unit
) {
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var scanJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Tracks previously logged (BSSID, hw_timestamp) to discard duplicates
    private val loggedScanKeys = LinkedHashMap<String, Long>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                processScanResults(success)
            }
        }
    }

    private var currentIntervalMs: Long = 1000L

    fun start(intervalMs: Long = 1000L) {
        currentIntervalMs = intervalMs
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        startScanLoop()
    }

    private fun startScanLoop() {
        scanJob?.cancel()
        scanJob = scope.launch {
            while (isActive) {
                try {
                    @Suppress("DEPRECATION")
                    wifiManager.startScan()
                } catch (e: Exception) {
                    // Ignored if throttled
                }
                delay(currentIntervalMs)
            }
        }
    }

    fun updateInterval(newIntervalMs: Long) {
        if (currentIntervalMs == newIntervalMs) return
        currentIntervalMs = newIntervalMs
        if (scanJob?.isActive == true) {
            startScanLoop()
        }
    }

    fun stop() {
        scanJob?.cancel()
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {}
    }

    private fun processScanResults(updated: Boolean) {
        val rawResults = try {
            wifiManager.scanResults ?: return
        } catch (e: SecurityException) {
            return
        }

        val nowBootNs = SystemClock.elapsedRealtimeNanos()
        val nowBootMs = nowBootNs / 1_000_000L

        val validItems = mutableListOf<WifiScanItem>()
        val band24ByGroup = mutableMapOf<String, WifiScanItem>()
        val band5ByGroup = mutableMapOf<String, WifiScanItem>()

        for (sr in rawResults) {
            val hwTimestampNs = sr.timestamp * 1000L // sr.timestamp is microseconds since boot
            val scanAgeMs = nowBootMs - (sr.timestamp / 1000L)

            // Discard stale cached APs older than 2.0s
            if (scanAgeMs > 2000L || scanAgeMs < -100L) continue

            val key = "${sr.BSSID}_${sr.timestamp}"
            if (loggedScanKeys.containsKey(key)) continue
            loggedScanKeys[key] = hwTimestampNs
            if (loggedScanKeys.size > 2000) {
                val oldestKey = loggedScanKeys.keys.first()
                loggedScanKeys.remove(oldestKey)
            }

            val bandStr = when {
                sr.frequency in 2400..2500 -> "2.4GHz"
                sr.frequency in 5100..5900 -> "5GHz"
                sr.frequency in 5925..7125 -> "6GHz"
                else -> "Other"
            }

            val groupId = resolveApGroupId(sr.BSSID, sr.SSID)

            // Retro-interpolate the phone's 6-DoF pose at the exact hardware timestamp
            val pose = poseRingBuffer.interpolateAt(hwTimestampNs)

            val item = WifiScanItem(
                bssid = sr.BSSID,
                ssid = sr.SSID ?: "",
                apGroupId = groupId,
                band = bandStr,
                freqMhz = sr.frequency,
                bwMhz = resolveChannelWidthMhz(sr.channelWidth),
                rssiDbm = sr.level,
                is80211mc = sr.is80211mcResponder,
                hwTimestampNs = hwTimestampNs,
                scanAgeMs = scanAgeMs,
                pose = pose
            )
            validItems.add(item)

            if (bandStr == "2.4GHz") band24ByGroup[groupId] = item
            if (bandStr == "5GHz") band5ByGroup[groupId] = item
        }

        if (validItems.isEmpty()) return

        // Compute Multi-Band Differential Wall Loss for colocated pairs
        val multiBandPairs = mutableListOf<MultiBandPair>()
        for ((groupId, item24) in band24ByGroup) {
            val item5 = band5ByGroup[groupId] ?: continue

            // Free space path loss offset: 20 * log10(5200 / 2437) ≈ 6.6 dB
            val f1 = item24.freqMhz.toDouble().coerceAtLeast(1.0)
            val f2 = item5.freqMhz.toDouble().coerceAtLeast(1.0)
            val theoreticalFsOffsetDb = 20.0 * log10(f2 / f1)

            // Any attenuation beyond free-space offset is excess wall absorption
            val measuredDiffDb = (item24.rssiDbm - item5.rssiDbm).toDouble()
            val excessWallLossDb = (measuredDiffDb - theoreticalFsOffsetDb).coerceAtLeast(0.0)

            multiBandPairs.add(
                MultiBandPair(
                    apGroupId = groupId,
                    rssi24Dbm = item24.rssiDbm,
                    rssi5Dbm = item5.rssiDbm,
                    excessWallLossDb = excessWallLossDb
                )
            )
        }

        val batchEvent = WifiScanBatchEvent(
            timestampNs = nowBootNs,
            scans = validItems,
            multibandPairs = multiBandPairs,
            pose = poseRingBuffer.getLatest()
        )

        onScanBatchCollected(batchEvent)
    }

    companion object {
        fun resolveApGroupId(bssid: String, ssid: String?): String {
            val parts = bssid.split(":")
            if (parts.size == 6) {
                // Routers usually vary only the last octet or low bits for multi-band virtual BSSIDs
                val prefix = "${parts[0]}:${parts[1]}:${parts[2]}:${parts[3]}:${parts[4]}"
                val cleanSsid = ssid?.replace("_5G", "")?.replace("_2G", "")?.replace("-5G", "") ?: ""
                return "$prefix-$cleanSsid"
            }
            return bssid
        }

        fun resolveChannelWidthMhz(channelWidthCode: Int): Int {
            return when (channelWidthCode) {
                ScanResult.CHANNEL_WIDTH_20MHZ -> 20
                ScanResult.CHANNEL_WIDTH_40MHZ -> 40
                ScanResult.CHANNEL_WIDTH_80MHZ -> 80
                ScanResult.CHANNEL_WIDTH_160MHZ -> 160
                else -> 20
            }
        }
    }
}
