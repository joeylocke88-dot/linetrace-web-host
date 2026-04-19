package com.linetrace.app.render

import android.opengl.GLES30
import com.linetrace.app.core.Point
import com.linetrace.app.core.PointType
import com.linetrace.app.feature.telemetry.PathBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PathRenderer {
    private var program = 0
    private var positionHandle = -1
    private var mvpHandle = -1
    private var offsetHandle = -1
    private var colorHandle = -1
    private var depthBiasHandle = -1
    private var screenSizeHandle = -1
    private var timeHandle = -1
    private var cameraDepthHandle = -1
    private var depthUvHandle = -1

    private var ribbonProgram = 0
    private var ribbonPositionHandle = -1
    private var ribbonStabilityHandle = -1
    private var ribbonMvpHandle = -1
    private var ribbonOffsetHandle = -1
    private var ribbonColorHandle = -1
    private var ribbonDepthBiasHandle = -1
    private var ribbonCamDepthHandle = -1
    private var ribbonScreenSizeHandle = -1
    private var ribbonWallHeightHandle = -1
    private var ribbonTimeHandle = -1
    private var ribbonThermalTempHandle = -1
    private var ribbonDepthUvHandle = -1

    fun init() {
        program = GLUtils.buildProgram(ShaderSource.VERTEX_SHADER, ShaderSource.FRAGMENT_SHADER)
        positionHandle = GLES30.glGetAttribLocation(program, "aPosition")
        mvpHandle = GLES30.glGetUniformLocation(program, "uMvpMatrix")
        offsetHandle = GLES30.glGetUniformLocation(program, "uOffset")
        colorHandle = GLES30.glGetUniformLocation(program, "uColor")
        depthBiasHandle = GLES30.glGetUniformLocation(program, "uDepthBias")
        screenSizeHandle = GLES30.glGetUniformLocation(program, "uScreenSize")
        timeHandle = GLES30.glGetUniformLocation(program, "uTime")
        cameraDepthHandle = GLES30.glGetUniformLocation(program, "uCameraDepth")
        depthUvHandle = GLES30.glGetUniformLocation(program, "uDepthUvMatrix")

        ribbonProgram = GLUtils.buildProgram(ShaderSource.RIBBON_VERTEX_SHADER, ShaderSource.RIBBON_FRAGMENT_SHADER)
        ribbonPositionHandle = GLES30.glGetAttribLocation(ribbonProgram, "aPosition")
        ribbonStabilityHandle = GLES30.glGetAttribLocation(ribbonProgram, "aStability")
        ribbonMvpHandle = GLES30.glGetUniformLocation(ribbonProgram, "uMvpMatrix")
        ribbonOffsetHandle = GLES30.glGetUniformLocation(ribbonProgram, "uOffset")
        ribbonColorHandle = GLES30.glGetUniformLocation(ribbonProgram, "uColor")
        ribbonDepthBiasHandle = GLES30.glGetUniformLocation(ribbonProgram, "uDepthBias")
        ribbonCamDepthHandle = GLES30.glGetUniformLocation(ribbonProgram, "uCameraDepth")
        ribbonScreenSizeHandle = GLES30.glGetUniformLocation(ribbonProgram, "uScreenSize")
        ribbonWallHeightHandle = GLES30.glGetUniformLocation(ribbonProgram, "uWallHeight")
        ribbonTimeHandle = GLES30.glGetUniformLocation(ribbonProgram, "uTime")
        ribbonThermalTempHandle = GLES30.glGetUniformLocation(ribbonProgram, "uThermalTemp")
        ribbonDepthUvHandle = GLES30.glGetUniformLocation(ribbonProgram, "uDepthUvMatrix")
    }

    fun drawPaths(
        vpMatrix: FloatArray,
        depthUvMatrix: FloatArray,
        cameraDepthTextureId: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        currentTime: Float,
        livePath: PathBuffer,
        livePathChunks: Map<Long, PathBuffer>,
        ghostPathChunks: Map<Long, PathBuffer>,
        camPos: FloatArray,
        wallHeight: Float,
        currentThermalTemp: Float
    ) {
        // 1. Path Render Pass (Lines)
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(mvpHandle, 1, false, vpMatrix, 0)
        GLES30.glUniformMatrix3fv(depthUvHandle, 1, false, depthUvMatrix, 0)
        GLES30.glUniform2f(screenSizeHandle, viewportWidth.toFloat(), viewportHeight.toFloat())
        GLES30.glUniform1f(timeHandle, currentTime)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cameraDepthTextureId)
        GLES30.glUniform1i(cameraDepthHandle, 1)

        // Ghost Path
        GLES30.glLineWidth(2.0f)
        GLES30.glUniform4f(colorHandle, 0.0f, 1.0f, 0.5f, 0.6f)
        GLES30.glUniform1f(depthBiasHandle, -0.001f)
        GLES30.glUniform3f(offsetHandle, 0f, 0f, 0f) 
        drawChunkedPathInternal(ghostPathChunks, camPos, positionHandle, radiusMeters = 20.0f)

        // Live Path
        GLES30.glLineWidth(3.0f) 
        GLES30.glUniform4f(colorHandle, 0.4f, 0.8f, 1.0f, 1.0f)
        GLES30.glUniform1f(depthBiasHandle, -0.002f)
        // Standard chunk drawing uses world space
        GLES30.glUniform3f(offsetHandle, 0f, 0f, 0f)
        drawChunkedPathInternal(livePathChunks, camPos, positionHandle, radiusMeters = 15.0f)
        // Live path uses Epoch Offset
        livePath.draw(positionHandle, offsetHandle)

        // 2. Wall Render Pass (Ribbons)
        GLES30.glUseProgram(ribbonProgram)
        GLES30.glUniformMatrix4fv(ribbonMvpHandle, 1, false, vpMatrix, 0)
        GLES30.glUniformMatrix3fv(ribbonDepthUvHandle, 1, false, depthUvMatrix, 0)
        GLES30.glUniform2f(ribbonScreenSizeHandle, viewportWidth.toFloat(), viewportHeight.toFloat())
        GLES30.glUniform1f(ribbonWallHeightHandle, wallHeight)
        GLES30.glUniform1f(ribbonDepthBiasHandle, -0.001f)
        GLES30.glUniform1f(ribbonTimeHandle, currentTime)
        GLES30.glUniform1f(ribbonThermalTempHandle, currentThermalTemp)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cameraDepthTextureId)
        GLES30.glUniform1i(ribbonCamDepthHandle, 1)

        GLES30.glUniform4f(ribbonColorHandle, 0.0f, 1.0f, 0.5f, 0.3f)
        GLES30.glUniform3f(ribbonOffsetHandle, 0f, 0f, 0f)
        drawChunkedPathInternal(ghostPathChunks, camPos, ribbonPositionHandle, ribbonStabilityHandle, true, 20.0f)

        GLES30.glUniform4f(ribbonColorHandle, 0.4f, 0.8f, 1.0f, 0.6f)
        GLES30.glUniform3f(ribbonOffsetHandle, 0f, 0f, 0f)
        drawChunkedPathInternal(livePathChunks, camPos, ribbonPositionHandle, ribbonStabilityHandle, true, 15.0f)
        livePath.drawRibbon(ribbonPositionHandle, ribbonStabilityHandle, ribbonOffsetHandle)
    }

    private fun drawChunkedPathInternal(
        chunksMap: Map<Long, PathBuffer>,
        camPos: FloatArray,
        posHandle: Int,
        stabHandle: Int = -1,
        isRibbon: Boolean = false,
        radiusMeters: Float = 10.0f
    ) {
        val cx = (camPos[0] / 2.0f).toInt()
        val cy = (camPos[1] / 2.0f).toInt()
        val cz = (camPos[2] / 2.0f).toInt()
        val r = (radiusMeters / 2.0f).toInt().coerceAtLeast(1)

        synchronized(chunksMap) {
            for (dx in -r..r) {
                for (dy in -r..r) {
                    for (dz in -r..r) {
                        val key = ((cx + dx).toLong() shl 42) or (((cy + dy).toLong() and 0x1FFFFF) shl 21) or ((cz + dz).toLong() and 0x1FFFFF)
                        chunksMap[key]?.let { chunk ->
                            if (isRibbon) {
                                if (stabHandle != -1) chunk.drawRibbon(posHandle, stabHandle, -1) // Chunks are pre-shifted or in world space
                            } else {
                                chunk.draw(posHandle, -1)
                            }
                        }
                    }
                }
            }
        }
    }

    fun drawPois(
        vpMatrix: FloatArray,
        camPos: FloatArray,
        poiChunks: Map<Long, List<Point>>
    ) {
        val cx = (camPos[0] / 2.0f).toInt()
        val cy = (camPos[1] / 2.0f).toInt()
        val cz = (camPos[2] / 2.0f).toInt()
        val r = 3 // 6m radius for POIs

        val nearbyPois = mutableListOf<Point>()
        synchronized(poiChunks) {
            for (dx in -r..r) {
                for (dy in -r..r) {
                    for (dz in -r..r) {
                        val key = ((cx + dx).toLong() shl 42) or (((cy + dy).toLong() and 0x1FFFFF) shl 21) or ((cz + dz).toLong() and 0x1FFFFF)
                        poiChunks[key]?.let { nearbyPois.addAll(it) }
                    }
                }
            }
        }

        if (nearbyPois.isEmpty()) return
        
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(mvpHandle, 1, false, vpMatrix, 0)
        GLES30.glUniform4f(colorHandle, 1.0f, 0.2f, 0.2f, 1.0f)
        GLES30.glUniform3f(offsetHandle, 0f, 0f, 0f)
        val poiBuffer = ByteBuffer.allocateDirect(nearbyPois.size * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (poi in nearbyPois) {
            poiBuffer.put(poi.x); poiBuffer.put(poi.y); poiBuffer.put(poi.z)
        }
        poiBuffer.flip()
        GLES30.glVertexAttribPointer(positionHandle, 3, GLES30.GL_FLOAT, false, 0, poiBuffer)
        GLES30.glEnableVertexAttribArray(positionHandle)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, nearbyPois.size)
    }

    fun onDestroy() {
        if (program != 0) {
            GLES30.glDeleteProgram(program)
            program = 0
        }
        if (ribbonProgram != 0) {
            GLES30.glDeleteProgram(ribbonProgram)
            ribbonProgram = 0
        }
    }
}
