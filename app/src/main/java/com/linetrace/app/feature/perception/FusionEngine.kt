package com.linetrace.app.feature.perception
import com.linetrace.app.feature.telemetry.StabilityAnalyzer
import com.linetrace.app.core.FusedState

import com.google.ar.core.Config
import com.google.ar.core.Pose
import kotlin.math.*

class FusionEngine {

    @Volatile private var arX = 0f
    @Volatile private var arY = 0f
    @Volatile private var arZ = 0f

    @Volatile private var imuVX = 0f
    @Volatile private var imuVY = 0f
    @Volatile private var imuVZ = 0f
    @Volatile private var imuDX = 0f
    @Volatile private var imuDY = 0f
    @Volatile private var imuDZ = 0f

    @Volatile private var currentPitch = 0f
    @Volatile private var currentYaw = 0f
    @Volatile private var currentRoll = 0f

    @Volatile private var currentGX = 0f
    @Volatile private var currentGY = 0f
    @Volatile private var currentGZ = 0f

    @Volatile private var lastTimestampNanos = 0L

    // Adaptive damping and influence based on stability
    private val baseDamping = 0.92f
    private val maxImuInfluence = 0.35f

    @Volatile private var currentLux = -1f

    val stabilityAnalyzer = StabilityAnalyzer()

    @Volatile
    var hasPose = false
        private set

    @Volatile
    var isArTracking = false

    fun reset() {
        arX = 0f; arY = 0f; arZ = 0f
        imuVX = 0f; imuVY = 0f; imuVZ = 0f
        imuDX = 0f; imuDY = 0f; imuDZ = 0f
        lastTimestampNanos = 0L
        hasPose = false
        isArTracking = false
        currentLux = -1f
        stabilityAnalyzer.reset()
    }

    fun updateFromAR(pose: Pose, timestampNanos: Long) {
        arX = pose.tx()
        arY = pose.ty()
        arZ = pose.tz()
        
        // Use the AR timestamp as the baseline for IMU integration
        lastTimestampNanos = timestampNanos
        
        // Reset IMU accumulation when we get a fresh AR pose
        imuDX = 0f
        imuDY = 0f
        imuDZ = 0f
        
        hasPose = true
        isArTracking = true
    }

    fun setArTrackingStatus(tracking: Boolean) {
        isArTracking = tracking
    }

    fun update(sample: MotionSample): FusedState {
        updateFromIMU(sample)
        return fusedState()
    }

    fun updateFromIMU(sample: MotionSample) {
        if (!sample.valid) return
        val now = sample.timestampNanos
        
        val lastTs = lastTimestampNanos
        // Lazarus: Avoid regressing time if a sample arrives out of order or before an AR correction
        if (lastTs != 0L && now <= lastTs) return
        
        val dt = if (lastTs == 0L) 0f else (now - lastTs) / 1_000_000_000f
        lastTimestampNanos = now

        if (dt <= 0f || dt > 0.25f) return
        
        currentLux = sample.lux

        // Lighting-Specific Tuning: Increase IMU trust in low light (< 10 lux)
        // because ARCore VIO is significantly more prone to drift/jitter.
        val dampingFactor = if (!isArTracking && hasPose) {
            if (currentLux in 0f..10f) 0.995f else 0.98f
        } else {
            val stability = stabilityAnalyzer.stabilityScore
            if (stability < 0.4f) 0.85f else baseDamping
        }
        val damping = dampingFactor.toDouble().pow(dt.toDouble()).toFloat()

        imuVX = (imuVX + sample.accelX * dt) * damping
        imuVY = (imuVY + sample.accelY * dt) * damping
        imuVZ = (imuVZ + sample.accelZ * dt) * damping

        // Integrate Gyro for high-frequency orientation refinement (Multimodal Sampling)
        currentPitch += sample.gyroX * dt
        currentYaw += sample.gyroY * dt
        currentRoll += sample.gyroZ * dt
        
        currentGX = sample.gyroX
        currentGY = sample.gyroY
        currentGZ = sample.gyroZ

        if (!isArTracking && hasPose) {
            arX += imuVX * dt
            arY += imuVY * dt
            arZ += imuVZ * dt
        } else {
            imuDX = (imuDX + imuVX * dt).coerceIn(-1.2f, 1.2f)
            imuDY = (imuDY + imuVY * dt).coerceIn(-1.2f, 1.2f)
            imuDZ = (imuDZ + imuVZ * dt).coerceIn(-1.2f, 1.2f)
        }

        stabilityAnalyzer.update(imuVX, imuVZ)
    }

    fun fusedState(): FusedState {
        // Minimal fusion: AR Pose + high-freq IMU displacement
        val stability = stabilityAnalyzer.stabilityScore
        val influence = (1.0f - stability) * maxImuInfluence
        
        val x = arX + imuDX * influence
        val y = arY + imuDY * influence
        val z = arZ + imuDZ * influence

        // Real-time Visual Feedback Metrics:
        // 1. Speed Factor (0 to ~3 m/s)
        val speed = sqrt(imuVX * imuVX + imuVY * imuVY + imuVZ * imuVZ)
        val speedFactor = (1.0f - (speed / 3.0f).coerceIn(0f, 1f))
        
        // 2. Curvature/Agility Factor (using gyro, 0 to ~5 rad/s)
        val rotSpeed = sqrt(currentGX * currentGX + currentGY * currentGY + currentGZ * currentGZ)
        val agilityFactor = (1.0f - (rotSpeed / 5.0f).coerceIn(0f, 1f))

        // Combined visual quality: mostly tracking stability, slightly influenced by speed/agility
        val visualQuality = (stability * 0.7f + speedFactor * 0.15f + agilityFactor * 0.15f).coerceIn(0f, 1f)

        return FusedState(
            x, y, z,
            floatArrayOf(imuVX, imuVY, imuVZ),
            floatArrayOf(currentGX, currentGY, currentGZ),
            lastTimestampNanos,
            stability,
            visualQuality
        )
    }

    fun arPosition(): Triple<Float, Float, Float> = Triple(arX, arY, arZ)

    fun getVelocity(): Float {
        // Simple distance/time check on last IMU integration
        val speed = kotlin.math.sqrt(imuVX * imuVX + imuVY * imuVY + imuVZ * imuVZ)
        return speed
    }
}
