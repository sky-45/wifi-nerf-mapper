package com.antigravity.wifimapper.cloud

import android.content.Context
import android.os.BatteryManager
import android.os.SystemClock
import com.antigravity.wifimapper.model.BatchEnvelope
import com.antigravity.wifimapper.model.TelemetryEvent
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
     * Path pattern: {ociParBaseUrl}/fleet/{device_id}/{env_id}/batch_{seq}.ndjson.gz
     */
    OCI_OBJECT_STORAGE,

    /**
     * Ingestion into a centralized REST telemetry microservice.
     * Default path: POST /api/v1/telemetry/batches
     */
    REST_INGESTION
}

/**
 * Configuration parameters for telemetry cloud uploading.
 */
data class CloudConfig(
    val mode: CloudProviderMode = CloudProviderMode.REST_INGESTION,
    val restEndpointUrl: String = "http://192.168.1.100:8000/api/v1/telemetry/batches",
    val ociParBaseUrl: String = "https://objectstorage.us-ashburn-1.oraclecloud.com/p/EXAMPLE_PAR_TOKEN/n/NAMESPACE/b/telemetry-bucket/o",
    val authToken: String = "demo-bearer-token",
    val batchIntervalMs: Long = 15_000L,
    val maxBatchEvents: Int = 200,
    val useOciPut: Boolean = true
)

/**
 * Manages store-and-forward batching, Gzip NDJSON compression,
 * hardware battery logging, and dual-mode REST / OCI Object Storage uploading.
 */
