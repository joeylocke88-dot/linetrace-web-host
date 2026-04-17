package com.linetrace.app.feature.perception
import com.linetrace.app.feature.sync.ImuNetworkBridge

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.util.concurrent.atomic.AtomicReference

data class MotionSample(
    val accelX: Float,
    val accelY: Float,
    val accelZ: Float,
    val gyroX: Float,
    val gyroY: Float,
    val gyroZ: Float,
    val timestampNanos: Long,
    val lux: Float = -1f,
    val valid: Boolean
)

class MotionTracker(val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        ?: throw IllegalStateException("SensorManager unavailable")

    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        ?: throw IllegalStateException("Accelerometer not available")
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

    private val gravity = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val linear = FloatArray(3)
    private val worldAccel = FloatArray(3)
    private val currentGyro = FloatArray(3)

    private val kfX = KalmanFilter()
    private val kfY = KalmanFilter()
    private val kfZ = KalmanFilter()

    private val sampleQueue = java.util.concurrent.ConcurrentLinkedQueue<MotionSample>()
    private val latest = AtomicReference(MotionSample(0f, 0f, 0f, 0f, 0f, 0f, 0L, -1f, false))
    @Volatile private var hasRotation = false
    @Volatile private var currentLux = -1f

    private var registered = false
    private var lastAccelTimestamp = 0L

    private var currentSamplingRate = SensorManager.SENSOR_DELAY_FASTEST
    private var isHighPowerMode = true

    // Draw Data Integration: Direct sensor-to-renderer pipe
    private var sensorDrawCallback: ((MotionSample) -> Unit)? = null
    var imuBridge: ImuNetworkBridge? = null

    fun setSensorDrawCallback(callback: (MotionSample) -> Unit) {
        sensorDrawCallback = callback
    }

    fun setPowerMode(highPower: Boolean) {
        if (isHighPowerMode == highPower) return
        isHighPowerMode = highPower
        currentSamplingRate = if (highPower) SensorManager.SENSOR_DELAY_FASTEST else SensorManager.SENSOR_DELAY_UI
        
        if (registered) {
            stop()
            start()
        }
        android.util.Log.i("MotionTracker", "Power mode changed: HighPower=$highPower, Rate=$currentSamplingRate")
    }

    fun start() {
        if (registered) return
        // Upgrading to SENSOR_DELAY_FASTEST (~200Hz+) for Tactical/High-Precision
        // Now authorized via HIGH_SAMPLING_RATE_SENSORS permission
        sensorManager.registerListener(this, accelerometer, currentSamplingRate)
        gyroscope?.let {
            sensorManager.registerListener(this, it, currentSamplingRate)
        }
        rotationVector?.let {
            sensorManager.registerListener(this, it, currentSamplingRate)
        }
        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        registered = true
    }

    fun stop() {
        if (!registered) return
        sensorManager.unregisterListener(this)
        registered = false
        imuBridge = null // Clear bridge reference on stop to prevent ghost transmissions
    }

    fun snapshot(): MotionSample = latest.get()

    fun pollSamples(): List<MotionSample> {
        val list = mutableListOf<MotionSample>()
        while (true) {
            val s = sampleQueue.poll() ?: break
            list.add(s)
        }
        return list
    }

    override fun onSensorChanged(event: SensorEvent) {
        try {
            when (event.sensor.type) {
                Sensor.TYPE_LIGHT -> {
                    currentLux = event.values[0]
                }
                
                Sensor.TYPE_ROTATION_VECTOR -> {
                    val values = event.values
                    // Check for invalid data from hardware
                    if (values.all { it == 0f }) return
                    
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
                    hasRotation = true
                }

                Sensor.TYPE_ACCELEROMETER -> {
                    val ts = event.timestamp
                    val dt = if (lastAccelTimestamp == 0L) 0f else (ts - lastAccelTimestamp) / 1_000_000_000f
                    lastAccelTimestamp = ts

                    val alpha = 0.8f
                    gravity[0] = alpha * gravity[0] + (1f - alpha) * event.values[0]
                    gravity[1] = alpha * gravity[1] + (1f - alpha) * event.values[1]
                    gravity[2] = alpha * gravity[2] + (1f - alpha) * event.values[2]

                    linear[0] = event.values[0] - gravity[0]
                    linear[1] = event.values[1] - gravity[1]
                    linear[2] = event.values[2] - gravity[2]

                    if (hasRotation) {
                        // Transform to world coordinates (Y is UP in standard AR systems, but here ARCore usually uses Y-up)
                        // This transform depends on how ARCore defines its local space vs sensor space.
                        // Standard sensor frame: X right, Y up (screen), Z out. 
                        // Rotation matrix converts sensor -> world.
                        worldAccel[0] = rotationMatrix[0] * linear[0] + rotationMatrix[1] * linear[1] + rotationMatrix[2] * linear[2]
                        worldAccel[1] = rotationMatrix[3] * linear[0] + rotationMatrix[4] * linear[1] + rotationMatrix[5] * linear[2]
                        worldAccel[2] = rotationMatrix[6] * linear[0] + rotationMatrix[7] * linear[1] + rotationMatrix[8] * linear[2]
                    } else {
                        worldAccel[0] = linear[0]
                        worldAccel[1] = linear[1]
                        worldAccel[2] = linear[2]
                    }

                    // Kalman smoothing for high-precision recording
                    val ax = kfX.update(worldAccel[0])
                    val ay = kfY.update(worldAccel[1])
                    val az = kfZ.update(worldAccel[2])

                    val sample = MotionSample(ax, ay, az, currentGyro[0], currentGyro[1], currentGyro[2], ts, currentLux, dt >= 0f && dt < 0.5f)
                    latest.set(sample)
                    
                    // Sensor Sampling Draw Data: If the renderer is stalled, 
                    // this callback allows the sensor thread to push draw data directly.
                    sensorDrawCallback?.invoke(sample)
                    
                    sampleQueue.add(sample)
                    if (sampleQueue.size > 100) sampleQueue.poll() // Guard against overflow
                }

                Sensor.TYPE_GYROSCOPE -> {
                    currentGyro[0] = event.values[0]
                    currentGyro[1] = event.values[1]
                    currentGyro[2] = event.values[2]
                }
            }
        } catch (_: Exception) {
            // Keep previous sample if the sensor pipeline hiccups.
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
