package com.linetrace.app

import kotlin.math.*

class ComplementaryFusion {

    // Orientation (gyro integrated)
    private var pitch = 0f
    private var roll = 0f
    private var yaw = 0f

    // Drift correction strength
    private val alpha = 0.98f  // gyro weight (high = smooth, low drift correction)

    // Gravity estimate
    private val gravity = floatArrayOf(0f, 0f, 0f)

    fun update(
        ax: Float, ay: Float, az: Float,
        gx: Float, gy: Float, gz: Float,
        dt: Float = 0.016f
    ): FusedImu {

        // =========================
        // 1. GYRO INTEGRATION (fast orientation)
        // =========================
        pitch += gx * dt
        roll  += gy * dt
        yaw   += gz * dt

        // =========================
        // 2. ACCEL MAGNITUDE (gravity reference)
        // =========================
        val accMag = sqrt(ax*ax + ay*ay + az*az)

        val axN = ax / accMag
        val ayN = ay / accMag
        val azN = az / accMag

        // =========================
        // 3. COMPLEMENTARY GRAVITY CORRECTION
        // (blend accel + gyro)
        // =========================
        gravity[0] = alpha * gravity[0] + (1 - alpha) * axN
        gravity[1] = alpha * gravity[1] + (1 - alpha) * ayN
        gravity[2] = alpha * gravity[2] + (1 - alpha) * azN

        // =========================
        // 4. REMOVE GRAVITY FROM ACCEL
        // =========================
        val linAx = ax - gravity[0]
        val linAy = ay - gravity[1]
        val linAz = az - gravity[2]

        return FusedImu(
            pitch = pitch,
            roll = roll,
            yaw = yaw,
            linAx = linAx,
            linAy = linAy,
            linAz = linAz
        )
    }
}

data class FusedImu(
    val pitch: Float,
    val roll: Float,
    val yaw: Float,
    val linAx: Float,
    val linAy: Float,
    val linAz: Float
)
