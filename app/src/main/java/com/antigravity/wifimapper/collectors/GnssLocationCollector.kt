package com.antigravity.wifimapper.collectors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.location.altitude.AltitudeConverter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.antigravity.wifimapper.model.GnssLocationEvent
import com.antigravity.wifimapper.model.GnssSnapshot
import com.antigravity.wifimapper.sync.PoseRingBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Captures real-world WGS84 and Orthometric Mean Sea Level (MSL) Earth coordinates using
 * Android's native LocationManager (Fused / GPS / Network), raw GNSS NMEA $GPGGA/$GNGGA
 * geoid separation sentences, and Android 14+ AltitudeConverter (EGM2008 geoid lookup).
 */
class GnssLocationCollector(
    private val context: Context,
    private val poseRingBuffer: PoseRingBuffer,
    private val onLocationFix: (GnssSnapshot, GnssLocationEvent) -> Unit
) {
    private val locationManager =
        context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val latestSnapshot = AtomicReference<GnssSnapshot?>(null)
    private var lastUpdateWallMs: Long = 0L

    // Latest hardware NMEA orthometric MSL altitude & geoid separation (N = Ellipsoid - MSL)
    @Volatile private var latestNmeaMslAltM: Double? = null
    @Volatile private var latestNmeaGeoidSepM: Float? = null
    @Volatile private var latestNmeaWallMs: Long = 0L

    private val nmeaListener = OnNmeaMessageListener { message, _ ->
        parseNmeaGga(message)
    }

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

        // Listen for raw hardware NMEA $GPGGA/$GNGGA sentences for exact MSL + geoid separation
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lm.addNmeaListener(context.mainExecutor, nmeaListener)
            } else {
                lm.addNmeaListener(nmeaListener, Handler(Looper.getMainLooper()))
            }
        }

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
            locationManager?.removeNmeaListener(nmeaListener)
            locationManager?.removeUpdates(locationListener)
        }
        scope.cancel()
    }

    private fun parseNmeaGga(sentence: String) {
        if (!sentence.startsWith("\$GPGGA") && !sentence.startsWith("\$GNGGA")) return
        val clean = sentence.substringBefore('*')
        val fields = clean.split(',')
        if (fields.size < 12) return

        val fixQuality = fields[6].toIntOrNull() ?: 0
        if (fixQuality <= 0) return

        val mslAlt = fields[9].toDoubleOrNull()
        val geoidSep = fields[11].toFloatOrNull()
        if (mslAlt != null && mslAlt.isFinite() && abs(mslAlt) < 20_000.0) {
            latestNmeaMslAltM = mslAlt
            if (geoidSep != null && geoidSep.isFinite() && abs(geoidSep) <= 150f) {
                latestNmeaGeoidSepM = geoidSep
            }
            latestNmeaWallMs = System.currentTimeMillis()
        }
    }

    private fun handleLocation(loc: Location) {
        if (!isValidCoordinate(loc.latitude, loc.longitude)) return

        val providerName = loc.provider ?: "unknown"
        val isHardwareGps = providerName == LocationManager.GPS_PROVIDER

        // On Android 14+ (API 34), compute orthometric MSL off the main thread via system EGM2008 table
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && loc.hasAltitude() && !loc.hasMslAltitude()) {
            val locCopy = Location(loc)
            scope.launch {
                runCatching {
                    AltitudeConverter().addMslAltitudeToLocation(context, locCopy)
                }
                emitProcessedLocation(locCopy, isHardwareGps)
            }
            return
        }

        emitProcessedLocation(loc, isHardwareGps)
    }

    private fun emitProcessedLocation(loc: Location, isHardwareGps: Boolean) {
        val current = latestSnapshot.get()
        val nowWallMs = System.currentTimeMillis()
        val providerName = loc.provider ?: "unknown"

        val altM = if (loc.hasAltitude() && loc.altitude.isFinite()) loc.altitude else null
        var mslAltM: Double? = null
        var geoidSepM: Float? = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && loc.hasMslAltitude()) {
            val convMsl = loc.mslAltitudeMeters
            if (convMsl.isFinite()) {
                mslAltM = convMsl
                if (altM != null) {
                    geoidSepM = (altM - convMsl).toFloat()
                }
            }
        }

        // Fallback to recent NMEA GGA orthometric MSL / geoid separation from GPS hardware
        if ((nowWallMs - latestNmeaWallMs) < 30_000L) {
            if (mslAltM == null && isHardwareGps) {
                mslAltM = latestNmeaMslAltM
            }
            if (geoidSepM == null) {
                geoidSepM = latestNmeaGeoidSepM
                if (mslAltM == null && altM != null && isHardwareGps && geoidSepM != null) {
                    mslAltM = altM - geoidSepM
                }
            }
        }

        val nowNs = if (loc.elapsedRealtimeNanos > 0L) {
            loc.elapsedRealtimeNanos
        } else {
            SystemClock.elapsedRealtimeNanos()
        }

        val accM = if (loc.hasAccuracy() && loc.accuracy.isFinite()) loc.accuracy else null
        val bearingDeg = if (loc.hasBearing() && loc.bearing.isFinite()) loc.bearing else null
        val speedMps = if (loc.hasSpeed() && loc.speed.isFinite()) loc.speed else null

        val snap = GnssSnapshot(
            lat = loc.latitude,
            lon = loc.longitude,
            altitudeM = altM,
            mslAltitudeM = mslAltM,
            geoidSepM = geoidSepM,
            accuracyM = accM,
            provider = providerName,
            bearingDeg = bearingDeg,
            speedMps = speedMps,
            timestampNs = nowNs
        )

        // Never drop true 3D GPS altitude fixes even if a recent network fix claimed smaller horizontal radius
        val curAcc = current?.accuracyM ?: 999f
        val newAcc = accM ?: 999f
        val shouldUpdateSnapshot = current == null ||
            (nowWallMs - lastUpdateWallMs) >= 15_000L ||
            newAcc <= curAcc * 2.5f + 15f ||
            (mslAltM != null && current.mslAltitudeM == null)

        if (shouldUpdateSnapshot) {
            latestSnapshot.set(snap)
            lastUpdateWallMs = nowWallMs
        }

        if (shouldUpdateSnapshot || isHardwareGps) {
            val pose = poseRingBuffer.interpolateAt(nowNs) ?: poseRingBuffer.getLatest()
            val event = GnssLocationEvent(
                timestampNs = nowNs,
                lat = loc.latitude,
                lon = loc.longitude,
                altitudeM = altM,
                mslAltitudeM = mslAltM,
                geoidSepM = geoidSepM,
                accuracyM = accM,
                provider = providerName,
                bearingDeg = bearingDeg,
                speedMps = speedMps,
                pose = pose
            )
            onLocationFix(snap, event)
        }
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
