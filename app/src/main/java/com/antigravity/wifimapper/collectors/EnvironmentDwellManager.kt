package com.antigravity.wifimapper.collectors

import android.os.SystemClock
import com.antigravity.wifimapper.model.DwellState
import com.antigravity.wifimapper.model.WifiScanItem
import java.security.MessageDigest

/**
 * Manages 24/7 background RF environment clustering, dwell time tracking,
 * and adaptive duty-cycling for continuous mobile collection.
 *
 * Concepts:
 * 1. Top-8 Strongest BSSIDs: Indoor Wi-Fi environments (apartments, offices, cafes)
 *    are characterized by the top-8 highest-RSSI access points. Weak distant APs
 *    fluctuate near the noise floor (-85 to -95 dBm) and introduce noise if not filtered.
 * 2. Jaccard Index Clustering:
 *       J(A, B) = |A ∩ B| / |A ∪ B|
 *    If J(A, B) >= threshold (0.50), the device is considered to remain within the
 *    same physical RF cluster.
 * 3. Canonical SHA-256 Environment ID:
 *    Sorted canonical BSSIDs hashed with SHA-256 yield a deterministic, cross-device
 *    cluster identifier: "env_{sha256_prefix}".
 * 4. State Machine (CAMPING vs IN_TRANSIT):
 *    - IN_TRANSIT: Rapid physical movement, transient APs, or walking between buildings.
 *      Requires fast sampling (1.5s scan interval, 15s upload) to map fine spatial gradients.
 *    - CAMPING: Stationary or dwelling in a single room/building for >30 seconds.
 *      Duty-cycles Wi-Fi scanning to 15s and uploads to 60s, saving >75% battery while
 *      maintaining 24/7 environment occupancy monitoring.
 */
class EnvironmentDwellManager(
    private val jaccardThreshold: Double = 0.50,
    private val campingDwellThresholdMs: Long = 30_000L,
    private val campingScanCountThreshold: Int = 3
) {
    // Current cluster signature (set of BSSIDs in uppercase canonical format)
    private var activeClusterBssids = setOf<String>()

    // Current state (Server manages macro-environment clustering and floor assignment)
    var currentEnvironmentId: String = "raw"
        private set

    var currentDwellState: DwellState = DwellState.IN_TRANSIT
        private set

    private var dwellStartBootNs: Long = SystemClock.elapsedRealtimeNanos()
    private var consecutiveMatchingScans: Int = 0
    private var consecutiveMismatchScans: Int = 0

    // Callback notifications
    var onDwellStateChanged: ((oldState: DwellState, newState: DwellState, envId: String) -> Unit)? = null
    var onDutyCycleChanged: ((wifiScanIntervalMs: Long, batchIntervalMs: Long) -> Unit)? = null

    /**
     * Dwell duration in milliseconds within the current environment.
     */
    val dwellDurationMs: Long
        get() {
            val nowNs = SystemClock.elapsedRealtimeNanos()
            return ((nowNs - dwellStartBootNs) / 1_000_000L).coerceAtLeast(0L)
        }

    /**
     * Recommended Wi-Fi scan interval based on current dwell state.
     */
    val recommendedWifiScanIntervalMs: Long
        get() = when (currentDwellState) {
            DwellState.CAMPING -> 15_000L
            DwellState.IN_TRANSIT -> 1_500L
        }

    /**
     * Recommended cloud batch upload interval based on current dwell state.
     */
    val recommendedBatchIntervalMs: Long
        get() = when (currentDwellState) {
            DwellState.CAMPING -> 60_000L
            DwellState.IN_TRANSIT -> 15_000L
        }

    /**
     * Processes a new Wi-Fi scan batch, calculates Jaccard similarity against the
     * active cluster, updates dwell state and duty-cycling.
     */
    @Synchronized
    fun processScanBatch(scans: List<WifiScanItem>): DwellState {
        if (scans.isEmpty()) {
            return currentDwellState
        }

        // Extract top-8 strongest unique BSSIDs sorted by RSSI descending
        val top8Bssids = scans
            .sortedByDescending { it.rssiDbm }
            .map { it.bssid.uppercase() }
            .distinct()
            .take(8)
            .toSet()

        if (top8Bssids.isEmpty()) {
            return currentDwellState
        }

        // Initial scan: establish initial activity window
        if (activeClusterBssids.isEmpty()) {
            activeClusterBssids = top8Bssids
            currentEnvironmentId = "raw"
            dwellStartBootNs = SystemClock.elapsedRealtimeNanos()
            consecutiveMatchingScans = 1
            consecutiveMismatchScans = 0
            return currentDwellState
        }

        val similarity = computeJaccardSimilarity(activeClusterBssids, top8Bssids)

        if (similarity >= jaccardThreshold) {
            // Matching environment: increment match counter
            consecutiveMatchingScans++
            consecutiveMismatchScans = 0

            // Gradually incorporate newly discovered APs into the cluster signature
            activeClusterBssids = (activeClusterBssids + top8Bssids)
                .take(12)
                .toSet()

            // Check if dwell duration qualifies for CAMPING transition
            if (currentDwellState == DwellState.IN_TRANSIT) {
                val dwellMs = dwellDurationMs
                if (consecutiveMatchingScans >= campingScanCountThreshold || dwellMs >= campingDwellThresholdMs) {
                    transitionState(DwellState.CAMPING)
                }
            }
        } else {
            // Cluster mismatch: potential relocation or transit
            consecutiveMismatchScans++
            consecutiveMatchingScans = 0

            // If mismatch persists for 2 or more scans, transition to IN_TRANSIT
            if (consecutiveMismatchScans >= 2) {
                val oldState = currentDwellState
                activeClusterBssids = top8Bssids
                currentEnvironmentId = "raw"
                dwellStartBootNs = SystemClock.elapsedRealtimeNanos()
                consecutiveMismatchScans = 0

                if (oldState != DwellState.IN_TRANSIT) {
                    transitionState(DwellState.IN_TRANSIT)
                }
            }
        }

        return currentDwellState
    }

    private fun transitionState(newState: DwellState) {
        val oldState = currentDwellState
        if (oldState == newState) return

        currentDwellState = newState
        onDwellStateChanged?.invoke(oldState, newState, currentEnvironmentId)
        onDutyCycleChanged?.invoke(recommendedWifiScanIntervalMs, recommendedBatchIntervalMs)
    }

    companion object {
        /**
         * Calculates Jaccard similarity index J(A, B) = |A ∩ B| / |A ∪ B|.
         */
        fun computeJaccardSimilarity(setA: Set<String>, setB: Set<String>): Double {
            if (setA.isEmpty() && setB.isEmpty()) return 1.0
            val union = setA.union(setB)
            if (union.isEmpty()) return 0.0
            val intersection = setA.intersect(setB)
            return intersection.size.toDouble() / union.size.toDouble()
        }

        /**
         * Computes a canonical SHA-256 hash from a set of BSSIDs.
         * BSSIDs are sorted lexicographically before hashing to ensure cross-device consistency.
         */
        fun computeEnvironmentHash(bssids: Set<String>): String {
            if (bssids.isEmpty()) return "env_empty"
            val canonicalString = bssids.map { it.uppercase().trim() }.sorted().joinToString("|")
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(canonicalString.toByteArray(Charsets.UTF_8))
            val hexString = digest.joinToString("") { "%02x".format(it) }
            return "env_${hexString.take(16)}"
        }
    }
}
