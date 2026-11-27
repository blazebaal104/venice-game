// The barene up close: clumps of salt-marsh grass standing in the shallow water, instanced around the camera.
// One small clump mesh (3 tapered blades) on the GPU; the instances (position, height, seed) are regenerated on the CPU
// only when the camera has moved a few metres - deterministic per world position, so nothing pops or shuffles.
// Sway is all in the vertex shader: the weather's wind sets direction and strength, gusts roll across the field.
// Past `radius` the flat marsh carpet of the ground pass takes over (dithered hand-over in both shaders).
package com.blazebaal.venice.render

import android.opengl.GLES30.*
import com.blazebaal.venice.core.WorldMap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class MarshGrass(private val world: WorldMap) {
    private val vao: Int; private val vbo: Int; private val ibo: Int
    private val verts: Int
    private val maxInst = 12000
    private val inst = FloatArray(maxInst * 4)
    private val buf = ByteBuffer.allocateDirect(maxInst * 16).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var count = 0
    private var lastX = Float.NaN; private var lastY = Float.NaN; private var lastR = 0f
    var radius = 60f

    init {
        // 3 blades round the clump, each a tapered quad + tip triangle, leaning outwards a little
        val v = ArrayList<Float>()
        for (b in 0 until 3) {
            val a = b * 2.094f + 0.3f; val c = cos(a); val s = sin(a)
            val lean = 0.10f + 0.05f * b
            fun p(w: Float, z: Float) { val out = lean * z * z; v.add(c * w - s * out); v.add(s * w + c * out); v.add(z) }
            val w0 = 0.055f; val w1 = 0.035f
            p(-w0, 0f); p(w0, 0f); p(w1, 0.55f); p(-w0, 0f); p(w1, 0.55f); p(-w1, 0.55f)
            p(-w1, 0.55f); p(w1, 0.55f); p(0f, 1f)
        }
        verts = v.size / 3
        val ids = IntArray(3)
        glGenVertexArrays(1, ids, 0); vao = ids[0]
        glGenBuffers(2, ids, 1); vbo = ids[1]; ibo = ids[2]
        glBindVertexArray(vao)
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        val vb = ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(v.toFloatArray()); vb.position(0)
        glBufferData(GL_ARRAY_BUFFER, v.size * 4, vb, GL_STATIC_DRAW)
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0)
        glBindBuffer(GL_ARRAY_BUFFER, ibo)
        glBufferData(GL_ARRAY_BUFFER, maxInst * 16, null, GL_DYNAMIC_DRAW)
        glEnableVertexAttribArray(2); glVertexAttribPointer(2, 4, GL_FLOAT, false, 16, 0); glVertexAttribDivisor(2, 1)
        glBindVertexArray(0)
    }

    private fun hash(ix: Int, iy: Int, k: Int): Float {
        var h = ix * 374761393 + iy * 668265263 + k * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xffff) / 65535f
    }

    /** rebuilds the instances when the camera moved enough; cheap otherwise */
    fun update(cx: Float, cy: Float) {
        if (!lastX.isNaN() && hypot(cx - lastX, cy - lastY) < 6f && lastR == radius) return
        lastX = cx; lastY = cy; lastR = radius
        // quick reject: no marsh anywhere near (most of the city)
        var any = false
        var sx = -radius
        while (sx <= radius && !any) { var sy = -radius; while (sy <= radius) { if (world.marsh(cx + sx, cy + sy) > 0.3f) { any = true; break }; sy += 12f }; sx += 12f }
        if (!any) { count = 0; return }
        val step = 1.05f
        val i0 = floor((cx - radius) / step).toInt(); val i1 = ceil((cx + radius) / step).toInt()
        val j0 = floor((cy - radius) / step).toInt(); val j1 = ceil((cy + radius) / step).toInt()
        var n = 0
        loop@ for (i in i0..i1) for (j in j0..j1) {
            val jx = (hash(i, j, 1) - 0.5f) * step; val jy = (hash(i, j, 2) - 0.5f) * step
            val x = i * step + jx; val y = j * step + jy
            val d = hypot(x - cx, y - cy); if (d > radius) continue
            // thinner further out (the carpet behind fills the gaps)
            val density = if (d < radius * 0.4f) 1f else 1f - 0.6f * (d - radius * 0.4f) / (radius * 0.6f)
            if (hash(i, j, 3) > density) continue
            val m = world.marsh(x, y); if (m < 0.45f) continue
            if (world.solid(x, y) > 0.4f) continue
            val seed = hash(i, j, 4)
            // tufts of different heights: low salicornia, mid limonium, the odd tall reed clump
            val hgt = (0.25f + 0.55f * hash(i, j, 5) + (if (seed > 0.9f) 0.6f else 0f)) * (0.55f + 0.45f * ((m - 0.45f) / 0.55f))
            val o = n * 4; inst[o] = x; inst[o + 1] = y; inst[o + 2] = hgt; inst[o + 3] = seed
            if (++n >= maxInst) break@loop
        }
        count = n
        buf.position(0); buf.put(inst, 0, n * 4); buf.position(0)
        glBindBuffer(GL_ARRAY_BUFFER, ibo)
        glBufferData(GL_ARRAY_BUFFER, maxInst * 16, null, GL_DYNAMIC_DRAW)
        if (n > 0) glBufferSubData(GL_ARRAY_BUFFER, 0, n * 16, buf)
    }

    fun draw() {
        if (count == 0) return
        glBindVertexArray(vao)
        glDrawArraysInstanced(GL_TRIANGLES, 0, verts, count)
        glBindVertexArray(0)
    }
}
