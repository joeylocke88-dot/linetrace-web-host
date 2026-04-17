package com.linetrace.app.feature.perception

class DriftDampener {

    private var vx = 0f
    private var vy = 0f
    private var vz = 0f

    private val damping = 0.92f

    fun integrate(ax: Float, ay: Float, az: Float, dt: Float = 0.016f): FloatArray {
        vx = (vx + ax * dt) * damping
        vy = (vy + ay * dt) * damping
        vz = (vz + az * dt) * damping

        return floatArrayOf(vx, vy, vz)
    }

    fun resetIfStill(accMag: Float, threshold: Float = 0.05f) {
        if (accMag < threshold) {
            vx = 0f
            vy = 0f
            vz = 0f
        }
    }
}
