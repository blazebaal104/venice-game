// Real wake: a 2D wave-equation heightfield (160 m x 160 m, 0.5 m cells, stepped at 30 Hz) that follows the boat.
// The hull pushes water as it moves (volume-displacement source), so the V-shaped Kelvin-like wake, the bow wave,
// stern waves and their reflections off quay walls all come out of the simulation instead of being drawn.
// A second field carries foam: propeller wash, water peeling off the hull at speed and breaking crests; foam stays
// where it was made (it doesn't follow the boat) and fades over several seconds.
package com.blazebaal.venice.core

import kotlin.math.*

class WakeSim(val n: Int = 320, val cell: Float = 0.5f) {
    val size = n * cell
    /** world position of the grid's (0,0) corner, always on whole cells */
    var ox = 0f; var oy = 0f
    var u = FloatArray(n * n); private var up = FloatArray(n * n); private var un = FloatArray(n * n)
    val foam = FloatArray(n * n)
    /** aerated "milk": the pale turquoise scar left once the white foam has burst; lasts ~25 s and widens slowly */
    val aer = FloatArray(n * n)
    /** 1 within ~3 m of the moving hull: churned, broken surface (no mirror next to a hull at speed) */
    private val near = FloatArray(n * n)
    private var jit = 0f
    /** heading of the boat (for streaking the foam along the wash) */
    var dirX = 1f; var dirY = 0f
    private val occ = FloatArray(n * n); private val occPrev = FloatArray(n * n); private val lapA = FloatArray(n * n)
    private val solid = ByteArray(n * n)
    private var started = false
    private var acc = 0f
    private val c = 3.6f                     // wave speed (m/s): gives the ~19° wake half-angle at planing speed
    private val stepDt = 1f / 30f
    /** true when the field changed since the last upload */
    var dirty = true

    private fun recentre(bx0: Float, by0: Float, world: WorldMap) {
        val bx = bx0 - dirX * 45f; val by = by0 - dirY * 45f          // keep more of the trail than of the water ahead
        val nx = floor((bx - size / 2) / cell) * cell; val ny = floor((by - size / 2) / cell) * cell
        if (!started) {
            ox = nx; oy = ny; started = true; refreshSolid(world, 0, 0, n, n); return
        }
        val dx = ((nx - ox) / cell).roundToInt(); val dy = ((ny - oy) / cell).roundToInt()
        if (abs(dx) < 24 && abs(dy) < 24) return
        // shift every field by (dx, dy) cells; new cells start calm
        for (arr in arrayOf(u, up, foam, aer, occPrev)) shift(arr, dx, dy)
        ox += dx * cell; oy += dy * cell
        refreshSolid(world, 0, 0, n, n)
    }

    private fun shift(a: FloatArray, dx: Int, dy: Int) {
        val t = FloatArray(n * n)
        for (j in 0 until n) {
            val sj = j + dy; if (sj < 0 || sj >= n) continue
            for (i in 0 until n) { val si = i + dx; if (si < 0 || si >= n) continue; t[j * n + i] = a[sj * n + si] }
        }
        t.copyInto(a)
    }

    private fun refreshSolid(world: WorldMap, i0: Int, j0: Int, i1: Int, j1: Int) {
        for (j in j0 until j1) for (i in i0 until i1)
            solid[j * n + i] = if (world.solid(ox + (i + 0.5f) * cell, oy + (j + 0.5f) * cell) > 0.5f) 1 else 0
    }

    /** Height of the simulated wake at a world point (0 outside the grid). Bilinear. */
    fun height(x: Float, y: Float): Float {
        val fx = (x - ox) / cell - 0.5f; val fy = (y - oy) / cell - 0.5f
        val i = floor(fx).toInt(); val j = floor(fy).toInt()
        if (i < 0 || j < 0 || i >= n - 1 || j >= n - 1) return 0f
        val tx = fx - i; val ty = fy - j
        val a = u[j * n + i]; val b = u[j * n + i + 1]; val cc = u[(j + 1) * n + i]; val d = u[(j + 1) * n + i + 1]
        return (a * (1 - tx) + b * tx) * (1 - ty) + (cc * (1 - tx) + d * tx) * ty
    }

    fun update(dt: Float, boat: Boat, throttle: Float, world: WorldMap) {
        recentre(boat.x, boat.y, world)
        acc += min(dt, 0.1f)
        while (acc >= stepDt) { acc -= stepDt; step(stepDt, boat, throttle); dirty = true }
    }

