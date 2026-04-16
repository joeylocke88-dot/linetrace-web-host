package com.linetrace.app

import kotlin.math.abs
import kotlin.math.exp

class StabilityAnalyzer {

    private var lastVx = 0f
    private var lastVz = 0f
    private var jitterEMA = 0f
    private var sampleCount = 0

    // Autostability Algorithm: Adaptive Convergence for VIO
    // Automatically calculates the 'Correction Velocity' needed to pull the 
    // coordinate system back to reality without causing visible "snapping".
    private var convergenceRate = 0.05f // Default 5% per frame
    private var driftMagnitude = 0f
    
    // Autostability Algorithm: Asymmetric Convergence.
    // Stability drops instantly on jitter but recovers slowly to prevent 
    // "rubber-banding" artifacts during VIO-IMU transitions.
    private val RECOVERY_RATE = 0.02f
    private val DROP_RATE = 0.8f

    // High stability = 1.0, Low stability = 0.0
    var stabilityScore: Float = 1f
        private set

    /**
     * Autostability Update: Analyzes motion delta to determine the optimal
     * blend between raw IMU and ARCore VIO.
     */
    fun update(vx: Float, vz: Float) {
        // Calculate "Jerk" (rate of change of acceleration/velocity)
        // High jerk typically indicates sensor noise or tracking jitters
        val dvx = vx - lastVx
        val dvz = vz - lastVz
        val currentJitter = abs(dvx) + abs(dvz)

        // Faster EMA for responsiveness to sudden tracking losses
        val alpha = if (sampleCount < 10) 0.5f else 0.15f
        jitterEMA = alpha * currentJitter + (1f - alpha) * jitterEMA
        sampleCount++

        // Autostability Algorithm: Asymmetric Convergence
        // Stability drops instantly on jitter but recovers slowly to prevent 
        // "rubber-banding" artifacts during VIO-IMU transitions.
        val targetStability = exp(-jitterEMA * 6.5f).coerceIn(0f, 1f)
        
        if (targetStability < stabilityScore) {
            // Rapid drop (High-speed reaction to jitter)
            stabilityScore = stabilityScore * (1f - DROP_RATE) + targetStability * DROP_RATE
        } else {
            // Damped recovery (Autostability)
            stabilityScore += (targetStability - stabilityScore) * RECOVERY_RATE
        }

        lastVx = vx
        lastVz = vz
    }

    fun reset() {
        lastVx = 0f
        lastVz = 0f
        jitterEMA = 0f
        sampleCount = 0
        stabilityScore = 1f
    }
}
