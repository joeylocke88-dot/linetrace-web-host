package com.linetrace.app.render

import android.opengl.GLES31
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * LineCrawler: Specialized Medical-Grade Structural Scanner
 * Combines Gaussian Surfel splatting with vertical structural infill.
 */
class LineCrawler(private val context: android.content.Context) {
    private var program = 0
    private var mvpHandle = -1
    private var invVpHandle = -1
    private var zParamsHandle = -1
    private var timeHandle = -1
    private var thermalTempHandle = -1
    private var surfelCountHandle = -1
    private var worldMinHandle = -1
    private var stabilizedOriginHandle = -1
    private var pathPointCountHandle = -1
    private var cellSizeHandle = -1
    private var depthSamplerHandle = -1
    private var depthUvHandle = -1

    private val quadBuffer: FloatBuffer = ByteBuffer.allocateDirect(4 * 3 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 0f, 1f, -1f, 0f, -1f, 1f, 0f, 1f, 1f, 0f))
            flip()
        }

    fun init() {
        program = GLUtils.buildProgram(ShaderSource.LINE_CRAWLER_VERTEX_SHADER, ShaderSource.LINE_CRAWLER_FRAGMENT_SHADER)
        mvpHandle = GLES31.glGetUniformLocation(program, "uMvpMatrix")
        invVpHandle = GLES31.glGetUniformLocation(program, "uInvVpMatrix")
        zParamsHandle = GLES31.glGetUniformLocation(program, "uZParams")
        timeHandle = GLES31.glGetUniformLocation(program, "uTime")
        thermalTempHandle = GLES31.glGetUniformLocation(program, "uThermalTemp")
        surfelCountHandle = GLES31.glGetUniformLocation(program, "uSurfelCount")
        worldMinHandle = GLES31.glGetUniformLocation(program, "uWorldMin")
        stabilizedOriginHandle = GLES31.glGetUniformLocation(program, "uStabilizedOrigin")
        pathPointCountHandle = GLES31.glGetUniformLocation(program, "uPathPointCount")
        cellSizeHandle = GLES31.glGetUniformLocation(program, "uCellSize")
        depthSamplerHandle = GLES31.glGetUniformLocation(program, "uCameraDepth")
        depthUvHandle = GLES31.glGetUniformLocation(program, "uDepthUvMatrix")
    }

    fun draw(
        vpMatrix: FloatArray,
        projection: FloatArray,
        depthTextureId: Int,
        depthUvMatrix: FloatArray,
        surfelSSBO: Int,
        gridSSBO: Int,
        surfelCount: Int,
        spatialMin: FloatArray,
        stabilizedOrigin: FloatArray,
        pathSSBO: Int,
        pathPointCount: Int,
        time: Float,
        thermalTemp: Float
    ) {
        if (program == 0) return
        
        GLES31.glUseProgram(program)
        
        val invVp = FloatArray(16)
        Matrix.invertM(invVp, 0, vpMatrix, 0)
        
        GLES31.glUniformMatrix4fv(mvpHandle, 1, false, vpMatrix, 0)
        GLES31.glUniformMatrix4fv(invVpHandle, 1, false, invVp, 0)
        GLES31.glUniform2f(zParamsHandle, projection[10], projection[14])
        GLES31.glUniformMatrix3fv(depthUvHandle, 1, false, depthUvMatrix, 0)
        GLES31.glUniform1f(timeHandle, time)
        GLES31.glUniform1f(thermalTempHandle, thermalTemp)
        GLES31.glUniform1i(surfelCountHandle, surfelCount)
        GLES31.glUniform1i(pathPointCountHandle, pathPointCount)
        GLES31.glUniform3f(worldMinHandle, spatialMin[0], spatialMin[1], spatialMin[2])
        GLES31.glUniform3f(stabilizedOriginHandle, stabilizedOrigin[0], stabilizedOrigin[1], stabilizedOrigin[2])
        GLES31.glUniform1f(cellSizeHandle, 1.0f)

        GLES31.glActiveTexture(GLES31.GL_TEXTURE1)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, depthTextureId)
        GLES31.glUniform1i(depthSamplerHandle, 1)

        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, surfelSSBO)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, gridSSBO)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 2, pathSSBO)

        GLES31.glEnable(GLES31.GL_BLEND)
        GLES31.glBlendFunc(GLES31.GL_SRC_ALPHA, GLES31.GL_ONE) 

        val posAttrib = GLES31.glGetAttribLocation(program, "aPosition")
        GLES31.glVertexAttribPointer(posAttrib, 3, GLES31.GL_FLOAT, false, 0, quadBuffer)
        GLES31.glEnableVertexAttribArray(posAttrib)
        GLES31.glDrawArrays(GLES31.GL_TRIANGLE_STRIP, 0, 4)
        
        GLES31.glDisable(GLES31.GL_BLEND)
    }

    fun onDestroy() {
        if (program != 0) {
            GLES31.glDeleteProgram(program)
            program = 0
        }
    }
}