    private var thrMem = 0f
    private fun step(dt: Float, b: Boat, throttle: Float) {
        // ---- hull occupancy (smooth displacement depth) for the current pose
        occ.fill(0f); near.fill(0f)
        jit += dt; val jitter = 1f + 0.08f * sin(jit * 15.7f) * sin(jit * 4.1f + 1.3f)   // successive crests vary
        val ch = cos(b.heading); val sh = sin(b.heading)
        val hl = Boat.HALF_LEN; val hb = Boat.HALF_BEAM
        val spd = abs(b.surge)
        val planing = (spd / 9f).coerceIn(0f, 1f)
        val depth = if (b.airborne) 0f else 0.45f * (1f - 0.35f * planing) * jitter   // planing hulls displace less, mostly aft
        val r = hl + 7f
        val ci = ((b.x - ox) / cell).toInt(); val cj = ((b.y - oy) / cell).toInt(); val rc = (r / cell).toInt() + 1
        if (depth > 0f) for (j in max(1, cj - rc)..min(n - 2, cj + rc)) for (i in max(1, ci - rc)..min(n - 2, ci + rc)) {
            val wx = ox + (i + 0.5f) * cell - b.x; val wy = oy + (j + 0.5f) * cell - b.y
            val lx = wx * ch + wy * sh; val ly = -wx * sh + wy * ch
            // bow narrows: half-beam shrinks towards the front; at speed the wetted area moves aft
            val t = (lx / hl).coerceIn(-1f, 1f)
            val beam = hb * (if (t > 0) sqrt(max(0f, 1f - t * t * t)) else 1f)
            // behind the transom: the outboard throws water up into a mound right behind the motor, and the water
            // falls into a hollow a few metres further back before the stern wave closes in
            if (lx < -hl) {
                val bk = -hl - lx                                        // metres behind the transom
                val latM = (1f - (ly / (hb * 0.45f)).let { it * it }).coerceAtLeast(0f)    // narrow boil right behind the leg
                val latH = (1f - (ly / (hb * 0.95f)).let { it * it }).coerceAtLeast(0f)
                if (latH <= 0f || bk > 6.5f) continue
                val thr = abs(throttle)
                val mound = (0.05f + 0.10f * planing) * thr * (if (bk < 2f) sin(PI.toFloat() * bk / 2f) else 0f) * latM
                val hollow = (0.07f + 0.10f * planing) * thr * (if (bk in 2f..6.5f) sin(PI.toFloat() * (bk - 2f) / 4.5f) else 0f) * latH
                occ[j * n + i] = hollow - mound
                continue
            }
            val ex = abs(lx) - hl; val ey = abs(ly) - beam
            val out = max(ex, ey)
            if (spd > 2f && out < 3f) near[j * n + i] = max(near[j * n + i], (1f - max(out, 0f) / 3f) * min(1f, spd / 8f))
            if (out > 0.4f) continue
            val aft = 1f - planing * 0.6f * max(0f, t)
            val inside = (1f - (ly / (beam + 0.8f)).let { it * it }).coerceAtLeast(0f) * (1f - t * t * 0.3f)
            val soft = if (out > 0f) (1f - out / 0.4f).let { it * it } else 1f
            occ[j * n + i] = depth * inside * aft * soft
        }
        // ---- wave equation with weak (gravity-like) dispersion, viscosity, walls and a sponge border:
        //   u_tt = c^2 lap(u) + beta lap(lap(u)) + nu lap(u_t)
        // the dispersion makes short waves lag behind, so each wake arm trails a train of smaller crests
        val k = (c * dt / cell).let { it * it }
        val beta = 0.10f * dt * dt / (cell * cell * cell * cell)       // stronger dispersion: feathered, en-echelon crests
        val nu = 0.006f
        for (j in 1 until n - 1) {
            val row = j * n
            for (i in 1 until n - 1) { val id = row + i; lapA[id] = u[id - 1] + u[id + 1] + u[id - n] + u[id + n] - 4f * u[id] }
        }
        val sponge = 16
        for (j in 1 until n - 1) {
            val row = j * n
            for (i in 1 until n - 1) {
                val id = row + i
                if (solid[id].toInt() == 1) { un[id] = 0f; continue }
                val lap = lapA[id]
                val lap2 = lapA[id - 1] + lapA[id + 1] + lapA[id - n] + lapA[id + n] - 4f * lap
                val lapPrev = up[id - 1] + up[id + 1] + up[id - n] + up[id + n] - 4f * up[id]
                var v = 2f * u[id] - up[id] + k * lap + beta * lap2 + nu * (lap - lapPrev)
                // the moving hull displaces water: the change of its footprint is added as a height displacement
                // (volume-conserving dipole: down where the hull arrives, up where it leaves) - no velocity kick, so the
                // field never pumps energy; the bow wave and the V wake radiate from it
                val dOcc = occ[id] - occPrev[id]
                v -= dOcc; u[id] -= dOcc
                val edge = min(min(i, j), min(n - 1 - i, n - 1 - j))
                val damp = if (edge < sponge) 0.86f + 0.1367f * edge / sponge else 0.9967f   // the V fades over ~10 s
                v *= damp
                un[id] = v
            }
        }
        val t0 = up; up = u; u = un; un = t0
        occ.copyInto(occPrev)
        // ---- foam sources
        if (!b.airborne) {
            // propeller wash: a churned strip about a hull-width wide right behind the outboard (the boil), which the
            // shader breaks into lace as it ages; amount per metre travelled so the strip has the same density at any speed
            val thr = abs(throttle)
            dirX = ch; dirY = sh
            val px = b.x - ch * (hl + 0.1f); val py = b.y - sh * (hl + 0.1f)
            val perM = (0.10f + 0.22f * thr) * (0.4f + 0.6f * planing)
            val wash = perM * max(spd, 1.5f) * dt
            // densest on the mound right behind the motor, thinner over the hollow, then a white seam where the
            // stern wave closes in
            splat(px, py, 0.6f + 0.25f * planing, wash * 1.6f)
            splat(px - ch * 1.2f, py - sh * 1.2f, 0.9f + 0.3f * planing, wash)
            if (planing > 0.3f) splat(px - ch * 7f, py - sh * 7f, 1.2f, wash * 1.5f * planing)
            // thin white lines peeling off both chines where the spray sheet lands, only when planing
            if (planing > 0.5f) for (side in intArrayOf(-1, 1)) {
                for (k in 0..3) {     // a continuous line along the aft half of the hull, where the spray sheet lands
                    val lx2 = -hl * (0.1f + 0.3f * k); val ly2 = side * (hb + 0.35f + 0.08f * k)
                    splat(b.x + lx2 * ch - ly2 * sh, b.y + lx2 * sh + ly2 * ch, 0.6f, (planing - 0.5f) * spd * dt * 0.45f)
                }
            }
        }
        val bxC = b.x; val byC = b.y
        // breaking crests make foam; fresh white foam bleaches fast (bubbles burst), the thin aerated remnant lingers
        // as lace for several seconds; everything spreads a little
        for (j in 1 until n - 1) {
            val row = j * n
            for (i in 1 until n - 1) {
                val id = row + i
                val gx = (u[id + 1] - u[id - 1]) / (2 * cell); val gy = (u[id + n] - u[id - n]) / (2 * cell)
                val steep = sqrt(gx * gx + gy * gy)
                val avg = (foam[id - 1] + foam[id + 1] + foam[id - n] + foam[id + n]) * 0.25f
                val f0 = foam[id] + (avg - foam[id]) * 0.015f
                val q = 1f - min(f0, 1f)
                val gate = 1f - ((hypot(ox + (i + 0.5f) * cell - bxC, oy + (j + 0.5f) * cell - byC) - 10f) / 10f).coerceIn(0f, 1f)
                var f = f0 * exp(-dt / (1.1f + 4.0f * q * q)) + (max(0f, steep - 0.12f) * 3.0f + max(0f, u[id] - 0.16f) * 3f) * dt * gate
                // bursting foam leaves aerated water behind; it fades slowly and spreads, widening the scar
                val aavg = (aer[id - 1] + aer[id + 1] + aer[id - n] + aer[id + n]) * 0.25f
                var a = (aer[id] + (aavg - aer[id]) * 0.03f) * exp(-dt / 25f)
                if (solid[id].toInt() == 1) { f = 0f; a = 0f }
                foam[id] = min(f, 1.0f); aer[id] = min(a, 1.0f)
            }
        }
    }

    private fun splat(x: Float, y: Float, rad: Float, amount: Float) {
        val ci = (x - ox) / cell; val cj = (y - oy) / cell; val rc = rad / cell
        for (j in max(1, (cj - rc).toInt())..min(n - 2, (cj + rc).toInt() + 1)) for (i in max(1, (ci - rc).toInt())..min(n - 2, (ci + rc).toInt() + 1)) {
            val d = hypot(i + 0.5f - ci, j + 0.5f - cj) / rc
            if (d < 1f) { foam[j * n + i] += amount * (1f - d * d); aer[j * n + i] = min(1f, aer[j * n + i] + amount * 0.5f * (1f - d * d)) }
        }
    }

    /** Interleaved (height, foam, aeration, hull proximity) for upload as an RGBA float texture. */
    fun pack(out: FloatArray) {
        for (i in 0 until n * n) { out[i * 4] = u[i]; out[i * 4 + 1] = foam[i]; out[i * 4 + 2] = aer[i]; out[i * 4 + 3] = near[i] }
    }
}
