package com.linetrace.app
import com.linetrace.app.feature.telemetry.PathBuffer
import com.linetrace.app.core.Point

import org.junit.Assert.*
import org.junit.Test

class PathBufferTest {

    @Test
    fun testSpatialSimplification() {
        val buffer = PathBuffer(capacityPoints = 100, maxPoints = 100)
        
        // Add first point
        buffer.addPoint(0f, 0f, 0f, 1.0f)
        assertEquals(1, buffer.size)
        
        // Add a point 0.5cm away (should be blended)
        // 0.005m = 0.5cm. 0.005^2 = 0.000025 < 0.0001
        buffer.addPoint(0.005f, 0f, 0f, 0.8f)
        assertEquals(1, buffer.size)
        
        val p = FloatArray(4)
        // History doesn't have it yet because it hasn't wrapped or been archived.
        // But we can check raw history if we export it, or use a mock/internal check.
        // Let's use getRawHistory which returns historyBuffer.
        // Actually addPoint blends in floatBuffer (Tier 1).
        
        // Let's add points far enough to trigger new points
        buffer.addPoint(0.02f, 0f, 0f, 1.0f) // 2cm away
        assertEquals(2, buffer.size)
        
        // Add many points in the same spot - size should not grow beyond 2
        for (i in 0 until 1000) {
            buffer.addPoint(0.02f, 0f, 0f, 1.0f)
        }
        assertEquals(2, buffer.size)
    }

    @Test
    fun testMemoryCapping() {
        val maxPoints = 500
        val buffer = PathBuffer(capacityPoints = 100, maxPoints = maxPoints)
        
        // Add 1000 points far apart to ensure they aren't simplified/blended
        for (i in 0 until 1000) {
            buffer.addPoint(i.toFloat() * 0.1f, 0f, 0f, 1.0f)
        }
        
        // Active buffer (Tier 1) should be capped at maxPoints
        assertEquals(maxPoints, buffer.size)
        
        // Unified History Size should be 1000 (Active + Archived)
        assertEquals(1000, buffer.getHistorySize())
        
        // Verify we can retrieve points from both tiers
        val p = FloatArray(4)
        buffer.getPoint(0, p) // From history
        assertEquals(0f, p[0], 0.001f)
        
        buffer.getPoint(999, p) // From active
        assertEquals(99.9f, p[0], 0.001f)
    }

    @Test
    fun testStressSpatialSimplification() {
        val buffer = PathBuffer(capacityPoints = 100, maxPoints = 1000)
        
        // Simulate 10 minutes at 200Hz = 120,000 points
        // If stationary, it should all be blended into 1 point.
        for (i in 0 until 120000) {
            buffer.addPoint(0.001f, 0.001f, 0.001f, 1.0f)
        }
        
        assertTrue("Buffer size should be minimal when stationary", buffer.size <= 2)
        assertEquals("Unified history size should match active size when nothing is archived", buffer.size, buffer.getHistorySize())
    }
}
