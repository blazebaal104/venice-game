// Gulls (yellow-legged gulls, the Venetian "cocai"): perched on the bricole and on the roof edges around you, taking
// off when you come close (or a vaporetto does), circling and gliding over the canals with the odd flap, landing on a
// free pole again. A tiny low-poly bird (body, head, yellow bill, grey wings with black tips) drawn instanced; the wing
// beat and the folding are done in the vertex shader. Calls are reported to the audio engine from where each bird is.
package com.blazebaal.venice.render

import android.opengl.GLES30.*
import com.blazebaal.venice.core.WorldMap
import com.blazebaal.venice.core.Z
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class Gulls(private val world: WorldMap) {
    private class G(var x: Float, var y: Float, var z: Float, var h: Float) {
        var state = PERCH; var t = 0f; var vx = 0f; var vy = 0f; var vz = 0f
        var phase = 0f; var amp = 0f; var fold = 1f; var bank = 0f; var flapT = 0f; var flapOn = 0f
        var cx = 0f; var cy = 0f; var cz = 25f; var rad = 40f; var dir = 1f; var life = 40f
        var tx = 0f; var ty = 0f; var tz = 0f; var spot = 0L; var callT = 5f; var speed = 9f
    }
    private val birds = ArrayList<G>()
    /** gull calls to play: x, y, strength */
    val calls = ArrayList<FloatArray>()
    private val taken = HashMap<Long, Float>()      // perch spots in use / recently left (key -> time free again)
    private var scanT = 0f; private var clock = 0f
    private val rnd = java.util.Random(11)
    private fun rr(a: Float, b: Float) = a + (b - a) * rnd.nextFloat()

    // ---- GPU
    private val vao: Int; private val vbo: Int; private val ibo: Int
    private val verts: Int
    private val maxN = 48
    private val inst = FloatArray(maxN * 8)
    private val buf = ByteBuffer.allocateDirect(maxN * 32).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var count = 0

    init {
        // x forward, y left, z up (metres); w = part: 0 body, 1 inner wing, 2 outer wing, 3 bill
        val v = ArrayList<Float>()
        fun t(a: FloatArray, b: FloatArray, c: FloatArray, part: Float) { for (p in arrayOf(a, b, c)) { v.add(p[0]); v.add(p[1]); v.add(p[2]); v.add(part) } }
        fun p(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)
        val nose = p(0.27f, 0f, 0.05f); val tail = p(-0.30f, 0f, 0.01f)
        val top = p(0.02f, 0f, 0.085f); val bot = p(0.0f, 0f, -0.055f)
        val l = p(0.0f, 0.065f, 0.015f); val r = p(0.0f, -0.065f, 0.015f)
        // body: two four-sided pyramids
        t(nose, top, l, 0f); t(nose, r, top, 0f); t(nose, l, bot, 0f); t(nose, bot, r, 0f)
        t(tail, l, top, 0f); t(tail, top, r, 0f); t(tail, bot, l, 0f); t(tail, r, bot, 0f)
        // tail fan
        t(p(-0.20f, 0f, 0.02f), p(-0.36f, 0.075f, 0.005f), p(-0.36f, -0.075f, 0.005f), 0f)
        // bill (yellow)
        t(p(0.36f, 0f, 0.045f), p(0.27f, 0.014f, 0.055f), p(0.27f, -0.014f, 0.055f), 3f)
        t(p(0.36f, 0f, 0.045f), p(0.27f, -0.014f, 0.04f), p(0.27f, 0.014f, 0.04f), 3f)
        for (s in floatArrayOf(1f, -1f)) {
            // inner wing: shoulder to elbow (sweeps back a little)
            val rf = p(0.09f, 0.055f * s, 0.03f); val rb = p(-0.09f, 0.055f * s, 0.03f)
            val ef = p(0.07f, 0.36f * s, 0.035f); val eb = p(-0.10f, 0.36f * s, 0.03f)
            t(rf, ef, rb, 1f); t(rb, ef, eb, 1f)
            // outer wing (hand) to the pointed tip
            val tf = p(-0.02f, 0.60f * s, 0.03f); val tip = p(-0.16f, 0.71f * s, 0.02f)
            t(ef, tf, eb, 2f); t(eb, tf, tip, 2f)
        }
        verts = v.size / 4
        val ids = IntArray(3)
        glGenVertexArrays(1, ids, 0); vao = ids[0]
        glGenBuffers(2, ids, 1); vbo = ids[1]; ibo = ids[2]
        glBindVertexArray(vao)
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        val vb = ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(v.toFloatArray()); vb.position(0)
        glBufferData(GL_ARRAY_BUFFER, v.size * 4, vb, GL_STATIC_DRAW)
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 4, GL_FLOAT, false, 16, 0)
        glBindBuffer(GL_ARRAY_BUFFER, ibo)
        glBufferData(GL_ARRAY_BUFFER, maxN * 32, null, GL_DYNAMIC_DRAW)
        glEnableVertexAttribArray(2); glVertexAttribPointer(2, 4, GL_FLOAT, false, 32, 0); glVertexAttribDivisor(2, 1)
        glEnableVertexAttribArray(4); glVertexAttribPointer(4, 4, GL_FLOAT, false, 32, 16); glVertexAttribDivisor(4, 1)
        glBindVertexArray(0)
    }

    private fun key(x: Float, y: Float) = (floor(x * 2f).toLong() shl 32) xor (floor(y * 2f).toLong() and 0xffffffffL)
    private fun hash(x: Float, y: Float): Float {
        var h = (x * 7.31f).toInt() * 374761393 + (y * 3.17f).toInt() * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xffff) / 65535f
    }
    private fun free(k: Long) = (taken[k] ?: -1f) < clock && birds.none { it.spot == k && it.state != FLY }

    private val posts = ArrayList<FloatArray>()
    /** perch spots near (x, y): bricole in the water and roof corners, a deterministic share of them */
    private fun spots(x: Float, y: Float, out: ArrayList<FloatArray>) {
        out.clear()
        world.posts(x, y, 110f, posts)
        for (p in posts) if (hash(p[0], p[1]) < 0.4f) out.add(floatArrayOf(p[0], p[1], 2.35f))
        val (tx, ty) = world.tileCoords(x, y)
        for (dy in -1..1) for (dx in -1..1) {
            val t = world.tile(tx + dx, ty + dy) ?: continue
            for (b in t.buildings) {
                if (b.ringSizes.isEmpty() || b.height < 6f) continue
                val bx = b.xy[0] + t.ox; val by = b.xy[1] + t.oy
                if (abs(bx - x) > 110f || abs(by - y) > 110f || hash(bx, by) > 0.05f) continue
                out.add(floatArrayOf(bx, by, Z.GROUND + b.height + 0.02f))
            }
        }
    }

    private val sp = ArrayList<FloatArray>()
    fun update(dt: Float, camX: Float, camY: Float, boatX: Float, boatY: Float, boatSpeed: Float, cityness: Float, windX: Float, windY: Float, boats: List<FloatArray>) {
        if (dt <= 0f) return
        clock += dt
        // ---- populate: perched birds on the spots around, a few in the air (more over the city)
        scanT -= dt
        if (scanT <= 0f) {
            scanT = 2f
            birds.removeAll { hypot(it.x - camX, it.y - camY) > 190f }
            spots(camX, camY, sp)
            var perched = birds.count { it.state == PERCH }
            for (s in sp) {
                if (perched >= 16 || birds.size >= maxN - 4) break
                val k = key(s[0], s[1])
                if (!free(k) || hypot(s[0] - boatX, s[1] - boatY) < 25f) continue
                birds.add(G(s[0], s[1], s[2], hash(s[1], s[0]) * 6.283f).also { it.spot = k; it.callT = rr(20f, 90f) })
                perched++
            }
            val flying = birds.count { it.state != PERCH }
            val want = (2 + 6 * cityness).toInt()
            if (flying < want && birds.size < maxN) {
                val a = rr(0f, 6.283f); val d = rr(50f, 120f)
                birds.add(G(camX + cos(a) * d, camY + sin(a) * d, rr(16f, 36f), rr(0f, 6.283f)).also { startFly(it, rr(25f, 60f)) })
            }
        }
        // ---- behave
        for (g in birds) {
            g.t += dt
            when (g.state) {
                PERCH -> {
                    g.fold = 1f; g.amp = 0f; g.bank = 0f
                    val near = hypot(g.x - boatX, g.y - boatY)
                    var scare = near < 13f || (near < 26f && boatSpeed > 8f)
                    for (b in boats) if (hypot(b[0] - g.x, b[1] - g.y) < 14f) scare = true
                    if (scare || rnd.nextFloat() < dt / 240f) takeOff(g, boatX, boatY)
                    g.callT -= dt
                    if (g.callT <= 0f) { g.callT = rr(25f, 90f); calls.add(floatArrayOf(g.x, g.y, 0.6f)) }
                    if (rnd.nextFloat() < dt / 6f) g.h += rr(-0.6f, 0.6f)          // looks round
                }
                TAKEOFF -> {
                    g.fold = max(0f, g.fold - dt * 5f); g.amp = 1.0f
                    g.phase += dt * 2f * PI.toFloat() * 4.2f
                    val sp0 = min(9f, 3f + g.t * 4f)
                    g.vx = cos(g.h) * sp0; g.vy = sin(g.h) * sp0; g.vz = 2.6f
                    g.x += g.vx * dt; g.y += g.vy * dt; g.z += g.vz * dt
                    if (g.t > 2.4f) startFly(g, rr(25f, 70f))
                }
                else -> fly(g, dt, windX, windY)
            }
        }
        birds.removeAll { it.state == LEAVING && hypot(it.x - camX, it.y - camY) > 170f }
        // ---- to the GPU
        var n = 0
        for (g in birds) {
            if (n >= maxN) break
            val o = n * 8
            inst[o] = g.x; inst[o + 1] = g.y; inst[o + 2] = g.z; inst[o + 3] = g.h
            inst[o + 4] = g.phase; inst[o + 5] = g.amp; inst[o + 6] = g.fold; inst[o + 7] = g.bank
            n++
        }
        count = n
        if (n > 0) {
            buf.position(0); buf.put(inst, 0, n * 8); buf.position(0)
            glBindBuffer(GL_ARRAY_BUFFER, ibo)
            glBufferData(GL_ARRAY_BUFFER, maxN * 32, null, GL_DYNAMIC_DRAW)
            glBufferSubData(GL_ARRAY_BUFFER, 0, n * 32, buf)
        }
    }

    private fun takeOff(g: G, fromX: Float, fromY: Float) {
        g.state = TAKEOFF; g.t = 0f
        taken[g.spot] = clock + 45f
        // away from what scared it, into the wind a little
        g.h = atan2(g.y - fromY, g.x - fromX) + rr(-0.6f, 0.6f)
        if (rnd.nextFloat() < 0.75f) calls.add(floatArrayOf(g.x, g.y, 1f))
    }
    private fun startFly(g: G, life: Float) {
        g.state = FLY; g.t = 0f; g.life = life
        g.rad = rr(22f, 55f); g.dir = if (rnd.nextBoolean()) 1f else -1f
        // circle centred off to the side of where it's heading
        g.cx = g.x + cos(g.h + g.dir * 1.5708f) * g.rad; g.cy = g.y + sin(g.h + g.dir * 1.5708f) * g.rad
        g.cz = rr(14f, 34f); g.speed = rr(8f, 10.5f); g.flapT = rr(1f, 4f)
    }

    private fun fly(g: G, dt: Float, wx: Float, wy: Float) {
        g.fold = max(0f, g.fold - dt * 4f)
        // where to: round the circle (drifting downwind), to a perch, or away
        var tx: Float; var ty: Float; var tz: Float
        when {
            g.state == LAND -> { tx = g.tx; ty = g.ty; tz = g.tz + 0.15f }
            g.state == LEAVING -> { tx = g.x + cos(g.h) * 50f; ty = g.y + sin(g.h) * 50f; tz = g.cz + 10f }
            else -> {
                g.cx += wx * 0.15f * dt; g.cy += wy * 0.15f * dt
                val a = atan2(g.y - g.cy, g.x - g.cx) + g.dir * 0.5f
                tx = g.cx + cos(a) * g.rad; ty = g.cy + sin(a) * g.rad; tz = g.cz
                if (g.t > g.life) {
                    // land on a free spot nearby, or carry on somewhere else
                    var best: FloatArray? = null; var bd = 90f
                    spots(g.x, g.y, sp)
                    for (s in sp) { val d = hypot(s[0] - g.x, s[1] - g.y); if (d < bd && d > 15f && free(key(s[0], s[1]))) { bd = d; best = s } }
                    if (best != null && rnd.nextFloat() < 0.7f) {
                        g.state = LAND; g.t = 0f; g.tx = best[0]; g.ty = best[1]; g.tz = best[2]; g.spot = key(best[0], best[1]); taken[g.spot] = clock + 1e6f
                    } else { g.state = LEAVING; g.t = 0f }
                }
            }
        }
        val dx = tx - g.x; val dy = ty - g.y; val dist = hypot(dx, dy)
        var spd = g.speed
        if (g.state == LAND) spd = (dist * 0.6f + 1.2f).coerceAtMost(g.speed)
        // turn towards it, banking into the turn
        val want = atan2(dy, dx)
        var dh = want - g.h
        while (dh > PI) dh -= 2f * PI.toFloat(); while (dh < -PI) dh += 2f * PI.toFloat()
        val turn = dh.coerceIn(-1.3f * dt, 1.3f * dt)
        g.h += turn
        g.bank += (-(turn / max(dt, 1e-3f)) * 0.45f - g.bank) * min(1f, dt * 3f)
        g.x += cos(g.h) * spd * dt; g.y += sin(g.h) * spd * dt
        g.z += ((tz - g.z) * 0.6f).coerceIn(-3f, 2.5f) * dt
        // gliding, a burst of flaps now and then (and always when climbing or landing)
        g.flapT -= dt
        if (g.flapT <= 0f) { g.flapOn = rr(0.8f, 1.8f); g.flapT = g.flapOn + rr(2.5f, 7f) }
        if (g.flapOn > 0f) g.flapOn -= dt
        val flapping = g.flapOn > 0f || (tz - g.z) > 2f || (g.state == LAND && dist < 8f)
        g.amp += ((if (flapping) 0.9f else 0.08f) - g.amp) * min(1f, dt * 4f)
        g.phase += dt * 2f * PI.toFloat() * (if (flapping) 3.4f else 0.6f)
        if (g.phase > 1000f) g.phase -= 1000f
        if (g.state == FLY) { g.callT -= dt; if (g.callT <= 0f) { g.callT = rr(6f, 22f); calls.add(floatArrayOf(g.x, g.y, 0.8f)) } }
        if (g.state == LAND && dist < 0.5f && abs(g.z - g.tz) < 0.6f) {
            g.x = g.tx; g.y = g.ty; g.z = g.tz; g.state = PERCH; g.t = 0f; g.amp = 0f; g.bank = 0f; g.callT = rr(3f, 30f)
            taken[g.spot] = clock
        }
    }

    fun draw() {
        if (count == 0) return
        glBindVertexArray(vao)
        glDrawArraysInstanced(GL_TRIANGLES, 0, verts, count)
        glBindVertexArray(0)
    }

    companion object { const val PERCH = 0; const val TAKEOFF = 1; const val FLY = 2; const val LAND = 3; const val LEAVING = 4 }
}
