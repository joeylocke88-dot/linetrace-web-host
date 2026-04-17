package com.linetrace.app.feature.mapping
import com.linetrace.app.core.Point

import android.content.Context
import android.opengl.GLES31
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

class GpuPoseSolver(private val context: Context) {
    private var programResidual = 0
    private var programTwist = 0
    private var programSolve = 0

    private val factorSSBO = IntArray(1)
    private val poseSSBO = IntArray(1)
    private val residualSSBO = IntArray(1)
    private val twistSSBO = IntArray(1)
    
    private val pcSSBO = IntArray(1)
    private val surfelSSBO = IntArray(2)
    private val mirrorSurfelSSBO = IntArray(1) // Persistent Mirror
    private val surfelCounts = IntArray(2)
    private var activeBufferIndex = 0

    private val MAX_NODES = 120
    private val MAX_FACTORS = 240
    private val MAX_SURFELS = 1000000 // Stress Test: Increased from 500k to 1M

    private var programSurfelFusion = 0
    private var programWarp = 0
    private var programCompress = 0
    private var programMorton = 0
    private var programBoundary = 0
    private var programSort = 0
    private var programGrid = 0
    private var extractProgram = 0
    
    private val mortonSSBO = IntArray(1)
    private val indicesSSBO = IntArray(1)
    private val gridSSBO = IntArray(1)
    private val mirrorGridSSBO = IntArray(1) // Persistent Mirror Grid
    private val boundarySSBO = IntArray(1)
    private val extractOutSSBO = IntArray(1)
    private val counterSSBO = IntArray(1)
    
    private var programHistogram = 0
    private var programScatter = 0

    private val histogramSSBO = IntArray(1)
    private val sortedSurfelSSBO = IntArray(1)
    
    private val keyIndexSSBO = IntArray(1)
    
    // Pre-allocated workspace to avoid OOM
    private val factorBuffer = ByteBuffer.allocateDirect(MAX_FACTORS * 80).order(ByteOrder.nativeOrder())
    private val poseBuffer = ByteBuffer.allocateDirect(MAX_NODES * 64).order(ByteOrder.nativeOrder())
    private val extractCounterBuffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
    private val deltaDownloadBuffer = ByteBuffer.allocateDirect(2000 * 64).order(ByteOrder.nativeOrder())

    private val warpSSBO = IntArray(1)
    private val MAX_WARP_POINTS = 128
    private val GRID_SIZE = 1024 * 1024 // 20-bit Hilbert space or Morton

