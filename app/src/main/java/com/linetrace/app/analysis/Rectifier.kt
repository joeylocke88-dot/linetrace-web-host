package com.linetrace.app.analysis

import com.linetrace.app.feature.perception.FusionEngine
import com.linetrace.app.feature.sync.ImuNetworkBridge
import com.linetrace.app.presentation.LineRenderer
import com.linetrace.app.core.FusedState
import android.util.Log

/**
 * Rectifier: Central System Integrity & Diagnostics Engine.
 * Amalgamates a suite of debuggers to monitor and repair the state of various subsystems.
 */
class Rectifier(
    private val fusion: FusionEngine,
    private val network: ImuNetworkBridge,
    private val renderer: LineRenderer
) {

    /**
     * PerceptionDebugger: Monitors the health of ARCore tracking and IMU fusion.
     */
    inner class PerceptionDebugger {
        fun getStability() = fusion.stabilityAnalyzer.stabilityScore
        fun isArTracking() = fusion.isArTracking
        fun getFusedPos() = fusion.fusedState().let { floatArrayOf(it.x, it.y, it.z) }
        
        fun rectify() {
            val stability = getStability()
            if (stability < 0.15f) {
                Log.w("Rectifier", "Perception critical failure (stability: $stability).")
                if (!isArTracking()) {
                    Log.w("Rectifier", "AR Tracking lost. Forcing fusion reset...")
                    fusion.reset()
                }
            }
        }
    }

    /**
     * ConnectivityDebugger: Monitors WebSocket status and data flow integrity.
     */
    inner class ConnectivityDebugger {
        fun isConnected() = network.isConnected
        
        fun rectify() {
            if (!isConnected()) {
                Log.w("Rectifier", "Network desync detected. Attempting bridge resurrection...")
                network.connect()
            }
        }
    }

    /**
     * InfrastructureDebugger: Monitors OpenGL rendering health and GPU buffer utilization.
     */
    inner class InfrastructureDebugger {
        fun getSurfelCount() = renderer.getSurfelCount()
        fun getTracingState() = renderer.tracingState
        
        fun rectify() {
            // Check for GL stalling and trigger Lazarus protocol if needed
            renderer.checkHealth()
            
            // If surfel count is extreme, it might indicate a buffer leak or noisy cloud
            if (getSurfelCount() > 500000) {
                Log.w("Rectifier", "Extreme surfel density detected. Consider origin reset to prune spatial index.")
            }
        }
    }

    /**
     * TelemetryDebugger: Monitors path integrity and recording state.
     */
    inner class TelemetryDebugger {
        fun isRecording() = renderer.isRecording
        
        fun rectify() {
            if (isRecording() && renderer.tracingState != LineRenderer.TracingState.TRACING) {
                Log.e("Rectifier", "Telemetry State Inconsistency: Recording while Idle.")
                // Potentially force stop recording here if we wanted to be aggressive
            }
        }
    }

    // Suite of debuggers available for individual or holistic scans
    val perception = PerceptionDebugger()
    val connectivity = ConnectivityDebugger()
    val infrastructure = InfrastructureDebugger()
    val telemetry = TelemetryDebugger()

    /**
     * Performs a holistic "Deep Scan" of all subsystems and applies automated repairs.
     */
    fun rectifyAll(): SystemReport {
        Log.i("Rectifier", "Initiating Full System Rectification...")
        
        perception.rectify()
        connectivity.rectify()
        infrastructure.rectify()
        telemetry.rectify()
        
        val pos = perception.getFusedPos()
        
        return SystemReport(
            perceptionStability = perception.getStability(),
            networkActive = connectivity.isConnected(),
            surfelDensity = infrastructure.getSurfelCount().toFloat(),
            isTracking = perception.isArTracking(),
            posX = pos[0],
            posY = pos[1],
            posZ = pos[2]
        )
    }
}

/**
 * Diagnostic data snapshot for UI or logging consumption.
 */
data class SystemReport(
    val perceptionStability: Float,
    val networkActive: Boolean,
    val surfelDensity: Float,
    val isTracking: Boolean,
    val posX: Float,
    val posY: Float,
    val posZ: Float
)
