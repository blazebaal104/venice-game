// Thin OpenGL ES 3.0 helpers: programs, vertex buffers, textures.
package com.blazebaal.venice.render

import android.opengl.GLES30.*
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class Program(vs: String, fs: String, name: String) {
    val id: Int
    private val locs = HashMap<String, Int>()

    init {
        fun compile(type: Int, src: String): Int {
            val s = glCreateShader(type)
            glShaderSource(s, src); glCompileShader(s)
            val ok = IntArray(1); glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) { val log = glGetShaderInfoLog(s); Log.e("Venice", "$name shader error: $log"); throw RuntimeException("$name: $log") }
            return s
        }
        id = glCreateProgram()
        glAttachShader(id, compile(GL_VERTEX_SHADER, vs)); glAttachShader(id, compile(GL_FRAGMENT_SHADER, fs))
        glLinkProgram(id)
        val ok = IntArray(1); glGetProgramiv(id, GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) { val log = glGetProgramInfoLog(id); Log.e("Venice", "$name link error: $log"); throw RuntimeException("$name: $log") }
    }

    fun use() = glUseProgram(id)
    fun loc(n: String) = locs.getOrPut(n) { glGetUniformLocation(id, n) }
    fun mat4(n: String, m: FloatArray) = glUniformMatrix4fv(loc(n), 1, false, m, 0)
    fun vec3(n: String, x: Float, y: Float, z: Float) = glUniform3f(loc(n), x, y, z)
    fun vec3(n: String, v: FloatArray) = glUniform3f(loc(n), v[0], v[1], v[2])
    fun f(n: String, v: Float) = glUniform1f(loc(n), v)
    fun i(n: String, v: Int) = glUniform1i(loc(n), v)
}

fun floatBuffer(a: FloatArray): FloatBuffer =
    ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

/** Non-indexed triangle mesh with the 12-float layout (pos, normal, uv, attr). */
class Mesh(data: FloatArray, stride: Int = 12, layout: IntArray = intArrayOf(3, 3, 2, 4)) {
    val vao: Int; val vbo: Int
    val count = data.size / stride

    init {
        val a = IntArray(1)
        glGenVertexArrays(1, a, 0); vao = a[0]
        glGenBuffers(1, a, 0); vbo = a[0]
        glBindVertexArray(vao)
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, data.size * 4, floatBuffer(data), GL_STATIC_DRAW)
        var off = 0
        for ((loc, n) in layout.withIndex()) {
            glEnableVertexAttribArray(loc)
            glVertexAttribPointer(loc, n, GL_FLOAT, false, stride * 4, off * 4)
            off += n
        }
        glBindVertexArray(0)
    }

    fun draw() { if (count == 0) return; glBindVertexArray(vao); glDrawArrays(GL_TRIANGLES, 0, count) }
    fun release() { glDeleteBuffers(1, intArrayOf(vbo), 0); glDeleteVertexArrays(1, intArrayOf(vao), 0) }
}

object Tex {
    fun create(w: Int, h: Int, format: Int, data: ByteArray, linear: Boolean, internal: Int): Int {
        val t = IntArray(1); glGenTextures(1, t, 0)
        glBindTexture(GL_TEXTURE_2D, t[0])
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        val buf = ByteBuffer.allocateDirect(data.size).order(ByteOrder.nativeOrder()).put(data); buf.position(0)
        glTexImage2D(GL_TEXTURE_2D, 0, internal, w, h, 0, format, GL_UNSIGNED_BYTE, buf)
        val f = if (linear) GL_LINEAR else GL_NEAREST
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, f); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, f)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        return t[0]
    }
    fun release(id: Int) = glDeleteTextures(1, intArrayOf(id), 0)
}
