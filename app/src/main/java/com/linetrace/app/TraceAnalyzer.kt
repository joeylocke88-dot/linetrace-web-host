package com.linetrace.app

import java.util.Locale
import kotlin.math.*

data class TraceMetrics(
    val totalDistance: Float,
    val durationSeconds: Float,
    val averageSpeed: Float,
    val maxSpeed: Float,
    val poiCount: Int,
    val verticalDisplacement: Float, // Elevation gain/loss
    val curvatureIndex: Float, // Path length / displacement
    val averageStability: Float,
    val jitters: Int, // Count of sudden position jumps
    val smoothnessScore: Float, // 0 to 1 score
    val totalPoints: Int,
    val loopClosureError: Float?, // Distance between start and end if intended to be closed
    val areaEstimated: Float?, // 2D Area if loop is roughly closed
    val elevationGain: Float,
    val elevationLoss: Float,
    val altitudeProfile: List<Float> = emptyList(), // Altitudes relative to start
    val segments: List<PathSegment> = emptyList(),
    val boundingBoxArea: Float,
    val scanWidth: Float,
    val scanDepth: Float,
    val isEnvironmentScan: Boolean,
    val integrityScore: Float = 1.0f,
    val anomalies: List<DataAnomaly> = emptyList()
)

data class DataAnomaly(
    val index: Int,
    val type: AnomalyType,
    val description: String
)

enum class AnomalyType {
    WARP_JUMP, GRAVITY_VIOLATION, SENSOR_STALL, PHYSICS_BREACH
}

data class PathSegment(
    val type: SegmentType,
    val length: Float,
    val startIndex: Int,
    val endIndex: Int
)

enum class SegmentType {
    STRAIGHT, CURVE
}

object TraceAnalyzer {

