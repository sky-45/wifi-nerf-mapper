package com.antigravity.wifimapper.collectors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.antigravity.wifimapper.model.GnssLocationEvent
import com.antigravity.wifimapper.model.GnssSnapshot
import com.antigravity.wifimapper.sync.PoseRingBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Captures real-world WGS84 Earth coordinates (latitude, longitude, altitude, accuracy)
 * using Android's native framework LocationManager across Fused (API 31+), GPS, and Network
 * providers so indoor-to-Earth alignment works seamlessly even inside multi-story buildings.
 */
class GnssLocationCollector(
    private val context: Context,
    private val poseRingBuffer: PoseRingBuffer,
    private val onLocationFix: (GnssSnapshot, GnssLocationEvent) -> Unit
) {
    private val locationManager =
        context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val latestSnapshot = AtomicReference<GnssSnapshot?>(null)
    private var lastUpdateWallMs: Long = 0L

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handleLocation(location)
        }

        @Deprecated("Deprecated in Android API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

        override fun onProviderEnabled(provider: String) {}

        override fun onProviderDisabled(provider: String) {}
    }

    fun getLatestSnapshot(): GnssSnapshot? = latestSnapshot.get()

    @SuppressLint("MissingPermission")
    fun start(minTimeMs: Long = 5_000L, minDistanceM: Float = 2.0f) {
        if (!hasLocationPermission()) return
        val lm = locationManager ?: return

        val candidateProviders = buildProviderList(lm)

        // Seed immediately with the most accurate / freshest cached system fix
        var bestLastLocation: Location? = null
        for (provider in candidateProviders) {
            val loc = runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: continue
            if (isValidCoordinate(loc.latitude, loc.longitude) && isBetterLocation(loc, bestLastLocation)) {
                bestLastLocation = loc
            }
        }
        bestLastLocation?.let { handleLocation(it) }

        // Subscribe to continuous updates across available providers
        for (provider in candidateProviders) {
            if (provider == LocationManager.PASSIVE_PROVIDER) continue
            runCatching {
                if (lm.allProviders.contains(provider)) {
                    lm.requestLocationUpdates(
                        provider,
                        minTimeMs,
                        minDistanceM,
                        locationListener,
                        Looper.getMainLooper()
                    )
                }
            }
        }
    }

    fun stop() {
        runCatching {
            locationManager?.removeUpdates(locationListener)
        }
    }

    private fun handleLocation(loc: Location) {
        if (!isValidCoordinate(loc.latitude, loc.longitude)) return

        val current = latestSnapshot.get()
        val nowWallMs = System.currentTimeMillis()
        // Reject stale coarse replacement if we recently got a tighter fix
        if (current != null && (nowWallMs - lastUpdateWallMs) < 15_000L) {
            val curAcc = current.accuracyM ?: 999f
            val newAcc = if (loc.hasAccuracy()) loc.accuracy else 999f
            if (newAcc > curAcc * 2.5f + 15f) {
                return
            }
        }

        val nowNs = if (loc.elapsedRealtimeNanos > 0L) {
            loc.elapsedRealtimeNanos
        } else {
            SystemClock.elapsedRealtimeNanos()
        }

        val altM = if (loc.hasAltitude() && loc.altitude.isFinite()) loc.altitude else null
        val accM = if (loc.hasAccuracy() && loc.accuracy.isFinite()) loc.accuracy else null
        val bearingDeg = if (loc.hasBearing() && loc.bearing.isFinite()) loc.bearing else null
        val speedMps = if (loc.hasSpeed() && loc.speed.isFinite()) loc.speed else null
        val providerName = loc.provider ?: "unknown"

        val snap = GnssSnapshot(
            lat = loc.latitude,
            lon = loc.longitude,
            altitudeM = altM,
            accuracyM = accM,
            provider = providerName,
            bearingDeg = bearingDeg,
            speedMps = speedMps,
            timestampNs = nowNs
        )
        latestSnapshot.set(snap)
        lastUpdateWallMs = nowWallMs

        val pose = poseRingBuffer.interpolateAt(nowNs) ?: poseRingBuffer.getLatest()
        val event = GnssLocationEvent(
            timestampNs = nowNs,
            lat = loc.latitude,
            lon = loc.longitude,
            altitudeM = altM,
            accuracyM = accM,
            provider = providerName,
            bearingDeg = bearingDeg,
            speedMps = speedMps,
            pose = pose
        )
        onLocationFix(snap, event)
    }

    private fun buildProviderList(lm: LocationManager): List<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(LocationManager.FUSED_PROVIDER)
        }
        list.add(LocationManager.GPS_PROVIDER)
        list.add(LocationManager.NETWORK_PROVIDER)
        list.add(LocationManager.PASSIVE_PROVIDER)
        val available = runCatching { lm.allProviders }.getOrDefault(emptyList())
        return list.filter { available.contains(it) }.distinct()
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        internal fun isValidCoordinate(lat: Double, lon: Double): Boolean {
            if (!lat.isFinite() || !lon.isFinite()) return false
            if (abs(lat) > 90.0 || abs(lon) > 180.0) return false
            if (abs(lat) < 1e-6 && abs(lon) < 1e-6) return false
            return true
        }

        private fun isBetterLocation(candidate: Location, currentBest: Location?): Boolean {
            if (currentBest == null) return true
            val timeDeltaMs = candidate.time - currentBest.time
            val isSignificantlyNewer = timeDeltaMs > 60_000L
            val isSignificantlyOlder = timeDeltaMs < -60_000L
            if (isSignificantlyNewer) return true
            if (isSignificantlyOlder) return false

            val accDelta = candidate.accuracy - currentBest.accuracy
            val isMoreAccurate = accDelta < 0f
            val isFromSameProvider = candidate.provider == currentBest.provider
            return isMoreAccurate || (timeDeltaMs > 0L && accDelta <= 15f) || (timeDeltaMs > 0L && isFromSameProvider)
        }
    }
}
