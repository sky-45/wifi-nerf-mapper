package com.antigravity.wifimapper.sync

import com.antigravity.wifimapper.model.Pose6DoF
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Thread-safe ring buffer storing time-indexed 6-DoF poses.
 * Allows retro-interpolating the exact phone position at any arbitrary hardware timestamp.
 */
class PoseRingBuffer(private val capacity: Int = 1000) {

    private val timestamps = LongArray(capacity)
    private val poses = arrayOfNulls<Pose6DoF>(capacity)
    private var writeIndex = 0
    private var count = 0
    private val rwLock = ReentrantReadWriteLock()

    fun push(timestampNs: Long, pose: Pose6DoF) = rwLock.write {
        timestamps[writeIndex] = timestampNs
        poses[writeIndex] = pose
        writeIndex = (writeIndex + 1) % capacity
        if (count < capacity) count++
    }

    /**
     * Interpolates the 6-DoF pose at targetTimestampNs using Linear XYZ + Quaternion SLERP.
     */
    fun interpolateAt(targetTimestampNs: Long, maxExtrapolationNs: Long = 1_500_000_000L): Pose6DoF? = rwLock.read {
        if (count == 0) return null

        val (orderedTimes, orderedPoses) = getOrderedSnapshot()
        val firstTime = orderedTimes.first()
        val lastTime = orderedTimes.last()

        // Reject if target is outside reasonable buffer bounds
        if (targetTimestampNs < firstTime - maxExtrapolationNs) return null
        if (targetTimestampNs > lastTime + maxExtrapolationNs) return null

        if (targetTimestampNs <= firstTime) return orderedPoses.first()
        if (targetTimestampNs >= lastTime) return orderedPoses.last()

        // Binary search for surrounding brackets
        var low = 0
        var high = orderedTimes.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val midTime = orderedTimes[mid]
            if (midTime < targetTimestampNs) {
                low = mid + 1
            } else if (midTime > targetTimestampNs) {
                high = mid - 1
            } else {
                return orderedPoses[mid]
            }
        }

        val i0 = high.coerceIn(0, orderedTimes.size - 1)
        val i1 = low.coerceIn(0, orderedTimes.size - 1)
        val t0 = orderedTimes[i0]
        val t1 = orderedTimes[i1]
        val p0 = orderedPoses[i0]
        val p1 = orderedPoses[i1]

        if (t1 == t0) return p0

        val alpha = ((targetTimestampNs - t0).toDouble() / (t1 - t0).toDouble()).coerceIn(0.0, 1.0)

        // Linear interpolation for translation
        val interpX = p0.x + alpha * (p1.x - p0.x)
        val interpY = p0.y + alpha * (p1.y - p0.y)
        val interpZ = p0.z + alpha * (p1.z - p0.z)

        // Spherical linear interpolation (SLERP) for rotation quaternion
        val slerpQuat = slerp(
            doubleArrayOf(p0.qx, p0.qy, p0.qz, p0.qw),
            doubleArrayOf(p1.qx, p1.qy, p1.qz, p1.qw),
            alpha
        )

        return Pose6DoF(
            x = interpX,
            y = interpY,
            z = interpZ,
            qx = slerpQuat[0],
            qy = slerpQuat[1],
            qz = slerpQuat[2],
            qw = slerpQuat[3],
            confidence = (p0.confidence * (1.0 - alpha) + p1.confidence * alpha),
            isStationary = p0.isStationary && p1.isStationary
        )
    }

    fun getLatest(): Pose6DoF? = rwLock.read {
        if (count == 0) return null
        val idx = if (writeIndex == 0) count - 1 else writeIndex - 1
        return poses[idx]
    }

    private fun getOrderedSnapshot(): Pair<List<Long>, List<Pose6DoF>> {
        val times = ArrayList<Long>(count)
        val snapshotPoses = ArrayList<Pose6DoF>(count)
        val startIdx = if (count < capacity) 0 else writeIndex
        for (i in 0 until count) {
            val idx = (startIdx + i) % capacity
            times.add(timestamps[idx])
            snapshotPoses.add(poses[idx]!!)
        }
        return Pair(times, snapshotPoses)
    }

    companion object {
        fun slerp(q1: DoubleArray, q2: DoubleArray, t: Double): DoubleArray {
            var dot = q1[0] * q2[0] + q1[1] * q2[1] + q1[2] * q2[2] + q1[3] * q2[3]
            val sign = if (dot < 0.0) {
                dot = -dot
                -1.0
            } else {
                1.0
            }

            if (dot > 0.9995) {
                val r0 = 1.0 - t
                val r1 = t * sign
                val out = doubleArrayOf(
                    r0 * q1[0] + r1 * q2[0],
                    r0 * q1[1] + r1 * q2[1],
                    r0 * q1[2] + r1 * q2[2],
                    r0 * q1[3] + r1 * q2[3]
                )
                val norm = sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2] + out[3] * out[3])
                return doubleArrayOf(out[0] / norm, out[1] / norm, out[2] / norm, out[3] / norm)
            }

            val theta0 = acos(dot.coerceIn(-1.0, 1.0))
            val theta = theta0 * t
            val sinTheta = sin(theta)
            val sinTheta0 = sin(theta0)

            val s0 = sin(theta0 - theta) / sinTheta0
            val s1 = (sinTheta / sinTheta0) * sign

            return doubleArrayOf(
                s0 * q1[0] + s1 * q2[0],
                s0 * q1[1] + s1 * q2[1],
                s0 * q1[2] + s1 * q2[2],
                s0 * q1[3] + s1 * q2[3]
            )
        }
    }
}
