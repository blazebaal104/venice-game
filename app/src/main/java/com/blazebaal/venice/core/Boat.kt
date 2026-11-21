// Arcade planing-hull physics for a 4.5 m runabout with a 40 hp outboard. Pure Kotlin.
package com.blazebaal.venice.core

import kotlin.math.*

class Boat(var x: Float, var y: Float, var heading: Float) {
    // body-frame velocities
    var surge = 0f   // forward m/s
    var sway = 0f    // sideways m/s (positive = to port)
    var yawRate = 0f // rad/s
    // presentation
    var pitch = 0f; var roll = 0f; var heave = 0f
    var bump = 0f    // last impact strength, for camera shake / effects
    private var time = 0f
    private var prevSurge = 0f
    private val posts = ArrayList<FloatArray>()

    companion object {
        const val TOP_SPEED = 16.5f      // m/s (~59 km/h)
        const val REVERSE_SPEED = 3.5f
        const val LENGTH = 4.5f; const val BEAM = 1.85f
        // hull outline sample points (body frame: +x forward, +y port), relative to the hull centre
        val HULL = arrayOf(
            floatArrayOf(2.25f, 0f), floatArrayOf(1.7f, 0.6f), floatArrayOf(1.7f, -0.6f), floatArrayOf(0.6f, 0.92f), floatArrayOf(0.6f, -0.92f),
            floatArrayOf(-0.8f, 0.92f), floatArrayOf(-0.8f, -0.92f), floatArrayOf(-2.2f, 0.85f), floatArrayOf(-2.2f, -0.85f), floatArrayOf(-2.6f, 0f),
        )
    }

    fun speedKmh() = abs(surge) * 3.6f

    /** throttle -1..1 (negative = brake/reverse), steer -1..1 (positive = turn left / port). */
    fun update(dt: Float, throttle: Float, steer: Float, world: WorldMap) {
        time += dt
        val thr = throttle.coerceIn(-1f, 1f)
        // thrust: full forward accel ~4.2 m/s², drag balances at TOP_SPEED
        val accel = if (thr >= 0) thr * 4.2f else thr * (if (surge > 0.5f) 7f else 2.2f)
        val drag = if (surge >= 0) 4.2f / (TOP_SPEED * TOP_SPEED) * surge * abs(surge) else 2.2f / (REVERSE_SPEED * REVERSE_SPEED) * surge * abs(surge)
        val idleDrag = 0.35f * surge
        surge += (accel - drag - idleDrag) * dt
        // lateral: the vee bottom resists side-slip, with a little drift at speed
        sway += (-sway * 3.2f - surge * yawRate * 0.12f) * dt
        // yaw: outboard thrust vectoring -> turning works with speed and with throttle
        val authority = (abs(surge) / 5f).coerceIn(0f, 1f) * 0.95f + abs(thr) * 0.35f * (1f - (abs(surge) / 6f).coerceIn(0f, 1f))
        val dirSign = if (surge >= -0.2f) 1f else -1f
        val target = steer * authority * dirSign * (1.05f - 0.35f * (abs(surge) / TOP_SPEED))
        yawRate += (target - yawRate) * min(1f, dt * 3.2f)
        heading += yawRate * dt
        val c = cos(heading); val s = sin(heading)
        x += (surge * c - sway * s) * dt
        y += (surge * s + sway * c) * dt

        collide(world)

        // presentation: bow rise while getting on the plane, squat on accel, lean into turns, chop
        val planing = (abs(surge) / 9f).coerceIn(0f, 1f)
        val acc = (surge - prevSurge) / max(dt, 1e-3f); prevSurge = surge
        val hump = sin(PI.toFloat() * (abs(surge) / 9f).coerceIn(0f, 1f)) * 0.09f // bow lifts most around hump speed
        val targetPitch = hump + 0.025f * planing - (acc * 0.008f).coerceIn(-0.05f, 0.05f)
        pitch += (targetPitch - pitch) * min(1f, dt * 2.5f)
        val targetRoll = (yawRate * surge * 0.045f).coerceIn(-0.22f, 0.22f)
        roll += (targetRoll - roll) * min(1f, dt * 3f)
        val chop = 0.035f + 0.02f * planing
        heave = sin(time * 1.9f) * chop + sin(time * 3.7f + 1.3f) * chop * 0.5f + planing * 0.06f
        bump *= exp(-dt * 4f)
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
                    val gl = hypot(gx, gy)
                    if (gl < 1e-3f) { gx = x - wx; gy = y - wy } // deep inside: push towards hull centre
                    val l = hypot(gx, gy).coerceAtLeast(1e-4f)
                    val depth = (sol - 0.5f) * 1.2f + 0.05f
                    pushX += gx / l * depth; pushY += gy / l * depth
                    deepest = max(deepest, depth)
                }
            }
            // mooring poles (bricole): solid circles
            world.posts(x, y, 6f, posts)
            for (pp in posts) {
                for (p in HULL) {
                    val wx = x + p[0] * c - p[1] * s; val wy = y + p[0] * s + p[1] * c
                    val dx = wx - pp[0]; val dy = wy - pp[1]; val d = hypot(dx, dy)
                    if (d < 0.75f && d > 1e-4f) { pushX += dx / d * (0.75f - d); pushY += dy / d * (0.75f - d); deepest = max(deepest, 0.75f - d) }
                }
            }
            if (deepest <= 0f) break
            val pl = hypot(pushX, pushY).coerceAtLeast(1e-4f)
            val nx = pushX / pl; val ny = pushY / pl
            x += nx * min(deepest, 0.5f); y += ny * min(deepest, 0.5f)
            // velocity response: kill the component into the wall, keep some slide
            val vx = surge * c - sway * s; val vy = surge * s + sway * c
            val vn = vx * nx + vy * ny
            if (vn < 0) {
                hit = max(hit, -vn)
                val rvx = vx - (1.35f) * vn * nx; val rvy = vy - (1.35f) * vn * ny // small bounce
                val fr = 0.75f
                surge = (rvx * c + rvy * s) * fr
                sway = (-rvx * s + rvy * c) * fr
                yawRate += (nx * s - ny * c) * vn * 0.08f
            }
        }
        if (hit > 0f) bump = max(bump, hit)
    }
}
