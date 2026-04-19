package com.linetrace.app.render

import android.opengl.GLES30
import android.util.Log

object GLUtils {
    fun buildProgram(vss: String, fss: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vss)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fss)
        if (vs == 0 || fs == 0) return 0
        
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        
        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            Log.e("GLUtils", "Program link error: ${GLES30.glGetProgramInfoLog(prog)}")
            GLES30.glDeleteProgram(prog)
            return 0
        }
        return prog
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e("GLUtils", "Shader compile error ($type): ${GLES30.glGetShaderInfoLog(shader)}")
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }
}