    fun analyze(points: List<Point>): TraceMetrics {
        if (points.isEmpty()) return TraceMetrics(0f, 0f, 0f, 0f, 0, 0f, 0f, 0f, 0, 0f, 0, null, null, 0f, 0f, emptyList(), emptyList(), 0f, 0f, 0f, false, 0f)

        var distance = 0f
        var maxSpeed = 0f
        var totalStability = 0f
        var poiCount = 0
        var jitters = 0
        var totalAcceleration = 0f
        var elevationGain = 0f
        var elevationLoss = 0f
        val altitudeProfile = mutableListOf<Float>()
        val anomalies = mutableListOf<DataAnomaly>()
        
        val startY = points[0].y
        var minY = points[0].y
        var maxY = points[0].y
        var minX = points[0].x
        var maxX = points[0].x
        var minZ = points[0].z
        var maxZ = points[0].z

        var prevSpeed = 0f
        var prevDt = 0f

        for (i in points.indices) {
            val p = points[i]
            totalStability += p.stability
            if (p.type == PointType.POI) poiCount++
            
            minY = minOf(minY, p.y)
            maxY = maxOf(maxY, p.y)
            minX = minOf(minX, p.x)
            maxX = maxOf(maxX, p.x)
            minZ = minOf(minZ, p.z)
            maxZ = maxOf(maxZ, p.z)
            
            altitudeProfile.add(p.y - startY)

            if (i > 0) {
                val prev = points[i - 1]
                val dx = p.x - prev.x
                val dy = p.y - prev.y
                val dz = p.z - prev.z
                
                if (dy > 0) elevationGain += dy
                else elevationLoss += abs(dy)

                val d = sqrt(dx * dx + dy * dy + dz * dz)
                distance += d

                val dt = (p.tNanos - prev.tNanos) / 1_000_000_000f
                
                // --- INTEGRITY VERIFICATION ---
                if (dt <= 0) {
                    anomalies.add(DataAnomaly(i, AnomalyType.SENSOR_STALL, "Zero or negative time delta"))
                } else {
                    val speed = d / dt
                    if (speed > maxSpeed) maxSpeed = speed
                    
                    // Logic: Human walking speed > 5m/s or Vehicle > 40m/s is a breach for LineTrace
                    if (speed > 25.0f) {
                        anomalies.add(DataAnomaly(i, AnomalyType.PHYSICS_BREACH, "Extreme velocity: ${String.format(Locale.US, "%.1f", speed)} m/s"))
                    }

                    // Warp Jump: Distance change > 5m in < 0.05s
                    if (d > 5.0f && dt < 0.05f) {
                        anomalies.add(DataAnomaly(i, AnomalyType.WARP_JUMP, "Instantaneous displacement detected"))
                    }

                    // Gravity Violation: Vertical speed > 10m/s (falling or teleporting floors)
                    if (abs(dy) / dt > 10.0f) {
                        anomalies.add(DataAnomaly(i, AnomalyType.GRAVITY_VIOLATION, "Impossible vertical velocity"))
                    }

                    // Jitter detection
                    if (i > 1 && prevDt > 0) {
                        val acceleration = abs(speed - prevSpeed) / dt
                        totalAcceleration += acceleration
                        if (abs(speed - prevSpeed) > 2.0f) jitters++
                    }
                    prevSpeed = speed
                    prevDt = dt
                }
            }
        }

        val duration = (points.last().tNanos - points.first().tNanos) / 1_000_000_000f
        val avgSpeed = if (duration > 0) distance / duration else 0f
        
        val smoothness = if (points.size > 2) {
            val avgAccel = totalAcceleration / (points.size - 2)
            (1.0f / (1.0f + avgAccel)).coerceIn(0f, 1f)
        } else 1.0f

        val start = points.first()
        val end = points.last()
        val displacement = sqrt((end.x - start.x) * (end.x - start.x) + 
                                (end.y - start.y) * (end.y - start.y) + 
                                (end.z - start.z) * (end.z - start.z))
        
        val curvature = if (displacement > 0.1f) distance / displacement else 1.0f

        val loopError = if (distance > 5.0f && displacement < 0.5f) displacement else null
        
        val area = if (loopError != null && points.size > 3) {
            var sum = 0f
            for (i in 0 until points.size - 1) {
                sum += points[i].x * points[i+1].z - points[i+1].x * points[i].z
            }
            sum += points.last().x * points.first().z - points.first().x * points.last().z
            abs(sum) / 2.0f
        } else null

        val segments = mutableListOf<PathSegment>()
        if (points.size > 3) {
            var currentSegmentStart = 0
            var isCurrentlyCurved = false
            var segmentLength = 0f

            for (i in 1 until points.size - 1) {
                val p1 = points[i - 1]
                val p2 = points[i]
                val p3 = points[i + 1]

                val v1x = p2.x - p1.x
                val v1z = p2.z - p1.z
                val v2x = p3.x - p2.x
                val v2z = p3.z - p2.z

                val d1 = sqrt(v1x * v1x + v1z * v1z)
                val d2 = sqrt(v2x * v2x + v2z * v2z)
                
                segmentLength += d1

                if (d1 > 0.001f && d2 > 0.001f) {
                    val dot = (v1x * v2x + v1z * v2z) / (d1 * d2)
                    val angle = acos(dot.toDouble().coerceIn(-1.0, 1.0))
                    val isCurved = angle > (15.0 * PI / 180.0)

                    if (i == 1) {
                        isCurrentlyCurved = isCurved
                    } else if (isCurved != isCurrentlyCurved) {
                        segments.add(PathSegment(
                            if (isCurrentlyCurved) SegmentType.CURVE else SegmentType.STRAIGHT,
                            segmentLength,
                            currentSegmentStart,
                            i
                        ))
                        currentSegmentStart = i
                        segmentLength = 0f
                        isCurrentlyCurved = isCurved
                    }
                }
            }
            segments.add(PathSegment(
                if (isCurrentlyCurved) SegmentType.CURVE else SegmentType.STRAIGHT,
                segmentLength,
                currentSegmentStart,
                points.size - 1
            ))
        }

        val scanWidth = maxX - minX
        val scanDepth = maxZ - minZ
        val bboxArea = scanWidth * scanDepth
        val isEnvironmentScan = bboxArea > 4.0f && distance > 5.0f

        // Calculate final integrity score based on anomaly density
        val integrityScore = (1.0f - (anomalies.size.toFloat() / points.size.toFloat() * 10f)).coerceIn(0f, 1f)

        return TraceMetrics(
            totalDistance = distance,
            durationSeconds = duration,
            averageSpeed = avgSpeed,
            maxSpeed = maxSpeed,
            poiCount = poiCount,
            verticalDisplacement = maxY - minY,
            curvatureIndex = curvature,
            averageStability = totalStability / points.size,
            jitters = jitters,
            smoothnessScore = smoothness,
            totalPoints = points.size,
            loopClosureError = loopError,
            areaEstimated = area,
            elevationGain = elevationGain,
            elevationLoss = elevationLoss,
            altitudeProfile = altitudeProfile,
            segments = segments,
            boundingBoxArea = bboxArea,
            scanWidth = scanWidth,
            scanDepth = scanDepth,
            isEnvironmentScan = isEnvironmentScan,
            integrityScore = integrityScore,
            anomalies = anomalies
        )
    }
}
