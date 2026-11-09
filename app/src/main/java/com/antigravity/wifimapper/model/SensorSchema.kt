package com.antigravity.wifimapper.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 24/7 lifecycle dwell state of the device within an RF environment.
 */
@Serializable
enum class DwellState {
    @SerialName("CAMPING") CAMPING,
    @SerialName("IN_TRANSIT") IN_TRANSIT
}

@Serializable
data class Pose6DoF(
    val x: Double,
    val y: Double,
    val z: Double = 0.0,
    val qx: Double = 0.0,
    val qy: Double = 0.0,
    val qz: Double = 0.0,
    val qw: Double = 1.0,
    val confidence: Double = 1.0,
    @SerialName("is_stationary") val isStationary: Boolean = false
)

@Serializable
sealed class TelemetryEvent {
    abstract val eventType: String
    abstract val timestampNs: Long
    abstract val pose: Pose6DoF?
    open val deviceId: String? get() = null
    open val environmentId: String? get() = null
    open val dwellState: String? get() = null
}

@Serializable
@SerialName("batch_envelope")
data class BatchEnvelope(
    @SerialName("event_type") override val eventType: String = "batch_envelope",
    @SerialName("timestamp_ns") override val timestampNs: Long,
    @SerialName("session_id") val sessionId: String,
    @SerialName("device_id") override val deviceId: String = "",
    @SerialName("environment_id") override val environmentId: String = "",
    @SerialName("dwell_state") override val dwellState: String = "IN_TRANSIT",
    @SerialName("batch_seq") val batchSeq: Long,
    @SerialName("tier") val tier: String = "hf",
    @SerialName("start_time_ns") val startTimeNs: Long,
    @SerialName("end_time_ns") val endTimeNs: Long,
    @SerialName("event_count") val eventCount: Int,
    @SerialName("battery_pct") val batteryPct: Int,
    @SerialName("battery_current_ua") val batteryCurrentUa: Long,
    @SerialName("step_count_delta") val stepCountDelta: Int,
    override val pose: Pose6DoF? = null
) : TelemetryEvent()

@Serializable
data class WifiScanItem(
    val bssid: String,
    val ssid: String,
    @SerialName("ap_group_id") val apGroupId: String,
    val band: String,
    @SerialName("freq_mhz") val freqMhz: Int,
    @SerialName("bw_mhz") val bwMhz: Int,
    @SerialName("rssi_dbm") val rssiDbm: Int,
    @SerialName("is_80211mc") val is80211mc: Boolean,
    @SerialName("hw_timestamp_ns") val hwTimestampNs: Long,
    @SerialName("scan_age_ms") val scanAgeMs: Long,
    val pose: Pose6DoF? = null
)

@Serializable
data class MultiBandPair(
    @SerialName("ap_group_id") val apGroupId: String,
    @SerialName("rssi_24_dbm") val rssi24Dbm: Int,
    @SerialName("rssi_5_dbm") val rssi5Dbm: Int,
    @SerialName("excess_wall_loss_db") val excessWallLossDb: Double
)

@Serializable
@SerialName("wifi_scan_batch")
data class WifiScanBatchEvent(
    @SerialName("event_type") override val eventType: String = "wifi_scan_batch",
    @SerialName("timestamp_ns") override val timestampNs: Long,
    @SerialName("device_id") override val deviceId: String? = null,
    @SerialName("environment_id") override val environmentId: String? = null,
    @SerialName("dwell_state") override val dwellState: String? = null,
    val scans: List<WifiScanItem>,
    @SerialName("multiband_pairs") val multibandPairs: List<MultiBandPair> = emptyList(),
    override val pose: Pose6DoF? = null
) : TelemetryEvent()

