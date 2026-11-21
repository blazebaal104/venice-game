// Turns a decoded map tile into render meshes (tile-local coordinates). Pure Kotlin.
package com.blazebaal.venice.core

import kotlin.math.*

object Z {
    const val GROUND = 1.1f      // fondamenta / campi height above mean water
    const val MARSH = 0.32f      // barene
    const val WALL_BOTTOM = -1.8f
}

object Kind { const val WALL = 0f; const val ROOF = 1f; const val QUAY = 2f; const val CORNICE = 3f }

class TileMeshes(val facade: FloatArray, val generic: FloatArray, val hasGround: Boolean, val hasMarsh: Boolean)

object Palette {
    val BRICK = floatArrayOf(0.50f, 0.24f, 0.17f, 0.05f)
    val ISTRIAN = floatArrayOf(0.84f, 0.83f, 0.78f, 0.15f)
    val TRACHYTE = floatArrayOf(0.42f, 0.42f, 0.41f, 0.05f)
    val WOOD_DARK = floatArrayOf(0.25f, 0.18f, 0.12f, 0.02f)
    val WOOD_POLE = floatArrayOf(0.33f, 0.27f, 0.21f, 0.02f)
    val WOOD_DECK = floatArrayOf(0.42f, 0.32f, 0.22f, 0.05f)
    val IRON = floatArrayOf(0.08f, 0.10f, 0.09f, 0.4f)
    val LEAF = arrayOf(floatArrayOf(0.20f, 0.32f, 0.13f), floatArrayOf(0.25f, 0.36f, 0.15f), floatArrayOf(0.17f, 0.28f, 0.12f))
    val TRUNK = floatArrayOf(0.30f, 0.24f, 0.18f, 0f)
    val CHIMNEY = arrayOf(floatArrayOf(0.62f, 0.33f, 0.25f), floatArrayOf(0.78f, 0.72f, 0.64f), floatArrayOf(0.70f, 0.45f, 0.33f))
    val ACTV_YELLOW = floatArrayOf(0.95f, 0.75f, 0.10f, 0.2f)
    val BRONZE = floatArrayOf(0.25f, 0.30f, 0.22f, 0.5f)
}

class CityMesher {
    private val fac = MeshBuilder(32768)
    private val gen = MeshBuilder(32768)

    fun build(t: TileData): TileMeshes {
        fac.clear(); gen.clear()
        for (b in t.buildings) building(b)
        for (w in t.walls) quay(w)
        for (br in t.bridges) bridge(br, t)
        for (p in t.piers) linePier(p)
        for (p in t.pierPolys) polyPier(p)
        for (p in t.points) prop(p, t)
        val hasMarsh = t.marsh?.any { it != 0.toByte() } ?: false
        return TileMeshes(fac.floats(), gen.floats(), t.hard != null, hasMarsh)
    }

    private fun rnd(seed: Int, k: Int): Float { var h = seed * 374761393 + k * 668265263; h = (h xor (h ushr 13)) * 1274126177; return ((h xor (h ushr 16)) and 0xffff) / 65535f }

    // ------------------------------------------------------------------ buildings
    private fun building(b: BuildingData) {
        val top = Z.GROUND + b.height
        val seed01 = (b.seed and 0xffff) / 65535f
        val kind = if (b.kind == 4) Kind.WALL else Kind.WALL
        var start = 0
        for ((ri, n) in b.ringSizes.withIndex()) {
            for (i in 0 until n) {
                val j = (i + 1) % n
                val ax = b.xy[(start + i) * 2]; val ay = b.xy[(start + i) * 2 + 1]
                val bx = b.xy[(start + j) * 2]; val by = b.xy[(start + j) * 2 + 1]
                val dx = bx - ax; val dy = by - ay; val len = sqrt(dx * dx + dy * dy)
                if (len < 0.05f) continue
                val nx = dy / len; val ny = -dx / len // outward (outer ring is CCW, holes CW)
                val z0 = Z.WALL_BOTTOM; val z1 = top - 0.32f
                fac.v(ax, ay, z0, nx, ny, 0f, 0f, z0, len, seed01, kind, 0f)
                fac.v(bx, by, z0, nx, ny, 0f, len, z0, len, seed01, kind, 0f)
                fac.v(bx, by, z1, nx, ny, 0f, len, z1, len, seed01, kind, 0f)
                fac.v(ax, ay, z0, nx, ny, 0f, 0f, z0, len, seed01, kind, 0f)
                fac.v(bx, by, z1, nx, ny, 0f, len, z1, len, seed01, kind, 0f)
                fac.v(ax, ay, z1, nx, ny, 0f, 0f, z1, len, seed01, kind, 0f)
            }
            // cornice (projecting eave band) on the outer ring
            if (ri == 0) cornice(b, start, n, top, seed01)
            start += n
        }
        // flat roof surface (sits at the top of the cornice)
        val tris = b.roofTris
        for (k in tris.indices step 3) {
            for (m in 0 until 3) {
                val vi = tris[k + m].toInt()
                val x = b.xy[vi * 2]; val y = b.xy[vi * 2 + 1]
                fac.v(x, y, top, 0f, 0f, 1f, x, y, 0f, seed01, Kind.ROOF, 0f)
            }
        }
        chimneys(b, top)
    }

