package com.linetrace.app
import com.linetrace.app.feature.perception.FusionEngine
import com.linetrace.app.feature.telemetry.PathBuffer
import com.linetrace.app.presentation.LineRenderer
import com.linetrace.app.feature.perception.MotionTracker
import com.linetrace.app.core.Point
import com.linetrace.app.feature.telemetry.SessionRecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock

class SplineTest {

    @Test
    fun testCatmullRomSpline() {
        val livePath = PathBuffer(100, 100)
        val ghostPath = PathBuffer(500, 500)
        
        // Mock all dependencies to avoid Android runtime crashes
        val renderer = LineRenderer(
            mock(MotionTracker::class.java),
            mock(FusionEngine::class.java),
            livePath,
            ghostPath,
            mock(SessionRecorder::class.java)
        )

        val inputPoints = listOf(
            Point(0f, 0f, 0f, 0L, 1.0f),
            Point(1f, 1f, 0f, 100L, 1.0f),
            Point(2f, 0f, 0f, 200L, 1.0f),
            Point(3f, 1f, 0f, 300L, 1.0f)
        )

        renderer.loadGhost(inputPoints)

        // Adaptive step: distance is sqrt(2) ~ 1.414 per segment.
        // steps = max(3, min(25, (1.414 * 20).toInt())) = 25 per segment.
        // 3 segments * 25 points + 1 last point = 76.
        
        assertTrue("Ghost path should have points", ghostPath.size > 0)
        assertEquals(76, ghostPath.size)
    }
}