class CloudBatchUploader(
    private val context: Context,
    var config: CloudConfig = CloudConfig(),
    private val deviceIdProvider: () -> String = { android.os.Build.MODEL },
    private val environmentIdProvider: () -> String = { "env_default" },
    private val dwellStateProvider: () -> String = { "IN_TRANSIT" }
) {
    private val sessionId = UUID.randomUUID().toString()
    private var batchSeq = 0L
    private val pendingEventQueue = ConcurrentLinkedQueue<TelemetryEvent>()
    private val spoolDir = File(context.filesDir, "telemetry_spool").apply { mkdirs() }

    private val httpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .build()

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var uploaderJob: Job? = null
    private var currentIntervalMs: Long = config.batchIntervalMs

    var onUploadTelemetryUpdate: ((batchesUploaded: Long, bytesCompressed: Long, pending: Int) -> Unit)? = null
    private var totalBatchesUploaded = 0L
    private var totalBytesUploaded = 0L

    fun start() {
        startUploaderLoop()
        retrySpoolFiles()
    }

    fun stop() {
        uploaderJob?.cancel()
        flushBatch()
    }

    /**
     * Dynamically updates the upload interval for adaptive duty-cycling
     * (e.g. 15s when IN_TRANSIT vs 60s when CAMPING).
     */
    @Synchronized
    fun updateBatchInterval(newIntervalMs: Long) {
        if (currentIntervalMs == newIntervalMs) return
        currentIntervalMs = newIntervalMs
        startUploaderLoop()
    }

    /**
     * Updates runtime configuration (e.g., switching between REST and OCI PAR).
     */
    @Synchronized
    fun updateConfig(newConfig: CloudConfig) {
        config = newConfig
        if (currentIntervalMs != newConfig.batchIntervalMs) {
            currentIntervalMs = newConfig.batchIntervalMs
            startUploaderLoop()
        }
    }

    private fun startUploaderLoop() {
        uploaderJob?.cancel()
        uploaderJob = scope.launch {
            while (isActive) {
                delay(currentIntervalMs)
                flushBatch()
                retrySpoolFiles()
            }
        }
    }

    fun enqueueEvent(event: TelemetryEvent) {
        pendingEventQueue.add(event)
        if (pendingEventQueue.size >= config.maxBatchEvents) {
            scope.launch { flushBatch() }
        }
    }

    @Synchronized
    private fun flushBatch() {
        if (pendingEventQueue.isEmpty()) return

        val eventsToUpload = mutableListOf<TelemetryEvent>()
        while (eventsToUpload.size < config.maxBatchEvents && !pendingEventQueue.isEmpty()) {
            val ev = pendingEventQueue.poll() ?: break
            eventsToUpload.add(ev)
        }

        if (eventsToUpload.isEmpty()) return

        val batchId = UUID.randomUUID().toString()
        batchSeq++
        val nowBootNs = SystemClock.elapsedRealtimeNanos()

        val batteryPct = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val currentUa = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)

        val deviceId = deviceIdProvider()
        val envId = environmentIdProvider()
        val dwellState = dwellStateProvider()

        val envelope = BatchEnvelope(
            timestampNs = nowBootNs,
            sessionId = sessionId,
            deviceId = deviceId,
            environmentId = envId,
            dwellState = dwellState,
            batchSeq = batchSeq,
            startTimeNs = eventsToUpload.first().timestampNs,
            endTimeNs = eventsToUpload.last().timestampNs,
            eventCount = eventsToUpload.size,
            batteryPct = batteryPct,
            batteryCurrentUa = currentUa,
            stepCountDelta = 0
        )

        // Serialize to Gzipped NDJSON stream
        val gzippedBytes = compressBatchToGzip(envelope, eventsToUpload)

        // Save to disk spool first (ensures zero data loss if network goes down)
        val cleanDeviceId = sanitizePathSegment(deviceId)
        val cleanEnvId = sanitizePathSegment(envId)
        val spoolFile = File(spoolDir, "batch_${cleanDeviceId}_${cleanEnvId}_${batchSeq}_$batchId.ndjson.gz")
        FileOutputStream(spoolFile).use { it.write(gzippedBytes) }

        // Attempt upload
        uploadSpoolFile(spoolFile, batchId, batchSeq, deviceId, envId, dwellState)
    }

    private fun compressBatchToGzip(envelope: BatchEnvelope, events: List<TelemetryEvent>): ByteArray {
        val byteOut = ByteArrayOutputStream()
        GZIPOutputStream(byteOut).bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(json.encodeToString(envelope))
            writer.write("\n")
            for (ev in events) {
                writer.write(json.encodeToString(ev))
                writer.write("\n")
            }
        }
        return byteOut.toByteArray()
    }

    private fun uploadSpoolFile(
        file: File,
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

        val request = when (config.mode) {
            CloudProviderMode.OCI_OBJECT_STORAGE -> {
                // Format: {baseUrl}/fleet/{device_id}/{env_id}/batch_{seq}.ndjson.gz
                val cleanDeviceId = sanitizePathSegment(deviceId)
                val cleanEnvId = sanitizePathSegment(envId)
                val url = "${config.ociParBaseUrl.trimEnd('/')}/fleet/$cleanDeviceId/$cleanEnvId/batch_${seq}.ndjson.gz"

                val builder = Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/x-ndjson")
                    .addHeader("Content-Encoding", "gzip")
                    .addHeader("X-Device-Id", deviceId)
                    .addHeader("X-Environment-Id", envId)
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

    private fun retrySpoolFiles() {
        val files = spoolDir.listFiles() ?: return
        if (files.isEmpty()) return

        for (file in files) {
            if (!file.isFile || !file.name.endsWith(".ndjson.gz")) continue
            // Avoid re-uploading file currently being written
            if (System.currentTimeMillis() - file.lastModified() < 3000L) continue

            // Parse metadata from filename if possible
            val parts = file.name.removeSuffix(".ndjson.gz").split("_")
            val (deviceId, envId, seq, batchId) = if (parts.size >= 5 && parts[0] == "batch") {
                Tuple4(parts[1], parts[2], parts[3].toLongOrNull() ?: batchSeq, parts[4])
            } else {
                Tuple4(deviceIdProvider(), environmentIdProvider(), batchSeq, UUID.randomUUID().toString())
            }

            uploadSpoolFile(file, batchId, seq, deviceId, envId, dwellStateProvider())
        }
    }

    private fun notifyTelemetry() {
        val pendingCount = spoolDir.listFiles()?.count { it.isFile && it.name.endsWith(".ndjson.gz") } ?: 0
        onUploadTelemetryUpdate?.invoke(totalBatchesUploaded, totalBytesUploaded, pendingCount)
    }

    private fun sanitizePathSegment(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
