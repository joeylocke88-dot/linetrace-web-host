package com.linetrace.app

import com.google.ar.core.Pose
import kotlin.math.*

class ArImuFusionEngine {

    private var lastArPose: Pose? = null

    private val imuScale = 0.15f  // how much IMU influences micro-motion

    fun update(
        arPose: Pose,
        imuVelocity: FloatArray
    ): FusedPose {

        lastArPose = arPose

        // =========================
        // ARCORE BASE POSITION
        // =========================
        val baseX = arPose.tx()
        val baseY = arPose.ty()
        val baseZ = arPose.tz()

        // =========================
        // IMU MICRO-MOTION OFFSET
        // (applied in AR coordinate frame)
        // =========================
        val dx = imuVelocity[0] * imuScale
        val dy = imuVelocity[1] * imuScale
        val dz = imuVelocity[2] * imuScale

        // =========================
        // FUSED OUTPUT
        // =========================
        return FusedPose(
            x = baseX + dx,
            y = baseY + dy,
            z = baseZ + dz
        )
    }

    fun reset() {
        lastArPose = null
    }
}

data class FusedPose(
    val x: Float,
    val y: Float,
    val z: Float
)
