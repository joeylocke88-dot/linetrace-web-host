package com.linetrace.app.feature.telemetry
import com.linetrace.app.core.Point
import com.linetrace.app.core.PointType

import android.os.Handler
import android.os.HandlerThread
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.Locale
import kotlin.math.sqrt

class SessionRecorder(private val baseDir: File? = null) {

    private val points = mutableListOf<Point>()
    private var startTime: Long = 0
    private var totalDistance: Float = 0f

    // Background thread for heavy JSON/File operations
    private val backgroundThread = HandlerThread("SessionRecorderThread").apply { start() }
    private val backgroundHandler = Handler(backgroundThread.looper)

    @Synchronized
    fun start() {
        points.clear()
        startTime = System.currentTimeMillis()
        totalDistance = 0f
    }

        fun record(x: Float, y: Float, z: Float, tNanos: Long, stability: Float, type: PointType = PointType.NORMAL) {
        val p = Point(x, y, z, tNanos, stability, type)
        synchronized(this) {
            if (points.isNotEmpty()) {
                val last = points.last()
                val dx = x - last.x
                val dy = y - last.y
                val dz = z - last.z
                val dist = sqrt(dx * dx + dy * dy + dz * dz)
                
                // 🛑 Optimization: Only record if moved > 1cm OR if it's a POI
                if (dist < 0.01f && type == PointType.NORMAL) return
                
                totalDistance += dist
            }
            points.add(p)
            
            // 🛑 Safety Cap: Prevent infinite memory growth
            if (points.size > 10000) {
                points.removeAt(0)
            }
        }
    }

    @Synchronized
    fun getAll(): List<Point> = points.toList()

    @Synchronized
    fun getDistance(): Float = totalDistance

    @Synchronized
    fun getVelocity(): Float {
        val lastTwo = synchronized(this) {
            if (points.size < 2) null else (points[points.size - 2] to points.last())
        } ?: return 0f
        
        val prev = lastTwo.first
        val last = lastTwo.second
        val dt = (last.tNanos - prev.tNanos) / 1_000_000_000f
        if (dt <= 0) return 0f
        val dx = last.x - prev.x
        val dz = last.z - prev.z
        // Velocity derived from horizontal movement to match Distance logic
        return sqrt(dx * dx + dz * dz) / dt
    }

    @Synchronized
    fun getPointCount(): Int = points.size

    @Synchronized
    fun offsetPoints(dx: Float, dy: Float, dz: Float) {
        for (i in points.indices) {
            val p = points[i]
            points[i] = Point(p.x + dx, p.y + dy, p.z + dz, p.tNanos, p.stability, p.type)
        }
    }

    /**
     * Saves the session to both JSON and CSV files asynchronously using streaming to prevent OOM.
     */
    fun saveSessionAsync(onComplete: (json: File?, csv: File?) -> Unit) {
        val pointsSnapshot = synchronized(this) { points.toList() }
        val startTimeSnapshot = startTime
        val distanceSnapshot = totalDistance
        
        if (pointsSnapshot.isEmpty() || baseDir == null) {
            onComplete(null, null)
            return
        }

        backgroundHandler.post {
            var jsonFile: File? = null
            var csvFile: File? = null
            try {
                // 1. Save JSON via Streaming
                val jsonFilename = "session_${startTimeSnapshot}.json"
                jsonFile = File(baseDir, jsonFilename)
                
                jsonFile.bufferedWriter().use { writer ->
                    val jsonWriter = android.util.JsonWriter(writer)
                    jsonWriter.setIndent("  ")
                    jsonWriter.beginObject()
                    
                    jsonWriter.name("startTime").value(startTimeSnapshot)
                    
                    val duration = if (pointsSnapshot.size >= 2) {
                        (pointsSnapshot.last().tNanos - pointsSnapshot.first().tNanos) / 1_000_000L
                    } else {
                        System.currentTimeMillis() - startTimeSnapshot
                    }
                    jsonWriter.name("duration").value(duration)
                    jsonWriter.name("distance").value(distanceSnapshot.toDouble())
                    
                    jsonWriter.name("points")
                    jsonWriter.beginArray()
                    for (p in pointsSnapshot) {
                        jsonWriter.beginObject()
                        jsonWriter.name("x").value(p.x.toDouble())
                        jsonWriter.name("y").value(p.y.toDouble())
                        jsonWriter.name("z").value(p.z.toDouble())
                        jsonWriter.name("t").value(p.tNanos)
                        jsonWriter.name("s").value(p.stability.toDouble())
                        if (p.type != PointType.NORMAL) {
                            jsonWriter.name("type").value(p.type.name)
                        }
                        jsonWriter.endObject()
                    }
                    jsonWriter.endArray()
                    
                    jsonWriter.endObject()
                    jsonWriter.close()
                }

                // 2. Save CSV
                val csvFilename = "session_${startTimeSnapshot}.csv"
                csvFile = File(baseDir, csvFilename)
                exportCsvInternal(csvFile, pointsSnapshot)
                
                onComplete(jsonFile, csvFile)
            } catch (e: Exception) {
                android.util.Log.e("SessionRecorder", "Failed to save session", e)
                onComplete(jsonFile, csvFile)
            }
        }
    }

