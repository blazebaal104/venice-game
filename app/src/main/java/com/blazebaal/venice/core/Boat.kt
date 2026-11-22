// Planing-hull boat physics: thrust, drag, side-slip, plus buoyancy on the shared wave field
// (heave / pitch / roll with gravity, so the boat rides waves, can leave the water and lands with a splash).
package com.blazebaal.venice.core

import kotlin.math.*

class Boat(var x: Float, var y: Float, var heading: Float) {
    companion object {
        const val SCALE = 1.44f            // game-size boat (another 15% up from 1.25)
        const val TOP_SPEED = 36.1f        // m/s (130 km/h)
        const val REVERSE_SPEED = 4f
        const val HALF_LEN = 2.25f * SCALE
        const val HALF_BEAM = 0.92f * SCALE
        val HULL = arrayOf(
            floatArrayOf(2.25f, 0f), floatArrayOf(1.7f, 0.6f), floatArrayOf(1.7f, -0.6f), floatArrayOf(0.6f, 0.92f), floatArrayOf(0.6f, -0.92f),
            floatArrayOf(-0.8f, 0.92f), floatArrayOf(-0.8f, -0.92f), floatArrayOf(-2.2f, 0.85f), floatArrayOf(-2.2f, -0.85f), floatArrayOf(-2.6f, 0f),
        ).map { floatArrayOf(it[0] * SCALE, it[1] * SCALE) }
    }

    var surge = 0f; var sway = 0f; var yawRate = 0f
    /** trim of the outboard, -1 (bow down) .. 1 (bow up): a little more top speed trimmed out, more grip trimmed in */
    var trim = 0f
    /** extra surface height from the simulated wake (own wake, reflections off walls), if any */
    var wakeHeight: ((Float, Float) -> Float)? = null
    // vertical dynamics
    var z = 0f; var vz = 0f
    var pitch = 0f; var pitchRate = 0f      // positive = bow up
    var roll = 0f; var rollRate = 0f        // positive = port side up
    var airborne = false
    var waveAmp = 0.08f
    // events for effects
    var bump = 0f; var splash = 0f; var wallHit = 0f
    var time = 0f
    private val posts = ArrayList<FloatArray>()

    fun speedKmh() = abs(surge) * 3.6f

    fun update(dt: Float, throttle: Float, steer: Float, world: WorldMap) {
        time += dt
        val thr = throttle.coerceIn(-1f, 1f)
        val inWater = !airborne
        // ---- horizontal
        // thrust strong at low speed (hole shot) and fading near the top: 0-100 km/h in ~7 s, 130 km/h flat out
        val top = TOP_SPEED * (1f + 0.04f * trim)
        val thrust = 8.6f * (1.15f - 0.35f * (surge / top).coerceIn(0f, 1f))
        val accel = if (!inWater) 0f else if (thr >= 0) thr * thrust else thr * (if (surge > 0.5f) 11f else 2.6f)
        val dragK = (8.6f * 0.8f - 0.06f * top) / (top * top)       // thrust = drag exactly at the top speed
        val drag = if (surge >= 0) dragK * surge * abs(surge) else 2.6f / (REVERSE_SPEED * REVERSE_SPEED) * surge * abs(surge)
        surge += (accel - drag * (if (inWater) 1f else 0.15f) - (if (surge >= 0) 0.06f else 0.3f) * surge * (if (inWater) 1f else 0.1f)) * dt
        sway += (-sway * (if (inWater) 3.4f else 0.3f) - surge * yawRate * 0.14f) * dt
        val authority = (abs(surge) / 5f).coerceIn(0f, 1f) * 0.95f * (1f - 0.1f * trim) + abs(thr) * 0.4f * (1f - (abs(surge) / 6f).coerceIn(0f, 1f))
        val dirSign = if (surge >= -0.2f) 1f else -1f
        // turn rate drops at high speed (wide, fast arcs instead of spinning on the spot)
        val target = if (inWater) steer * authority * dirSign * (1.0f - 0.55f * (abs(surge) / TOP_SPEED)) else yawRate
        yawRate += (target - yawRate) * min(1f, dt * 3.0f)
        heading += yawRate * dt
        val c = cos(heading); val s = sin(heading)
        x += (surge * c - sway * s) * dt
        y += (surge * s + sway * c) * dt
        collide(world)

        // ---- wave amplitude follows how open the water is around the boat (canals calm, lagoon choppy)
        var open = 0f
        for (k in 0 until 8) { val a = k * PI.toFloat() / 4; if (world.solid(x + cos(a) * 28f, y + sin(a) * 28f) < 0.5f) open += 1f }
        for (k in 0 until 8) { val a = k * PI.toFloat() / 4 + 0.3f; if (world.solid(x + cos(a) * 70f, y + sin(a) * 70f) < 0.5f) open += 1f }
        val targetAmp = 0.035f + 0.075f * ((open / 16f - 0.35f) / 0.65f).coerceIn(0f, 1f)
        waveAmp += (targetAmp - waveAmp) * min(1f, dt * 0.5f)

        // ---- buoyancy on four hull points (bow, stern, port, starboard)
        val L = HALF_LEN * 0.8f; val B = HALF_BEAM * 0.85f
        fun wh(lx: Float, ly: Float): Float {
            val px = x + lx * c - ly * s; val py = y + lx * s + ly * c
            return Waves.height(px, py, time, waveAmp)
        }
        val hb = wh(L, 0f); val hs = wh(-L, 0f); val hp = wh(0f, B); val hst = wh(0f, -B)
        val planing = (abs(surge) / 9f).coerceIn(0f, 1f)
        val lift = planing * (0.16f + 0.04f * trim)        // planing hulls ride higher at speed
        val hump = sin(PI.toFloat() * (abs(surge) / 8f).coerceIn(0f, 1f)) * 0.10f
        // hull point heights given current pose
        val zb = z + sin(pitch) * L; val zs = z - sin(pitch) * L
        val zp = z + sin(roll) * B; val zst = z - sin(roll) * B
        val draft = 0.258f   // equilibrium: k * draft = g, so the hull floats at the design waterline
        fun sub(hw: Float, zh: Float) = (hw + draft + lift - zh)
        val db = sub(hb, zb); val ds = sub(hs, zs); val dp = sub(hp, zp); val dst = sub(hst, zst)
        val k = 38f; val damp = 6.5f
        fun f(d: Float) = if (d > 0) min(d, 0.6f) * k else 0f
        val fb = f(db); val fs = f(ds); val fp = f(dp); val fst = f(dst)
        val wet = (if (db > 0) 1 else 0) + (if (ds > 0) 1 else 0) + (if (dp > 0) 1 else 0) + (if (dst > 0) 1 else 0)
        val wasAir = airborne
        airborne = wet == 0
        val fz = (fb + fs + fp + fst) / 4f - 9.81f - (if (wet > 0) damp * vz else 0f)
        vz += fz * dt
        z += vz * dt
        // pitch: buoyancy difference + the bow rising over the hump while getting on the plane
        val pTorque = (fb - fs) * 0.11f + (if (!airborne) hump * 9f + trim * planing * 0.5f - pitch * 6f else -0.6f) - pitchRate * (if (airborne) 0.4f else 3.2f)
        pitchRate += pTorque * dt; pitch = (pitch + pitchRate * dt).coerceIn(-0.35f, 0.45f)
        val rTorque = (fp - fst) * 0.16f + (yawRate * surge * 0.05f).coerceIn(-0.2f, 0.2f) * 6f - roll * 5f - rollRate * (if (airborne) 0.4f else 3.5f)
        rollRate += rTorque * dt; roll = (roll + rollRate * dt).coerceIn(-0.4f, 0.4f)
        if (wasAir && !airborne && vz < -1.2f) { splash = max(splash, -vz); bump = max(bump, -vz * 0.6f) }
        if (!airborne && vz < -2.5f) vz *= 0.6f
        bump *= exp(-dt * 4f); splash *= exp(-dt * 6f); wallHit *= exp(-dt * 6f)
    }