@Serializable
@SerialName("connected_probe")
data class ConnectedProbeEvent(
    @SerialName("event_type") override val eventType: String = "connected_probe",
    @SerialName("timestamp_ns") override val timestampNs: Long,
    @SerialName("device_id") override val deviceId: String? = null,
    @SerialName("environment_id") override val environmentId: String? = null,
    @SerialName("dwell_state") override val dwellState: String? = null,
    val bssid: String,
    @SerialName("freq_mhz") val freqMhz: Int,
    @SerialName("rssi_dbm") val rssiDbm: Int,
    @SerialName("tx_mbps") val txMbps: Int,
    @SerialName("rx_mbps") val rxMbps: Int,
    @SerialName("udp_rtt_ms") val udpRttMs: Double,
    override val pose: Pose6DoF? = null
) : TelemetryEvent()

@Serializable
@SerialName("wifi_rtt")
data class WifiRttEvent(
    @SerialName("event_type") override val eventType: String = "wifi_rtt",
    @SerialName("timestamp_ns") override val timestampNs: Long,
    @SerialName("device_id") override val deviceId: String? = null,
    @SerialName("environment_id") override val environmentId: String? = null,
    @SerialName("dwell_state") override val dwellState: String? = null,
    val bssid: String,
    val mode: String,
    @SerialName("distance_mm") val distanceMm: Int,
    @SerialName("std_dev_mm") val stdDevMm: Int,
    @SerialName("rssi_dbm") val rssiDbm: Int,
    val attempted: Int,
    val successful: Int,
    override val pose: Pose6DoF? = null
) : TelemetryEvent()

@Serializable
@SerialName("mag_baro_pdr")
data class MagBaroPdrEvent(
    @SerialName("event_type") override val eventType: String = "mag_baro_pdr",
    @SerialName("timestamp_ns") override val timestampNs: Long,
    @SerialName("device_id") override val deviceId: String? = null,
    @SerialName("environment_id") override val environmentId: String? = null,
    @SerialName("dwell_state") override val dwellState: String? = null,
    @SerialName("mag_uncal_ut") val magUncalUt: List<Float> = emptyList(),
    @SerialName("mag_norm_ut") val magNormUt: Float = 0f,
    @SerialName("pressure_hpa") val pressureHpa: Float = 0f,
    @SerialName("step_detected") val stepDetected: Boolean = false,
    @SerialName("stride_length_m") val strideLengthM: Double = 0.0,
    @SerialName("is_stationary") val isStationary: Boolean = false,
    override val pose: Pose6DoF? = null
) : TelemetryEvent()

@Serializable
data class EchoPeak(
    @SerialName("distance_m") val distanceM: Double,
    @SerialName("snr_db") val snrDb: Double
)

@Serializable
@SerialName("acoustic_sonar")
data class AcousticSonarEvent(
    @SerialName("event_type") override val eventType: String = "acoustic_sonar",
    @SerialName("timestamp_ns") override val timestampNs: Long,
    @SerialName("device_id") override val deviceId: String? = null,
    @SerialName("environment_id") override val environmentId: String? = null,
    @SerialName("dwell_state") override val dwellState: String? = null,
    @SerialName("temp_c") val tempC: Double = 22.0,
    @SerialName("nearest_wall_m") val nearestWallM: Double,
    val echoes: List<EchoPeak> = emptyList(),
    override val pose: Pose6DoF? = null
) : TelemetryEvent()

data class LiveMappingTelemetry(
    val isRunning: Boolean = false,
    val deviceId: String = "",
    val environmentId: String = "unknown",
    val dwellState: String = "IN_TRANSIT",
    val totalSteps: Int = 0,
    val totalDistanceM: Double = 0.0,
    val currentPose: Pose6DoF = Pose6DoF(0.0, 0.0),
    val visibleApCount: Int = 0,
    val rttAnchorCount: Int = 0,
    val lastExcessWallLossDb: Double? = null,
    val batteryPct: Int = 100,
    val batteryCurrentUa: Long = 0,
    val uploadedBatches: Long = 0,
    val totalUploadedBytesCompressed: Long = 0,
    val pendingUploads: Int = 0
)
