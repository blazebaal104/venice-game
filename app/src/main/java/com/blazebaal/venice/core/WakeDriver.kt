// What the boat does to the water in one wake-simulation step: its displacement pose (for the hull's moving
// footprint, the prop mound and hollow) and the foam it makes (prop wash, the seam where the stern wave closes,
// chine lines). Pure Kotlin, shared by the GPU simulation and the preview exporter.
package com.blazebaal.venice.core

import kotlin.math.*

class WakeDriver {
    var dirX = 1f; var dirY = 0f
    /** x, y, heading, displacement depth */
    val b0 = FloatArray(4)
    /** throttle, planing factor, speed */
    val b0b = FloatArray(4)
    /** up to 12 foam splats: x, y, radius, amount */
    val splats = FloatArray(48); var nSplat = 0
    private var jit = 0f

    private fun splat(x: Float, y: Float, r: Float, amount: Float) {
        if (nSplat >= 12) return
        val o = nSplat * 4; splats[o] = x; splats[o + 1] = y; splats[o + 2] = r; splats[o + 3] = amount; nSplat++
    }

    fun prepare(b: Boat, throttle: Float, dt: Float) {
        val ch = cos(b.heading); val sh = sin(b.heading)
        dirX = ch; dirY = sh
        val hl = Boat.HALF_LEN; val hb = Boat.HALF_BEAM
        val spd = abs(b.surge)
        val planing = (spd / 9f).coerceIn(0f, 1f)
        jit += dt
        val jitter = 1f + 0.08f * sin(jit * 15.7f) * sin(jit * 4.1f + 1.3f)
        val depth = if (b.airborne) 0f else 0.45f * (1f - 0.35f * planing) * jitter
        val thr = if (b.airborne) 0f else abs(throttle)
        b0[0] = b.x; b0[1] = b.y; b0[2] = b.heading; b0[3] = depth
        b0b[0] = thr; b0b[1] = planing; b0b[2] = spd; b0b[3] = 0f
        nSplat = 0
        if (b.airborne) return
        val px = b.x - ch * (hl + 0.1f); val py = b.y - sh * (hl + 0.1f)
        val perM = (0.10f + 0.22f * thr) * (0.4f + 0.6f * planing)
        val wash = perM * max(spd, 1.5f) * dt
        splat(px, py, 0.6f + 0.25f * planing, wash * 1.6f)
        splat(px - ch * 1.2f, py - sh * 1.2f, 0.9f + 0.3f * planing, wash)
        if (planing > 0.3f) splat(px - ch * 7f, py - sh * 7f, 1.2f, wash * 1.5f * planing)
        if (planing > 0.5f) for (side in intArrayOf(-1, 1)) for (k in 0..3) {
            val lx2 = -hl * (0.1f + 0.3f * k); val ly2 = side * (hb + 0.35f + 0.08f * k)
            splat(b.x + lx2 * ch - ly2 * sh, b.y + lx2 * sh + ly2 * ch, 0.6f, (planing - 0.5f) * min(spd, 20f) * dt * 0.45f)
        }
    }
}
