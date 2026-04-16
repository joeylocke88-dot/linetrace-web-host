package com.linetrace.app

import android.opengl.Matrix

/**
 * PoseStabilizer: Implements the "Mirror Shield" protocol for AR tracking.
 * Provides a stable pose even during ARCore tracking stalls or transient failures.
 */
class PoseStabilizer {
    private val _lastPose = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    val lastPose: FloatArray get() = _lastPose
    
    var initialized = false
        private set

    fun update(cameraMatrix: FloatArray): FloatArray {
        System.arraycopy(cameraMatrix, 0, _lastPose, 0, 16)
        initialized = true
        return _lastPose
    }

    fun reset() {
        Matrix.setIdentityM(_lastPose, 0)
        initialized = false
    }
}
