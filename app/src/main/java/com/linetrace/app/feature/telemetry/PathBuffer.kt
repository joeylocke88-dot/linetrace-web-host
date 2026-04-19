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
    private var lastVboHead = -1
    private var ribbonVboId = -1
    private var ribbonVboCapacity = 0

    // Epoch-based offset to avoid O(N) CPU shifts
    private var offsetX = 0f
    private var offsetY = 0f
    private var offsetZ = 0f

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
        lastVboHead = -1
        vboNeedsSync = true
        alienSeed = -1
        hasTempPoint = false
        offsetX = 0f; offsetY = 0f; offsetZ = 0f
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
        
        // Apply current offset to the exported data so it matches the world
        for (i in 0 until totalPoints) {
            data[dataOffset + i * coordsPerPoint] += offsetX
            data[dataOffset + i * coordsPerPoint + 1] += offsetY
            data[dataOffset + i * coordsPerPoint + 2] += offsetZ
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
            out[0] = historyBuffer.get(index * coordsPerPoint) + offsetX
            out[1] = historyBuffer.get(index * coordsPerPoint + 1) + offsetY
            out[2] = historyBuffer.get(index * coordsPerPoint + 2) + offsetZ
            out[3] = historyBuffer.get(index * coordsPerPoint + 3)
        } else {
            val activeIdx = index - historySize
            val actualIdx = if (size >= maxPoints) (head + activeIdx) % maxPoints else activeIdx
            out[0] = floatBuffer.get(actualIdx * coordsPerPoint) + offsetX
            out[1] = floatBuffer.get(actualIdx * coordsPerPoint + 1) + offsetY
            out[2] = floatBuffer.get(actualIdx * coordsPerPoint + 2) + offsetZ
            out[3] = floatBuffer.get(actualIdx * coordsPerPoint + 3)
        }
    }

    @Synchronized
    fun findClosestIndex(x: Float, y: Float, z: Float): Int {
        var minD2 = Float.MAX_VALUE
        var closestIdx = -1
        val totalSize = historySize + size
        
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
            if (minD2 < 0.000025f) break 
        }
        return closestIdx
    }

    @Synchronized
    fun importHistory(data: FloatArray, count: Int) {
        clear()
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

    fun resetResources() {
        vboId = -1
        ribbonVboId = -1
        ribbonVboCapacity = 0
        vboSyncedSize = 0
        lastVboHead = -1
        vboNeedsSync = true
    }

    private val SIMPLIFICATION_THRESHOLD_SQ = 0.0001f // 1cm squared
    private val MAX_SEGMENT_LENGTH_SQ = 1.0f // 1 meter squared safety limit

    @Synchronized
    fun addPoint(x: Float, y: Float, z: Float, stability: Float = 1.0f) {
        // Adjust input point by reverse-offset so it's stored in local space
        val lx_local = x - offsetX
        val ly_local = y - offsetY
        val lz_local = z - offsetZ

        if (size > 0) {
            val lastIdx = if (size >= maxPoints) (head - 1 + maxPoints) % maxPoints else size - 1
            val lx = floatBuffer.get(lastIdx * coordsPerPoint)
            val ly = floatBuffer.get(lastIdx * coordsPerPoint + 1)
            val lz = floatBuffer.get(lastIdx * coordsPerPoint + 2)
            
            val dx = lx_local - lx
            val dy = ly_local - ly
            val dz = lz_local - lz
            val d2 = dx*dx + dy*dy + dz*dz

            if (d2 > MAX_SEGMENT_LENGTH_SQ && stability < 0.9f) {
                return
            }

            if (d2 < SIMPLIFICATION_THRESHOLD_SQ) {
                floatBuffer.put(lastIdx * coordsPerPoint, (lx + lx_local) * 0.5f)
                floatBuffer.put(lastIdx * coordsPerPoint + 1, (ly + ly_local) * 0.5f)
                floatBuffer.put(lastIdx * coordsPerPoint + 2, (lz + lz_local) * 0.5f)
                floatBuffer.put(lastIdx * coordsPerPoint + 3, Math.max(floatBuffer.get(lastIdx * coordsPerPoint + 3), stability))
                return
            }
        }

        if (size >= maxPoints) {
            val oldX = floatBuffer.get(head * coordsPerPoint)
            val oldY = floatBuffer.get(head * coordsPerPoint + 1)
            val oldZ = floatBuffer.get(head * coordsPerPoint + 2)
            val oldS = floatBuffer.get(head * coordsPerPoint + 3)
            archivePoint(oldX, oldY, oldZ, oldS)

            floatBuffer.put(head * coordsPerPoint, lx_local)
            floatBuffer.put(head * coordsPerPoint + 1, ly_local)
            floatBuffer.put(head * coordsPerPoint + 2, lz_local)
            floatBuffer.put(head * coordsPerPoint + 3, stability)
            head = (head + 1) % maxPoints
        } else {
            ensureCapacity(size + 1)
            floatBuffer.put(size * coordsPerPoint, lx_local)
            floatBuffer.put(size * coordsPerPoint + 1, ly_local)
            floatBuffer.put(size * coordsPerPoint + 2, lz_local)
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

    fun offsetPoints(dx: Float, dy: Float, dz: Float) {
        offsetX += dx
        offsetY += dy
        offsetZ += dz
        if (alienSeed != -1) {
            alienX += dx
            alienY += dy
            alienZ += dz
        }
    }

    @Synchronized
    fun draw(positionHandle: Int, offsetHandle: Int = -1) {
        syncVboIfNeeded() 
        if (offsetHandle != -1) {
            GLES30.glUniform3f(offsetHandle, offsetX, offsetY, offsetZ)
        }

        if (size > 0 || hasTempPoint) {
            val active = getBuffer()
            val hasHistory = historySize > 0
            val renderSize = size + (if (hasHistory) 1 else 0) + (if (hasTempPoint) 1 else 0)
            GLES30.glVertexAttribPointer(positionHandle, coordsPerPoint, GLES30.GL_FLOAT, false, 0, active)
            GLES30.glEnableVertexAttribArray(positionHandle)
            GLES30.glDrawArrays(GLES30.GL_LINE_STRIP, 0, renderSize)
        }

        if (vboId != -1 && historySize > 0) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
            GLES30.glVertexAttribPointer(positionHandle, coordsPerPoint, GLES30.GL_FLOAT, false, 0, 0)
            GLES30.glEnableVertexAttribArray(positionHandle)
            GLES30.glDrawArrays(GLES30.GL_LINE_STRIP, 0, historySize)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        }
    }

    @Synchronized
    fun drawRibbon(positionHandle: Int, stabilityHandle: Int, offsetHandle: Int = -1) {
        if (offsetHandle != -1) {
            GLES30.glUniform3f(offsetHandle, offsetX, offsetY, offsetZ)
        }
        val totalPoints = size + historySize + (if (hasTempPoint) 1 else 0)
        if (totalPoints < 2) return

        val strideFloats = 5 
        val strideBytes = strideFloats * 4
        val totalVertices = totalPoints * 2
        val neededSize = totalVertices * strideBytes

        if (ribbonVboId == -1) {
            val vbos = IntArray(1)
            GLES30.glGenBuffers(1, vbos, 0)
            ribbonVboId = vbos[0]
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ribbonVboId)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, Math.max(neededSize, 2000 * strideBytes), null, GLES30.GL_DYNAMIC_DRAW)
            ribbonVboCapacity = Math.max(neededSize, 2000 * strideBytes)
        } else if (neededSize > ribbonVboCapacity) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ribbonVboId)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, neededSize, null, GLES30.GL_DYNAMIC_DRAW)
            ribbonVboCapacity = neededSize
        }

        if (ribbonByteBuffer == null || ribbonByteBuffer!!.capacity() < neededSize) {
            ribbonByteBuffer = ByteBuffer.allocateDirect(neededSize).order(ByteOrder.nativeOrder())
            ribbonFloatBuffer = ribbonByteBuffer!!.asFloatBuffer()
        }

        val out = ribbonFloatBuffer!!
        out.clear()

        for (i in 0 until historySize) {
            val hOffset = i * coordsPerPoint
            val x = historyBuffer.get(hOffset)
            val y = historyBuffer.get(hOffset + 1)
            val z = historyBuffer.get(hOffset + 2)
            val s = historyBuffer.get(hOffset + 3)
            out.put(x).put(y).put(z).put(0f).put(s)
            out.put(x).put(y).put(z).put(1f).put(s)
        }

        for (i in 0 until size) {
            val idx = if (size >= maxPoints) (head + i) % maxPoints else i
            val aOffset = idx * coordsPerPoint
            val x = floatBuffer.get(aOffset)
            val y = floatBuffer.get(aOffset + 1)
            val z = floatBuffer.get(aOffset + 2)
            val s = floatBuffer.get(aOffset + 3)
            out.put(x).put(y).put(z).put(0f).put(s)
            out.put(x).put(y).put(z).put(1f).put(s)
        }

        if (hasTempPoint) {
            val tx_local = tempX - offsetX
            val ty_local = tempY - offsetY
            val tz_local = tempZ - offsetZ
            out.put(tx_local).put(ty_local).put(tz_local).put(0f).put(tempS)
            out.put(tx_local).put(ty_local).put(tz_local).put(1f).put(tempS)
        }

        out.flip()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ribbonVboId)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, neededSize, out)
        
        GLES30.glVertexAttribPointer(positionHandle, 4, GLES30.GL_FLOAT, false, strideBytes, 0)
        GLES30.glEnableVertexAttribArray(positionHandle)
        GLES30.glVertexAttribPointer(stabilityHandle, 1, GLES30.GL_FLOAT, false, strideBytes, 16)
        GLES30.glEnableVertexAttribArray(stabilityHandle)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, totalVertices)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun syncVboIfNeeded() {
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
            out.put(tempX - offsetX).put(tempY - offsetY).put(tempZ - offsetZ).put(tempS)
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
