package com.linetrace.app.feature.perception

/**
 * Enhanced Kalman Filter with Adaptive Noise Sampling for Multimodal Sensor Fusion.
 */
class KalmanFilter(
    private var processNoise: Float = 0.005f,   // Lower base noise for smoother traces
    private var measurementNoise: Float = 0.5f  // Trust sensor data more by default
) {
    private var estimate = 0f
    private var errorEstimate = 1f
    private var lastMeasurement = 0f

    /**
     * Updates the filter with a new measurement.
     * Incorporates Multimodal Vertex Sampling by adjusting noise based on rate of change.
     */
    fun update(measurement: Float): Float {
        // Adaptive Noise: If the measurement jumps significantly, increase measurement noise 
        // temporarily to prevent "ghosting" or jitter in the line.
        val delta = kotlin.math.abs(measurement - lastMeasurement)
        val adaptiveMeasurementNoise = measurementNoise + (delta * 2.0f)
        
        val gainDenominator = errorEstimate + adaptiveMeasurementNoise
        val kalmanGain = if (gainDenominator == 0f) 0f else errorEstimate / gainDenominator
        
        estimate += kalmanGain * (measurement - estimate)
        errorEstimate = (1f - kalmanGain) * errorEstimate + kotlin.math.abs(estimate - measurement) * processNoise
        
        lastMeasurement = measurement
        return estimate
    }

    /**
     * Tune parameters dynamically based on environmental stability (e.g. from FusionEngine).
     */
    fun tune(pNoise: Float, mNoise: Float) {
        this.processNoise = pNoise
        this.measurementNoise = mNoise
    }

    fun reset(value: Float = 0f) {
        estimate = value
        lastMeasurement = value
        errorEstimate = 1f
    }
}