    private fun collide(world: WorldMap) {
        val c = cos(heading); val s = sin(heading)
        var hit = 0f
        for (iter in 0 until 6) {
            var pushX = 0f; var pushY = 0f; var deepest = 0f
            for (p in HULL) {
                val wx = x + p[0] * c - p[1] * s; val wy = y + p[0] * s + p[1] * c
                val sol = world.solid(wx, wy)
                if (sol > 0.5f) {
                    var (gx, gy) = world.toWater(wx, wy)
                    if (hypot(gx, gy) < 1e-3f) { gx = x - wx; gy = y - wy }
                    val l = hypot(gx, gy).coerceAtLeast(1e-4f)
                    val depth = (sol - 0.5f) * 1.2f + 0.05f
                    pushX += gx / l * depth; pushY += gy / l * depth
                    deepest = max(deepest, depth)
                }
            }
            world.posts(x, y, 8f, posts)
            for (pp in posts) for (p in HULL) {
                val wx = x + p[0] * c - p[1] * s; val wy = y + p[0] * s + p[1] * c
                val dx = wx - pp[0]; val dy = wy - pp[1]; val d = hypot(dx, dy)
                if (d < 0.75f && d > 1e-4f) { pushX += dx / d * (0.75f - d); pushY += dy / d * (0.75f - d); deepest = max(deepest, 0.75f - d) }
            }
            for (o in Obstacles.stops) {
                // oriented box: x, y, rotation, half length, half width
                if (abs(o[0] - x) > o[3] + 9f || abs(o[1] - y) > o[3] + 9f) continue
                val oc = cos(o[2]); val os = sin(o[2])
                for (p in HULL) {
                    val wx = x + p[0] * c - p[1] * s; val wy = y + p[0] * s + p[1] * c
                    val dx = wx - o[0]; val dy = wy - o[1]
                    val lx = dx * oc + dy * os; val ly = -dx * os + dy * oc
                    val px = o[3] - abs(lx); val py = o[4] - abs(ly)
                    if (px <= 0f || py <= 0f) continue
                    if (px < py) { val sgn = if (lx >= 0) 1f else -1f; pushX += oc * sgn * px; pushY += os * sgn * px; deepest = max(deepest, px) }
                    else { val sgn = if (ly >= 0) 1f else -1f; pushX += -os * sgn * py; pushY += oc * sgn * py; deepest = max(deepest, py) }
                }
            }
            if (deepest <= 0f) break
            val pl = hypot(pushX, pushY).coerceAtLeast(1e-4f)
            val nx = pushX / pl; val ny = pushY / pl
            x += nx * min(deepest, 0.5f); y += ny * min(deepest, 0.5f)
            val vx = surge * c - sway * s; val vy = surge * s + sway * c
            val vn = vx * nx + vy * ny
            if (vn < 0) {
                hit = max(hit, -vn)
                val rvx = vx - 1.35f * vn * nx; val rvy = vy - 1.35f * vn * ny
                surge = (rvx * c + rvy * s) * 0.75f; sway = (-rvx * s + rvy * c) * 0.75f
                yawRate += (nx * s - ny * c) * vn * 0.08f
            }
        }
        if (hit > 0f) { bump = max(bump, hit); wallHit = max(wallHit, hit) }
    }
}
