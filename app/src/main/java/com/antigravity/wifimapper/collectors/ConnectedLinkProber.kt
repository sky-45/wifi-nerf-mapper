package com.antigravity.wifimapper.collectors

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import com.antigravity.wifimapper.model.ConnectedProbeEvent
import com.antigravity.wifimapper.sync.PoseRingBuffer
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Sends lightweight UDP echo packets to the router gateway (10-20 Hz)
 * to keep the Wi-Fi PHY active and poll high-density RSSI/link measurements.
 */
class ConnectedLinkProber(
    private val context: Context,
    private val poseRingBuffer: PoseRingBuffer,
    private val onProbeCollected: (ConnectedProbeEvent) -> Unit
) {
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start(intervalMs: Long = 100L) {
        job = scope.launch {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.soTimeout = 80
                val sendData = byteArrayOf(0x42)

                while (isActive) {
                    val info = wifiManager.connectionInfo
                    val bssid = info?.bssid

                    if (bssid != null && bssid != "02:00:00:00:00:00") {
                        val gatewayIpInt = wifiManager.dhcpInfo?.gateway ?: 0
                        var roundTripMs = 0.0

                        if (gatewayIpInt != 0) {
                            val ipBytes = byteArrayOf(
                                (gatewayIpInt and 0xFF).toByte(),
                                ((gatewayIpInt shr 8) and 0xFF).toByte(),
                                ((gatewayIpInt shr 16) and 0xFF).toByte(),
                                ((gatewayIpInt shr 24) and 0xFF).toByte()
                            )
                            val targetAddr = InetAddress.getByAddress(ipBytes)
                            val packet = DatagramPacket(sendData, sendData.size, targetAddr, 33434)

                            val t0 = System.nanoTime()
                            try {
                                socket.send(packet)
                                roundTripMs = (System.nanoTime() - t0) / 1_000_000.0
                            } catch (_: Exception) {}
                        }

                        val nowNs = SystemClock.elapsedRealtimeNanos()
                        val pose = poseRingBuffer.interpolateAt(nowNs)

                        val event = ConnectedProbeEvent(
                            timestampNs = nowNs,
                            bssid = bssid,
                            freqMhz = info.frequency,
                            rssiDbm = info.rssi,
                            txMbps = info.linkSpeed,
                            rxMbps = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                info.rxLinkSpeedMbps
                            } else info.linkSpeed,
                            udpRttMs = roundTripMs,
                            pose = pose
                        )
                        onProbeCollected(event)
                    }

                    // Throttle down if phone is currently stationary
                    val latestPose = poseRingBuffer.getLatest()
                    val dynamicDelay = if (latestPose?.isStationary == true) 1000L else intervalMs
                    delay(dynamicDelay)
                }
            } catch (e: Exception) {
                // Background exception guard
            } finally {
                socket?.close()
            }
        }
    }

    fun stop() {
        job?.cancel()
    }
}
