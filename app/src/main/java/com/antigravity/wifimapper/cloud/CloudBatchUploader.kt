package com.antigravity.wifimapper.cloud

import android.content.Context
import android.os.BatteryManager
import android.os.SystemClock
import com.antigravity.wifimapper.model.BatchEnvelope
import com.antigravity.wifimapper.model.ConnectedProbeEvent
import com.antigravity.wifimapper.model.MagBaroPdrEvent
import com.antigravity.wifimapper.model.TelemetryEvent
import com.antigravity.wifimapper.model.WifiRttEvent
import com.antigravity.wifimapper.model.WifiScanBatchEvent
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.GZIPOutputStream

/**
 * Cloud target modes supported by the uploader.
 */
enum class CloudProviderMode {
    /**
     * Direct upload to Oracle Cloud Infrastructure (OCI) Object Storage
     * using Pre-Authenticated Requests (PAR) or S3-compatible REST endpoints.
     * Path pattern: {ociParBaseUrl}/fleet/{device_id}/{env_id}/{hf|lf}/batch_{seq}.ndjson.gz
     */
    OCI_OBJECT_STORAGE,

    /**
     * Ingestion into a centralized REST telemetry microservice.
     * Default path: POST /api/v1/telemetry/batches
     */
    REST_INGESTION
}

/**
 * Operational telemetry stream tier.
 */
enum class TelemetryTier {
    /** High-Frequency live telemetry: Link probing (100ms), IMU step deltas (1.5s flush, ~1.8KB) */
    HF,
    /** Low-Frequency architectural scans: Multi-band scan batches, 802.11mc FTM RTT (30s flush, ~45KB) */
    LF
}

/**
 * Configuration parameters for telemetry cloud uploading.
 */
data class CloudConfig(
    val mode: CloudProviderMode = CloudProviderMode.OCI_OBJECT_STORAGE,
    val restEndpointUrl: String = "http://192.168.1.100:8000/api/v1/telemetry/batches",
    val ociParBaseUrl: String = "https://objectstorage.us-ashburn-1.oraclecloud.com/p/EXAMPLE_PAR_TOKEN/n/NAMESPACE/b/telemetry-bucket/o",
    val authToken: String = "demo-bearer-token",
    val hfBatchIntervalMs: Long = 1_500L,
    val lfBatchIntervalMs: Long = 30_000L,
    val maxHfEvents: Int = 30,
    val maxLfEvents: Int = 200,
    val useOciPut: Boolean = true
)

/**
 * Manages dual-rate store-and-forward batching, Gzip NDJSON compression,
 * hardware battery logging, and direct OCI Object Storage uploading into
 * strictly separated /hf/ and /lf/ subdirectories.
 */
