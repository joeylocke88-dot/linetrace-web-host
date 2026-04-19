package com.linetrace.app.feature.telemetry
import com.linetrace.app.core.Point

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * The "Dapper" Buffer: High-performance dual-tier storage (RAM + VRAM).
 * Manages an active rolling window in RAM and a long-term history in GPU VRAM.
 * Upgraded to 4D (X, Y, Z, Stability) for vertical tracking and confidence-based coloring.
 */
class PathBuffer(private var capacityPoints: Int = 1024, val maxPoints: Int = 2000) {
    
    private val coordsPerPoint = 4 // X, Y, Z, Stability

    // --- RAM Tier 1 (Active Rolling Window) ---
    private var byteBuffer = ByteBuffer.allocateDirect(capacityPoints * coordsPerPoint * 4)
        .order(ByteOrder.nativeOrder())
    private var floatBuffer = byteBuffer.asFloatBuffer()
    private var head = 0
    @Volatile var size: Int = 0
        private set

    // --- RAM Tier 2 (Full History for VBO Sync) ---
    private val historyMaxPoints = 50000
    private var historyBuffer = ByteBuffer.allocateDirect(historyMaxPoints * coordsPerPoint * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var historySize = 0

    // --- VRAM Tier (GPU VBO) ---
    private var vboId = -1
    private var vboNeedsSync = false
    private var vboSyncedSize = 0
    private var ribbonVboId = -1
    private var ribbonVboCapacity = 0

    // Pre-allocated for circular -> linear conversion
    private var renderByteBuffer: ByteBuffer? = null
    private var renderFloatBuffer: FloatBuffer? = null

    private var ribbonByteBuffer: ByteBuffer? = null
    private var ribbonFloatBuffer: FloatBuffer? = null

    // --- Alien Tier (Persistent Dealers) ---
    private var alienX: Float = 0f
    private var alienY: Float = 0f
    private var alienZ: Float = 0f
    private var alienSeed: Int = -1

    fun getVboId(): Int {
        syncVboIfNeeded()
        return vboId
    }

    fun syncVboExternal() {
        syncVboIfNeeded()
    }

    // --- Temporary Point for "Line follows device" ---
    private var tempX: Float = 0f
    private var tempY: Float = 0f
    private var tempZ: Float = 0f
    private var tempS: Float = 0f
    private var hasTempPoint = false

    @Synchronized
    fun setTemporaryPoint(x: Float, y: Float, z: Float, s: Float) {
        tempX = x
        tempY = y
        tempZ = z
        tempS = s
        hasTempPoint = true
    }

    @Synchronized
    fun clearTemporaryPoint() {
        hasTempPoint = false
    }

    @Synchronized
    fun setAlien(x: Float, y: Float, z: Float, seed: Int) {
        alienX = x
        alienY = y
        alienZ = z
        alienSeed = seed
    }

    @Synchronized
    fun getAlienData(): FloatArray = floatArrayOf(alienX, alienY, alienZ, alienSeed.toFloat())

    @Synchronized
    fun hasAlien(): Boolean = alienSeed != -1

    @Synchronized
    fun clear() {
        size = 0
        head = 0
        historySize = 0
        vboSyncedSize = 0
        vboNeedsSync = true
        alienSeed = -1
        hasTempPoint = false
    }

    @Synchronized
    fun getRawHistory(): FloatArray {
        val totalPoints = historySize + size
        val alienData = getAlienData()
        val data = FloatArray(totalPoints * coordsPerPoint + alienData.size)
        
        // 0. Copy Alien Data at the very beginning
        System.arraycopy(alienData, 0, data, 0, alienData.size)
        val dataOffset = alienData.size

        // 1. Copy History
        historyBuffer.position(0)
        historyBuffer.get(data, dataOffset, historySize * coordsPerPoint)
        
        // 2. Copy Active (handling circular buffer)
        val activeOffset = dataOffset + historySize * coordsPerPoint
        if (size >= maxPoints && head != 0) {
            val part1Size = (maxPoints - head) * coordsPerPoint
            floatBuffer.position(head * coordsPerPoint)
            floatBuffer.get(data, activeOffset, part1Size)
            
            floatBuffer.position(0)
            floatBuffer.get(data, activeOffset + part1Size, head * coordsPerPoint)
        } else {
            floatBuffer.position(0)
            floatBuffer.get(data, activeOffset, size * coordsPerPoint)
        }
        
        // Reset positions
        historyBuffer.position(0)
        floatBuffer.position(0)
        floatBuffer.limit(floatBuffer.capacity())
        
        return data
    }

    @Synchronized
    fun getHistorySize(): Int = historySize + size

    fun getPoint(index: Int, out: FloatArray) {
        val totalSize = historySize + size
        if (index < 0 || index >= totalSize) return
        
        if (index < historySize) {
            out[0] = historyBuffer.get(index * coordsPerPoint)
            out[1] = historyBuffer.get(index * coordsPerPoint + 1)
            out[2] = historyBuffer.get(index * coordsPerPoint + 2)
            out[3] = historyBuffer.get(index * coordsPerPoint + 3)
        } else {
            val activeIdx = index - historySize
            val actualIdx = if (size >= maxPoints) (head + activeIdx) % maxPoints else activeIdx
            out[0] = floatBuffer.get(actualIdx * coordsPerPoint)
            out[1] = floatBuffer.get(actualIdx * coordsPerPoint + 1)
            out[2] = floatBuffer.get(actualIdx * coordsPerPoint + 2)
            out[3] = floatBuffer.get(actualIdx * coordsPerPoint + 3)
        }
    }

    @Synchronized
    fun findClosestIndex(x: Float, y: Float, z: Float): Int {
        var minD2 = Float.MAX_VALUE
        var closestIdx = -1
        val totalSize = historySize + size
        
        // Performance Optimization: Search backwards from the most recent points first.
        // Users are statistically more likely to be near the "active" end of the path.
        val p = FloatArray(4)
        for (i in totalSize - 1 downTo 0) {
            getPoint(i, p)
            val dx = x - p[0]
            val dy = y - p[1]
            val dz = z - p[2]
            val d2 = dx*dx + dy*dy + dz*dz
            
            if (d2 < minD2) {
                minD2 = d2
                closestIdx = i
            }
            
            // Early exit if we find a "perfect" match (within 5mm) to save CPU
            if (minD2 < 0.000025f) break 
        }
        return closestIdx
    }

    @Synchronized
    fun importHistory(data: FloatArray, count: Int) {
        clear()
        
        // Count how many non-alien points we have
        // data[0..3] is alien data (x, y, z, seed)
        val alienX = data[0]
        val alienY = data[1]
        val alienZ = data[2]
        val alienSeed = data[3].toInt()
        
        if (alienSeed != -1) {
            setAlien(alienX, alienY, alienZ, alienSeed)
        }

        val pointsToImport = Math.min((data.size - 4) / coordsPerPoint, historyMaxPoints)
        historyBuffer.position(0)
        historyBuffer.put(data, 4, pointsToImport * coordsPerPoint)
        historySize = pointsToImport
        vboNeedsSync = true
    }

    /**
     * Resets GL resources on context loss.
     */
    fun resetResources() {
        vboId = -1
        ribbonVboId = -1
        ribbonVboCapacity = 0
        vboSyncedSize = 0
        vboNeedsSync = true
    }

    private val SIMPLIFICATION_THRESHOLD_SQ = 0.0001f // 1cm squared
    private val MAX_SEGMENT_LENGTH_SQ = 1.0f // 1 meter squared safety limit

    /**
     * Adds a point with adaptive Catmull-Rom-like stability checks.
     * Prevents "spikes" during VIO jumps or hardware stalls.
     */
    @Synchronized
    fun addPoint(x: Float, y: Float, z: Float, stability: Float = 1.0f) {
        if (size > 0) {
            val lastIdx = if (size >= maxPoints) (head - 1 + maxPoints) % maxPoints else size - 1
            val lx = floatBuffer.get(lastIdx * coordsPerPoint)
            val ly = floatBuffer.get(lastIdx * coordsPerPoint + 1)
            val lz = floatBuffer.get(lastIdx * coordsPerPoint + 2)
            
            val dx = x - lx
            val dy = y - ly
            val dz = z - lz
            val d2 = dx*dx + dy*dy + dz*dz

            // VIO Spike Guard: Reject points that jump > 1m instantly unless stability is 100%
            if (d2 > MAX_SEGMENT_LENGTH_SQ && stability < 0.9f) {
                android.util.Log.w("PathBuffer", "Ghost Path: VIO Spike Detected (dist: ${Math.sqrt(d2.toDouble())}m). Point suppressed.")
                return
            }

            // Spatial Simplification: Blend extremely close points
            if (d2 < SIMPLIFICATION_THRESHOLD_SQ) {
                floatBuffer.put(lastIdx * coordsPerPoint, (lx + x) * 0.5f)
                floatBuffer.put(lastIdx * coordsPerPoint + 1, (ly + y) * 0.5f)
                floatBuffer.put(lastIdx * coordsPerPoint + 2, (lz + z) * 0.5f)
                floatBuffer.put(lastIdx * coordsPerPoint + 3, Math.max(floatBuffer.get(lastIdx * coordsPerPoint + 3), stability))
                return
            }
        }

        if (size >= maxPoints) {
            // Archive the point being overwritten
            val oldX = floatBuffer.get(head * coordsPerPoint)
            val oldY = floatBuffer.get(head * coordsPerPoint + 1)
            val oldZ = floatBuffer.get(head * coordsPerPoint + 2)
            val oldS = floatBuffer.get(head * coordsPerPoint + 3)
            archivePoint(oldX, oldY, oldZ, oldS)

            floatBuffer.put(head * coordsPerPoint, x)
            floatBuffer.put(head * coordsPerPoint + 1, y)
            floatBuffer.put(head * coordsPerPoint + 2, z)
            floatBuffer.put(head * coordsPerPoint + 3, stability)
            head = (head + 1) % maxPoints
        } else {
            ensureCapacity(size + 1)
            floatBuffer.put(size * coordsPerPoint, x)
            floatBuffer.put(size * coordsPerPoint + 1, y)
            floatBuffer.put(size * coordsPerPoint + 2, z)
            floatBuffer.put(size * coordsPerPoint + 3, stability)
            size++
        }
    }

    private fun archivePoint(x: Float, y: Float, z: Float, s: Float) {
        if (historySize < historyMaxPoints) {
            historyBuffer.put(historySize * coordsPerPoint, x)
            historyBuffer.put(historySize * coordsPerPoint + 1, y)
            historyBuffer.put(historySize * coordsPerPoint + 2, z)
            historyBuffer.put(historySize * coordsPerPoint + 3, s)
            historySize++
            vboNeedsSync = true
        }
    }

    /**
     * Shifts all points (RAM and History) and marks VRAM for re-sync.
     * Also offsets the persistent Alien Contact position.
     */
    fun offsetPoints(dx: Float, dy: Float, dz: Float) {
        // Shift active window
        for (i in 0 until size) {
            floatBuffer.put(i * coordsPerPoint, floatBuffer.get(i * coordsPerPoint) + dx)
            floatBuffer.put(i * coordsPerPoint + 1, floatBuffer.get(i * coordsPerPoint + 1) + dy)
            floatBuffer.put(i * coordsPerPoint + 2, floatBuffer.get(i * coordsPerPoint + 2) + dz)
        }
        // Shift history archive
        for (i in 0 until historySize) {
            historyBuffer.put(i * coordsPerPoint, historyBuffer.get(i * coordsPerPoint) + dx)
            historyBuffer.put(i * coordsPerPoint + 1, historyBuffer.get(i * coordsPerPoint + 1) + dy)
            historyBuffer.put(i * coordsPerPoint + 2, historyBuffer.get(i * coordsPerPoint + 2) + dz)
        }
        
        // Shift Alien Contact
        if (alienSeed != -1) {
            alienX += dx
            alienY += dy
            alienZ += dz
        }

        vboNeedsSync = true
    }

    /**
     * Renders the path with high performance.
     */
    @Synchronized
    fun draw(positionHandle: Int) {
        syncVboIfNeeded() 

        // 1. Draw Active (RAM) - Using X, Y, Z, and Stability (W)
        if (size > 0 || hasTempPoint) {
            val active = getBuffer()
            val hasHistory = historySize > 0
            val renderSize = size + (if (hasHistory) 1 else 0) + (if (hasTempPoint) 1 else 0)
            GLES30.glVertexAttribPointer(positionHandle, coordsPerPoint, GLES30.GL_FLOAT, false, 0, active)
            GLES30.glEnableVertexAttribArray(positionHandle)
            GLES30.glDrawArrays(GLES30.GL_LINE_STRIP, 0, renderSize)
        }

        // 2. Draw History (VRAM)
        if (vboId != -1 && historySize > 0) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
            GLES30.glVertexAttribPointer(positionHandle, coordsPerPoint, GLES30.GL_FLOAT, false, 0, 0)
            GLES30.glEnableVertexAttribArray(positionHandle)
            GLES30.glDrawArrays(GLES30.GL_LINE_STRIP, 0, historySize)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        }
    }

