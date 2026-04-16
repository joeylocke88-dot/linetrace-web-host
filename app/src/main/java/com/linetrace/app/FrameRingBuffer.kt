package com.linetrace.app

class FrameRingBuffer(private val capacity: Int) {
    private val poses = Array(capacity) { FloatArray(16) }
    private val timestamps = LongArray(capacity)
    private val pointClouds = Array<FloatArray?>(capacity) { null }

    @Volatile private var writeIndex = 0L
    @Volatile private var readIndex = 0L

    fun push(pose: FloatArray, timestamp: Long, pc: FloatArray?) {
        val i = (writeIndex % capacity).toInt()

        System.arraycopy(pose, 0, poses[i], 0, 16)
        timestamps[i] = timestamp
        pointClouds[i] = pc

        writeIndex++
    }

    fun poll(): FrameData? {
        if (readIndex >= writeIndex) return null
        
        val i = (readIndex % capacity).toInt()
        val data = FrameData(
            poses[i].copyOf(),
            timestamps[i],
            pointClouds[i]
        )
        readIndex++
        return data
    }

    fun isAvailable(): Boolean = writeIndex > readIndex
    
    fun getLatestPose(): FloatArray? {
        if (writeIndex == 0L) return null
        val i = ((writeIndex - 1) % capacity).toInt()
        return poses[i].copyOf()
    }

    data class FrameData(val pose: FloatArray, val timestamp: Long, val pc: FloatArray?)
}
