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
    fun vec2(n: String, x: Float, y: Float) = glUniform2f(loc(n), x, y)
    fun vec3(n: String, x: Float, y: Float, z: Float) = glUniform3f(loc(n), x, y, z)
    fun vec3(n: String, v: FloatArray) = glUniform3f(loc(n), v[0], v[1], v[2])
    fun f(n: String, v: Float) = glUniform1f(loc(n), v)
    fun i(n: String, v: Int) = glUniform1i(loc(n), v)
}

fun floatBuffer(a: FloatArray): FloatBuffer =
    ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

/** Non-indexed triangle mesh with the 12-float layout (pos, normal, uv, attr) unless told otherwise. */
class Mesh(buf: FloatBuffer, val count: Int, stride: Int = 12, layout: IntArray = intArrayOf(3, 3, 2, 4)) {
    val vao: Int; val vbo: Int
    constructor(data: FloatArray, stride: Int = 12, layout: IntArray = intArrayOf(3, 3, 2, 4)) : this(floatBuffer(data), data.size / stride, stride, layout)

    init {
        val a = IntArray(1)
        glGenVertexArrays(1, a, 0); vao = a[0]
        glGenBuffers(1, a, 0); vbo = a[0]
        glBindVertexArray(vao)
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        buf.position(0)
        glBufferData(GL_ARRAY_BUFFER, count * stride * 4, buf, GL_STATIC_DRAW)
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

/** Texture arrays for the photo materials: albedo (RGBA, non-premultiplied) and normal/roughness. */
object TexArrays {
    fun load(ctx: android.content.Context, count: Int): IntArray {
        val ids = IntArray(2); glGenTextures(2, ids, 0)
        val size = 512
        for ((k, prefix) in listOf(Pair("c_", "png"), Pair("n_", "jpg")).withIndex()) {
            glBindTexture(GL_TEXTURE_2D_ARRAY, ids[k])
            glTexStorage3D(GL_TEXTURE_2D_ARRAY, 10, GL_RGBA8, size, size, count)
            val buf = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder())
            for (i in 0 until count) {
                val opts = android.graphics.BitmapFactory.Options().apply { inPremultiplied = false; inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
                val bmp = ctx.assets.open("tex/${prefix.first}${"%02d".format(i)}.${prefix.second}").use { android.graphics.BitmapFactory.decodeStream(it, null, opts)!! }
                buf.position(0); bmp.copyPixelsToBuffer(buf); buf.position(0)
                glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, i, size, size, 1, GL_RGBA, GL_UNSIGNED_BYTE, buf)
                bmp.recycle()
            }
            glGenerateMipmap(GL_TEXTURE_2D_ARRAY)
            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT); glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT)
        }
        return ids
    }
}

/** Off-screen colour target with depth; tries half-float HDR first. */
class Target(val w: Int, val h: Int, hdr: Boolean, depth: Boolean) {
    val fbo: Int; val tex: Int; private var rb = 0
    var isHdr = false
    init {
        val t = IntArray(1); glGenTextures(1, t, 0); tex = t[0]
        val f = IntArray(1); glGenFramebuffers(1, f, 0); fbo = f[0]
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        if (depth) {
            val r = IntArray(1); glGenRenderbuffers(1, r, 0); rb = r[0]
            glBindRenderbuffer(GL_RENDERBUFFER, rb); glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, w, h)
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, rb)
        }
        fun alloc(internal: Int, type: Int) {
            glBindTexture(GL_TEXTURE_2D, tex)
            glTexImage2D(GL_TEXTURE_2D, 0, internal, w, h, 0, GL_RGBA, type, null)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0)
        }
        if (hdr) { alloc(GL_RGBA16F, GL_HALF_FLOAT); isHdr = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE }
        if (!isHdr) alloc(GL_RGBA8, GL_UNSIGNED_BYTE)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }
    fun bind() { glBindFramebuffer(GL_FRAMEBUFFER, fbo); glViewport(0, 0, w, h) }
    fun release() {
        glDeleteFramebuffers(1, intArrayOf(fbo), 0); glDeleteTextures(1, intArrayOf(tex), 0)
        if (rb != 0) glDeleteRenderbuffers(1, intArrayOf(rb), 0)
    }
}