    /**
     * Renders a 3D ribbon/wall based on the path.
     * Uses GL_TRIANGLE_STRIP to extrude the line vertically.
     */
    @Synchronized
    fun drawRibbon(positionHandle: Int, stabilityHandle: Int) {
        val totalPoints = size + historySize + (if (hasTempPoint) 1 else 0)
        if (totalPoints < 2) return

        val strideFloats = 5 // x, y, z, heightFactor, stability
        val strideBytes = strideFloats * 4
        val totalVertices = totalPoints * 2
        val neededSize = totalVertices * strideBytes

        // Ensure VBO is initialized and has enough capacity
        if (ribbonVboId == -1) {
            val vbos = IntArray(1)
            GLES30.glGenBuffers(1, vbos, 0)
            ribbonVboId = vbos[0]
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ribbonVboId)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, neededSize, null, GLES30.GL_DYNAMIC_DRAW)
            ribbonVboCapacity = neededSize
        } else if (neededSize > ribbonVboCapacity) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ribbonVboId)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, neededSize, null, GLES30.GL_DYNAMIC_DRAW)
            ribbonVboCapacity = neededSize
        }

        // Prepare local CPU buffer for upload
        if (ribbonByteBuffer == null || ribbonByteBuffer!!.capacity() < neededSize) {
            ribbonByteBuffer = ByteBuffer.allocateDirect(neededSize).order(ByteOrder.nativeOrder())
            ribbonFloatBuffer = ribbonByteBuffer!!.asFloatBuffer()
        }

        val out = ribbonFloatBuffer!!
        out.clear()

        // 1. Add History
        for (i in 0 until historySize) {
            val hOffset = i * coordsPerPoint
            val x = historyBuffer.get(hOffset)
            val y = historyBuffer.get(hOffset + 1)
            val z = historyBuffer.get(hOffset + 2)
            val s = historyBuffer.get(hOffset + 3)
            
            out.put(x).put(y).put(z).put(0f) // Base: xyz + w=0
            out.put(s)                       // Stability
            out.put(x).put(y).put(z).put(1f) // Top: xyz + w=1
            out.put(s)                       // Stability
        }

        // 2. Add Active
        for (i in 0 until size) {
            val idx = if (size >= maxPoints) (head + i) % maxPoints else i
            val aOffset = idx * coordsPerPoint
            val x = floatBuffer.get(aOffset)
            val y = floatBuffer.get(aOffset + 1)
            val z = floatBuffer.get(aOffset + 2)
            val s = floatBuffer.get(aOffset + 3)

            out.put(x).put(y).put(z).put(0f)
            out.put(s)
            out.put(x).put(y).put(z).put(1f)
            out.put(s)
        }

        // 3. Add Temp
        if (hasTempPoint) {
            out.put(tempX).put(tempY).put(tempZ).put(0f)
            out.put(tempS)
            out.put(tempX).put(tempY).put(tempZ).put(1f)
            out.put(tempS)
        }

        out.flip()
        
        // Upload to VBO
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ribbonVboId)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, neededSize, out)
        
        // Set pointers using the VBO
        GLES30.glVertexAttribPointer(positionHandle, 4, GLES30.GL_FLOAT, false, strideBytes, 0)
        GLES30.glEnableVertexAttribArray(positionHandle)
        
        GLES30.glVertexAttribPointer(stabilityHandle, 1, GLES30.GL_FLOAT, false, strideBytes, 16)
        GLES30.glEnableVertexAttribArray(stabilityHandle)
        
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, totalVertices)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun syncVboIfNeeded() {
        // Must be called from a @Synchronized context or be @Synchronized itself
        if (!vboNeedsSync) return
        
        if (vboId == -1) {
            val vbos = IntArray(1)
            GLES30.glGenBuffers(1, vbos, 0)
            vboId = vbos[0]
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, historyMaxPoints * coordsPerPoint * 4, null, GLES30.GL_DYNAMIC_DRAW)
            vboSyncedSize = 0
        }

        if (historySize > vboSyncedSize) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
            val newPoints = historySize - vboSyncedSize
            val offset = vboSyncedSize * coordsPerPoint * 4
            val sizeBytes = newPoints * coordsPerPoint * 4
            
            historyBuffer.position(vboSyncedSize * coordsPerPoint)
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, offset, sizeBytes, historyBuffer)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            historyBuffer.position(0)
            vboSyncedSize = historySize
        }
        vboNeedsSync = false
    }

    private fun getBuffer(): FloatBuffer {
        val hasHistory = historySize > 0
        val extraPoints = (if (hasHistory) 1 else 0) + (if (hasTempPoint) 1 else 0)
        val totalPointsToRender = size + extraPoints
        val neededCapacity = totalPointsToRender * coordsPerPoint * 4
        
        if (renderByteBuffer == null || renderByteBuffer!!.capacity() < neededCapacity) {
            renderByteBuffer = ByteBuffer.allocateDirect(neededCapacity).order(ByteOrder.nativeOrder())
            renderFloatBuffer = renderByteBuffer!!.asFloatBuffer()
        }
        
        val out = renderFloatBuffer!!
        out.clear()
        
        if (hasHistory) {
            // Continuity Fix: Prepend the last point of history to the active strip
            val hOffset = (historySize - 1) * coordsPerPoint
            out.put(historyBuffer.get(hOffset))
            out.put(historyBuffer.get(hOffset + 1))
            out.put(historyBuffer.get(hOffset + 2))
            out.put(historyBuffer.get(hOffset + 3))
        }

        if (size > 0) {
            if (size >= maxPoints && head != 0) {
                floatBuffer.position(head * coordsPerPoint); floatBuffer.limit(size * coordsPerPoint); out.put(floatBuffer)
                floatBuffer.position(0); floatBuffer.limit(head * coordsPerPoint); out.put(floatBuffer)
            } else {
                floatBuffer.position(0); floatBuffer.limit(size * coordsPerPoint); out.put(floatBuffer)
            }
        }
        
        if (hasTempPoint) {
            out.put(tempX)
            out.put(tempY)
            out.put(tempZ)
            out.put(tempS)
        }

        out.flip()
        floatBuffer.limit(floatBuffer.capacity())
        return out
    }

    private fun ensureCapacity(requiredPoints: Int) {
        if (requiredPoints <= capacityPoints) return
        var newCapacity = capacityPoints
        while (newCapacity < requiredPoints) newCapacity *= 2
        if (newCapacity > maxPoints) newCapacity = maxPoints
        if (newCapacity <= capacityPoints) return

        val newByteBuffer = ByteBuffer.allocateDirect(newCapacity * coordsPerPoint * 4).order(ByteOrder.nativeOrder())
        val newFloatBuffer = newByteBuffer.asFloatBuffer()
        floatBuffer.position(0); floatBuffer.limit(size * coordsPerPoint); newFloatBuffer.put(floatBuffer)
        byteBuffer = newByteBuffer; floatBuffer = newFloatBuffer; capacityPoints = newCapacity
    }
}
