package com.antigravity.wifimapper.cloud

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Metadata snapshot describing the physical hardware, operating system,
 * and onboard sensing capabilities of the collector device.
 */
@Serializable
data class DeviceIdentityInfo(
    @SerialName("device_id") val deviceId: String,
    val manufacturer: String,
    val model: String,
    val brand: String,
    val device: String,
    val product: String,
    val hardware: String,
    @SerialName("sdk_int") val sdkInt: Int,
    @SerialName("release") val release: String,
    @SerialName("has_wifi_rtt") val hasWifiRtt: Boolean,
    @SerialName("has_accelerometer") val hasAccelerometer: Boolean,
    @SerialName("has_gyroscope") val hasGyroscope: Boolean,
    @SerialName("has_compass") val hasCompass: Boolean,
    @SerialName("has_barometer") val hasBarometer: Boolean,
    @SerialName("has_step_detector") val hasStepDetector: Boolean
)

/**
 * Manages persistent multi-device fleet identity for crowd-sourced RF mapping.
 *
 * Why not IMEI or MAC address?
 * 1. Privacy & Security: In Android 10+ (API 29+), non-resettable hardware identifiers
 *    (IMEI, serial numbers, MEID) are restricted behind READ_PRIVILEGED_PHONE_STATE,
 *    which is only granted to system apps. Wi-Fi MAC addresses are randomized per network.
 * 2. Store Compliance: Google Play Developer Distribution Agreement explicitly forbids
 *    unnecessary collection of persistent hardware IDs for user tracking.
 * 3. Cryptographic Storage: This manager generates a cryptographically random UUIDv4 on first
 *    launch and persists it in Android KeyStore-backed EncryptedSharedPreferences (AES-256 GCM).
 *    This installation ID remains strictly persistent across reboots, background service
 *    restarts, and OS updates, but is safely resettable upon app uninstall.
 */
class DeviceIdentityManager(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        initSecurePreferences()
    }

    private val cachedDeviceId: String by lazy {
        resolveOrGenerateDeviceId()
    }

    private val cachedIdentityInfo: DeviceIdentityInfo by lazy {
        buildIdentityInfo()
    }

    /**
     * Initializes EncryptedSharedPreferences with hardware-backed Keystore MasterKey.
     * Includes graceful fallback to standard private SharedPreferences if Keystore
     * provider is unavailable (e.g., custom ROMs, legacy emulators).
     */
    private fun initSecurePreferences(): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback to standard private SharedPreferences
            context.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    /**
     * Retrieves the persistent hardware-backed device UUID. If this is the first execution,
     * a new UUIDv4 is generated and securely saved.
     */
    private fun resolveOrGenerateDeviceId(): String {
        val existingId = prefs.getString(KEY_DEVICE_ID, null)
        if (!existingId.isNullOrBlank()) {
            return existingId
        }

        val newId = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
        return newId
    }

    /**
     * Captures system model, Android release, and available sensor hardware.
     */
    private fun buildIdentityInfo(): DeviceIdentityInfo {
        val pm = context.packageManager
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

        val hasRtt = pm?.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT) ?: false
        val hasAccel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
        val hasGyro = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        val hasCompass = sm?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null
        val hasBaro = sm?.getDefaultSensor(Sensor.TYPE_PRESSURE) != null
        val hasStep = sm?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null

        return DeviceIdentityInfo(
            deviceId = cachedDeviceId,
            manufacturer = Build.MANUFACTURER ?: "Unknown",
            model = Build.MODEL ?: "Unknown",
            brand = Build.BRAND ?: "Unknown",
            device = Build.DEVICE ?: "Unknown",
            product = Build.PRODUCT ?: "Unknown",
            hardware = Build.HARDWARE ?: "Unknown",
            sdkInt = Build.VERSION.SDK_INT,
            release = Build.VERSION.RELEASE ?: "",
            hasWifiRtt = hasRtt,
            hasAccelerometer = hasAccel,
            hasGyroscope = hasGyro,
            hasCompass = hasCompass,
            hasBarometer = hasBaro,
            hasStepDetector = hasStep
        )
    }

    /**
     * Returns the persistent installation UUID for this device.
     */
    fun getDeviceId(): String = cachedDeviceId

    /**
     * Returns the full hardware and capabilities snapshot.
     */
    fun getIdentityInfo(): DeviceIdentityInfo = cachedIdentityInfo

    /**
     * Returns a human-readable model identifier (e.g., "Google Pixel 8 Pro").
     */
    fun getDeviceDescription(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    companion object {
        private const val SECURE_PREFS_NAME = "wifi_mapper_secure_identity"
        private const val FALLBACK_PREFS_NAME = "wifi_mapper_identity_fallback"
        private const val KEY_DEVICE_ID = "persistent_installation_device_id"
    }
}