/** Vertex buffer rewritten every frame (wake ribbons, spray points). */
class DynMesh(val stride: Int, layout: IntArray, capacityFloats: Int) {
    val vao: Int; val vbo: Int
    private val buf = ByteBuffer.allocateDirect(capacityFloats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    var count = 0
    init {
        val a = IntArray(1); glGenVertexArrays(1, a, 0); vao = a[0]; glGenBuffers(1, a, 0); vbo = a[0]
        glBindVertexArray(vao); glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, capacityFloats * 4, null, GL_DYNAMIC_DRAW)
        var off = 0
        for ((loc, n) in layout.withIndex()) { glEnableVertexAttribArray(loc); glVertexAttribPointer(loc, n, GL_FLOAT, false, stride * 4, off * 4); off += n }
        glBindVertexArray(0)
    }
    fun upload(data: FloatArray, floats: Int) {
        val n = minOf(floats, buf.capacity())
        buf.position(0); buf.put(data, 0, n); buf.position(0)
        glBindBuffer(GL_ARRAY_BUFFER, vbo); glBufferSubData(GL_ARRAY_BUFFER, 0, n * 4, buf)
        count = n / stride
    }
    fun draw(mode: Int) { if (count == 0) return; glBindVertexArray(vao); glDrawArrays(mode, 0, count) }
}

/** Instanced quads: a static 6-vertex corner buffer (location 3) + per-instance data rewritten every frame. */
class InstMesh(val stride: Int, layout: IntArray, val maxInstances: Int) {
    val vao: Int; private val vbo: Int; private val cornerVbo: Int
    private val buf = ByteBuffer.allocateDirect(maxInstances * stride * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    var count = 0
    init {
        val a = IntArray(1); glGenVertexArrays(1, a, 0); vao = a[0]
        glGenBuffers(1, a, 0); vbo = a[0]; glGenBuffers(1, a, 0); cornerVbo = a[0]
        glBindVertexArray(vao)
        glBindBuffer(GL_ARRAY_BUFFER, cornerVbo)
        glBufferData(GL_ARRAY_BUFFER, 12 * 4, floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, -1f, 1f, 1f, -1f, 1f)), GL_STATIC_DRAW)
        glEnableVertexAttribArray(3); glVertexAttribPointer(3, 2, GL_FLOAT, false, 8, 0)
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, maxInstances * stride * 4, null, GL_STREAM_DRAW)
        var off = 0
        for ((loc, n) in layout.withIndex()) {
            glEnableVertexAttribArray(loc); glVertexAttribPointer(loc, n, GL_FLOAT, false, stride * 4, off * 4)
            glVertexAttribDivisor(loc, 1); off += n
        }
        glBindVertexArray(0)
    }
    fun upload(data: FloatArray, instances: Int) {
        count = minOf(instances, maxInstances)
        if (count == 0) return
        buf.position(0); buf.put(data, 0, count * stride); buf.position(0)
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, maxInstances * stride * 4, null, GL_STREAM_DRAW)   // orphan: no sync stall
        glBufferSubData(GL_ARRAY_BUFFER, 0, count * stride * 4, buf)
    }
    fun draw() { if (count == 0) return; glBindVertexArray(vao); glDrawArraysInstanced(GL_TRIANGLES, 0, 6, count) }
}

/** Depth-only shadow map (hardware PCF compare). */
class ShadowMap(val size: Int) {
    var tex = 0; var fbo = 0
    init {
        val t = IntArray(1); glGenTextures(1, t, 0); tex = t[0]
        glBindTexture(GL_TEXTURE_2D, tex)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, size, size, 0, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, null)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL)
        val f = IntArray(1); glGenFramebuffers(1, f, 0); fbo = f[0]
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, tex, 0)
        glDrawBuffers(1, intArrayOf(GL_NONE), 0); glReadBuffer(GL_NONE)
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) { Log.e("Venice", "shadow FBO incomplete"); tex = 0 }
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }
    fun bind() { glBindFramebuffer(GL_FRAMEBUFFER, fbo); glViewport(0, 0, size, size) }
}