    // Pre-allocated workspace to avoid GC pressure during high-frequency updates
    private val zeroBuffer = ByteBuffer.allocateDirect(GRID_SIZE * 4).order(ByteOrder.nativeOrder())
    private val histogramBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder())
    private val histogramCounts = IntArray(16)
    private val radixOffsets = IntArray(16)

    private var initialized = false

    fun init() {
        if (initialized) return

        programResidual = LinkProgram(loadShader(GLES31.GL_COMPUTE_SHADER, "shaders/residual.comp"))
        programTwist = LinkProgram(loadShader(GLES31.GL_COMPUTE_SHADER, "shaders/twist.comp"))
        programSolve = LinkProgram(loadShader(GLES31.GL_COMPUTE_SHADER, "shaders/solve.comp"))
        programSurfelFusion = LinkProgram(loadShader(GLES31.GL_COMPUTE_SHADER, "shaders/surfel_fusion.comp"))
        programWarp = LinkProgram(loadShader(GLES31.GL_COMPUTE_SHADER, "shaders/warp.comp"))
        programCompress = LinkProgram(loadShader(GLES31.GL_COMPUTE_SHADER, "shaders/compress.comp"))
        programMorton = LinkProgram(loadShader(GLES31.GL_COMPUTE_SHADER, "shaders/morton.comp"))
        programBoundary = LinkProgram(loadShader(GLES31.GL_COMPUTE_SHADER, "shaders/boundary.comp"))
        programSort = LinkProgram(loadShader(GLES31.GL_COMPUTE_SHADER, "shaders/radix_sort.comp"))
        programGrid = LinkProgram(loadShader(GLES31.GL_COMPUTE_SHADER, "shaders/grid.comp"))

        programHistogram = LinkProgram(loadShader(GLES31.GL_COMPUTE_SHADER, "shaders/histogram.comp"))
        programScatter = LinkProgram(loadShader(GLES31.GL_COMPUTE_SHADER, "shaders/scatter_sort.comp"))
        
        GLES31.glGenBuffers(1, factorSSBO, 0)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, factorSSBO[0])
        GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, MAX_FACTORS * 80, null, GLES31.GL_DYNAMIC_DRAW)

        GLES31.glGenBuffers(1, poseSSBO, 0)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, poseSSBO[0])
        GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, MAX_NODES * 64, null, GLES31.GL_DYNAMIC_DRAW)

        GLES31.glGenBuffers(1, residualSSBO, 0)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, residualSSBO[0])
        GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, MAX_FACTORS * 16, null, GLES31.GL_DYNAMIC_DRAW)

        GLES31.glGenBuffers(1, twistSSBO, 0)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, twistSSBO[0])
        GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, MAX_NODES * 16, null, GLES31.GL_DYNAMIC_DRAW)
        
        GLES31.glGenBuffers(1, pcSSBO, 0)

        GLES31.glGenBuffers(2, surfelSSBO, 0)
        for (i in 0..1) {
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, surfelSSBO[i])
            GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, MAX_SURFELS * 64, null, GLES31.GL_DYNAMIC_DRAW)
        }

        GLES31.glGenBuffers(1, mirrorSurfelSSBO, 0)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, mirrorSurfelSSBO[0])
        GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, MAX_SURFELS * 64, null, GLES31.GL_DYNAMIC_COPY)

        GLES31.glGenBuffers(1, warpSSBO, 0)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, warpSSBO[0])
        GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, MAX_WARP_POINTS * 32, null, GLES31.GL_DYNAMIC_DRAW)
        
        GLES31.glGenBuffers(1, mortonSSBO, 0)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, mortonSSBO[0])
        GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, MAX_SURFELS * 4, null, GLES31.GL_DYNAMIC_DRAW)

        GLES31.glGenBuffers(1, indicesSSBO, 0)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, indicesSSBO[0])
        GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, MAX_SURFELS * 4, null, GLES31.GL_DYNAMIC_DRAW)

        GLES31.glGenBuffers(1, keyIndexSSBO, 0)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, keyIndexSSBO[0])
        GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, MAX_SURFELS * 8, null, GLES31.GL_DYNAMIC_DRAW)

        GLES31.glGenBuffers(1, gridSSBO, 0)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, gridSSBO[0])
        GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, GRID_SIZE * 4, null, GLES31.GL_DYNAMIC_DRAW)

        GLES31.glGenBuffers(1, mirrorGridSSBO, 0)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, mirrorGridSSBO[0])
        GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, GRID_SIZE * 4, null, GLES31.GL_DYNAMIC_COPY)

        GLES31.glGenBuffers(1, boundarySSBO, 0)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, boundarySSBO[0])
        GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, 4 + MAX_SURFELS * 4, null, GLES31.GL_DYNAMIC_DRAW)

        GLES31.glGenBuffers(1, histogramSSBO, 0)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, histogramSSBO[0])
        GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, GRID_SIZE * 4, null, GLES31.GL_DYNAMIC_DRAW)

        GLES31.glGenBuffers(1, sortedSurfelSSBO, 0)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, sortedSurfelSSBO[0])
        GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, MAX_SURFELS * 64, null, GLES31.GL_DYNAMIC_DRAW)

        initExtraction(MAX_SURFELS)

        initialized = true
    }

    fun initExtraction(maxSurfels: Int) {
        extractProgram = LinkProgram(loadShader(GLES31.GL_COMPUTE_SHADER, "shaders/extract_region.comp"))

        GLES31.glGenBuffers(1, extractOutSSBO, 0)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, extractOutSSBO[0])
        GLES31.glBufferData(
            GLES31.GL_SHADER_STORAGE_BUFFER,
            maxSurfels * 64,
            null,
            GLES31.GL_DYNAMIC_COPY
        )

        GLES31.glGenBuffers(1, counterSSBO, 0)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, counterSSBO[0])
        GLES31.glBufferData(
            GLES31.GL_SHADER_STORAGE_BUFFER,
            4,
            null,
            GLES31.GL_DYNAMIC_COPY
        )
    }

    fun uploadSurfels(data: ByteBuffer) {
        val count = data.remaining() / 64
        if (count == 0) return
        
        val spaceRemaining = MAX_SURFELS - surfelCounts[activeBufferIndex]
        val uploadCount = count.coerceAtMost(spaceRemaining)
        if (uploadCount == 0) return

        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, surfelSSBO[activeBufferIndex])
        GLES31.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, surfelCounts[activeBufferIndex] * 64, uploadCount * 64, data)
        
        surfelCounts[activeBufferIndex] += uploadCount
    }

    private var currentSurfelCount = 0

    fun applyWarp(controlPoints: FloatArray) {
        val cpCount = controlPoints.size / 8
        if (cpCount == 0 || surfelCounts[activeBufferIndex] == 0) return

        val cpBuffer = ByteBuffer.allocateDirect(controlPoints.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        cpBuffer.put(controlPoints).flip()

        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, warpSSBO[0])
        GLES31.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, controlPoints.size * 4, cpBuffer)

        GLES31.glUseProgram(programWarp)
        GLES31.glUniform1i(GLES31.glGetUniformLocation(programWarp, "uSurfelCount"), surfelCounts[activeBufferIndex])
        GLES31.glUniform1i(GLES31.glGetUniformLocation(programWarp, "uControlPointCount"), cpCount)

        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, surfelSSBO[activeBufferIndex])
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, warpSSBO[0])

        GLES31.glDispatchCompute((surfelCounts[activeBufferIndex] + 127) / 128, 1, 1)
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
    }

    fun downloadSurfelsRaw(count: Int): ByteBuffer? {
        if (count == 0) return null
        
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, surfelSSBO[activeBufferIndex])
        val ptr = GLES31.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, count * 64, GLES31.GL_MAP_READ_BIT)
        if (ptr != null) {
            val gpuData = (ptr as ByteBuffer).order(ByteOrder.nativeOrder())
            val copy = ByteBuffer.allocateDirect(count * 64).order(ByteOrder.nativeOrder())
            copy.put(gpuData)
            copy.flip()
            GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
            return copy
        }
        return null
    }

    fun downloadSurfelDelta(offset: Int, count: Int): ByteBuffer? {
        if (count <= 0) return null
        val byteOffset = offset * 64
        val byteCount = count * 64
        
        if (byteCount > deltaDownloadBuffer.capacity()) {
            Log.w("GpuPoseSolver", "Delta too large for reusable buffer: $byteCount bytes")
            return null
        }

        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, surfelSSBO[activeBufferIndex])
        val ptr = GLES31.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, byteOffset, byteCount, GLES31.GL_MAP_READ_BIT)
        if (ptr != null) {
            deltaDownloadBuffer.clear()
            deltaDownloadBuffer.put(ptr as ByteBuffer)
            deltaDownloadBuffer.flip()
            GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
            return deltaDownloadBuffer
        }
        return null
    }

    fun clearSurfels() {
        surfelCounts[activeBufferIndex] = 0
    }

    fun swapBuffers() {
        activeBufferIndex = 1 - activeBufferIndex
        surfelCounts[activeBufferIndex] = 0
    }

    fun getActiveBufferIndex(): Int = activeBufferIndex

    fun getSurfelCount(index: Int): Int = surfelCounts[index]

    fun getSurfelSSBO(index: Int): Int = surfelSSBO[index]
    fun getMirrorSurfelSSBO(): Int = mirrorSurfelSSBO[0]

    fun getGridSSBO(): Int = gridSSBO[0]
    fun getMirrorGridSSBO(): Int = mirrorGridSSBO[0]

    fun updateMirrorShield() {
        val count = surfelCounts[activeBufferIndex]
        if (count <= 0) return
        
        // Copy current state to Mirror Shield
        GLES31.glBindBuffer(GLES31.GL_COPY_READ_BUFFER, surfelSSBO[activeBufferIndex])
        GLES31.glBindBuffer(GLES31.GL_COPY_WRITE_BUFFER, mirrorSurfelSSBO[0])
        GLES31.glCopyBufferSubData(GLES31.GL_COPY_READ_BUFFER, GLES31.GL_COPY_WRITE_BUFFER, 0, 0, count * 64)

        GLES31.glBindBuffer(GLES31.GL_COPY_READ_BUFFER, gridSSBO[0])
        GLES31.glBindBuffer(GLES31.GL_COPY_WRITE_BUFFER, mirrorGridSSBO[0])
        GLES31.glCopyBufferSubData(GLES31.GL_COPY_READ_BUFFER, GLES31.GL_COPY_WRITE_BUFFER, 0, 0, GRID_SIZE * 4)
    }

    fun fuseSurfels(pcBuffer: java.nio.FloatBuffer, cameraPose: FloatArray, timestamp: Long) {
        val pointCount = pcBuffer.remaining() / 4
        if (pointCount == 0) return

        uploadPointCloud(pcBuffer)

        GLES31.glUseProgram(programSurfelFusion)
        
        GLES31.glUniformMatrix4fv(GLES31.glGetUniformLocation(programSurfelFusion, "uCameraPose"), 1, false, cameraPose, 0)
        GLES31.glUniform1i(GLES31.glGetUniformLocation(programSurfelFusion, "uPointCount"), pointCount)
        GLES31.glUniform1i(GLES31.glGetUniformLocation(programSurfelFusion, "uMaxSurfels"), MAX_SURFELS)
        GLES31.glUniform1i(GLES31.glGetUniformLocation(programSurfelFusion, "uCurrentSurfelCount"), surfelCounts[activeBufferIndex])
        
        // Pass timestamp as two uints
        val tsLow = (timestamp and 0xFFFFFFFFL).toInt()
        val tsHigh = (timestamp shr 32).toInt()
        GLES31.glUniform2ui(GLES31.glGetUniformLocation(programSurfelFusion, "uTimestamp"), tsLow, tsHigh)

        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, pcSSBO[0])
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, surfelSSBO[activeBufferIndex])

        GLES31.glDispatchCompute((pointCount + 127) / 128, 1, 1)
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)

        surfelCounts[activeBufferIndex] = (surfelCounts[activeBufferIndex] + pointCount).coerceAtMost(MAX_SURFELS)
    }

    private var pcTextureId = -1

    fun uploadPointCloud(buffer: java.nio.FloatBuffer) {
        val size = buffer.remaining() * 4
        if (size == 0) return

        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, pcSSBO[0])
        // Efficient sub-data update for streaming point clouds
        GLES31.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, size, buffer)
    }

    fun solve(nodes: List<PoseNode>, edges: List<PoseEdge>): FloatArray {
        if (edges.isEmpty()) return FloatArray(3)

        // 1. Flatten Data using pre-allocated buffers
        factorBuffer.clear()
        for (edge in edges) {
            val fromIdx = nodes.indexOfFirst { it.id == edge.from }
            val toIdx = nodes.indexOfFirst { it.id == edge.to }
            if (fromIdx == -1 || toIdx == -1) continue
            
            factorBuffer.putInt(fromIdx)
            factorBuffer.putInt(toIdx)
            factorBuffer.putInt(0); factorBuffer.putInt(0) 
            for (v in edge.transform) factorBuffer.putFloat(v)
        }
        factorBuffer.flip()

        poseBuffer.clear()
        for (node in nodes) {
            for (v in node.pose) poseBuffer.putFloat(v)
        }
        poseBuffer.flip()

        // 2. Upload to GPU
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, factorSSBO[0])
        GLES31.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, factorBuffer.limit(), factorBuffer)

        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, poseSSBO[0])
        GLES31.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, poseBuffer.limit(), poseBuffer)

        // 3. Dispatch Pipeline
        // PASS 1: Residuals
        GLES31.glUseProgram(programResidual)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, factorSSBO[0])
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, poseSSBO[0])
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 2, residualSSBO[0])
        GLES31.glDispatchCompute((edges.size + 63) / 64, 1, 1)
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)

        // PASS 2: Twist Computation
        GLES31.glUseProgram(programTwist)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, residualSSBO[0])
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, twistSSBO[0])
        GLES31.glDispatchCompute((edges.size + 31) / 32, 1, 1)
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)

        // PASS 3: SE(3) Apply Update
        GLES31.glUseProgram(programSolve)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, poseSSBO[0])
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, twistSSBO[0])
        GLES31.glDispatchCompute((nodes.size + 31) / 32, 1, 1)
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)

        // 4. Map back to CPU
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, poseSSBO[0])
        val ptr = GLES31.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, nodes.size * 64, GLES31.GL_MAP_READ_BIT)
        if (ptr != null) {
            val gpuData = (ptr as ByteBuffer).order(ByteOrder.nativeOrder())
            for (node in nodes) {
                for (i in 0 until 16) {
                    node.pose[i] = gpuData.float
                }
            }
            GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
        }
        
        return FloatArray(3)
    }

    fun compressWorld(confidenceThreshold: Float, radiusThreshold: Float) {
        if (surfelCounts[activeBufferIndex] == 0) return

        GLES31.glUseProgram(programCompress)
        GLES31.glUniform1i(GLES31.glGetUniformLocation(programCompress, "uSurfelCount"), surfelCounts[activeBufferIndex])
        GLES31.glUniform1f(GLES31.glGetUniformLocation(programCompress, "uConfidenceThreshold"), confidenceThreshold)
        GLES31.glUniform1f(GLES31.glGetUniformLocation(programCompress, "uRadiusThreshold"), radiusThreshold)

        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, surfelSSBO[activeBufferIndex])

        GLES31.glDispatchCompute((surfelCounts[activeBufferIndex] + 127) / 128, 1, 1)
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
        
        // After compression, we should ideally compact the buffer, 
        // but for now we'll just let the next fusion reuse slots or keep count.
        // In a more advanced version, we'd use an atomic counter to get the new count.
    }

    fun extractRegion(center: FloatArray, radius: Float, bufferIndex: Int, extractInside: Boolean = true): Pair<Int, ByteBuffer?> {
        GLES31.glUseProgram(extractProgram)

        // 1. Reset Counter
        extractCounterBuffer.clear()
        extractCounterBuffer.putInt(0).position(0)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, counterSSBO[0])
        GLES31.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, 4, extractCounterBuffer)

        // 2. Bind buffers
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, surfelSSBO[bufferIndex])
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, extractOutSSBO[0])
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 2, counterSSBO[0])

        // 3. Set uniforms
        val surfelCount = surfelCounts[bufferIndex]
        GLES31.glUniform3f(GLES31.glGetUniformLocation(extractProgram, "regionCenter"), center[0], center[1], center[2])
        GLES31.glUniform1f(GLES31.glGetUniformLocation(extractProgram, "radius"), radius)
        GLES31.glUniform1ui(GLES31.glGetUniformLocation(extractProgram, "maxInCount"), surfelCount)
        GLES31.glUniform1i(GLES31.glGetUniformLocation(extractProgram, "extractInside"), if (extractInside) 1 else 0)

        // 4. Dispatch Compute
        val groups = (surfelCount + 127) / 128
        if (groups > 0) {
            GLES31.glDispatchCompute(groups, 1, 1)
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
        }

        // 5. Read counter
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, counterSSBO[0])
        val countPtr = GLES31.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, 4, GLES31.GL_MAP_READ_BIT)
        val count = if (countPtr != null) {
            val c = (countPtr as ByteBuffer).order(ByteOrder.nativeOrder()).getInt(0)
            GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
            c
        } else 0

        if (count <= 0) return Pair(0, null)

        // 6. Read extracted surfels (Hardened size calculation for GLES31 constraints)
        val outSizeLong = count.toLong() * 64L
        if (outSizeLong > Int.MAX_VALUE) {
            Log.e("GpuPoseSolver", "Extraction size exceeds Int limit: $outSizeLong")
            return Pair(0, null)
        }
        val outSize = outSizeLong.toInt()

        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, extractOutSSBO[0])
        val dataPtr = GLES31.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, outSize, GLES31.GL_MAP_READ_BIT)
        val outBuffer = if (dataPtr != null) {
            val copy = ByteBuffer.allocateDirect(outSize).order(ByteOrder.nativeOrder())
            copy.put(dataPtr as ByteBuffer)
            copy.flip()
            GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
            copy
        } else null

        return Pair(count, outBuffer)
    }

    fun buildSpatialIndex(bufferIndex: Int, worldMin: FloatArray, cellSize: Float): Int {
        val count = surfelCounts[bufferIndex]
        if (count <= 0) return 0
        val startTime = System.nanoTime()

        // 1. Generate Morton Keys & Initialize Indices
        GLES31.glUseProgram(programMorton)
        GLES31.glUniform1f(GLES31.glGetUniformLocation(programMorton, "cellSize"), cellSize)
        GLES31.glUniform3f(GLES31.glGetUniformLocation(programMorton, "worldMin"), worldMin[0], worldMin[1], worldMin[2])
        GLES31.glUniform1ui(GLES31.glGetUniformLocation(programMorton, "uCount"), count)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, surfelSSBO[bufferIndex])
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, mortonSSBO[0])
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 2, indicesSSBO[0])
        GLES31.glDispatchCompute((count + 127) / 128, 1, 1)
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)

        // 2. Multi-pass Radix Sort
        GLES31.glUseProgram(programSort)
        val bitOffsetLoc = GLES31.glGetUniformLocation(programSort, "uBitOffset")
        val passLoc = GLES31.glGetUniformLocation(programSort, "uPass")
        val countLoc = GLES31.glGetUniformLocation(programSort, "uCount")
        GLES31.glUniform1ui(countLoc, count)

        var srcKeys = mortonSSBO[0]
        var srcIdx = indicesSSBO[0]
        var dstKeys = keyIndexSSBO[0] // Reusing this as temporary for keys
        var dstIdx = extractOutSSBO[0] // Reusing this as temporary for indices

        for (bit in 0 until 20 step 4) { // Sort 20 bits (enough for 1024^3 grid)
            // Reset and compute histogram
            histogramCounts.fill(0)
            histogramBuffer.clear()
            histogramBuffer.asIntBuffer().put(histogramCounts).position(0)
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, histogramSSBO[0])
            GLES31.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, 16 * 4, histogramBuffer)

            GLES31.glUniform1ui(bitOffsetLoc, bit)
            GLES31.glUniform1ui(passLoc, 0) // Histogram
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, srcKeys)
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 4, histogramSSBO[0])
            GLES31.glDispatchCompute((count + 127) / 128, 1, 1)
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)

            // Prefix sum on CPU
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, histogramSSBO[0])
            val ptr = GLES31.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, 16 * 4, GLES31.GL_MAP_READ_BIT)
            ptr?.let {
                (it as ByteBuffer).order(ByteOrder.nativeOrder()).asIntBuffer().get(histogramCounts)
                GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
            }
            
            var sum = 0
            for (i in 0 until 16) {
                radixOffsets[i] = sum
                sum += histogramCounts[i]
            }
            histogramBuffer.clear()
            histogramBuffer.asIntBuffer().put(radixOffsets).position(0)
            GLES31.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, 16 * 4, histogramBuffer)

            // Scatter
            GLES31.glUniform1ui(passLoc, 1) // Scatter
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, srcKeys)
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, srcIdx)
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 2, dstKeys)
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 3, dstIdx)
            GLES31.glDispatchCompute((count + 127) / 128, 1, 1)
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)

            // Ping-pong
            val tmpK = srcKeys; srcKeys = dstKeys; dstKeys = tmpK
            val tmpI = srcIdx; srcIdx = dstIdx; dstIdx = tmpI
        }

        // 3. Reorder Surfels using sorted indices
        GLES31.glUseProgram(programScatter)
        GLES31.glUniform1ui(GLES31.glGetUniformLocation(programScatter, "uCount"), count)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, srcIdx)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, surfelSSBO[bufferIndex])
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 2, sortedSurfelSSBO[0])
        GLES31.glDispatchCompute((count + 127) / 128, 1, 1)
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)

        // Copy back to main buffer (Hardened size calculation)
        val copySizeLong = count.toLong() * 64L
        if (copySizeLong <= Int.MAX_VALUE) {
            GLES31.glBindBuffer(GLES31.GL_COPY_READ_BUFFER, sortedSurfelSSBO[0])
            GLES31.glBindBuffer(GLES31.GL_COPY_WRITE_BUFFER, surfelSSBO[bufferIndex])
            GLES31.glCopyBufferSubData(GLES31.GL_COPY_READ_BUFFER, GLES31.GL_COPY_WRITE_BUFFER, 0, 0, copySizeLong.toInt())
        }

        // 4. Build Grid Offsets
        GLES31.glUseProgram(programGrid)
        GLES31.glUniform1ui(GLES31.glGetUniformLocation(programGrid, "uCount"), count)
        // Reset grid (important: use GRID_SIZE)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, gridSSBO[0])
        GLES31.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, GRID_SIZE * 4, zeroBuffer)

        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, srcKeys)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, gridSSBO[0])
        GLES31.glDispatchCompute((count + 127) / 128, 1, 1)
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)

        return count
    }

    private fun loadShader(type: Int, path: String): Int {
        val source = context.assets.open(path).bufferedReader().use { it.readText() }
        val shader = GLES31.glCreateShader(type)
        GLES31.glShaderSource(shader, source)
        GLES31.glCompileShader(shader)
        val compiled = IntBuffer.allocate(1)
        GLES31.glGetShaderiv(shader, GLES31.GL_COMPILE_STATUS, compiled)
        if (compiled[0] == 0) {
            Log.e("GpuPoseSolver", "Could not compile shader $type: ${GLES31.glGetShaderInfoLog(shader)}")
            GLES31.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun LinkProgram(computeShader: Int): Int {
        val program = GLES31.glCreateProgram()
        GLES31.glAttachShader(program, computeShader)
        GLES31.glLinkProgram(program)
        val linked = IntBuffer.allocate(1)
        GLES31.glGetProgramiv(program, GLES31.GL_LINK_STATUS, linked)
        if (linked[0] == 0) {
            Log.e("GpuPoseSolver", "Could not link program: ${GLES31.glGetProgramInfoLog(program)}")
            return 0
        }
        return program
    }

    interface WorldCallback {
        fun onChunkCompressed(chunk: WorldChunk)
    }

    fun processWorld(camPos: FloatArray, streamer: WorldStreamer, callback: WorldCallback) {
        // Implement world processing logic
        // Extract surfels within a radius of the camera from the GPU surfel buffer
        val radius = 8.0f
        val (count, buffer) = extractRegion(camPos, radius, activeBufferIndex, true)
        if (count > 0 && buffer != null) {
            // Offload the heavy merging and spatial binning to the streamer's background thread
            streamer.addSurfelsAsync(buffer) { chunk ->
                // This callback is executed when a chunk is updated/merged
                callback.onChunkCompressed(chunk)
            }
        }
    }

    fun onDestroy() {
        val ssbos = intArrayOf(
            factorSSBO[0], poseSSBO[0], residualSSBO[0], twistSSBO[0],
            pcSSBO[0], surfelSSBO[0], surfelSSBO[1], mortonSSBO[0],
            indicesSSBO[0], gridSSBO[0], boundarySSBO[0], extractOutSSBO[0],
            counterSSBO[0], histogramSSBO[0], sortedSurfelSSBO[0], keyIndexSSBO[0],
            warpSSBO[0]
        )
        GLES31.glDeleteBuffers(ssbos.size, ssbos, 0)
        
        val programs = intArrayOf(
            programResidual, programTwist, programSolve, programSurfelFusion,
            programWarp, programCompress, programMorton, programBoundary,
            programSort, programGrid, extractProgram, programHistogram, programScatter
        )
        programs.forEach { if (it != 0) GLES31.glDeleteProgram(it) }
        
        if (pcTextureId != 0) {
            GLES31.glDeleteTextures(1, intArrayOf(pcTextureId), 0)
        }
    }
}