    fun listSessions(): List<File> {
        return baseDir?.listFiles { f -> f.name.startsWith("session_") && (f.name.endsWith(".json") || f.name.endsWith(".csv")) }
            ?.toList()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun loadSession(file: File): List<Point> {
        return try {
            val json = JSONObject(file.readText())
            val array = json.getJSONArray("points")
            val loaded = mutableListOf<Point>()
            for (i in 0 until array.length()) {
                val pj = array.getJSONObject(i)
                val x = pj.getDouble("x").toFloat()
                val hasZ = pj.has("z")
                val y = if (hasZ) pj.getDouble("y").toFloat() else 0f
                val z = if (hasZ) pj.getDouble("z").toFloat() else pj.getDouble("y").toFloat()
                val t = pj.getLong("t")
                val s = pj.optDouble("s", 1.0).toFloat()
                val typeStr = pj.optString("type", "NORMAL")
                val type = try { PointType.valueOf(typeStr) } catch(e: Exception) { PointType.NORMAL }
                loaded.add(Point(x, y, z, t, s, type))
            }
            loaded
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Migrates legacy 2D sessions (X, Y) to the new 3D format (X, Y, Z, Stability).
     * Legacy 'y' is mapped to 'z', and a new 'y' of 0.0 is inserted.
     */
    fun migrateLegacySessions(onComplete: (count: Int) -> Unit) {
        backgroundHandler.post {
            val sessions = listSessions().filter { it.name.endsWith(".json") }
            var migratedCount = 0
            
            for (file in sessions) {
                try {
                    val content = file.readText()
                    val json = JSONObject(content)
                    val array = json.getJSONArray("points")
                    var needsMigration = false
                    
                    val migratedPoints = JSONArray()
                    for (i in 0 until array.length()) {
                        val pj = array.getJSONObject(i)
                        if (!pj.has("z")) {
                            needsMigration = true
                            val legacyX = pj.getDouble("x")
                            val legacyY = pj.getDouble("y") // This was actually the Z in 2D plane
                            val t = pj.optLong("t", 0L)
                            val s = pj.optDouble("s", 1.0)
                            
                            val newPj = JSONObject()
                            newPj.put("x", legacyX)
                            newPj.put("y", 0.0) // Flat ground
                            newPj.put("z", legacyY)
                            newPj.put("t", t)
                            newPj.put("s", s)
                            migratedPoints.put(newPj)
                        } else {
                            migratedPoints.put(pj)
                        }
                    }
                    
                    if (needsMigration) {
                        json.put("points", migratedPoints)
                        json.put("migrated", true)
                        file.writeText(json.toString())
                        migratedCount++
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SessionRecorder", "Migration failed for ${file.name}", e)
                }
            }
            onComplete(migratedCount)
        }
    }

    @Synchronized
    fun exportCsv(file: File) {
        exportCsvInternal(file, points)
    }

    private fun exportCsvInternal(file: File, pointsList: List<Point>) {
        try {
            FileWriter(file).use { writer ->
                writer.appendLine("x,y,z,tNanos,stability,type")
                for (p in pointsList) {
                    writer.appendLine(String.format(Locale.US, "%.6f,%.6f,%.6f,%d,%.2f,%s", p.x, p.y, p.z, p.tNanos, p.stability, p.type.name))
                }
            }
        } catch (e: IOException) {
            throw RuntimeException("Failed to export CSV: ${e.message}", e)
        }
    }
    
    @Synchronized
    fun clear() {
        points.clear()
        totalDistance = 0f
    }
    
    fun shutdown() {
        backgroundThread.quitSafely()
    }
}
