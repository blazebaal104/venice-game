// Boat spray simulated on the CPU: bow sheets breaking into droplets, the rooster tail thrown up behind the outboard,
// landing splashes and wall impacts. Drawn as instanced, velocity-stretched sprites.
package com.blazebaal.venice.core

import kotlin.math.*
import kotlin.random.Random

class Effects {
    companion object { const val MAX_P = 4500 }
    // ---- droplets: x y z vx vy vz life maxLife size
    val p = FloatArray(MAX_P * 9)
    var count = 0
    private val rnd = Random(7)
    private var sprayAcc = 0f; private var roosterAcc = 0f; private var mistAcc = 0f

    private fun emit(x: Float, y: Float, z: Float, vx: Float, vy: Float, vz: Float, life: Float, size: Float) {
        if (count >= MAX_P) return
        val o = count * 9
        p[o] = x; p[o + 1] = y; p[o + 2] = z; p[o + 3] = vx; p[o + 4] = vy; p[o + 5] = vz; p[o + 6] = life; p[o + 7] = life; p[o + 8] = size
        count++
    }
    private fun r() = rnd.nextFloat()

    fun update(dt: Float, b: Boat, throttle: Float) {
        if (dt <= 0f) return
        val c = cos(b.heading); val s = sin(b.heading)
        val spd = abs(b.surge)
        val vx = b.surge * c - b.sway * s; val vy = b.surge * s + b.sway * c
        val thr = abs(throttle)
        if (!b.airborne && spd > 3f) {
            // bow spray: water peels off both chines in the forward third, thrown out, up and back in thin sheets
            sprayAcc += dt * min(spd * 24f + abs(b.vz) * 60f, 700f)
            while (sprayAcc > 1f) {
                sprayAcc -= 1f
                val side = if (rnd.nextBoolean()) 1f else -1f
                val lx = Boat.HALF_LEN * (0.05f + r() * 0.5f); val ly = side * Boat.HALF_BEAM * (0.9f + r() * 0.15f)
                val wx = b.x + lx * c - ly * s; val wy = b.y + lx * s + ly * c
                val nx = -s * side; val ny = c * side
                val outV = 1.0f + min(spd, 22f) * (0.12f + 0.16f * r())
                val back = 0.35f + 0.35f * r()
                val up = 0.8f + min(spd, 24f) * (0.08f + 0.12f * r())
                emit(wx, wy, b.z + 0.05f, vx * back + nx * outV, vy * back + ny * outV, up, 0.5f + r() * 0.6f, 0.022f + r() * 0.045f)
                if (r() < 0.10f) emit(wx, wy, b.z + 0.2f, vx * 0.3f + nx * outV * 0.5f, vy * 0.3f + ny * outV * 0.5f, up * 0.5f, 1.0f + r(), 0.28f + r() * 0.2f)
            }
        }
        // rooster tail: the outboard's lower unit throws a column of water up and back; it arcs over and falls into
        // the wash behind the boat. Taller, longer and denser the faster you go (and the harder the throttle).
        if (!b.airborne && spd > 5f && thr > 0.2f) {
            val k = ((spd - 5f) / 31f).coerceIn(0f, 1f)           // 0 at 18 km/h .. 1 at 130 km/h
            val lx = -Boat.HALF_LEN - 0.35f * Boat.SCALE
            val bx = b.x + lx * c; val by = b.y + lx * s
            roosterAcc += dt * (110f + 520f * k) * (0.5f + 0.5f * thr)
            while (roosterAcc > 1f) {
                roosterAcc -= 1f
                val lat = (r() - 0.5f) * (0.25f + 0.35f * k)
                // the jet leaves the lower unit backwards relative to the boat, so in the world it keeps most of the
                // boat's speed: the arc trails 3..9 m behind the motor and falls back into the wash
                val keep = 0.93f - 0.13f * k - 0.05f * r()
                val vz = (1.2f + 5.0f * k) * (0.7f + 0.45f * r()) * (0.6f + 0.4f * thr)
                val side = (r() - 0.5f) * (0.5f + 1.2f * k)
                emit(bx - s * lat, by + c * lat, b.z - 0.05f,
                    vx * keep - s * side, vy * keep + c * side, vz,
                    0.45f + 0.5f * r() * (0.5f + k), 0.04f + r() * (0.06f + 0.08f * k))
            }
            // the thick white base of the column and a veil of mist drifting off the top
            mistAcc += dt * (10f + 45f * k)
            while (mistAcc > 1f) {
                mistAcc -= 1f
                val keep = 0.85f - 0.1f * k
                emit(bx + (r() - 0.5f) * 0.4f, by + (r() - 0.5f) * 0.4f, b.z + 0.1f,
                    vx * keep, vy * keep, (0.8f + 3.5f * k) * (0.6f + 0.6f * r()),
                    0.7f + r() * 0.6f, 0.32f + r() * (0.25f + 0.3f * k))
            }
        }
        // landing splash
        if (b.splash > 1.5f) {
            val n = (b.splash * 40).toInt().coerceAtMost(260)
            for (i in 0 until n) {
                val a = r() * 6.283f; val rr = Boat.HALF_BEAM * (0.6f + r() * 0.8f)
                val ox = cos(a) * rr; val oy = sin(a) * rr * 1.8f
                emit(b.x + ox * c - oy * s, b.y + ox * s + oy * c, b.z, vx * 0.4f + cos(a) * 2.5f, vy * 0.4f + sin(a) * 2.5f, 2f + r() * b.splash * 0.9f, 0.7f + r() * 0.7f, 0.2f + r() * 0.25f)
            }
            b.splash = 0f
        }
        // wall impact spray
        if (b.wallHit > 2f) {
            for (i in 0 until (b.wallHit * 20).toInt().coerceAtMost(160)) {
                val lx = Boat.HALF_LEN * 0.9f
                emit(b.x + lx * c, b.y + lx * s, b.z + 0.3f, -vx * 0.2f + (r() - 0.5f) * 4f, -vy * 0.2f + (r() - 0.5f) * 4f, 1.5f + r() * 3f, 0.6f + r() * 0.5f, 0.15f + r() * 0.2f)
            }
            b.wallHit = 0f
        }
        // integrate droplets
        var i = 0
        val dragK = exp(-dt * 1.1f)
        while (i < count) {
            val o = i * 9
            p[o + 6] -= dt
            if (p[o + 6] <= 0f || (p[o + 2] < -0.25f && p[o + 5] < 0f)) {
                val last = (count - 1) * 9
                for (k in 0 until 9) p[o + k] = p[last + k]
                count--; continue
            }
            p[o + 5] -= 9.81f * dt
            p[o + 3] *= dragK; p[o + 4] *= dragK
            p[o] += p[o + 3] * dt; p[o + 1] += p[o + 4] * dt; p[o + 2] += p[o + 5] * dt
            i++
        }
    }

    /** One instance per droplet: x, y, z, vx, vy, vz, life fraction, size. Returns the instance count. */
    fun buildInstances(out: FloatArray): Int {
        val n = min(count, out.size / 8)
        for (i in 0 until n) {
            val b = i * 9; val o = i * 8
            out[o] = p[b]; out[o + 1] = p[b + 1]; out[o + 2] = p[b + 2]
            out[o + 3] = p[b + 3]; out[o + 4] = p[b + 4]; out[o + 5] = p[b + 5]
            out[o + 6] = p[b + 6] / p[b + 7]; out[o + 7] = p[b + 8]
        }
        return n
    }
}
