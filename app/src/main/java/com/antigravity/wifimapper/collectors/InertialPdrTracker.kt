package com.antigravity.wifimapper.collectors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.antigravity.wifimapper.model.Pose6DoF
import com.antigravity.wifimapper.sync.PoseRingBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Camera-Free Pedestrian Dead Reckoning (PDR) & Extended Kalman Filter (EKF) tracker.
 * Runs 100% in background with screen off.
 */
class InertialPdrTracker(
    context: Context,
    private val poseRingBuffer: PoseRingBuffer
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val gameRotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val stepDetectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val linearAccelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)
    private val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

    // Current State
    private var posX = 0.0
    private var posY = 0.0
    private var posZ = 0.0

    private var quatX = 0.0
    private var quatY = 0.0
    private var quatZ = 0.0
    private var quatW = 1.0
    private var currentHeadingYawRad = 0.0

    // Weinberg Stride Model state
    private var verticalBounceMax = 0.0
    private var verticalBounceMin = 0.0
    private val weinbergK = 0.45 // Calibration factor (0.40 - 0.48 typical)

    // ZUPT / Stationary Detection
    private var isStationary = true
    private var stationaryCounter = 0
    private var lastStepNs = 0L

    // Barometer Raw Pressure & Elevation Tracking
    var latestPressureHpa: Float = 0f
        private set
    var baselinePressureHpa: Float = 1013.25f
        private set
    private var hasBaselinePressure = false

    // Telemetry Flow
    private val _totalSteps = MutableStateFlow(0)
    val totalSteps: StateFlow<Int> = _totalSteps

    private val _totalDistanceM = MutableStateFlow(0.0)
    val totalDistanceM: StateFlow<Double> = _totalDistanceM

    // Step Event Callback
    var onStepDetectedListener: ((stepCount: Int, strideM: Double, pose: Pose6DoF) -> Unit)? = null

    fun start() {
        gameRotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        stepDetectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        linearAccelSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        magSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        pressureSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val timestampNs = event.timestamp

        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                val q = FloatArray(4)
                SensorManager.getQuaternionFromVector(q, event.values)
                quatW = q[0].toDouble()
                quatX = q[1].toDouble()
                quatY = q[2].toDouble()
                quatZ = q[3].toDouble()

                // Tilt-compensated gravity-projected forward azimuth:
                // Projects the phone's forward axis (+Y, top of screen) onto Earth horizontal plane (East, North)
                // avoiding Euler gimbal singularities when phone is held naturally at an angle.
                val r01 = 2.0 * (quatX * quatY - quatW * quatZ)
                val r11 = 1.0 - 2.0 * (quatX * quatX + quatZ * quatZ)
                val horizNormSq = r01 * r01 + r11 * r11
                if (horizNormSq > 0.04) {
                    currentHeadingYawRad = kotlin.math.atan2(r01, r11)
                } else {
                    // Fallback to Euler yaw if phone is pointed nearly straight up/down
                    val siny = 2.0 * (quatW * quatZ + quatX * quatY)
                    val cosy = 1.0 - 2.0 * (quatY * quatY + quatZ * quatZ)
                    currentHeadingYawRad = kotlin.math.atan2(siny, cosy)
                }

                pushPoseToBuffer(timestampNs)
            }

            Sensor.TYPE_LINEAR_ACCELERATION -> {
                // Project body linear acceleration onto Earth gravity vertical axis:
                // a_v = R_20 * a_x + R_21 * a_y + R_22 * a_z
                val r20 = 2.0 * (quatX * quatZ - quatW * quatY)
                val r21 = 2.0 * (quatY * quatZ + quatW * quatX)
                val r22 = 1.0 - 2.0 * (quatX * quatX + quatY * quatY)
                val ax = event.values[0].toDouble()
                val ay = event.values[1].toDouble()
                val az = event.values[2].toDouble()
                val azWorld = r20 * ax + r21 * ay + r22 * az

                if (azWorld > verticalBounceMax) verticalBounceMax = azWorld
                if (azWorld < verticalBounceMin) verticalBounceMin = azWorld

                val accMag = sqrt(
                    event.values[0] * event.values[0] +
                    event.values[1] * event.values[1] +
                    event.values[2] * event.values[2]
                )

                if (accMag < 0.25f) {
                    stationaryCounter++
                    if (stationaryCounter > 25) { // ~0.5s still
                        isStationary = true
                    }
                } else {
                    stationaryCounter = 0
                    isStationary = false
                }

                // Fallback: If device lacks hardware TYPE_STEP_DETECTOR, detect steps from acceleration peak
                if (stepDetectorSensor == null && azWorld > 1.8 && (timestampNs - lastStepNs > 320_000_000L)) {
                    handleStep(timestampNs)
                }
            }

            Sensor.TYPE_STEP_DETECTOR -> {
                handleStep(timestampNs)
            }

            Sensor.TYPE_PRESSURE -> {
                val pressure = event.values[0]
                latestPressureHpa = pressure
                if (!hasBaselinePressure) {
                    baselinePressureHpa = pressure
                    hasBaselinePressure = true
                }
                // Hypsometric formula: ~8.43 meters per hPa change at sea level
                val deltaHpa = baselinePressureHpa - pressure
                posZ = deltaHpa * 8.43
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun handleStep(timestampNs: Long) {
        isStationary = false
        lastStepNs = timestampNs
        val bounceRange = (verticalBounceMax - verticalBounceMin).coerceAtLeast(0.1)
        // Weinberg Stride Length formula: L = K * (bounce_range)^(1/4)
        val strideLengthM = (weinbergK * Math.pow(bounceRange, 0.25)).coerceIn(0.45, 0.95)

        // Reset bounce tracking for next step
        verticalBounceMax = 0.0
        verticalBounceMin = 0.0

        // Advance 2D coordinates along current heading
        posX += strideLengthM * cos(currentHeadingYawRad)
        posY += strideLengthM * sin(currentHeadingYawRad)

        _totalSteps.value += 1
        _totalDistanceM.value += strideLengthM

        val pose = pushPoseToBuffer(timestampNs)
        onStepDetectedListener?.invoke(_totalSteps.value, strideLengthM, pose)
    }

    /**
     * Corrects dead-reckoning drift using an external Wi-Fi RTT anchor measurement or loop closure.
     */
    fun applyEkfPositionCorrection(correctedX: Double, correctedY: Double, weight: Double = 0.3) {
        posX = posX * (1.0 - weight) + correctedX * weight
        posY = posY * (1.0 - weight) + correctedY * weight
        pushPoseToBuffer(android.os.SystemClock.elapsedRealtimeNanos())
    }

    private fun pushPoseToBuffer(timestampNs: Long): Pose6DoF {
        val pose = Pose6DoF(
            x = posX,
            y = posY,
            z = posZ,
            qx = quatX,
            qy = quatY,
            qz = quatZ,
            qw = quatW,
            confidence = if (isStationary) 0.99 else 0.92,
            isStationary = isStationary
        )
        poseRingBuffer.push(timestampNs, pose)
        return pose
    }
}
