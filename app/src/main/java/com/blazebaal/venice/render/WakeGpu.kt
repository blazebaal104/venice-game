// The wake simulation on the GPU: a 256 m x 256 m wave-equation heightfield (0.5 m cells) that follows the boat,
// stepped at a fixed 60 Hz in a fragment shader (ping-pong RGBA16F: height, previous height, foam, aeration).
// The CPU only describes the boat pose and the foam sources each step, so the simulation costs no CPU time and
// nothing is uploaded per frame (the old CPU version took several ms per step and stalled on a 1.6 MB upload).
package com.blazebaal.venice.render

import android.opengl.GLES30.*
import com.blazebaal.venice.core.Boat
import com.blazebaal.venice.core.WorldMap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class WakeGpu(private val tri: Mesh) {
    val n = 512
    val cell = 0.5f
    val size = n * cell
    /** world position of the grid's (0,0) corner, always on whole cells */
    var ox = 0f; var oy = 0f
    var dirX = 1f; var dirY = 0f
    private val state = arrayOf(Target(n, n, true, false), Target(n, n, true, false))
    private var cur = 0
    val ok = state[0].isHdr && state[1].isHdr
    val tex get() = state[cur].tex
    private val prog = Program(Shaders.WAKESIM_VS, Shaders.WAKESIM_FS, "wakesim")
    private val solid = ByteArray(n * n)
    private val solidBuf = ByteBuffer.allocateDirect(n * n).order(ByteOrder.nativeOrder())
    private val solidTex: Int
    private var started = false
    private var acc = 0f
    private val stepDt = 1f / 60f
    private val c = 3.6f
    private val drv = com.blazebaal.venice.core.WakeDriver()
    // previous step's pose (for the hull's change of footprint)
    private val prevB = FloatArray(4); private val prevBb = FloatArray(4)

    init {
        solidTex = Tex.create(n, n, GL_RED, solid, false, GL_R8)
        for (t in state) { t.bind(); glClearColor(0f, 0f, 0f, 0f); glClear(GL_COLOR_BUFFER_BIT) }
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    private fun refreshSolid(world: WorldMap, i0: Int, i1: Int, j0: Int, j1: Int) {
        for (j in j0 until j1) for (i in i0 until i1)
            solid[j * n + i] = if (world.solid(ox + (i + 0.5f) * cell, oy + (j + 0.5f) * cell) > 0.5f) -1 else 0
    }

    /** Returns the shift (cells) the next step must apply when the window moved. */
    private fun recentre(b: Boat, world: WorldMap): IntArray {
        // most of the window behind the boat: the trail lasts much longer than the water ahead matters
        val bx = b.x - dirX * 70f; val by = b.y - dirY * 70f
        val nx = floor((bx - size / 2) / cell) * cell; val ny = floor((by - size / 2) / cell) * cell
        if (!started) {
            started = true; ox = nx; oy = ny; refreshSolid(world, 0, n, 0, n); uploadSolid(); return intArrayOf(0, 0)
        }
        val dx = ((nx - ox) / cell).roundToInt(); val dy = ((ny - oy) / cell).roundToInt()
        if (abs(dx) < 32 && abs(dy) < 32) return intArrayOf(0, 0)
        if (abs(dx) >= n || abs(dy) >= n) { ox = nx; oy = ny; refreshSolid(world, 0, n, 0, n); uploadSolid(); return intArrayOf(n, n) }
        // shift the land mask on the CPU, only compute the new strips
        val t = ByteArray(n * n)
        for (j in 0 until n) { val sj = j + dy; if (sj < 0 || sj >= n) continue
            for (i in 0 until n) { val si = i + dx; if (si in 0 until n) t[j * n + i] = solid[sj * n + si] } }
        t.copyInto(solid)
        ox += dx * cell; oy += dy * cell
        if (dx > 0) refreshSolid(world, n - dx, n, 0, n) else if (dx < 0) refreshSolid(world, 0, -dx, 0, n)
        if (dy > 0) refreshSolid(world, 0, n, n - dy, n) else if (dy < 0) refreshSolid(world, 0, n, 0, -dy)
        uploadSolid()
        return intArrayOf(dx, dy)
    }

    private fun uploadSolid() {
        solidBuf.position(0); solidBuf.put(solid); solidBuf.position(0)
        glBindTexture(GL_TEXTURE_2D, solidTex); glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, n, n, GL_RED, GL_UNSIGNED_BYTE, solidBuf)
    }

    /** Advance the simulation (fixed 60 Hz steps, at most 3 per frame). Leaves the default framebuffer bound. */
    fun update(dt: Float, b: Boat, throttle: Float, world: WorldMap) {
        if (!ok) return
        acc += min(dt, 0.1f)
        var steps = 0
        while (acc >= stepDt && steps < 3) { acc -= stepDt; step(b, throttle, world); steps++ }
        if (steps == 3) acc = 0f
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    private fun step(b: Boat, throttle: Float, world: WorldMap) {
        val dt = stepDt
        drv.prepare(b, throttle, dt)
        dirX = drv.dirX; dirY = drv.dirY
        val shift = recentre(b, world)
        if (prevB[0] == 0f && prevB[1] == 0f) { prevB[0] = b.x; prevB[1] = b.y; prevB[2] = b.heading }
        // ---- one step
        val dst = state[1 - cur]
        dst.bind()
        prog.use()
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, state[cur].tex); prog.i("uState", 0)
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, solidTex); prog.i("uSolid", 1)
        prog.i("uN", n); prog.f("uCell", cell); glUniform2f(prog.loc("uOrigin"), ox, oy)
        glUniform2i(prog.loc("uShift"), shift[0], shift[1])
        prog.f("uDt", dt)
        prog.f("uK", (c * dt / cell).let { it * it })
        prog.f("uBeta", 0.10f * dt * dt / (cell * cell * cell * cell))
        prog.f("uNu", 0.18f * dt)
        prog.f("uDamp", 0.99835f)          // = 0.9967 per 1/30 s: the V fades over ~10 s
        prog.f("uSpongeMin", 0.927f)
        prog.f("uFoamDiff", 0.0075f); prog.f("uAerDiff", 0.015f)
        glUniform4fv(prog.loc("uB0"), 1, drv.b0, 0); glUniform4fv(prog.loc("uB0b"), 1, drv.b0b, 0)
        glUniform4fv(prog.loc("uB1"), 1, prevB, 0); glUniform4fv(prog.loc("uB1b"), 1, prevBb, 0)
        glUniform2f(prog.loc("uHull"), Boat.HALF_LEN, Boat.HALF_BEAM)
        glUniform4fv(prog.loc("uSplat"), 12, drv.splats, 0); prog.i("uNSplat", drv.nSplat)
        glDisable(GL_DEPTH_TEST)
        tri.draw()
        glEnable(GL_DEPTH_TEST)
        cur = 1 - cur
        drv.b0.copyInto(prevB); drv.b0b.copyInto(prevBb)
    }
}