    private fun cornice(b: BuildingData, start: Int, n: Int, top: Float, seed01: Float) {
        val over = 0.28f
        val ox = FloatArray(n); val oy = FloatArray(n)
        for (i in 0 until n) {
            val p = (i - 1 + n) % n; val q = (i + 1) % n
            val x = b.xy[(start + i) * 2]; val y = b.xy[(start + i) * 2 + 1]
            var e1x = x - b.xy[(start + p) * 2]; var e1y = y - b.xy[(start + p) * 2 + 1]
            var e2x = b.xy[(start + q) * 2] - x; var e2y = b.xy[(start + q) * 2 + 1] - y
            val l1 = sqrt(e1x * e1x + e1y * e1y).coerceAtLeast(1e-6f); val l2 = sqrt(e2x * e2x + e2y * e2y).coerceAtLeast(1e-6f)
            e1x /= l1; e1y /= l1; e2x /= l2; e2y /= l2
            var mx = e1y + e2y; var my = -e1x - e2x
            val ml = sqrt(mx * mx + my * my)
            if (ml < 1e-4f) { mx = e1y; my = -e1x } else { mx /= ml; my /= ml }
            val cosHalf = (mx * e1y - my * e1x).coerceAtLeast(0.35f)
            ox[i] = x + mx * over / cosHalf; oy[i] = y + my * over / cosHalf
        }
        val zb = top - 0.32f
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ax = b.xy[(start + i) * 2]; val ay = b.xy[(start + i) * 2 + 1]; val bx = b.xy[(start + j) * 2]; val by = b.xy[(start + j) * 2 + 1]
            val dx = bx - ax; val dy = by - ay; val len = sqrt(dx * dx + dy * dy)
            if (len < 0.05f) continue
            val nx = dy / len; val ny = -dx / len
            // underside (wall -> overhang), sloping face, outer face, top lip
            fq(ax, ay, zb, bx, by, zb, ox[j], oy[j], zb + 0.12f, ox[i], oy[i], zb + 0.12f, nx * 0.4f, ny * 0.4f, -0.9f, len, seed01)
            fq(ox[i], oy[i], zb + 0.12f, ox[j], oy[j], zb + 0.12f, ox[j], oy[j], top, ox[i], oy[i], top, nx, ny, 0f, len, seed01)
            fq(ox[i], oy[i], top, ox[j], oy[j], top, bx, by, top, ax, ay, top, 0f, 0f, 1f, len, seed01)
        }
    }

    private fun fq(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float, dx: Float, dy: Float, dz: Float,
                   nx: Float, ny: Float, nz: Float, len: Float, seed01: Float) {
        val k = Kind.CORNICE
        fac.v(ax, ay, az, nx, ny, nz, 0f, az, len, seed01, k, 0f); fac.v(bx, by, bz, nx, ny, nz, len, bz, len, seed01, k, 0f); fac.v(cx, cy, cz, nx, ny, nz, len, cz, len, seed01, k, 0f)
        fac.v(ax, ay, az, nx, ny, nz, 0f, az, len, seed01, k, 0f); fac.v(cx, cy, cz, nx, ny, nz, len, cz, len, seed01, k, 0f); fac.v(dx, dy, dz, nx, ny, nz, 0f, dz, len, seed01, k, 0f)
    }

    /** Venetian "camini a fumaiolo": shaft + inverted cone crown, placed on roof triangles. */
    private fun chimneys(b: BuildingData, top: Float) {
        val n = b.roofTris.size / 3
        if (n == 0 || b.height < 6f) return
        var area = 0f
        for (k in 0 until n) area += triArea(b, k)
        val count = when { area < 50f -> if (rnd(b.seed, 1) < 0.5f) 1 else 0; area < 200f -> 1 + (rnd(b.seed, 2) * 2).toInt(); else -> 2 + (rnd(b.seed, 3) * 3).toInt() }
        for (c in 0 until count) {
            // pick a triangle weighted by area
            var pick = rnd(b.seed, 10 + c) * area; var k = 0
            while (k < n - 1 && pick > triArea(b, k)) { pick -= triArea(b, k); k++ }
            val i0 = b.roofTris[k * 3].toInt(); val i1 = b.roofTris[k * 3 + 1].toInt(); val i2 = b.roofTris[k * 3 + 2].toInt()
            var u = rnd(b.seed, 20 + c); var v = rnd(b.seed, 30 + c); if (u + v > 1) { u = 1 - u; v = 1 - v }
            val x = b.xy[i0 * 2] + (b.xy[i1 * 2] - b.xy[i0 * 2]) * u + (b.xy[i2 * 2] - b.xy[i0 * 2]) * v
            val y = b.xy[i0 * 2 + 1] + (b.xy[i1 * 2 + 1] - b.xy[i0 * 2 + 1]) * u + (b.xy[i2 * 2 + 1] - b.xy[i0 * 2 + 1]) * v
            val col = Palette.CHIMNEY[(rnd(b.seed, 40 + c) * 3).toInt().coerceAtMost(2)]
            gen.color(col[0], col[1], col[2], 0.02f)
            val h = 1.4f + rnd(b.seed, 50 + c) * 0.8f
            val rot = rnd(b.seed, 60 + c) * 1.57f
            gen.box(x, y, top + h / 2, 0.28f, 0.28f, h / 2, rot)
            gen.frustum(x, y, top + h, 0.22f, 0.55f, 0.7f, 8)       // funnel crown
            gen.color(0.12f, 0.11f, 0.1f, 0f)
            gen.frustum(x, y, top + h + 0.7f, 0.5f, 0.38f, 0.08f, 8)  // soot-dark rim
        }
    }

    private fun triArea(b: BuildingData, k: Int): Float {
        val i0 = b.roofTris[k * 3].toInt(); val i1 = b.roofTris[k * 3 + 1].toInt(); val i2 = b.roofTris[k * 3 + 2].toInt()
        return abs((b.xy[i1 * 2] - b.xy[i0 * 2]) * (b.xy[i2 * 2 + 1] - b.xy[i0 * 2 + 1]) - (b.xy[i2 * 2] - b.xy[i0 * 2]) * (b.xy[i1 * 2 + 1] - b.xy[i0 * 2 + 1])) / 2
    }

    // ------------------------------------------------------------------ quay walls (fondamenta edges)
    private fun quay(p: FloatArray) {
        val n = p.size / 2
        if (n < 2) return
        // per-vertex mitred normal (water side) so the stone coping is continuous
        val mx = FloatArray(n); val my = FloatArray(n)
        for (i in 0 until n) {
            var sx = 0f; var sy = 0f
            if (i > 0) { val dx = p[i * 2] - p[i * 2 - 2]; val dy = p[i * 2 + 1] - p[i * 2 - 1]; val l = hypot(dx, dy).coerceAtLeast(1e-6f); sx += dy / l; sy += -dx / l }
            if (i < n - 1) { val dx = p[i * 2 + 2] - p[i * 2]; val dy = p[i * 2 + 3] - p[i * 2 + 1]; val l = hypot(dx, dy).coerceAtLeast(1e-6f); sx += dy / l; sy += -dx / l }
            val l = hypot(sx, sy).coerceAtLeast(1e-6f); mx[i] = sx / l; my[i] = sy / l
            if (i in 1 until n - 1) {
                val dx = p[i * 2 + 2] - p[i * 2]; val dy = p[i * 2 + 3] - p[i * 2 + 1]; val ll = hypot(dx, dy).coerceAtLeast(1e-6f)
                val c = (mx[i] * dy / ll - my[i] * dx / ll).coerceAtLeast(0.5f)
                mx[i] /= c; my[i] /= c
            }
        }
        var u = 0f
        val o = 0.05f; val wdt = 0.34f; val zt = Z.GROUND + 0.05f
        for (i in 0 until n - 1) {
            val ax = p[i * 2]; val ay = p[i * 2 + 1]; val bx = p[i * 2 + 2]; val by = p[i * 2 + 3]
            val dx = bx - ax; val dy = by - ay; val len = sqrt(dx * dx + dy * dy)
            if (len < 0.01f) continue
            val nx = dy / len; val ny = -dx / len // water side
            val z0 = Z.WALL_BOTTOM; val z1 = Z.GROUND - 0.2f
            fac.v(ax, ay, z0, nx, ny, 0f, u, z0, len, 0f, Kind.QUAY, 0f); fac.v(bx, by, z0, nx, ny, 0f, u + len, z0, len, 0f, Kind.QUAY, 0f); fac.v(bx, by, z1, nx, ny, 0f, u + len, z1, len, 0f, Kind.QUAY, 0f)
            fac.v(ax, ay, z0, nx, ny, 0f, u, z0, len, 0f, Kind.QUAY, 0f); fac.v(bx, by, z1, nx, ny, 0f, u + len, z1, len, 0f, Kind.QUAY, 0f); fac.v(ax, ay, z1, nx, ny, 0f, u, z1, len, 0f, Kind.QUAY, 0f)
            // Istrian stone coping: continuous, mitred, projecting 5 cm over the water
            gen.color(Palette.ISTRIAN)
            val aox = ax + mx[i] * o; val aoy = ay + my[i] * o; val box = bx + mx[i + 1] * o; val boy = by + my[i + 1] * o
            val aix = ax - mx[i] * wdt; val aiy = ay - my[i] * wdt; val bix = bx - mx[i + 1] * wdt; val biy = by - my[i + 1] * wdt
            gen.quad(Vec3(aox, aoy, zt), Vec3(box, boy, zt), Vec3(bix, biy, zt), Vec3(aix, aiy, zt))
            gen.quad(Vec3(aox, aoy, Z.GROUND - 0.22f), Vec3(box, boy, Z.GROUND - 0.22f), Vec3(box, boy, zt), Vec3(aox, aoy, zt))
            gen.quad(Vec3(ax, ay, Z.GROUND - 0.22f), Vec3(bx, by, Z.GROUND - 0.22f), Vec3(box, boy, Z.GROUND - 0.22f), Vec3(aox, aoy, Z.GROUND - 0.22f))
            u += len
        }
    }

    // ------------------------------------------------------------------ bridges
    private fun bridge(br: LineData, t: TileData) {
        val n = br.xy.size / 2
        if (n < 2) return
        val cum = FloatArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + hypot(br.xy[i * 2] - br.xy[i * 2 - 2], br.xy[i * 2 + 1] - br.xy[i * 2 - 1])
        val total = cum[n - 1]
        if (total < 1f) return
        val flat = total > 70f
        val rise = if (flat) 0f else (0.11f * total + 0.35f).coerceIn(0.5f, 7.0f)
        val w = br.width.coerceIn(1.6f, if (flat) 30f else 24f)
        val hw = w / 2
        val stepH = 0.16f
        fun pos(s: Float): FloatArray {
            var i = 0
            while (i < n - 2 && cum[i + 1] < s) i++
            val seg = (cum[i + 1] - cum[i]).coerceAtLeast(1e-6f)
            val f = ((s - cum[i]) / seg).coerceIn(0f, 1f)
            val x = br.xy[i * 2] + (br.xy[i * 2 + 2] - br.xy[i * 2]) * f; val y = br.xy[i * 2 + 1] + (br.xy[i * 2 + 3] - br.xy[i * 2 + 1]) * f
            val dx = (br.xy[i * 2 + 2] - br.xy[i * 2]) / seg; val dy = (br.xy[i * 2 + 3] - br.xy[i * 2 + 1]) / seg
            return floatArrayOf(x, y, dx, dy)
        }
        fun deckZ(s: Float): Float {
            if (flat) return Z.GROUND + 2.2f
            val raw = Z.GROUND + rise * sin(PI.toFloat() * (s / total))
            return if (br.steps && rise > 0.4f) Z.GROUND + floor((raw - Z.GROUND) / stepH + 0.5f) * stepH else raw
        }
        fun underZ(s: Float): Float = if (flat) Z.GROUND + 1.4f else Z.GROUND - 0.45f + (rise + 0.1f) * sin(PI.toFloat() * (s / total))
        val stepLen = if (br.steps) 0.35f else 0.6f
        val body = if (total > 25f && !flat) Palette.ISTRIAN else Palette.BRICK  // big bridges (Rialto, Accademia, Scalzi) are stone
        val m = max(2, (total / stepLen).toInt())
        var prev: FloatArray? = null; var prevZ = 0f; var prevU = 0f
        for (k in 0..m) {
            val s = total * k / m
            val p = pos(s)
            val z = deckZ(s); val uz = underZ(s)
            if (prev != null) {
                val ax = prev[0]; val ay = prev[1]; val bx = p[0]; val by = p[1]
                val nx = -prev[3]; val ny = prev[2] // left normal
                val nbx = -p[3]; val nby = p[2]
                val zA = prevZ
                // deck: tread at the previous height then riser (steps) - or a smooth ramp
                val zTread = if (br.steps) zA else z
                gen.color(if (br.steps) Palette.ISTRIAN else Palette.TRACHYTE)
                gen.quad(Vec3(ax - nx * hw, ay - ny * hw, zA), Vec3(bx - nbx * hw, by - nby * hw, zTread), Vec3(bx + nbx * hw, by + nby * hw, zTread), Vec3(ax + nx * hw, ay + ny * hw, zA))
                if (br.steps && abs(z - zTread) > 0.01f) {
                    gen.color(Palette.TRACHYTE)
                    gen.quad(Vec3(bx - nbx * hw, by - nby * hw, zTread), Vec3(bx - nbx * hw, by - nby * hw, z), Vec3(bx + nbx * hw, by + nby * hw, z), Vec3(bx + nbx * hw, by + nby * hw, zTread))
                }
                // parapets (brick body, Istrian stone top) on both sides
                for (side in intArrayOf(-1, 1)) {
                    val sx0 = ax + nx * hw * side; val sy0 = ay + ny * hw * side; val sx1 = bx + nbx * hw * side; val sy1 = by + nby * hw * side
                    val ph = 0.95f
                    gen.color(body)
                    // spandrel from the arch underside up to the parapet top (outer face)
                    if (side > 0) gen.quad(Vec3(sx0, sy0, prevU), Vec3(sx1, sy1, uz), Vec3(sx1, sy1, z + ph), Vec3(sx0, sy0, zA + ph))
                    else gen.quad(Vec3(sx1, sy1, uz), Vec3(sx0, sy0, prevU), Vec3(sx0, sy0, zA + ph), Vec3(sx1, sy1, z + ph))
                    gen.color(Palette.ISTRIAN)
                    val ix0 = sx0 - nx * 0.25f * side; val iy0 = sy0 - ny * 0.25f * side; val ix1 = sx1 - nbx * 0.25f * side; val iy1 = sy1 - nby * 0.25f * side
                    gen.quad(Vec3(sx0, sy0, zA + ph), Vec3(sx1, sy1, z + ph), Vec3(ix1, iy1, z + ph), Vec3(ix0, iy0, zA + ph))
                    gen.color(body)
                    if (side > 0) gen.quad(Vec3(ix1, iy1, z), Vec3(ix0, iy0, zA), Vec3(ix0, iy0, zA + ph), Vec3(ix1, iy1, z + ph))
                    else gen.quad(Vec3(ix0, iy0, zA), Vec3(ix1, iy1, z), Vec3(ix1, iy1, z + ph), Vec3(ix0, iy0, zA + ph))
                }
                // arch intrados (underside)
                gen.color(body[0] * 0.8f, body[1] * 0.8f, body[2] * 0.8f, 0f)
                gen.quad(Vec3(ax + nx * hw, ay + ny * hw, prevU), Vec3(bx + nbx * hw, by + nby * hw, uz), Vec3(bx - nbx * hw, by - nby * hw, uz), Vec3(ax - nx * hw, ay - ny * hw, prevU))
                if (flat && k % 12 == 0) { // piers for long causeway bridges
                    gen.color(Palette.ISTRIAN)
                    gen.box(bx, by, (Z.WALL_BOTTOM + uz) / 2, hw * 0.9f, 0.6f, (uz - Z.WALL_BOTTOM) / 2, atan2(p[3], p[2]) + PI.toFloat() / 2)
                }
            }
            prev = p; prevZ = z; prevU = uz
        }
    }

    // ------------------------------------------------------------------ piers / docks
    private fun linePier(p: LineData) {
        val n = p.xy.size / 2
        val hw = (p.width / 2).coerceIn(0.6f, 4f)
        val z = 0.85f
        for (i in 0 until n - 1) {
            val ax = p.xy[i * 2]; val ay = p.xy[i * 2 + 1]; val bx = p.xy[i * 2 + 2]; val by = p.xy[i * 2 + 3]
            val len = hypot(bx - ax, by - ay); if (len < 0.05f) continue
            val nx = -(by - ay) / len; val ny = (bx - ax) / len
            gen.color(Palette.WOOD_DECK)
            gen.quad(Vec3(ax - nx * hw, ay - ny * hw, z), Vec3(bx - nx * hw, by - ny * hw, z), Vec3(bx + nx * hw, by + ny * hw, z), Vec3(ax + nx * hw, ay + ny * hw, z))
            gen.color(Palette.WOOD_DARK)
            gen.quad(Vec3(ax - nx * hw, ay - ny * hw, z - 0.2f), Vec3(bx - nx * hw, by - ny * hw, z - 0.2f), Vec3(bx - nx * hw, by - ny * hw, z), Vec3(ax - nx * hw, ay - ny * hw, z))
            gen.quad(Vec3(bx + nx * hw, by + ny * hw, z - 0.2f), Vec3(ax + nx * hw, ay + ny * hw, z - 0.2f), Vec3(ax + nx * hw, ay + ny * hw, z), Vec3(bx + nx * hw, by + ny * hw, z))
            val posts = max(1, (len / 3f).toInt())
            gen.color(Palette.WOOD_POLE)
            for (k in 0..posts) {
                val f = k.toFloat() / posts
                val x = ax + (bx - ax) * f; val y = ay + (by - ay) * f
                for (s in intArrayOf(-1, 1)) gen.frustum(x + nx * hw * s, y + ny * hw * s, Z.WALL_BOTTOM, 0.11f, 0.1f, 1.6f - Z.WALL_BOTTOM + (if (k % 2 == 0) 0.4f else 0f), 6)
            }
        }
    }

    private fun polyPier(p: PolyData) {
        val z = 0.85f
        gen.color(Palette.WOOD_DECK)
        for (k in p.tris.indices step 3) {
            val a = p.tris[k].toInt(); val b = p.tris[k + 1].toInt(); val c = p.tris[k + 2].toInt()
            var bx = p.xy[b * 2]; var by = p.xy[b * 2 + 1]; var cx = p.xy[c * 2]; var cy = p.xy[c * 2 + 1]
            val ar = (bx - p.xy[a * 2]) * (cy - p.xy[a * 2 + 1]) - (cx - p.xy[a * 2]) * (by - p.xy[a * 2 + 1])
            if (ar < 0) { val tx = bx; val ty = by; bx = cx; by = cy; cx = tx; cy = ty }
            gen.tri(p.xy[a * 2], p.xy[a * 2 + 1], z, bx, by, z, cx, cy, z)
        }
        val n = p.xy.size / 2
        gen.color(Palette.WOOD_POLE)
        for (i in 0 until n) gen.frustum(p.xy[i * 2], p.xy[i * 2 + 1], Z.WALL_BOTTOM, 0.12f, 0.11f, 1.3f - Z.WALL_BOTTOM, 6)
    }

    // ------------------------------------------------------------------ street furniture & lagoon props
    private fun prop(p: PointData, t: TileData) {
        val onLand = t.solidAt(p.x, p.y) > 0.5f
        val gz = if (onLand) Z.GROUND else 0f
        val s = (p.x * 73.1f + p.y * 19.7f).toInt()
        val dir = if (p.dir >= 0) Math.toRadians(90.0 - p.dir).toFloat() else rnd(s, 7) * PI.toFloat()
        when (p.type) {
            Feature.BRICOLA -> {
                // three tarred oak piles leaning together, lashed at the top
                val lean = 0.06f
                val baseR = 0.35f
                gen.color(Palette.WOOD_POLE)
                for (k in 0 until 3) {
                    val a = dir + k * 2.094f
                    val bx = p.x + cos(a) * baseR; val by = p.y + sin(a) * baseR
                    gen.frustum(bx, by, Z.WALL_BOTTOM, 0.17f, 0.14f, 3.6f - Z.WALL_BOTTOM, 7, -cos(a) * lean, -sin(a) * lean)
                }
                gen.color(0.12f, 0.12f, 0.12f, 0.1f)
                gen.frustum(p.x, p.y, 2.75f, 0.40f, 0.36f, 0.25f, 8, capTop = false)
                gen.color(0.20f, 0.30f, 0.22f, 0f) // algae ring at the waterline
                gen.frustum(p.x, p.y, -0.05f, 0.58f, 0.56f, 0.35f, 8, capTop = false)
            }
            Feature.BENCH -> {
                gen.color(Palette.ISTRIAN)
                gen.box(p.x, p.y, gz + 0.42f, 0.9f, 0.23f, 0.05f, dir)
                gen.box(p.x + cos(dir) * 0.7f, p.y + sin(dir) * 0.7f, gz + 0.2f, 0.08f, 0.2f, 0.2f, dir)
                gen.box(p.x - cos(dir) * 0.7f, p.y - sin(dir) * 0.7f, gz + 0.2f, 0.08f, 0.2f, 0.2f, dir)
            }
            Feature.WELL -> { // vera da pozzo
                gen.color(Palette.ISTRIAN)
                gen.frustum(p.x, p.y, gz, 0.95f, 0.95f, 0.12f, 8)
                gen.frustum(p.x, p.y, gz + 0.12f, 0.72f, 0.66f, 0.85f, 8)
                gen.frustum(p.x, p.y, gz + 0.97f, 0.78f, 0.78f, 0.1f, 8)
                gen.color(Palette.BRONZE)
                gen.frustum(p.x, p.y, gz + 1.07f, 0.62f, 0.2f, 0.12f, 8)
            }
            Feature.TREE -> {
                val h = 3f + rnd(s, 1) * 4f
                gen.color(Palette.TRUNK)
                gen.frustum(p.x, p.y, gz, 0.18f, 0.12f, h * 0.65f, 6)
                val c = Palette.LEAF[(rnd(s, 2) * 3).toInt().coerceAtMost(2)]
                gen.color(c[0], c[1], c[2], 0f)
                val rad = 1.3f + rnd(s, 3) * 1.2f
                gen.blob(p.x, p.y, gz + h * 0.75f, rad, 0.85f, s)
                gen.blob(p.x + rad * 0.5f, p.y + rad * 0.2f, gz + h * 0.62f, rad * 0.75f, 0.8f, s + 1)
                gen.blob(p.x - rad * 0.4f, p.y - rad * 0.35f, gz + h * 0.66f, rad * 0.7f, 0.8f, s + 2)
            }
            Feature.LAMP -> {
                gen.color(Palette.IRON)
                gen.frustum(p.x, p.y, gz, 0.09f, 0.05f, 3.4f, 6)
                gen.color(0.9f, 0.85f, 0.6f, 0.6f)
                gen.frustum(p.x, p.y, gz + 3.4f, 0.13f, 0.18f, 0.38f, 6)
                gen.color(Palette.IRON)
                gen.frustum(p.x, p.y, gz + 3.78f, 0.2f, 0.02f, 0.18f, 6)
            }
            Feature.STOP -> {
                gen.color(Palette.IRON)
                gen.frustum(p.x, p.y, gz, 0.05f, 0.05f, 2.6f, 6)
                gen.color(Palette.ACTV_YELLOW)
                gen.box(p.x, p.y, gz + 2.5f, 0.35f, 0.04f, 0.22f, dir)
            }
            Feature.FLAGPOLE -> { gen.color(0.85f, 0.85f, 0.82f, 0.3f); gen.frustum(p.x, p.y, gz, 0.09f, 0.04f, 9f, 6) }
            Feature.BIN -> { gen.color(0.12f, 0.16f, 0.13f, 0.2f); gen.frustum(p.x, p.y, gz, 0.22f, 0.24f, 0.9f, 8) }
            Feature.FOUNTAIN -> { gen.color(Palette.IRON); gen.frustum(p.x, p.y, gz, 0.16f, 0.11f, 1.0f, 8) }
        }
    }
}
