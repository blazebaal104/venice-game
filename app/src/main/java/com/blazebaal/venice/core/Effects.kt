// Boat effects simulated on the CPU: spray/splash droplets, propeller foam and a Kelvin (V-shaped) wake.
package com.blazebaal.venice.core

import kotlin.math.*
import kotlin.random.Random

class Effects {
    companion object { const val MAX_WAKE = 240 }
    // ---- droplets: x y z vx vy vz life maxLife size
    val maxP = 2400
    val p = FloatArray(maxP * 9)
    var count = 0
    private val rnd = Random(7)

    // ---- wake: each emitted sample has a centre track point plus two arms that spread sideways (Kelvin angle ~19.5°)
    class WakePt(var x: Float, var y: Float, var nx: Float, var ny: Float, var spread: Float, var age: Float, var strength: Float, var width: Float)
    val wake = ArrayDeque<WakePt>()
    private var wakeTimer = 0f
    private var sprayAcc = 0f

    private fun emit(x: Float, y: Float, z: Float, vx: Float, vy: Float, vz: Float, life: Float, size: Float) {
        if (count >= maxP) return
        val o = count * 9
        p[o] = x; p[o + 1] = y; p[o + 2] = z; p[o + 3] = vx; p[o + 4] = vy; p[o + 5] = vz; p[o + 6] = life; p[o + 7] = life; p[o + 8] = size
        count++
    }

    fun update(dt: Float, b: Boat, throttle: Float) {
        val c = cos(b.heading); val s = sin(b.heading)
        val spd = abs(b.surge)
        val wv = b.surge  // forward velocity in world
        val vx = wv * c - b.sway * s; val vy = wv * s + b.sway * c
        // bow spray: water peels off both chines in the forward third and is thrown out, up and back in thin sheets,
        // breaking into droplets; a little fine mist hangs behind it
        if (!b.airborne && spd > 3f) {
            sprayAcc += dt * (spd * 26f + abs(b.vz) * 60f)
            while (sprayAcc > 1f) {
                sprayAcc -= 1f
                val side = if (rnd.nextBoolean()) 1f else -1f
                val lx = Boat.HALF_LEN * (0.05f + rnd.nextFloat() * 0.5f); val ly = side * Boat.HALF_BEAM * (0.9f + rnd.nextFloat() * 0.15f)
                val wx = b.x + lx * c - ly * s; val wy = b.y + lx * s + ly * c
                val nx = -s * side; val ny = c * side
                val outV = 1.0f + spd * (0.12f + 0.16f * rnd.nextFloat())
                val back = 0.35f + 0.35f * rnd.nextFloat()           // fraction of hull speed kept (droplets lag behind)
                val up = 0.8f + spd * (0.08f + 0.12f * rnd.nextFloat())
                emit(wx, wy, b.z + 0.05f, vx * back + nx * outV, vy * back + ny * outV, up, 0.5f + rnd.nextFloat() * 0.6f, 0.035f + rnd.nextFloat() * 0.07f)
                if (rnd.nextFloat() < 0.12f) emit(wx, wy, b.z + 0.2f, vx * 0.3f + nx * outV * 0.5f, vy * 0.3f + ny * outV * 0.5f, up * 0.5f, 1.0f + rnd.nextFloat(), 0.28f + rnd.nextFloat() * 0.2f)
            }
            // rooster tail from the outboard at speed
            if (spd > 7f && rnd.nextFloat() < 0.8f) {
                val lx = -Boat.HALF_LEN - 0.5f
                emit(b.x + lx * c, b.y + lx * s, b.z, vx * 0.25f + (rnd.nextFloat() - 0.5f) * 1.2f, vy * 0.25f + (rnd.nextFloat() - 0.5f) * 1.2f, 1.5f + spd * 0.12f * rnd.nextFloat(), 0.5f + rnd.nextFloat() * 0.4f, 0.04f + rnd.nextFloat() * 0.06f)
            }
        }
        // landing splash
        if (b.splash > 1.5f) {
            val n = (b.splash * 40).toInt().coerceAtMost(220)
            for (i in 0 until n) {
                val a = rnd.nextFloat() * 6.283f; val r = Boat.HALF_BEAM * (0.6f + rnd.nextFloat() * 0.8f)
                val ox = cos(a) * r; val oy = sin(a) * r * 1.8f
                emit(b.x + ox * c - oy * s, b.y + ox * s + oy * c, b.z, vx * 0.4f + cos(a) * 2.5f, vy * 0.4f + sin(a) * 2.5f, 2f + rnd.nextFloat() * b.splash * 0.9f, 0.7f + rnd.nextFloat() * 0.7f, 0.2f + rnd.nextFloat() * 0.25f)
            }
            b.splash = 0f
        }
        // wall impact spray
        if (b.wallHit > 2f) {
            for (i in 0 until (b.wallHit * 20).toInt().coerceAtMost(120)) {
                val lx = Boat.HALF_LEN * 0.9f
                emit(b.x + lx * c, b.y + lx * s, b.z + 0.3f, -vx * 0.2f + (rnd.nextFloat() - 0.5f) * 4f, -vy * 0.2f + (rnd.nextFloat() - 0.5f) * 4f, 1.5f + rnd.nextFloat() * 3f, 0.6f + rnd.nextFloat() * 0.5f, 0.15f + rnd.nextFloat() * 0.2f)
            }
            b.wallHit = 0f
        }
        // propeller wash: churned foam blobs just behind the outboard
        if (!b.airborne && abs(throttle) > 0.15f) {
            val n = if (rnd.nextFloat() < abs(throttle) * 0.9f) 2 else 0
            for (i in 0 until n) {
                val lx = -Boat.HALF_LEN - 0.6f
                emit(b.x + lx * c + (rnd.nextFloat() - 0.5f) * 0.5f, b.y + lx * s + (rnd.nextFloat() - 0.5f) * 0.5f, b.z + 0.02f, -vx * 0.2f, -vy * 0.2f, 0.5f + rnd.nextFloat() * 1.2f * abs(throttle), 0.35f + rnd.nextFloat() * 0.3f, 0.12f + rnd.nextFloat() * 0.12f)
            }
        }
        // integrate droplets
        var i = 0
        while (i < count) {
            val o = i * 9
            p[o + 6] -= dt
            if (p[o + 6] <= 0f || p[o + 2] < -0.3f) {
                val last = (count - 1) * 9
                for (k in 0 until 9) p[o + k] = p[last + k]
                count--; continue
            }
            p[o + 5] -= 9.81f * dt
            val dragK = exp(-dt * 1.4f)
            p[o + 3] *= dragK; p[o + 4] *= dragK
            p[o] += p[o + 3] * dt; p[o + 1] += p[o + 4] * dt; p[o + 2] += p[o + 5] * dt
            i++
        }
        // wake samples
        wakeTimer -= dt
        if (!b.airborne && spd > 0.8f && wakeTimer <= 0f) {
            wakeTimer = 0.06f
            val lx = -Boat.HALF_LEN
            wake.addLast(WakePt(b.x + lx * c, b.y + lx * s, -s, c, Boat.HALF_BEAM, 0f, (spd / 10f).coerceIn(0.15f, 1.2f), Boat.HALF_BEAM * 1.3f))
            while (wake.size > MAX_WAKE) wake.removeFirst()
        }
        for (w in wake) {
            w.age += dt
            w.spread += dt * (0.35f * w.strength * 9f).coerceIn(0.5f, 4.5f)   // arms move outward ~ tan(19.5°) * speed
            w.width += dt * 0.9f
        }
        while (wake.isNotEmpty() && wake.first().age > 14f) wake.removeFirst()
    }