class CloudBatchUploader(
    private val context: Context,
    var config: CloudConfig = CloudConfig(),
    private val deviceIdProvider: () -> String = { android.os.Build.MODEL },
    private val environmentIdProvider: () -> String = { "env_default" },
    private val dwellStateProvider: () -> String = { "IN_TRANSIT" }
) {
    private val sessionId = UUID.randomUUID().toString()

    // Dual-Rate Queues & Sequences
    private var batchSeqHf = 0L
    private var batchSeqLf = 0L
    private val pendingHfQueue = ConcurrentLinkedQueue<TelemetryEvent>()
    private val pendingLfQueue = ConcurrentLinkedQueue<TelemetryEvent>()

    // Separate Spool Directories
    private val hfSpoolDir = File(context.filesDir, "telemetry_spool/hf").apply { mkdirs() }
    private val lfSpoolDir = File(context.filesDir, "telemetry_spool/lf").apply { mkdirs() }

    private val httpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .build()

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var hfUploaderJob: Job? = null
    private var lfUploaderJob: Job? = null
    private var currentLfIntervalMs: Long = config.lfBatchIntervalMs

    var onUploadTelemetryUpdate: ((batchesUploaded: Long, bytesCompressed: Long, pending: Int) -> Unit)? = null
    private var totalBatchesUploaded = 0L
    private var totalBytesUploaded = 0L

    fun start() {
        startUploaderLoops()
        retrySpoolFiles()
    }

    fun stop() {
        hfUploaderJob?.cancel()
        lfUploaderJob?.cancel()
        flushHfBatch()
        flushLfBatch()
    }

    /**
     * Dynamically updates the Low-Frequency upload interval for adaptive duty-cycling
     * (e.g. 15s when IN_TRANSIT vs 60s when CAMPING).
     */
    @Synchronized
    fun updateBatchInterval(newIntervalMs: Long) {
        if (currentLfIntervalMs == newIntervalMs) return
        currentLfIntervalMs = newIntervalMs
        startLfLoop()
    }

    /**
     * Updates runtime configuration (e.g., switching between REST and OCI PAR).
     */
    @Synchronized
    fun updateConfig(newConfig: CloudConfig) {
        config = newConfig
        if (currentLfIntervalMs != newConfig.lfBatchIntervalMs) {
            currentLfIntervalMs = newConfig.lfBatchIntervalMs
        }
        startUploaderLoops()
    }

    private fun startUploaderLoops() {
        startHfLoop()
        startLfLoop()
    }

    private fun startHfLoop() {
        hfUploaderJob?.cancel()
        hfUploaderJob = scope.launch {
            while (isActive) {
                delay(config.hfBatchIntervalMs)
                flushHfBatch()
                retrySpoolDirectory(hfSpoolDir, TelemetryTier.HF)
            }
        }
    }

    private fun startLfLoop() {
        lfUploaderJob?.cancel()
        lfUploaderJob = scope.launch {
            while (isActive) {
                delay(currentLfIntervalMs)
                flushLfBatch()
                retrySpoolDirectory(lfSpoolDir, TelemetryTier.LF)
            }
        }
    }

    /**
     * Enqueues a High-Frequency live telemetry event (link probe, IMU dead reckoning).
     */
    fun enqueueHfEvent(event: TelemetryEvent) {
        pendingHfQueue.add(event)
        if (pendingHfQueue.size >= config.maxHfEvents) {
            scope.launch { flushHfBatch() }
        }
    }

    /**
     * Enqueues a Low-Frequency architectural scan event (all-channel Wi-Fi, 802.11mc FTM RTT).
     */
    fun enqueueLfEvent(event: TelemetryEvent) {
        pendingLfQueue.add(event)
        if (pendingLfQueue.size >= config.maxLfEvents) {
            scope.launch { flushLfBatch() }
        }
    }

    /**
     * Universal enqueue: routes automatically by event type.
     */
    fun enqueueEvent(event: TelemetryEvent) {
        when (event) {
            is WifiScanBatchEvent, is WifiRttEvent -> enqueueLfEvent(event)
            else -> enqueueHfEvent(event)
        }
    }

    @Synchronized
    private fun flushHfBatch() {
        if (pendingHfQueue.isEmpty()) return

        val eventsToUpload = mutableListOf<TelemetryEvent>()
        while (eventsToUpload.size < config.maxHfEvents && !pendingHfQueue.isEmpty()) {
            pendingHfQueue.poll()?.let { eventsToUpload.add(it) }
        }
        if (eventsToUpload.isEmpty()) return

        val seq = batchSeqHf++
        val batchId = UUID.randomUUID().toString()
        val deviceId = deviceIdProvider()
        val envId = environmentIdProvider()
        val dwellState = dwellStateProvider()

        val envelope = createEnvelope(seq, eventsToUpload, deviceId, envId, dwellState, "hf")
        val allEvents = listOf(envelope) + eventsToUpload
        val gzippedBytes = compressEventsToGzipNdjson(allEvents)

        val cleanDev = sanitizePathSegment(deviceId)
        val cleanEnv = sanitizePathSegment(envId)
        val spoolFile = File(hfSpoolDir, "batch_hf_${cleanDev}_${cleanEnv}_${seq}_${batchId}.ndjson.gz")
        try {
            FileOutputStream(spoolFile).use { it.write(gzippedBytes) }
        } catch (e: Exception) {
            return
        }

        uploadSpoolFile(spoolFile, TelemetryTier.HF, batchId, seq, deviceId, envId, dwellState)
    }

    @Synchronized
    private fun flushLfBatch() {
        if (pendingLfQueue.isEmpty()) return

        val eventsToUpload = mutableListOf<TelemetryEvent>()
        while (eventsToUpload.size < config.maxLfEvents && !pendingLfQueue.isEmpty()) {
            pendingLfQueue.poll()?.let { eventsToUpload.add(it) }
        }
        if (eventsToUpload.isEmpty()) return

        val seq = batchSeqLf++
        val batchId = UUID.randomUUID().toString()
        val deviceId = deviceIdProvider()
        val envId = environmentIdProvider()
        val dwellState = dwellStateProvider()

        val envelope = createEnvelope(seq, eventsToUpload, deviceId, envId, dwellState, "lf")
        val allEvents = listOf(envelope) + eventsToUpload
        val gzippedBytes = compressEventsToGzipNdjson(allEvents)

        val cleanDev = sanitizePathSegment(deviceId)
        val cleanEnv = sanitizePathSegment(envId)
        val spoolFile = File(lfSpoolDir, "batch_lf_${cleanDev}_${cleanEnv}_${seq}_${batchId}.ndjson.gz")
        try {
            FileOutputStream(spoolFile).use { it.write(gzippedBytes) }
        } catch (e: Exception) {
            return
        }

        uploadSpoolFile(spoolFile, TelemetryTier.LF, batchId, seq, deviceId, envId, dwellState)
    }

    private fun createEnvelope(
        seq: Long,
        events: List<TelemetryEvent>,
        deviceId: String,
        envId: String,
        dwellState: String,
        tier: String
    ): BatchEnvelope {
        val nowNs = System.nanoTime()
        val startNs = events.minOfOrNull { it.timestampNs } ?: nowNs
        val endNs = events.maxOfOrNull { it.timestampNs } ?: nowNs

        val batteryPct = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val batteryCurrentUa = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)

        return BatchEnvelope(
            timestampNs = nowNs,
            sessionId = sessionId,
            deviceId = deviceId,
            environmentId = envId,
            dwellState = dwellState,
            batchSeq = seq,
            tier = tier,
            startTimeNs = startNs,
            endTimeNs = endNs,
            eventCount = events.size,
            batteryPct = batteryPct,
            batteryCurrentUa = batteryCurrentUa,
            stepCountDelta = events.count { it is MagBaroPdrEvent && it.stepDetected }
        )
    }

    private fun compressEventsToGzipNdjson(events: List<TelemetryEvent>): ByteArray {
        val byteOut = ByteArrayOutputStream()
        GZIPOutputStream(byteOut).use { gzip ->
            events.forEach { event ->
                val line = json.encodeToString(event) + "\n"
                gzip.write(line.toByteArray(Charsets.UTF_8))
            }
        }
        return byteOut.toByteArray()
    }

    private fun uploadSpoolFile(
        file: File,
        tier: TelemetryTier,
        batchId: String,
        seq: Long,
        deviceId: String,
        envId: String,
        dwellState: String
    ) {
        val payloadBytes = try {
            file.readBytes()
        } catch (e: Exception) {
            return
        }

        val mediaType = "application/x-ndjson".toMediaType()
        val requestBody = payloadBytes.toRequestBody(mediaType)
        val tierSegment = if (tier == TelemetryTier.HF) "hf" else "lf"

        val request = when (config.mode) {
            CloudProviderMode.OCI_OBJECT_STORAGE -> {
                // Strictly tiered format: {baseUrl}fleet/{device_id}/{env_id}/{hf|lf}/batch_{seq}.ndjson.gz
                val cleanDeviceId = sanitizePathSegment(deviceId)
                val cleanEnvId = sanitizePathSegment(envId)
                val base = config.ociParBaseUrl.trim().trimEnd('/')
                val url = "$base/fleet/$cleanDeviceId/$cleanEnvId/$tierSegment/batch_${seq}.ndjson.gz"

                val builder = Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/x-ndjson")
                    .addHeader("Content-Encoding", "gzip")
                    .addHeader("X-Device-Id", deviceId)
                    .addHeader("X-Environment-Id", envId)
                    .addHeader("X-Telemetry-Tier", tierSegment)
                    .addHeader("X-Dwell-State", dwellState)
                    .addHeader("X-Session-Id", sessionId)
                    .addHeader("X-Batch-Id", batchId)
                    .addHeader("X-Batch-Seq", seq.toString())

                if (config.authToken.isNotBlank()) {
                    builder.addHeader("Authorization", "Bearer ${config.authToken}")
                }

                if (config.useOciPut) {
                    builder.put(requestBody).build()
                } else {
                    builder.post(requestBody).build()
                }
            }

            CloudProviderMode.REST_INGESTION -> {
                val builder = Request.Builder()
                    .url(config.restEndpointUrl)
                    .addHeader("Content-Type", "application/x-ndjson")
                    .addHeader("Content-Encoding", "gzip")
                    .addHeader("X-Session-Id", sessionId)
                    .addHeader("X-Batch-Id", batchId)
                    .addHeader("X-Batch-Seq", seq.toString())
                    .addHeader("X-Device-Id", deviceId)
                    .addHeader("X-Environment-Id", envId)
                    .addHeader("X-Telemetry-Tier", tierSegment)
                    .addHeader("X-Dwell-State", dwellState)

                if (config.authToken.isNotBlank()) {
                    builder.addHeader("Authorization", "Bearer ${config.authToken}")
                }

                builder.post(requestBody).build()
            }
        }

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                // Keep file in spool directory for retry
                notifyTelemetry()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        file.delete()
                        totalBatchesUploaded++
                        totalBytesUploaded += payloadBytes.size
                        notifyTelemetry()
                    } else {
                        notifyTelemetry()
                    }
                }
            }
        })
    }

    private fun retrySpoolDirectory(dir: File, tier: TelemetryTier) {
        val files = dir.listFiles() ?: return
        if (files.isEmpty()) return

        for (file in files) {
            if (!file.isFile || !file.name.endsWith(".ndjson.gz")) continue
            // Avoid re-uploading file currently being written
            if (System.currentTimeMillis() - file.lastModified() < 3000L) continue

            // Filename format: batch_{hf|lf}_{dev}_{env}_{seq}_{batchId}.ndjson.gz
            val parts = file.name.removeSuffix(".ndjson.gz").split("_")
            val (deviceId, envId, seq, batchId) = if (parts.size >= 6 && parts[0] == "batch") {
                Tuple4(parts[2], parts[3], parts[4].toLongOrNull() ?: 0L, parts[5])
            } else {
                Tuple4(deviceIdProvider(), environmentIdProvider(), 0L, UUID.randomUUID().toString())
            }

            uploadSpoolFile(file, tier, batchId, seq, deviceId, envId, dwellStateProvider())
        }
    }

    private fun retrySpoolFiles() {
        retrySpoolDirectory(hfSpoolDir, TelemetryTier.HF)
        retrySpoolDirectory(lfSpoolDir, TelemetryTier.LF)
    }

    private fun notifyTelemetry() {
        val pendingHf = hfSpoolDir.listFiles()?.count { it.isFile && it.name.endsWith(".ndjson.gz") } ?: 0
        val pendingLf = lfSpoolDir.listFiles()?.count { it.isFile && it.name.endsWith(".ndjson.gz") } ?: 0
        onUploadTelemetryUpdate?.invoke(totalBatchesUploaded, totalBytesUploaded, pendingHf + pendingLf)
    }

    private fun sanitizePathSegment(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
