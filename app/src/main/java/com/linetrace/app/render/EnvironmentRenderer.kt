package com.linetrace.app.render

import android.opengl.GLES30
import android.opengl.Matrix
import com.google.ar.core.Anchor
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.linetrace.app.feature.sync.ImuNetworkBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class EnvironmentRenderer {
    private var program = 0
    private var positionHandle = -1
    private var mvpHandle = -1
    private var colorHandle = -1
    private var depthBiasHandle = -1

    private val planeVertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(2000 * 3 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var planeExtrudedBuffer: FloatBuffer = ByteBuffer.allocateDirect(2000 * 2 * 3 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    
    private var lastAnchorTime = 0L
    private val anchorThrottleMs = 1000L

    fun init() {
        program = GLUtils.buildProgram(ShaderSource.VERTEX_SHADER, ShaderSource.FRAGMENT_SHADER)
        positionHandle = GLES30.glGetAttribLocation(program, "aPosition")
        mvpHandle = GLES30.glGetUniformLocation(program, "uMvpMatrix")
        colorHandle = GLES30.glGetUniformLocation(program, "uColor")
        depthBiasHandle = GLES30.glGetUniformLocation(program, "uDepthBias")
    }

    fun drawPlanes(
        session: Session,
        vpMatrix: FloatArray,
        wallHeight: Float,
        wallAlpha: Float,
        planeAlpha: Float,
        wsManager: ImuNetworkBridge
    ) {
        val planes = session.getAllTrackables(Plane::class.java)
        GLES30.glUseProgram(program)
        GLES30.glUniform1f(depthBiasHandle, 0.001f)
        
        for (plane in planes) {
            if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) continue
            val isVertical = plane.type == Plane.Type.VERTICAL
            val polygon = plane.polygon
            val numVertices = polygon.remaining() / 2
            if (numVertices == 0) continue
            
            planeVertexBuffer.clear()
            for (i in 0 until numVertices) {
                planeVertexBuffer.put(polygon.get())
                planeVertexBuffer.put(0f)
                planeVertexBuffer.put(polygon.get())
            }
            planeVertexBuffer.flip()
            
            val centerPose = plane.centerPose
            centerPose.toMatrix(modelMatrix, 0)
            Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
            GLES30.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
            GLES30.glVertexAttribPointer(positionHandle, 3, GLES30.GL_FLOAT, false, 0, planeVertexBuffer)
            GLES30.glEnableVertexAttribArray(positionHandle)
            
            if (isVertical) {
                GLES30.glEnable(GLES30.GL_DEPTH_TEST)
                GLES30.glDepthMask(true)
                GLES30.glUniform4f(colorHandle, 0.8f, 0.0f, 0.8f, wallAlpha)
                
                val verticesNeeded = numVertices * 2
                if (planeExtrudedBuffer.capacity() < verticesNeeded * 3) {
                    planeExtrudedBuffer = ByteBuffer.allocateDirect(verticesNeeded * 3 * 4)
                        .order(ByteOrder.nativeOrder()).asFloatBuffer()
                }
                planeExtrudedBuffer.clear()
                planeVertexBuffer.rewind()
                for (i in 0 until numVertices) {
                    val vx = planeVertexBuffer.get()
                    val vy = planeVertexBuffer.get() 
                    val vz = planeVertexBuffer.get()
                    planeExtrudedBuffer.put(vx).put(0f).put(vz)
                    planeExtrudedBuffer.put(vx).put(wallHeight).put(vz)
                }
                planeExtrudedBuffer.flip()
                GLES30.glVertexAttribPointer(positionHandle, 3, GLES30.GL_FLOAT, false, 0, planeExtrudedBuffer)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, verticesNeeded)

                val now = System.currentTimeMillis()
                if (now - lastAnchorTime > anchorThrottleMs) {
                    wsManager.sendVerticalPlane(centerPose.tx(), centerPose.ty(), centerPose.tz(), wallHeight, wallAlpha)
                    lastAnchorTime = now
                }
            } else {
                GLES30.glUniform4f(colorHandle, 0.0f, 0.8f, 0.8f, planeAlpha)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_FAN, 0, numVertices)
            }
        }
    }

    fun onDestroy() {
        if (program != 0) {
            GLES30.glDeleteProgram(program)
            program = 0
        }
    }
}