    /** Wake ribbons as triangles (x, y, z, along, across, intensity): centre track + two spreading arms. Returns floats written. */
    fun buildWake(t: Float, amp: Float, out: FloatArray): Int {
        var o = 0
        val pts = wake.toList()
        if (pts.size < 2) return 0
        for (arm in -1..1) {
            for (i in 0 until pts.size - 1) {
                if (o + 36 > out.size) break
                val a = pts[i]; val b = pts[i + 1]
                val fadeA = (1f - a.age / 14f).coerceIn(0f, 1f) * a.strength; val fadeB = (1f - b.age / 14f).coerceIn(0f, 1f) * b.strength
                val off = arm.toFloat()
                val axC = a.x + a.nx * a.spread * off; val ayC = a.y + a.ny * a.spread * off
                val bxC = b.x + b.nx * b.spread * off; val byC = b.y + b.ny * b.spread * off
                val wa = if (arm == 0) a.width * 0.6f else 0.45f + a.age * 0.22f; val wb = if (arm == 0) b.width * 0.6f else 0.45f + b.age * 0.22f
                val ia = if (arm == 0) fadeA * 1.2f else fadeA * fadeA * 0.85f; val ib = if (arm == 0) fadeB * 1.2f else fadeB * fadeB * 0.85f
                val za = Waves.height(axC, ayC, t, amp) + 0.05f; val zb = Waves.height(bxC, byC, t, amp) + 0.05f
                val a1x = axC - a.nx * wa; val a1y = ayC - a.ny * wa; val a2x = axC + a.nx * wa; val a2y = ayC + a.ny * wa
                val b1x = bxC - b.nx * wb; val b1y = byC - b.ny * wb; val b2x = bxC + b.nx * wb; val b2y = byC + b.ny * wb
                val v = floatArrayOf(
                    a1x, a1y, za, i.toFloat(), -1f, ia, b1x, b1y, zb, i + 1f, -1f, ib, b2x, b2y, zb, i + 1f, 1f, ib,
                    a1x, a1y, za, i.toFloat(), -1f, ia, b2x, b2y, zb, i + 1f, 1f, ib, a2x, a2y, za, i.toFloat(), 1f, ia)
                v.copyInto(out, o); o += v.size
            }
        }
        return o
    }

    /** Spray as camera-facing quads stretched along velocity: per vertex (x,y,z, vx,vy,vz, lifeFraction, size, cornerX, cornerY). */
    fun buildSpray(out: FloatArray): Int {
        var o = 0
        val cx = floatArrayOf(-1f, 1f, 1f, -1f, 1f, -1f); val cy = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f)
        for (i in 0 until count) {
            if (o + 60 > out.size) break
            val b = i * 9
            val life = p[b + 6] / p[b + 7]
            for (k in 0 until 6) {
                out[o++] = p[b]; out[o++] = p[b + 1]; out[o++] = p[b + 2]
                out[o++] = p[b + 3]; out[o++] = p[b + 4]; out[o++] = p[b + 5]
                out[o++] = life; out[o++] = p[b + 8]; out[o++] = cx[k]; out[o++] = cy[k]
            }
        }
        return o
    }
}
