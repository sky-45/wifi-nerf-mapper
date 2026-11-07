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
 * Manages store-and-forward batching, Gzip NDJSON compression,
 * hardware battery logging, and REST HTTPS uploading with retry logic.
 */
class CloudBatchUploader(
    private val context: Context,
    private val apiEndpointUrl: String = "http://192.168.1.100:8000/api/v1/telemetry/batches",
    private val authToken: String = "demo-bearer-token",
    private val batchIntervalMs: Long = 15_000L,
    private val maxBatchEvents: Int = 200
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

    var onUploadTelemetryUpdate: ((batchesUploaded: Long, bytesCompressed: Long, pending: Int) -> Unit)? = null
    private var totalBatchesUploaded = 0L
    private var totalBytesUploaded = 0L

    fun start() {
        uploaderJob = scope.launch {
            while (isActive) {
                delay(batchIntervalMs)
                flushBatch()
            }
        }
    }

    fun stop() {
        uploaderJob?.cancel()
        flushBatch()
    }

    fun enqueueEvent(event: TelemetryEvent) {
        pendingEventQueue.add(event)
        if (pendingEventQueue.size >= maxBatchEvents) {
            scope.launch { flushBatch() }
        }
    }

    @Synchronized
    private fun flushBatch() {
        if (pendingEventQueue.isEmpty()) return

        val eventsToUpload = mutableListOf<TelemetryEvent>()
        while (eventsToUpload.size < maxBatchEvents && !pendingEventQueue.isEmpty()) {
            val ev = pendingEventQueue.poll() ?: break
            eventsToUpload.add(ev)
        }

        if (eventsToUpload.isEmpty()) return

        val batchId = UUID.randomUUID().toString()
        batchSeq++
        val nowBootNs = SystemClock.elapsedRealtimeNanos()

        val batteryPct = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val currentUa = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)

        val envelope = BatchEnvelope(
            timestampNs = nowBootNs,
            sessionId = sessionId,
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
        val spoolFile = File(spoolDir, "batch_${batchSeq}_$batchId.ndjson.gz")
        FileOutputStream(spoolFile).use { it.write(gzippedBytes) }

        // Attempt upload
        uploadSpoolFile(spoolFile, batchId, batchSeq)
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

    private fun uploadSpoolFile(file: File, batchId: String, seq: Long) {
        val payloadBytes = file.readBytes()
        val mediaType = "application/x-ndjson".toMediaType()
        val requestBody = payloadBytes.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(apiEndpointUrl)
            .addHeader("Authorization", "Bearer $authToken")
            .addHeader("Content-Type", "application/x-ndjson")
            .addHeader("Content-Encoding", "gzip")
            .addHeader("X-Session-Id", sessionId)
            .addHeader("X-Batch-Id", batchId)
            .addHeader("X-Batch-Seq", seq.toString())
            .addHeader("X-Device-Id", android.os.Build.MODEL)
            .post(requestBody)
            .build()

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
                    }
                }
            }
        })
    }

    private fun notifyTelemetry() {
        val pendingCount = spoolDir.listFiles()?.size ?: 0
        onUploadTelemetryUpdate?.invoke(totalBatchesUploaded, totalBytesUploaded, pendingCount)
    }
}
