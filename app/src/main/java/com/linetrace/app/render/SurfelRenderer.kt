package com.linetrace.app.render

import android.opengl.GLES30
import android.opengl.GLES31
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class SurfelRenderer {
    private var program = 0
    private var positionHandle = -1
    private var mvpHandle = -1
    private var camDepthHandle = -1
    private var screenSizeHandle = -1
    private var invVpHandle = -1
    private var zParamsHandle = -1
    private var stalledHandle = -1
    private var timeHandle = -1
    private var worldMinHandle = -1
    private var cellSizeHandle = -1
    private var surfelCountHandle = -1
    private var isHypervisorHandle = -1

    private val quadBuffer: FloatBuffer = ByteBuffer.allocateDirect(4 * 3 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 0f, 1f, -1f, 0f, -1f, 1f, 0f, 1f, 1f, 0f))
            flip()
        }

    fun init() {
        program = GLUtils.buildProgram(ShaderSource.DIAGNOSTIC_VERTEX_SHADER, ShaderSource.DIAGNOSTIC_FRAGMENT_SHADER)
        positionHandle = GLES30.glGetAttribLocation(program, "aPosition")
        mvpHandle = GLES30.glGetUniformLocation(program, "uMvpMatrix")
        camDepthHandle = GLES30.glGetUniformLocation(program, "uCameraDepth")
        screenSizeHandle = GLES30.glGetUniformLocation(program, "uScreenSize")
        invVpHandle = GLES30.glGetUniformLocation(program, "uInvVpMatrix")
        zParamsHandle = GLES30.glGetUniformLocation(program, "uZParams")
        stalledHandle = GLES30.glGetUniformLocation(program, "uStalled")
        timeHandle = GLES30.glGetUniformLocation(program, "uTime")
        worldMinHandle = GLES30.glGetUniformLocation(program, "uWorldMin")
        cellSizeHandle = GLES30.glGetUniformLocation(program, "uCellSize")
        surfelCountHandle = GLES30.glGetUniformLocation(program, "uSurfelCount")
        isHypervisorHandle = GLES30.glGetUniformLocation(program, "uIsHypervisor")
    }

    fun draw(
        vpMatrix: FloatArray,
        projection: FloatArray,
        cameraDepthTextureId: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        currentTime: Float,
        isStalled: Boolean,
        isHypervisor: Boolean,
        surfelSSBO: Int,
        gridSSBO: Int,
        surfelCount: Int,
        origin: FloatArray
    ) {
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(mvpHandle, 1, false, vpMatrix, 0)
        GLES30.glUniform2f(screenSizeHandle, viewportWidth.toFloat(), viewportHeight.toFloat())
        GLES30.glUniform1f(timeHandle, currentTime)
        GLES30.glUniform1i(stalledHandle, if (isStalled) 1 else 0)
        
        val invVp = FloatArray(16)
        if (Matrix.invertM(invVp, 0, vpMatrix, 0)) {
            GLES30.glUniformMatrix4fv(invVpHandle, 1, false, invVp, 0)
        }
        GLES30.glUniform2f(zParamsHandle, projection[10], projection[14])
        GLES30.glUniform3f(worldMinHandle, origin[0] - 50f, origin[1] - 50f, origin[2] - 50f)
        GLES30.glUniform1f(cellSizeHandle, 1.0f)

        GLES30.glUniform1i(surfelCountHandle, surfelCount)
        GLES30.glUniform1i(isHypervisorHandle, if (isHypervisor) 1 else 0)
        
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, surfelSSBO)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, gridSSBO)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cameraDepthTextureId)
        GLES30.glUniform1i(camDepthHandle, 1)

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
        
        GLES30.glVertexAttribPointer(positionHandle, 3, GLES30.GL_FLOAT, false, 0, quadBuffer)
        GLES30.glEnableVertexAttribArray(positionHandle)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    fun onDestroy() {
        if (program != 0) {
            GLES30.glDeleteProgram(program)
            program = 0
        }
    }
}
