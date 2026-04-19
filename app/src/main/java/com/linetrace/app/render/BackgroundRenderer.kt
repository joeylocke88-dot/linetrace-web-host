package com.linetrace.app.render

import android.opengl.GLES11Ext
import android.opengl.GLES30
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class BackgroundRenderer {
    private var program = 0
    private var positionHandle = -1
    private var textureHandle = -1
    private var samplerHandle = -1

    private val quadBuffer: FloatBuffer = ByteBuffer.allocateDirect(4 * 3 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 0f, 1f, -1f, 0f, -1f, 1f, 0f, 1f, 1f, 0f))
            flip()
        }

    private val displayUvBuffer: FloatBuffer = ByteBuffer.allocateDirect(4 * 2 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))
            flip()
        }
    
    private val transformedDisplayUvBuffer: FloatBuffer = ByteBuffer.allocateDirect(4 * 2 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun init() {
        program = GLUtils.buildProgram(ShaderSource.BACKGROUND_VERTEX_SHADER, ShaderSource.BACKGROUND_FRAGMENT_SHADER)
        positionHandle = GLES30.glGetAttribLocation(program, "aPosition")
        textureHandle = GLES30.glGetAttribLocation(program, "aTexCoord")
        samplerHandle = GLES30.glGetUniformLocation(program, "sTexture")
    }

    fun draw(frame: Frame, textureId: Int) {
        if (textureId == -1) return

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glUseProgram(program)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glUniform1i(samplerHandle, 0)

        transformedDisplayUvBuffer.rewind()
        frame.transformCoordinates2d(
            Coordinates2d.VIEW_NORMALIZED,
            displayUvBuffer,
            Coordinates2d.TEXTURE_NORMALIZED,
            transformedDisplayUvBuffer
        )

        GLES30.glVertexAttribPointer(positionHandle, 3, GLES30.GL_FLOAT, false, 0, quadBuffer)
        GLES30.glEnableVertexAttribArray(positionHandle)
        GLES30.glVertexAttribPointer(textureHandle, 2, GLES30.GL_FLOAT, false, 0, transformedDisplayUvBuffer)
        GLES30.glEnableVertexAttribArray(textureHandle)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    fun onDestroy() {
        if (program != 0) {
            GLES30.glDeleteProgram(program)
            program = 0
        }
    }
}
