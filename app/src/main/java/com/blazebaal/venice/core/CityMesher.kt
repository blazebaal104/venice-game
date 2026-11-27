// Turns a decoded map tile into render meshes (tile-local coordinates). Pure Kotlin.
package com.blazebaal.venice.core

import kotlin.math.*

object Z {
    const val GROUND = 1.1f      // fondamenta / campi height above mean water
    const val MARSH = 0.32f      // barene
    const val WALL_BOTTOM = -1.8f
}

object Kind { const val WALL = 0f; const val ROOF = 1f; const val QUAY = 2f; const val CORNICE = 3f; const val TOWER = 4f }

class TileMeshes(val facade: FloatArray, val generic: FloatArray, val detail: FloatArray, val vegetation: FloatArray, val hasGround: Boolean, val hasMarsh: Boolean)

object Palette {
    val BRICK = floatArrayOf(1f, 1f, 1f, 100f + L.BRICK0)
    val ISTRIAN = floatArrayOf(1f, 0.99f, 0.95f, 100f + L.ISTRIAN)
    val TRACHYTE = floatArrayOf(1f, 1f, 1f, 100f + L.MASEGNI)
    val WOOD_DARK = floatArrayOf(0.7f, 0.65f, 0.6f, 100f + L.PLANKS_OLD)
    val WOOD_POLE = floatArrayOf(0.9f, 0.85f, 0.8f, 100f + L.POLE)
    val WOOD_DECK = floatArrayOf(1f, 1f, 1f, 100f + L.PLANKS)
    val IRON = floatArrayOf(0.08f, 0.10f, 0.09f, 0.4f)
    val LEAF = arrayOf(floatArrayOf(0.20f, 0.32f, 0.13f), floatArrayOf(0.25f, 0.36f, 0.15f), floatArrayOf(0.17f, 0.28f, 0.12f))
    val TRUNK = floatArrayOf(0.30f, 0.24f, 0.18f, 0f)
    val CHIMNEY = arrayOf(floatArrayOf(0.62f, 0.33f, 0.25f), floatArrayOf(0.78f, 0.72f, 0.64f), floatArrayOf(0.70f, 0.45f, 0.33f))
    val ACTV_YELLOW = floatArrayOf(0.95f, 0.75f, 0.10f, 0.2f)
    val BRONZE = floatArrayOf(0.25f, 0.30f, 0.22f, 0.5f)
    // shutters (persiane): Venetian green, brown, dove grey; awnings; palina stripe colours
    val SHUTTER = arrayOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(0.30f, 0.62f, 0.40f), floatArrayOf(0.62f, 0.42f, 0.30f), floatArrayOf(0.82f, 0.82f, 0.78f))
    val AWNING = arrayOf(floatArrayOf(0.55f, 0.12f, 0.10f), floatArrayOf(0.15f, 0.30f, 0.20f), floatArrayOf(0.80f, 0.74f, 0.60f), floatArrayOf(0.20f, 0.25f, 0.42f))
    val PALINA = arrayOf(floatArrayOf(0.85f, 0.10f, 0.10f), floatArrayOf(0.10f, 0.20f, 0.55f), floatArrayOf(0.10f, 0.40f, 0.20f), floatArrayOf(0.90f, 0.70f, 0.10f))
}

/** How one building looks: architectural type, rhythm of floors and openings, materials, roof. */
/** close-up detail / vegetation vertex budgets per tile (memory: 12 floats per vertex) */
private const val DET_MAX = 380_000
private const val VEG_MAX = 260_000

private class Style(
    val type: Int,                 // 0 casa popolare, 1 gothic palazzo, 2 renaissance, 3 baroque, 4 19th century
    val groundH: Float, val floorH: Float, val pn: Boolean,
    val winType: Int, val shutter: Int, val frame: Int, val winW: Float, val winH: Float,
    val mat: Int, val string: Int, val rust: Boolean,
    val roof: Int, val slope: Float, val shopP: Float, val balP: Float,
)

private class Bay(val c: Float, val w: Float, val kind: Int, val ground: Int, val jseed: Int, val bal: Int)

class CityMesher {
    private val fac = FacadeBuilder(32768)
    private val genMain = MeshBuilder(32768)
    /** trees and bushes go in their own mesh so they can have a shorter render distance than the buildings */
    private val veg = MeshBuilder(16384)
    private var gen = genMain
    private val det = MeshBuilder(32768)
    private val occ = ShortArray(TILE_SIZE * TILE_SIZE)

    /** Every item is built on its own: if one building, boat or prop fails (bad data, a bug), only that item is
     *  skipped - never the whole tile (a failed tile used to leave a hole in the city until the app restarted).
     *  minimal = quays and ground only, the last-resort build for a tile that keeps failing. */
    fun build(t: TileData, minimal: Boolean = false): TileMeshes {
        gen = genMain
        fac.clear(); genMain.clear(); veg.clear(); det.clear()
        fun safe(what: String, f: () -> Unit) { try { f() } catch (e: Throwable) { gen = genMain; android.util.Log.w("Venice", "tile ${t.tx},${t.ty}: $what failed", e) } }
        safe("occupancy") { rasterOccupancy(t) }
        if (!minimal) for ((i, b) in t.buildings.withIndex()) safe("building $i") { building(b, i, t) }
        fac.style(0f, 0f, 0f, 0f)
        for (w in t.walls) safe("quay") { quay(w) }
        if (!minimal) {
            for (w in t.walls) safe("moorings") { mooredBoats(w, t) }
            // bridges are hand-built models placed by GameRenderer.loadBridges (assets/bridges), nothing generic is meshed here
            for (p in t.piers) safe("pier") { linePier(p) }
            for (p in t.pierPolys) safe("pier polygon") { polyPier(p) }
            for (p in t.points) safe("prop") { prop(p, t) }
            safe("parks") { parkTrees(t) }
            safe("courtyards") { courtyardTrees(t) }
        }
        val hasMarsh = t.marsh?.any { it != 0.toByte() } ?: false
        return TileMeshes(fac.floats(), genMain.floats(), det.floats(), veg.floats(), t.hard != null, hasMarsh)
    }

    private fun rnd(seed: Int, k: Int): Float { var h = seed * 374761393 + k * 668265263; h = (h xor (h ushr 13)) * 1274126177; return ((h xor (h ushr 16)) and 0xffff) / 65535f }
    private fun pick(r: Float, vararg w: Float): Int { var acc = 0f; val tot = w.sum(); for ((i, x) in w.withIndex()) { acc += x / tot; if (r < acc) return i }; return w.size - 1 }

    // ------------------------------------------------------------------ which building covers each square metre
    private fun rasterOccupancy(t: TileData) {
        occ.fill(0)
        val xs = FloatArray(256)
        for ((bi, b) in t.buildings.withIndex()) {
            var minY = 1e9f; var maxY = -1e9f
            for (k in 0 until b.xy.size / 2) { minY = min(minY, b.xy[k * 2 + 1]); maxY = max(maxY, b.xy[k * 2 + 1]) }
            for (row in max(0, floor(minY).toInt())..min(TILE_SIZE - 1, ceil(maxY).toInt())) {
                val y = row + 0.5f; var nx = 0; var start = 0
                for (n in b.ringSizes) {
                    for (i in 0 until n) {
                        val j = (i + 1) % n
                        val ay = b.xy[(start + i) * 2 + 1]; val by = b.xy[(start + j) * 2 + 1]
                        if ((ay <= y) != (by <= y) && nx < xs.size) {
                            val ax = b.xy[(start + i) * 2]; val bx = b.xy[(start + j) * 2]
                            xs[nx++] = ax + (y - ay) / (by - ay) * (bx - ax)
                        }
                    }
                    start += n
                }
                xs.sort(0, nx)
                var k = 0
                while (k + 1 < nx) {
                    for (col in max(0, ceil(xs[k] - 0.5f).toInt())..min(TILE_SIZE - 1, floor(xs[k + 1] - 0.5f).toInt())) occ[row * TILE_SIZE + col] = (bi + 1).toShort()
                    k += 2
                }
            }
        }
    }
    private fun occAt(x: Float, y: Float): Int {
        val i = floor(x).toInt(); val j = floor(y).toInt()
        if (i < 0 || j < 0 || i >= TILE_SIZE || j >= TILE_SIZE) return 0
        return occ[j * TILE_SIZE + i].toInt()
    }

    // ------------------------------------------------------------------ style
    private fun styleFor(b: BuildingData, longest: Float, waterFront: Boolean): Style {
        val s = b.seed
        val big = longest > 13f && b.height > 12f
        // canal fronts are lined with palazzi (gothic above all); the inner calli with plain houses
        val type = if (waterFront && b.height > 11f && longest > 9f) pick(rnd(s, 1), 0.14f, 0.44f, 0.14f, 0.12f, 0.16f)
                   else if (big) pick(rnd(s, 1), 0.30f, 0.26f, 0.12f, 0.10f, 0.22f)
                   else pick(rnd(s, 1), 0.70f, 0.07f, 0.02f, 0.03f, 0.18f)
        val r = { k: Int -> rnd(s, 100 + k) }
        return when (type) {
            1 -> Style(1, 3.7f + r(1) * 0.8f, 3.6f + r(2) * 0.6f, true, if (r(3) < 0.5f) 3 else if (r(3) < 0.8f) 2 else 6,
                pick(r(4), 0.5f, 0.3f, 0.2f, 0f), 1, 0.9f + r(5) * 0.25f, 2.0f + r(6) * 0.45f,
                pick(r(7), 0.5f, 0.15f, 0.35f, 0f), if (r(8) < 0.7f) 1 else 2, false,
                if (r(9) < 0.75f) 0 else 1, 0.38f + r(10) * 0.08f, 0.1f, 0.28f)
            2 -> Style(2, 3.9f + r(1) * 0.7f, 3.8f + r(2) * 0.6f, true, if (r(3) < 0.7f) 1 else 0,
                if (r(4) < 0.6f) 0 else 1, 1, 0.95f + r(5) * 0.2f, 2.1f + r(6) * 0.4f,
                if (r(7) < 0.55f) 3 else 0, 1, r(8) < 0.7f, 0, 0.38f + r(10) * 0.06f, 0.05f, 0.38f)
            3 -> Style(3, 3.9f + r(1) * 0.6f, 3.8f + r(2) * 0.5f, true, if (r(3) < 0.5f) 4 else 5,
                if (r(4) < 0.5f) 0 else 1, 1, 1.0f + r(5) * 0.2f, 2.1f + r(6) * 0.3f,
                if (r(7) < 0.5f) 3 else 0, 1, r(8) < 0.5f, 0, 0.36f + r(10) * 0.06f, 0.05f, 0.42f)
            4 -> Style(4, 3.3f + r(1) * 0.6f, 3.1f + r(2) * 0.4f, false, if (r(3) < 0.65f) 0 else 5,
                pick(r(4), 0.1f, 0.7f, 0.15f, 0.05f), if (r(5) < 0.8f) 1 else 0, 0.85f + r(6) * 0.2f, 1.65f + r(7) * 0.3f,
                if (r(8) < 0.85f) 0 else 1, if (r(9) < 0.7f) 1 else 2, r(10) < 0.25f, if (r(11) < 0.8f) 0 else 1, 0.36f + r(12) * 0.08f, 0.3f, 0.45f)
            else -> Style(0, 2.7f + r(1) * 0.7f, 2.75f + r(2) * 0.4f, false, pick(r(3), 0.74f, 0.16f, 0.10f),
                pick(r(4), 0.12f, 0.55f, 0.25f, 0.08f), pick(r(5), 0.3f, 0.6f, 0.0f, 0.1f), 0.72f + r(6) * 0.3f, 1.3f + r(7) * 0.35f,
                pick(r(8), 0.55f, 0.35f, 0.10f), if (r(9) < 0.6f) 0 else 2, false,
                pick(r(10), 0.72f, 0.2f, 0.08f), 0.36f + r(11) * 0.12f, 0.28f, 0.22f)
        }
    }

    private fun code1(st: Style, bay: Bay): Float =
        (st.winType + 8 * st.shutter + 32 * st.frame + 128 * bay.kind + 512 * bay.ground + 4096 * (if (st.pn) 1 else 0) +
            8192 * st.mat + 32768 * st.string + 131072 * (if (st.rust) 1 else 0)).toFloat()
    private fun code2(st: Style, bay: Bay): Float =
        ((st.winW * 100).roundToInt().coerceIn(30, 255) + 256 * (st.winH * 50).roundToInt().coerceIn(30, 255) + 65536 * bay.jseed + 1048576 * bay.bal).toFloat()

    // ------------------------------------------------------------------ buildings
    private fun building(b: BuildingData, bi: Int, t: TileData) {
        if (Exclusions.zones.isNotEmpty()) {
            var sx = 0f; var sy = 0f; val n = b.ringSizes[0]
            for (i in 0 until n) { sx += b.xy[i * 2]; sy += b.xy[i * 2 + 1] }
            if (n > 0 && Exclusions.inside(t.ox + sx / n, t.oy + sy / n)) return      // replaced by a landmark
        }
        if (b.kind == 6) { tower(b); return }
        val top = Z.GROUND + b.height
        val seed01 = (b.seed and 0xffff) / 65535f
        val n0 = b.ringSizes[0]
        // classify the outer walls: against another building (party wall), on the water, or on a street
        val cls = IntArray(n0); var longest = 0f; var waterFront = false
        for (i in 0 until n0) {
            val j = (i + 1) % n0
            val ax = b.xy[i * 2]; val ay = b.xy[i * 2 + 1]; val bx = b.xy[j * 2]; val by = b.xy[j * 2 + 1]
            val len = hypot(bx - ax, by - ay); if (len < 0.05f) continue
            val nx = (by - ay) / len; val ny = -(bx - ax) / len
            var party = 0; var water = 0
            for (f in floatArrayOf(0.2f, 0.5f, 0.8f)) {
                val px = ax + (bx - ax) * f + nx * 1.2f; val py = ay + (by - ay) * f + ny * 1.2f
                val o = occAt(px, py); if (o != 0 && o != bi + 1) party++
                if (t.solidAt(ax + (bx - ax) * f + nx * 3f, ay + (by - ay) * f + ny * 3f) < 0.5f) water++
            }
            cls[i] = if (party >= 2) 1 else if (water >= 2) 2 else 0
            if (cls[i] == 2) waterFront = true
            if (cls[i] != 1) longest = max(longest, len)
        }
        val st = styleFor(b, longest, waterFront)
        // the main facade (polifora + balconies) of a palazzo: its longest canal front, else its longest street front
        var main = -1; var mainLen = 0f
        if (st.type in 1..3) for (i in 0 until n0) {
            if (cls[i] == 1) continue
            val j = (i + 1) % n0
            val len = hypot(b.xy[j * 2] - b.xy[i * 2], b.xy[j * 2 + 1] - b.xy[i * 2 + 1])
            val score = len + if (cls[i] == 2) 100f else 0f
            if (len >= 8.5f && score > mainLen) { mainLen = score; main = i }
        }
        val roofFn = roof(b, st, top, seed01)
        var start = 0
        for ((ri, n) in b.ringSizes.withIndex()) {
            for (i in 0 until n) {
                val j = (i + 1) % n
                val ax = b.xy[(start + i) * 2]; val ay = b.xy[(start + i) * 2 + 1]
                val bx = b.xy[(start + j) * 2]; val by = b.xy[(start + j) * 2 + 1]
                val c = if (ri == 0) cls[i] else 0
                wall(b, st, ax, ay, bx, by, top, seed01, c, ri == 0 && i == main, ri > 0, roofFn)
            }
            if (ri == 0) cornice(b, start, n, top, seed01, st)
            start += n
        }
        roofProps(b, st, top, roofFn)
    }

    /** One wall: split into bays (each a quad carrying its opening codes), plus its 3D details. */
    private fun wall(b: BuildingData, st: Style, ax: Float, ay: Float, bx: Float, by: Float, top: Float, seed01: Float,
                     cls: Int, main: Boolean, court: Boolean, roofFn: (Float, Float) -> Float) {
        val dx = bx - ax; val dy = by - ay; val len = sqrt(dx * dx + dy * dy)
        if (len < 0.05f) return
        val tx = dx / len; val ty = dy / len
        val nx = ty; val ny = -tx // outward (outer ring is CCW, holes CW)
        val z0 = Z.WALL_BOTTOM; val z1 = top - 0.32f
        val es = b.seed * 31 + (ax * 7.3f + ay * 3.1f).toInt()
        val bays = ArrayList<Bay>()
        val water = cls == 2
        if (cls == 1 || len < 1.8f) bays.add(Bay(len / 2, len, 2, 4, 0, 0))
        else if (main && len >= 8.5f) {
            // tripartite palazzo front: side windows, a central polifora of 3..7 lights over balconies
            val lightW = 0.95f + rnd(es, 3) * 0.2f
            val nL = (len * 0.32f / lightW).roundToInt().coerceIn(3, 7)
            val cw = nL * lightW
            val side = (len - cw) / 2
            val ns = max(1, (side / 2.7f).toInt())
            val sw = side / ns
            val balFloors = 1 or (if (rnd(es, 4) < 0.55f) 2 else 0)
            var u = 0f
            for (k in 0 until ns) { bays.add(Bay(u + sw / 2, sw, 0, if (water) 0 else if (rnd(es, 10 + k) < 0.5f) 0 else 4, (rnd(es, 20 + k) * 16).toInt(), 0)); u += sw }
            for (k in 0 until nL) {
                val gk = if (water) (if (k == nL / 2 || (nL % 2 == 0 && k == nL / 2 - 1)) 3 else 4) else (if (k == nL / 2) 1 else 4)
                bays.add(Bay(u + lightW / 2, lightW, 1, gk, (rnd(es, 40 + k) * 16).toInt(), balFloors)); u += lightW
            }
            for (k in 0 until ns) { bays.add(Bay(u + sw / 2, sw, 0, if (water) 0 else if (rnd(es, 30 + k) < 0.5f) 0 else 4, (rnd(es, 50 + k) * 16).toInt(), 0)); u += sw }
        } else {
            val target = when (st.type) { 0 -> 2.6f + rnd(b.seed, 7) * 0.8f; 4 -> 2.9f + rnd(b.seed, 7) * 0.5f; else -> 3.2f + rnd(b.seed, 7) * 0.6f }
            val nb = max(1, (len / target).toInt())
            val w = FloatArray(nb) { if (st.type == 0) 1f + 0.45f * (rnd(es, 60 + it) - 0.5f) else 1f }
            val ws = w.sum()
            var u = 0f
            val doorAt = if (water) -1 else (rnd(es, 5) * nb).toInt().coerceAtMost(nb - 1)
            val waterDoorAt = if (water && nb >= 2 && rnd(es, 6) < 0.8f) nb / 2 else -1
            val waterDoor2 = if (water && nb >= 6 && rnd(es, 9) < 0.5f) (if (rnd(es, 10) < 0.5f) 1 else nb - 2) else -1
            for (k in 0 until nb) {
                val bw = len * w[k] / ws
                val blank = st.type == 0 && !court && rnd(es, 70 + k) < 0.10f
                val ground = when {
                    court -> 0
                    k == waterDoorAt || k == waterDoor2 -> 3
                    k == doorAt -> 1
                    !water && rnd(es, 80 + k) < st.shopP -> 2
                    rnd(es, 90 + k) < 0.68f -> 0
                    else -> 4
                }
                // single balconies (poggioli): mostly on the first floor
                var bal = 0
                // (more on canal fronts; palazzi often stack them on the first two piani nobili)
                if (!court && !blank && rnd(es, 110 + k) < st.balP * (if (water) 1.15f else 1f)) {
                    val r2 = rnd(es, 120 + k)
                    bal = when (st.type) {
                        0 -> if (r2 < 0.55f) 1 else if (r2 < 0.9f) 2 else 3
                        else -> if (r2 < 0.65f) 1 else if (r2 < 0.85f) 3 else 2
                    }
                }
                bays.add(Bay(u + bw / 2, bw, if (blank) 2 else 0, ground, (rnd(es, 100 + k) * 16).toInt(), bal))
                u += bw
            }
        }
        // ---- the wall quads, one per bay
        for (bay in bays) {
            fac.style(st.groundH, st.floorH, code1(st, bay), code2(st, bay))
            val u0 = bay.c - bay.w / 2; val u1 = bay.c + bay.w / 2
            val pax = ax + tx * u0; val pay = ay + ty * u0; val pbx = ax + tx * u1; val pby = ay + ty * u1
            fac.v(pax, pay, z0, nx, ny, 0f, u0, z0, bay.c, seed01, Kind.WALL, bay.w)
            fac.v(pbx, pby, z0, nx, ny, 0f, u1, z0, bay.c, seed01, Kind.WALL, bay.w)
            fac.v(pbx, pby, z1, nx, ny, 0f, u1, z1, bay.c, seed01, Kind.WALL, bay.w)
            fac.v(pax, pay, z0, nx, ny, 0f, u0, z0, bay.c, seed01, Kind.WALL, bay.w)
            fac.v(pbx, pby, z1, nx, ny, 0f, u1, z1, bay.c, seed01, Kind.WALL, bay.w)
            fac.v(pax, pay, z1, nx, ny, 0f, u0, z1, bay.c, seed01, Kind.WALL, bay.w)
        }
        // ---- gable / infill wall up to the roof surface along this edge (where the roof does not come down to it)
        val blankBay = Bay(len / 2, len, 2, 4, 0, 0)
        fac.style(st.groundH, st.floorH, code1(st, blankBay), code2(st, blankBay))
        val k = max(1, ceil(len / 0.8f).toInt())
        for (q in 0 until k) {
            val ua = len * q / k; val ub = len * (q + 1) / k
            val pax = ax + tx * ua; val pay = ay + ty * ua; val pbx = ax + tx * ub; val pby = ay + ty * ub
            val ha = roofFn(pax, pay); val hb = roofFn(pbx, pby)
            if (max(ha, hb) < 0.03f) continue
            fac.v(pax, pay, top - 0.02f, nx, ny, 0f, ua, top, blankBay.c, seed01, Kind.WALL, len)
            fac.v(pbx, pby, top - 0.02f, nx, ny, 0f, ub, top, blankBay.c, seed01, Kind.WALL, len)
            fac.v(pbx, pby, top + hb, nx, ny, 0f, ub, top + hb, blankBay.c, seed01, Kind.WALL, len)
            fac.v(pax, pay, top - 0.02f, nx, ny, 0f, ua, top, blankBay.c, seed01, Kind.WALL, len)
            fac.v(pbx, pby, top + hb, nx, ny, 0f, ub, top + hb, blankBay.c, seed01, Kind.WALL, len)
            fac.v(pax, pay, top + ha, nx, ny, 0f, ua, top + ha, blankBay.c, seed01, Kind.WALL, len)
        }
        if (cls == 1 || court) return
        // per-tile memory budget for close-up detail: the densest tiles stop adding balconies, shutters, poles
        // instead of growing one mesh to hundreds of MB (that ran the app out of memory)
        if (det.count > DET_MAX || veg.count > VEG_MAX) return
        details(b, st, bays, ax, ay, tx, ty, nx, ny, top, water, es, len)
    }

    // ------------------------------------------------------------------ 3D details on a wall (near-LOD mesh)
    private fun floorOf(st: Style, fl: Int): Pair<Float, Float> { // (start rel z, height)
        val pnF = if (st.pn) 1.22f else 1f
        return when (fl) { 0 -> Pair(0f, st.groundH); 1 -> Pair(st.groundH, st.floorH * pnF); else -> Pair(st.groundH + st.floorH * pnF + (fl - 2) * st.floorH, st.floorH) }
    }

    private fun details(b: BuildingData, st: Style, bays: List<Bay>, ax: Float, ay: Float, tx: Float, ty: Float, nx: Float, ny: Float,
                        top: Float, water: Boolean, es: Int, len: Float) {
        val rot = atan2(ty, tx)
        fun wx(u: Float, off: Float) = ax + tx * u + nx * off
        fun wy(u: Float, off: Float) = ay + ty * u + ny * off
        val relTop = top - 0.32f - Z.GROUND
        // polifora balcony groups (contiguous bays of kind 1)
        var g0 = -1
        for (i in 0..bays.size) {
            val inG = i < bays.size && bays[i].kind == 1
            if (inG && g0 < 0) g0 = i
            if (!inG && g0 >= 0) {
                val u0 = bays[g0].c - bays[g0].w / 2; val u1 = bays[i - 1].c + bays[i - 1].w / 2
                val mask = bays[g0].bal
                for (fl in 1..4) if ((mask shr (fl - 1)) and 1 == 1) {
                    val (fs, fh) = floorOf(st, fl)
                    if (fs + fh > relTop) continue
                    balcony(wx((u0 + u1) / 2, 0f), wy((u0 + u1) / 2, 0f), rot, nx, ny, (u1 - u0) / 2 + 0.25f, Z.GROUND + fs + 0.5f, true, st.type == 1, es + fl * 31)
                }
                g0 = -1
            }
        }
        val pnF = if (st.pn) 1.22f else 1f
        for (bay in bays) {
            if (bay.kind == 2) continue
            // ---- ground floor: paline in front of water doors, awnings over shops
            if (bay.ground == 1 && !water && rnd(es + bay.jseed, 13) < 0.22f) {
                // potted shrubs either side of a front door (vegetation mesh: shorter draw distance, dither fade)
                val saved = gen; gen = veg
                for (side in intArrayOf(-1, 1)) {
                    val u = bay.c + side * (min(bay.w, 1.6f) / 2 + 0.3f)
                    gen.color(0.50f, 0.28f, 0.18f, 0.05f); gen.frustum(wx(u, 0.35f), wy(u, 0.35f), Z.GROUND, 0.2f, 0.26f, 0.5f, 6)
                    gen.color(0.18f, 0.34f + rnd(es, 14) * 0.08f, 0.13f, 0f)
                    gen.frustum(wx(u, 0.35f), wy(u, 0.35f), Z.GROUND + 0.5f, 0.32f, 0.12f, 0.9f + rnd(es + bay.jseed, 15) * 0.6f, 7)
                }
                gen = saved
            }
            if (bay.ground != 3 && st.type == 0 && rnd(es + bay.jseed, 16) < 0.04f) {
                // ivy climbing the wall from the ground
                val saved = gen; gen = veg
                val hgt = 2.5f + rnd(es + bay.jseed, 17) * 4f
                for (k in 0 until 9) {
                    val u = bay.c + (rnd(es + k, 18) - 0.5f) * bay.w * 0.9f
                    val z0 = Z.GROUND + rnd(es + k, 19) * hgt * 0.7f
                    val sz = 0.5f + rnd(es + k, 20) * 0.7f
                    gen.color(0.16f + rnd(es + k, 21) * 0.06f, 0.30f + rnd(es + k, 22) * 0.1f, 0.12f, 0.05f)
                    gen.quad(Vec3(wx(u - sz, 0.06f), wy(u - sz, 0.06f), z0), Vec3(wx(u + sz, 0.06f), wy(u + sz, 0.06f), z0),
                        Vec3(wx(u + sz * 0.7f, 0.1f), wy(u + sz * 0.7f, 0.1f), z0 + sz * 1.6f), Vec3(wx(u - sz * 0.7f, 0.1f), wy(u - sz * 0.7f, 0.1f), z0 + sz * 1.6f))
                }
                gen = saved
            }
            if (water && bay.ground != 3) {
                // more poles on the water: a painted palina in front of palazzi, plain timber mooring poles elsewhere
                val r1 = rnd(es + bay.jseed, 40)
                val off = 1.4f + rnd(es + bay.jseed, 41) * 0.6f
                if (st.type in 1..3 && r1 < 0.28f) palina(wx(bay.c, off), wy(bay.c, off), Palette.PALINA[(rnd(es, 7) * 4).toInt().coerceAtMost(3)])
                else if (r1 < 0.24f) {
                    woodPole(wx(bay.c - bay.w * 0.2f, off), wy(bay.c - bay.w * 0.2f, off), es + bay.jseed)
                    if (rnd(es + bay.jseed, 42) < 0.4f) woodPole(wx(bay.c + bay.w * 0.25f, off + 0.2f), wy(bay.c + bay.w * 0.25f, off + 0.2f), es + bay.jseed + 5)
                }
            }
            if (bay.ground == 3) {
                val cols = Palette.PALINA[(rnd(es, 7) * 4).toInt().coerceAtMost(3)]
                for (side in intArrayOf(-1, 1)) palina(wx(bay.c + side * (min(bay.w - 0.5f, 2.4f) / 2 + 0.5f), 1.3f), wy(bay.c + side * (min(bay.w - 0.5f, 2.4f) / 2 + 0.5f), 1.3f), cols)
            }
            if (bay.ground == 0 && !water && st.type != 1 && rnd(es + bay.jseed, 5) < 0.5f) {
                // iron grate (inferriata) over a ground-floor window
                val gw = min(st.winW, bay.w - 0.5f) / 2 + 0.06f
                val z0 = Z.GROUND + 0.75f; val z1 = Z.GROUND + min(st.groundH - 0.5f, 2.3f)
                det.color(Palette.IRON)
                val nbar = max(3, (gw * 2 / 0.14f).toInt())
                for (k in 0..nbar) { val u = bay.c - gw + 2 * gw * k / nbar; det.bar(wx(u, 0.07f), wy(u, 0.07f), (z0 + z1) / 2, 0.012f, 0.012f, (z1 - z0) / 2, rot) }
                for (zz in floatArrayOf(z0 + 0.05f, (z0 + z1) / 2, z1 - 0.05f)) det.box(wx(bay.c, 0.07f), wy(bay.c, 0.07f), zz, gw, 0.015f, 0.012f, rot)
            }
            if (bay.ground == 2 && rnd(es + bay.jseed, 3) < 0.45f) {
                val col = Palette.AWNING[(rnd(es + bay.jseed, 4) * 4).toInt().coerceAtMost(3)]
                det.color(col[0], col[1], col[2], 0.05f)
                val hw = bay.w / 2 - 0.2f; val zt = Z.GROUND + min(st.groundH - 0.55f, 2.8f) + 0.35f
                det.quad(Vec3(wx(bay.c - hw, 0.05f), wy(bay.c - hw, 0.05f), zt), Vec3(wx(bay.c + hw, 0.05f), wy(bay.c + hw, 0.05f), zt),
                    Vec3(wx(bay.c + hw, 1.1f), wy(bay.c + hw, 1.1f), zt - 0.55f), Vec3(wx(bay.c - hw, 1.1f), wy(bay.c - hw, 1.1f), zt - 0.55f))
                det.quad(Vec3(wx(bay.c - hw, 1.1f), wy(bay.c - hw, 1.1f), zt - 0.55f), Vec3(wx(bay.c + hw, 1.1f), wy(bay.c + hw, 1.1f), zt - 0.55f),
                    Vec3(wx(bay.c + hw, 1.1f), wy(bay.c + hw, 1.1f), zt - 0.8f), Vec3(wx(bay.c - hw, 1.1f), wy(bay.c - hw, 1.1f), zt - 0.8f))
            }
            if (bay.kind != 0) continue
            // ---- upper floors: single balconies, open shutter leaves
            for (fl in 1..8) {
                val (fs, fh) = floorOf(st, fl)
                if (fs + fh > relTop + 0.05f) break
                val ow = min(st.winW, bay.w - 0.4f)
                val balHere = (bay.bal shr (fl - 1)) and 1 == 1
                val sill = if (balHere) 0.05f else if (fl == 1 && st.pn) 0.75f else 0.9f
                val y1 = min(fh - 0.45f, (if (fl == 1 && st.pn) 0.75f else 0.9f) + st.winH * (fh / st.floorH))
                val jit = ((bay.jseed * 7 + fl * 13) % 17) / 16f - 0.5f
                val xoff = if (st.pn || st.mat == 3) 0f else jit * max(0f, bay.w - ow - 0.6f) * 0.35f
                val cu = bay.c + xoff
                if (balHere) balcony(wx(cu, 0f), wy(cu, 0f), rot, nx, ny, ow / 2 + 0.28f, Z.GROUND + fs - 0.02f, st.type in 1..3, false, es + bay.jseed * 17 + fl)
                // washing hung out on a line along the facade (ordinary houses, never on palazzi or over a balcony)
                if (!balHere && st.type == 0 && fl <= 3 && rnd(es + bay.jseed, 60 + fl) < 0.06f) laundry(::wx, ::wy, rot, cu, Z.GROUND + fs + sill - 0.25f, es + bay.jseed * 3 + fl)
                val rectish = st.winType == 0 || st.winType >= 4 || (st.type == 0 && st.winType == 1)
                if (st.shutter != 0 && rectish && (bay.jseed * 5 + fl * 11) % 7 >= 2) {
                    val col = Palette.SHUTTER[st.shutter]
                    det.mat(L.SHUTTER, col[0], col[1], col[2])
                    val h0 = Z.GROUND + fs + sill; val h1 = Z.GROUND + fs + y1
                    val lw = ow / 2
                    for (side in intArrayOf(-1, 1)) {
                        // leaf hinged at the jamb, swung open ~160 degrees, standing 4 cm off the wall
                        val hu = cu + side * (ow / 2 + 0.06f)
                        val ang = 0.35f
                        val eu = hu + side * lw * cos(ang); val eo = 0.04f + lw * sin(ang)
                        det.quad(Vec3(wx(hu, 0.04f), wy(hu, 0.04f), h0), Vec3(wx(eu, eo), wy(eu, eo), h0), Vec3(wx(eu, eo), wy(eu, eo), h1), Vec3(wx(hu, 0.04f), wy(hu, 0.04f), h1))
                    }
                }
                // a few flower boxes on sills
                if (!balHere && rnd(es + bay.jseed, 30 + fl) < 0.07f) {
                    det.color(0.45f, 0.25f, 0.18f, 0f)
                    val zz = Z.GROUND + fs + sill
                    det.box(wx(cu, 0.17f), wy(cu, 0.17f), zz + 0.08f, ow / 2, 0.11f, 0.08f, rot)
                    det.color(0.22f, 0.40f, 0.14f, 0f)
                    det.box(wx(cu, 0.17f), wy(cu, 0.17f), zz + 0.22f, ow / 2 - 0.03f, 0.13f, 0.08f, rot)
                    det.color(0.85f, 0.20f, 0.25f, 0f)
                    det.box(wx(cu + ow * 0.2f, 0.22f), wy(cu + ow * 0.2f, 0.22f), zz + 0.31f, 0.07f, 0.06f, 0.04f, rot)
                }
            }
        }
        // ---- downpipe at one end and a gutter along the eave (plain houses)
        if (st.type == 0 || st.type == 4) {
            if (len > 5f && rnd(es, 11) < 0.45f) {
                det.color(0.36f, 0.30f, 0.26f, 0.2f)
                val u = if (rnd(es, 12) < 0.5f) 0.25f else len - 0.25f
                det.frustum(wx(u, 0.1f), wy(u, 0.1f), Z.GROUND, 0.055f, 0.055f, top - 0.4f - Z.GROUND, 6)
            }
            if (len > 3f) {
                det.color(0.40f, 0.33f, 0.28f, 0.25f)
                det.box(wx(len / 2, 0.36f), wy(len / 2, 0.36f), top - 0.08f, len / 2, 0.07f, 0.06f, rot)
            }
        }
    }

    /** Balcony on brackets. Stone: balusters, a gothic arcade of little columns, or a solid carved parapet.
     *  Iron (poggiolo): straight bars, the bellied Venetian "pancia", or bars with a crossed band. Some carry plants. */
    private fun balcony(cx: Float, cy: Float, rot: Float, nx: Float, ny: Float, hw: Float, z: Float, stone: Boolean, gothic: Boolean, seed: Int) {
        val style = (rnd(seed, 1) * 3).toInt().coerceAtMost(2)
        val d = if (stone) 0.55f else 0.42f + (if (!stone && style == 1) 0.08f else 0f)
        val ox = cx + nx * d / 2; val oy = cy + ny * d / 2
        val tx = -ny; val ty = nx
        det.color(Palette.ISTRIAN)
        det.box(ox, oy, z - 0.07f, hw, d / 2, 0.08f, rot)
        // moulded edge under the slab
        det.box(ox + nx * 0.02f, oy + ny * 0.02f, z - 0.18f, hw - 0.04f, d / 2 - 0.04f, 0.04f, rot)
        // brackets (mensole)
        val nb = max(2, (hw * 2 / 0.9f).toInt())
        for (k in 0..nb) {
            val f = -hw + 0.12f + (2 * hw - 0.24f) * k / nb
            det.box(cx + tx * f + nx * d * 0.35f, cy + ty * f + ny * d * 0.35f, z - 0.3f, 0.07f, d * 0.35f, 0.12f, rot)
            det.box(cx + tx * f + nx * d * 0.15f, cy + ty * f + ny * d * 0.15f, z - 0.45f, 0.06f, d * 0.15f, 0.07f, rot)
        }
        fun front(f: Float, out: Float) = floatArrayOf(ox + tx * f + nx * (d / 2 - out), oy + ty * f + ny * (d / 2 - out))
        if (stone) {
            val g = gothic || style == 1
            when {
                g -> {
                    // gothic arcade: slender columns, pointed arches between them, a rail on top
                    val n = max(3, (hw * 2 / 0.32f).toInt())
                    for (k in 0..n) {
                        val f = -hw + 0.08f + (2 * hw - 0.16f) * k / n; val p = front(f, 0.07f)
                        det.bar(p[0], p[1], z + 0.25f, 0.035f, 0.035f, 0.25f, rot)
                        if (k < n) {
                            val f2 = -hw + 0.08f + (2 * hw - 0.16f) * (k + 0.5f) / n; val q = front(f2, 0.07f)
                            val span = (2 * hw - 0.16f) / n
                            det.box(q[0], q[1], z + 0.56f, span * 0.28f, 0.05f, 0.025f, rot + 0.5f)
                            det.box(q[0], q[1], z + 0.56f, span * 0.28f, 0.05f, 0.025f, rot - 0.5f)
                        }
                    }
                    val p = front(0f, 0.07f); det.box(p[0], p[1], z + 0.66f, hw, 0.08f, 0.05f, rot)
                }
                style == 2 -> {
                    // solid parapet with a carved, recessed panel
                    val p = front(0f, 0.08f); det.box(p[0], p[1], z + 0.35f, hw, 0.08f, 0.35f, rot)
                    det.color(Palette.ISTRIAN[0] * 0.85f, Palette.ISTRIAN[1] * 0.84f, Palette.ISTRIAN[2] * 0.8f, 0.1f)
                    val q = front(0f, -0.005f); det.box(q[0], q[1], z + 0.35f, hw - 0.18f, 0.01f, 0.2f, rot)
                    det.color(Palette.ISTRIAN)
                    val r = front(0f, 0.08f); det.box(r[0], r[1], z + 0.72f, hw + 0.02f, 0.1f, 0.04f, rot)
                }
                else -> {
                    val n = max(3, (hw * 2 / 0.19f).toInt())
                    for (k in 0..n) {
                        val f = -hw + 0.06f + (2 * hw - 0.12f) * k / n; val p = front(f, 0.08f)
                        det.bar(p[0], p[1], z + 0.3f, 0.038f, 0.038f, 0.3f, rot + 0.785f)
                    }
                    val p = front(0f, 0.08f); det.box(p[0], p[1], z + 0.66f, hw, 0.08f, 0.05f, rot)
                }
            }
            for (side in intArrayOf(-1, 1)) det.box(ox + tx * side * (hw - 0.06f), oy + ty * side * (hw - 0.06f), z + 0.33f, 0.07f, d / 2 - 0.02f, 0.33f, rot)
        } else {
            det.color(Palette.IRON)
            val n = max(4, (hw * 2 / 0.15f).toInt())
            for (k in 0..n) {
                val f = -hw + 0.03f + (2 * hw - 0.06f) * k / n
                if (style == 1) {
                    // bellied railing: each bar bows out over the street
                    val a = front(f, 0.11f); val m = front(f, -0.04f); val t = front(f, 0.06f)
                    det.bar((a[0] + m[0]) / 2, (a[1] + m[1]) / 2, z + 0.25f, 0.009f, 0.009f, 0.26f, rot)
                    det.bar((m[0] + t[0]) / 2, (m[1] + t[1]) / 2, z + 0.73f, 0.009f, 0.009f, 0.25f, rot)
                } else {
                    val p = front(f, 0.03f); det.bar(p[0], p[1], z + 0.48f, 0.009f, 0.009f, 0.48f, rot)
                }
            }
            val bulge = if (style == 1) -0.02f else 0.03f
            val top = front(0f, if (style == 1) 0.06f else 0.03f); det.box(top[0], top[1], z + 0.97f, hw, 0.025f, 0.02f, rot)
            val low = front(0f, bulge); det.box(low[0], low[1], z + 0.12f, hw, 0.015f, 0.015f, rot)
            if (style == 2) {
                // decorative band: a row of crosses
                val bz = z + 0.72f; val p = front(0f, 0.03f)
                det.box(p[0], p[1], bz + 0.1f, hw, 0.012f, 0.012f, rot); det.box(p[0], p[1], bz - 0.1f, hw, 0.012f, 0.012f, rot)
                val nc = max(2, (hw * 2 / 0.25f).toInt())
                for (k in 0 until nc) {
                    val f = -hw + (2 * hw) * (k + 0.5f) / nc; val q = front(f, 0.03f)
                    det.box(q[0], q[1], bz, 0.11f, 0.01f, 0.01f, rot + 0.785f); det.box(q[0], q[1], bz, 0.11f, 0.01f, 0.01f, rot - 0.785f)
                }
            }
            for (side in intArrayOf(-1, 1)) det.box(ox + tx * side * (hw - 0.02f), oy + ty * side * (hw - 0.02f), z + 0.97f, 0.02f, d / 2 - 0.02f, 0.02f, rot)
        }
        // plants: pots at the ends, greenery spilling over, sometimes geraniums
        if (rnd(seed, 7) < 0.35f) {
            for (side in intArrayOf(-1, 1)) {
                if (rnd(seed, 8 + side) < 0.3f) continue
                val f = side * (hw - 0.22f); val p = front(f, d / 2)
                det.color(0.55f, 0.30f, 0.20f, 0.05f); det.frustum(p[0], p[1], z + 0.01f, 0.13f, 0.17f, 0.25f, 6)
                det.color(0.20f + rnd(seed, 9) * 0.1f, 0.38f, 0.14f, 0f); det.box(p[0], p[1], z + 0.42f, 0.2f, 0.17f, 0.18f, rot + rnd(seed, 10))
                if (rnd(seed, 11 + side) < 0.5f) { det.color(0.85f, 0.15f, 0.2f, 0f); det.box(p[0], p[1], z + 0.6f, 0.13f, 0.1f, 0.05f, rot) }
            }
        }
    }

    /** A washing line along the facade under a window, with a few clothes pegged on it. */
    private fun laundry(wx: (Float, Float) -> Float, wy: (Float, Float) -> Float, rot: Float, cu: Float, z: Float, seed: Int) {
        val hl = 0.9f + rnd(seed, 1) * 0.6f
        det.color(0.75f, 0.75f, 0.72f, 0.2f)
        for (side in intArrayOf(-1, 1)) det.box(wx(cu + side * hl, 0.18f), wy(cu + side * hl, 0.18f), z, 0.015f, 0.18f, 0.015f, rot)
        det.box(wx(cu, 0.35f), wy(cu, 0.35f), z, hl, 0.006f, 0.006f, rot)
        val cols = arrayOf(floatArrayOf(0.92f, 0.92f, 0.9f), floatArrayOf(0.25f, 0.35f, 0.6f), floatArrayOf(0.8f, 0.25f, 0.25f), floatArrayOf(0.9f, 0.8f, 0.4f), floatArrayOf(0.4f, 0.6f, 0.45f), floatArrayOf(0.95f, 0.7f, 0.75f))
        var u = cu - hl + 0.15f; var k = 0
        while (u < cu + hl - 0.15f) {
            val w = 0.18f + rnd(seed, 20 + k) * 0.25f; val h = 0.3f + rnd(seed, 30 + k) * 0.4f
            val c = cols[(rnd(seed, 40 + k) * cols.size).toInt().coerceAtMost(cols.size - 1)]
            det.color(c[0], c[1], c[2], 0.02f)
            det.quad(Vec3(wx(u, 0.35f), wy(u, 0.35f), z), Vec3(wx(u + w, 0.35f), wy(u + w, 0.35f), z), Vec3(wx(u + w, 0.37f), wy(u + w, 0.37f), z - h), Vec3(wx(u, 0.37f), wy(u, 0.37f), z - h))
            u += w + 0.08f + rnd(seed, 50 + k) * 0.15f; k++
        }
    }

    /** Striped mooring pole (palina) in front of a palazzo's water door. */
    /** a plain timber mooring pole: weathered wood, green slime band at the waterline, dark top */
    private fun woodPole(x: Float, y: Float, seed: Int) {
        val z0 = Z.WALL_BOTTOM; val top = 3.2f + rnd(seed, 1) * 1.4f
        val r = 0.11f + rnd(seed, 2) * 0.04f
        det.color(0.16f, 0.19f, 0.11f, 0.35f); det.frustum(x, y, z0, r, r * 0.98f, 0.55f - z0, 7)
        val w = 0.26f + rnd(seed, 3) * 0.1f
        det.color(w, w * 0.8f, w * 0.6f, 0.05f); det.frustum(x, y, 0.55f, r * 0.98f, r * 0.85f, top - 0.55f, 7)
        det.color(0.12f, 0.10f, 0.08f, 0.05f); det.frustum(x, y, top, r * 0.85f, r * 0.4f, 0.12f, 7, capTop = true)
    }

    private fun palina(x: Float, y: Float, col: FloatArray) {
        val z0 = Z.WALL_BOTTOM; val h = 5.2f - Z.WALL_BOTTOM
        val n = 9
        for (k in 0 until n) {
            if (k % 2 == 0) det.color(col[0], col[1], col[2], 0.3f) else det.color(0.92f, 0.92f, 0.88f, 0.3f)
            val za = z0 + h * k / n
            det.frustum(x, y, za, 0.12f - 0.03f * k / n, 0.12f - 0.03f * (k + 1) / n, h / n, 7, capTop = k == n - 1)
        }
        det.color(col[0], col[1], col[2], 0.3f)
        det.frustum(x, y, z0 + h, 0.1f, 0.02f, 0.25f, 7)
    }

    // ------------------------------------------------------------------ roofs: hip / gable / flat over the oriented box
    /** Builds the roof and returns its height above `top` at any world point of the footprint. */
    private fun roof(b: BuildingData, st: Style, top: Float, seed01: Float): (Float, Float) -> Float {
        val n0 = b.ringSizes[0]
        // oriented bounding rectangle: best of the footprint's own edge directions
        var best = 1e18f; var ang = 0f; var cxB = 0f; var cyB = 0f; var hA = 1f; var hB = 1f
        for (i in 0 until n0) {
            val j = (i + 1) % n0
            val a = atan2(b.xy[j * 2 + 1] - b.xy[i * 2 + 1], b.xy[j * 2] - b.xy[i * 2])
            val c = cos(a); val s = sin(a)
            var x0 = 1e9f; var x1 = -1e9f; var y0 = 1e9f; var y1 = -1e9f
            for (k in 0 until n0) { val px = b.xy[k * 2] * c + b.xy[k * 2 + 1] * s; val py = -b.xy[k * 2] * s + b.xy[k * 2 + 1] * c
                x0 = min(x0, px); x1 = max(x1, px); y0 = min(y0, py); y1 = max(y1, py) }
            val area = (x1 - x0) * (y1 - y0)
            if (area < best) {
                best = area
                val mx = (x0 + x1) / 2; val my = (y0 + y1) / 2
                if (x1 - x0 >= y1 - y0) { ang = a; hA = (x1 - x0) / 2; hB = (y1 - y0) / 2; cxB = mx * c - my * s; cyB = mx * s + my * c }
                else { ang = a + PI.toFloat() / 2; hA = (y1 - y0) / 2; hB = (x1 - x0) / 2; cxB = mx * c - my * s; cyB = mx * s + my * c }
            }
        }
        val ex = cos(ang); val ey = sin(ang)
        val A = hA; val B = hB
        val type = if (b.height < 5f) 2 else st.roof
        val s = st.slope
        val cap = if (type == 2) 0f else min(B, 3.0f / s)
        val lx = { x: Float, y: Float -> (x - cxB) * ex + (y - cyB) * ey }
        val ly = { x: Float, y: Float -> -(x - cxB) * ey + (y - cyB) * ex }
        val hFn: (Float, Float) -> Float = when (type) {
            0 -> { x, y -> s * max(0f, minOf(B - abs(ly(x, y)), A - abs(lx(x, y)), cap)) }
            1 -> { x, y -> s * max(0f, min(B - abs(ly(x, y)), cap)) }
            else -> { _, _ -> 0f }
        }
        // facets: convex region (local coords) + plane: z = s * (k0 + kx*lx + ky*ly), downslope direction (local)
        class Facet(val poly: FloatArray, val k0: Float, val kx: Float, val ky: Float, val dx: Float, val dy: Float)
        val c = cap
        val fs = ArrayList<Facet>()
        when (type) {
            0 -> {
                fs.add(Facet(floatArrayOf(-A, B, -A + c, B - c, A - c, B - c, A, B), B, 0f, -1f, 0f, 1f))
                fs.add(Facet(floatArrayOf(A, -B, A - c, -B + c, -A + c, -B + c, -A, -B), B, 0f, 1f, 0f, -1f))
                fs.add(Facet(floatArrayOf(A, B, A - c, B - c, A - c, -B + c, A, -B), A, -1f, 0f, 1f, 0f))
                fs.add(Facet(floatArrayOf(-A, -B, -A + c, -B + c, -A + c, B - c, -A, B), A, 1f, 0f, -1f, 0f))
                if (c < B - 0.01f) fs.add(Facet(floatArrayOf(-A + c, -B + c, -A + c, B - c, A - c, B - c, A - c, -B + c), c, 0f, 0f, 0f, 1f))
            }
            1 -> {
                fs.add(Facet(floatArrayOf(-A, B, -A, B - c, A, B - c, A, B), B, 0f, -1f, 0f, 1f))
                fs.add(Facet(floatArrayOf(A, -B, A, -B + c, -A, -B + c, -A, -B), B, 0f, 1f, 0f, -1f))
                if (c < B - 0.01f) fs.add(Facet(floatArrayOf(-A, -B + c, -A, B - c, A, B - c, A, -B + c), c, 0f, 0f, 0f, 1f))
            }
            else -> fs.add(Facet(floatArrayOf(-A - 1f, -B - 1f, -A - 1f, B + 1f, A + 1f, B + 1f, A + 1f, -B - 1f), 0f, 0f, 0f, 0f, 1f))
        }
        val tris = b.roofTris
        val bufA = FloatArray(64); val bufB = FloatArray(64)
        for (f in fs) {
            // world-space normal & uv frame of this facet
            val gx = f.kx * ex - f.ky * ey; val gy = f.kx * ey + f.ky * ex      // gradient of the plane (per metre) in world
            var nxw = -s * gx; var nyw = -s * gy; var nzw = 1f
            val nl = sqrt(nxw * nxw + nyw * nyw + nzw * nzw); nxw /= nl; nyw /= nl; nzw /= nl
            val ddx = f.dx * ex - f.dy * ey; val ddy = f.dx * ey + f.dy * ex     // downslope (world)
            for (k in tris.indices step 3) {
                // triangle in local coords, clipped against the facet's convex region
                var m = 0
                for (q in 0 until 3) { val vi = tris[k + q].toInt(); val x = b.xy[vi * 2]; val y = b.xy[vi * 2 + 1]; bufA[m * 2] = lx(x, y); bufA[m * 2 + 1] = ly(x, y); m++ }
                // orientation of the clip polygon
                val pp = f.poly; val pn = pp.size / 2
                var area = 0f
                for (q in 0 until pn) { val r = (q + 1) % pn; area += pp[q * 2] * pp[r * 2 + 1] - pp[r * 2] * pp[q * 2 + 1] }
                val sign = if (area >= 0) 1f else -1f
                var src = bufA; var dst = bufB
                for (q in 0 until pn) {
                    val r = (q + 1) % pn
                    val ax = pp[q * 2]; val ay = pp[q * 2 + 1]; val bx = pp[r * 2]; val by = pp[r * 2 + 1]
                    if (abs(bx - ax) + abs(by - ay) < 1e-5f) continue
                    var o = 0
                    fun inside(x: Float, y: Float) = sign * ((bx - ax) * (y - ay) - (by - ay) * (x - ax)) >= -1e-5f
                    for (v in 0 until m) {
                        val w = (v + 1) % m
                        val px = src[v * 2]; val py = src[v * 2 + 1]; val qx = src[w * 2]; val qy = src[w * 2 + 1]
                        val pin = inside(px, py); val qin = inside(qx, qy)
                        if (pin && o < 30) { dst[o * 2] = px; dst[o * 2 + 1] = py; o++ }
                        if (pin != qin && o < 30) {
                            val d1 = (bx - ax) * (py - ay) - (by - ay) * (px - ax); val d2 = (bx - ax) * (qy - ay) - (by - ay) * (qx - ax)
                            val tt = d1 / (d1 - d2)
                            dst[o * 2] = px + (qx - px) * tt; dst[o * 2 + 1] = py + (qy - py) * tt; o++
                        }
                    }
                    m = o; val tmp = src; src = dst; dst = tmp
                    if (m < 3) break
                }
                if (m < 3) continue
                // fan: lift to the facet plane, emit (CCW from above)
                fun emit(q: Int) {
                    val px = src[q * 2]; val py = src[q * 2 + 1]
                    val wx = cxB + px * ex - py * ey; val wy = cyB + px * ey + py * ex
                    val z = top + s * max(0f, f.k0 + f.kx * px + f.ky * py)
                    fac.v(wx, wy, z, nxw, nyw, nzw, wx * -ddy + wy * ddx, wx * ddx + wy * ddy, 0f, seed01, Kind.ROOF, 0f)
                }
                for (q in 1 until m - 1) {
                    val x0 = src[0]; val y0 = src[1]; val x1 = src[q * 2]; val y1 = src[q * 2 + 1]; val x2 = src[q * 2 + 2]; val y2 = src[q * 2 + 3]
                    val cr = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
                    if (abs(cr) < 1e-6f) continue
                    if (cr > 0) { emit(0); emit(q); emit(q + 1) } else { emit(0); emit(q + 1); emit(q) }
                }
            }
        }
        return hFn
    }

    // ------------------------------------------------------------------ roof furniture: chimneys, altane, antennas
    private fun roofProps(b: BuildingData, st: Style, top: Float, roofH: (Float, Float) -> Float) {
        val n = b.roofTris.size / 3
        if (n == 0 || b.height < 6f) return
        var area = 0f
        for (k in 0 until n) area += triArea(b, k)
        fun randomPoint(k0: Int): FloatArray {
            var pick = rnd(b.seed, k0) * area; var k = 0
            while (k < n - 1 && pick > triArea(b, k)) { pick -= triArea(b, k); k++ }
            val i0 = b.roofTris[k * 3].toInt(); val i1 = b.roofTris[k * 3 + 1].toInt(); val i2 = b.roofTris[k * 3 + 2].toInt()
            var u = rnd(b.seed, k0 + 1); var v = rnd(b.seed, k0 + 2); if (u + v > 1) { u = 1 - u; v = 1 - v }
            return floatArrayOf(b.xy[i0 * 2] + (b.xy[i1 * 2] - b.xy[i0 * 2]) * u + (b.xy[i2 * 2] - b.xy[i0 * 2]) * v,
                b.xy[i0 * 2 + 1] + (b.xy[i1 * 2 + 1] - b.xy[i0 * 2 + 1]) * u + (b.xy[i2 * 2 + 1] - b.xy[i0 * 2 + 1]) * v)
        }
        // chimneys: several Venetian shapes - funnel (a fumaiolo), inverted bell, cube with a little roof, plain stack
        val count = when { area < 50f -> if (rnd(b.seed, 1) < 0.6f) 1 else 0; area < 200f -> 1 + (rnd(b.seed, 2) * 2.5f).toInt(); else -> 2 + (rnd(b.seed, 3) * 4).toInt() }
        val shape = (rnd(b.seed, 9) * 4).toInt().coerceAtMost(3)
        for (c in 0 until count) {
            val p = randomPoint(10 + c * 3)
            val base = top + roofH(p[0], p[1])
            val col = Palette.CHIMNEY[(rnd(b.seed, 40 + c) * 3).toInt().coerceAtMost(2)]
            gen.mat(L.PLASTER0 + 2, col[0] * 1.25f, col[1] * 1.25f, col[2] * 1.25f)
            val h = 1.3f + rnd(b.seed, 50 + c) * 0.9f
            val rot = rnd(b.seed, 60 + c) * 1.57f
            gen.box(p[0], p[1], base - 0.3f + (h + 0.3f) / 2, 0.27f, 0.27f, (h + 0.3f) / 2, rot)
            val zt = base + h
            when ((shape + c) % 4) {
                0 -> { gen.frustum(p[0], p[1], zt, 0.22f, 0.58f, 0.75f, 8); gen.color(0.12f, 0.11f, 0.1f, 0f); gen.frustum(p[0], p[1], zt + 0.75f, 0.52f, 0.4f, 0.08f, 8) }
                1 -> { gen.frustum(p[0], p[1], zt, 0.3f, 0.48f, 0.35f, 8); gen.frustum(p[0], p[1], zt + 0.35f, 0.48f, 0.3f, 0.35f, 8); gen.color(0.12f, 0.11f, 0.1f, 0f); gen.frustum(p[0], p[1], zt + 0.7f, 0.3f, 0.2f, 0.05f, 8) }
                2 -> { gen.box(p[0], p[1], zt + 0.25f, 0.4f, 0.4f, 0.25f, rot); gen.mat(L.ROOF0, 1f, 1f, 1f); gen.frustum(p[0], p[1], zt + 0.5f, 0.55f, 0.05f, 0.35f, 4) }
                else -> { gen.color(0.12f, 0.11f, 0.1f, 0f); gen.box(p[0], p[1], zt + 0.04f, 0.3f, 0.3f, 0.04f, rot) }
            }
        }
        // altana: the wooden roof terrace on posts, typical of Venice
        if (st.type == 0 && area > 45f && rnd(b.seed, 70) < 0.24f) {
            val p = randomPoint(80)
            val h0 = top + roofH(p[0], p[1])
            val zDeck = top + max(roofH(p[0], p[1]) + 0.9f, 2.0f)
            val w = 1.4f + rnd(b.seed, 71) * 0.8f; val d = 1.1f + rnd(b.seed, 72) * 0.6f
            val rot = rnd(b.seed, 73) * 3.14f
            val c = cos(rot); val s = sin(rot)
            det.mat(L.PLANKS_OLD, 0.9f, 0.85f, 0.8f)
            det.box(p[0], p[1], zDeck, w, d, 0.05f, rot)
            for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) {
                val px = p[0] + c * sx * (w - 0.1f) - s * sy * (d - 0.1f); val py = p[1] + s * sx * (w - 0.1f) + c * sy * (d - 0.1f)
                val zb = top + roofH(px, py) - 0.1f
                det.box(px, py, (zb + zDeck + 1.0f) / 2, 0.06f, 0.06f, (zDeck + 1.0f - zb) / 2, rot)
            }
            for (sy in intArrayOf(-1, 1)) det.box(p[0] - s * sy * (d - 0.1f), p[1] + c * sy * (d - 0.1f), zDeck + 1.0f, w, 0.04f, 0.04f, rot)
            for (sx in intArrayOf(-1, 1)) det.box(p[0] + c * sx * (w - 0.1f), p[1] + s * sx * (w - 0.1f), zDeck + 1.0f, 0.04f, d, 0.04f, rot)
            for (sy in intArrayOf(-1, 1)) det.box(p[0] - s * sy * (d - 0.1f), p[1] + c * sy * (d - 0.1f), zDeck + 0.5f, w, 0.03f, 0.03f, rot)
            if (h0 < zDeck) { det.color(0.25f, 0.4f, 0.18f, 0f); det.box(p[0] + c * (w - 0.4f), p[1] + s * (w - 0.4f), zDeck + 0.2f, 0.2f, 0.2f, 0.15f, rot) }
        }
        // TV antennas
        if (rnd(b.seed, 90) < 0.35f) {
            val p = randomPoint(95)
            val base = top + roofH(p[0], p[1])
            det.color(0.55f, 0.56f, 0.58f, 0.6f)
            det.frustum(p[0], p[1], base, 0.025f, 0.02f, 2.6f, 4)
            val rot = rnd(b.seed, 96) * 3.14f
            for (k in 0..2) det.box(p[0], p[1], base + 1.8f + k * 0.3f, 0.5f - k * 0.1f, 0.01f, 0.01f, rot)
        }
    }

    /** Campanile: brick shaft (facade shader draws lesene and the belfry arcade), stone cornice, spire. */
    private fun tower(b: BuildingData) {
        val n = b.ringSizes[0]
        val total = Z.GROUND + b.height
        val r = rnd(b.seed, 5)
        val spireH = b.height * (0.20f + 0.08f * r)
        val top = total - spireH
        val seed01 = (b.seed and 0xffff) / 65535f
        fac.style(0f, 0f, 0f, 0f)
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ax = b.xy[i * 2]; val ay = b.xy[i * 2 + 1]; val bx = b.xy[j * 2]; val by = b.xy[j * 2 + 1]
            val dx = bx - ax; val dy = by - ay; val len = sqrt(dx * dx + dy * dy)
            if (len < 0.05f) continue
            val nx = dy / len; val ny = -dx / len
            val z0 = Z.WALL_BOTTOM; val z1 = top - 0.32f
            fac.v(ax, ay, z0, nx, ny, 0f, 0f, z0, len, seed01, Kind.TOWER, top)
            fac.v(bx, by, z0, nx, ny, 0f, len, z0, len, seed01, Kind.TOWER, top)
            fac.v(bx, by, z1, nx, ny, 0f, len, z1, len, seed01, Kind.TOWER, top)
            fac.v(ax, ay, z0, nx, ny, 0f, 0f, z0, len, seed01, Kind.TOWER, top)
            fac.v(bx, by, z1, nx, ny, 0f, len, z1, len, seed01, Kind.TOWER, top)
            fac.v(ax, ay, z1, nx, ny, 0f, 0f, z1, len, seed01, Kind.TOWER, top)
        }
        cornice(b, 0, n, top, seed01, null)
        var cx = 0f; var cy = 0f
        for (i in 0 until n) { cx += b.xy[i * 2]; cy += b.xy[i * 2 + 1] }
        cx /= n; cy /= n
        when { r < 0.35f -> gen.mat(L.LEAD, 0.55f, 0.82f, 0.70f); r < 0.7f -> gen.mat(L.BRICK0, 0.95f, 0.85f, 0.8f); else -> gen.mat(L.LEAD, 0.9f, 0.9f, 0.92f) }
        val apex = total
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ax = cx + (b.xy[i * 2] - cx) * 0.86f; val ay = cy + (b.xy[i * 2 + 1] - cy) * 0.86f
            val bx = cx + (b.xy[j * 2] - cx) * 0.86f; val by = cy + (b.xy[j * 2 + 1] - cy) * 0.86f
            gen.tri(ax, ay, top, bx, by, top, cx, cy, apex)
        }
        gen.mat(L.ISTRIAN, 1f, 1f, 1f)
        gen.frustum(cx, cy, apex - 0.2f, 0.18f, 0.05f, 1.6f, 6)   // finial
    }

    private fun cornice(b: BuildingData, start: Int, n: Int, top: Float, seed01: Float, st: Style?) {
        // palazzi: deep dentilled stone cornice; plain houses: a slim eave
        val over = when (st?.type) { null -> 0.28f; 0 -> 0.22f + rnd(b.seed, 77) * 0.1f; 4 -> 0.32f; else -> 0.42f }
        fac.style(0f, 0f, 0f, 0f)
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

    // ------------------------------------------------------------------ boats moored along the fondamenta
    /** Small boats tied up along a quay wall: only on canals at least ~9 m wide, never under a bridge, at a vaporetto
     *  stop or inside a landmark area. Sandoli, fibreglass barchini, varnished topi, and gondolas near San Marco. */
    private fun mooredBoats(p: FloatArray, t: TileData) {
        val n = p.size / 2
        if (n < 2) return
        var acc = 0f
        for (i in 0 until n - 1) {
            val ax = p[i * 2]; val ay = p[i * 2 + 1]; val bx = p[i * 2 + 2]; val by = p[i * 2 + 3]
            val dx = bx - ax; val dy = by - ay; val len = sqrt(dx * dx + dy * dy)
            if (len < 6f) { acc += len; continue }
            val tx = dx / len; val ty = dy / len; val nx = ty; val ny = -tx     // water side (as in quay())
            var u = 3f + rnd(i * 31 + n, 1) * 6f
            while (u < len - 3f) {
                val seed = ((t.tx * 7919 + t.ty * 104729) * 31 + i * 977 + (u * 10).toInt()) and 0x7fffffff
                val kind = rnd(seed, 1)
                val wx = t.ox + ax + tx * u; val wy = t.oy + ay + ty * u
                val gondolaZone = hypot(wx - 0f, wy - 0f) < 700f || hypot(wx + 240f, wy - 454f) < 350f
                val type = when { gondolaZone && kind < 0.45f -> 3; kind < 0.38f -> 0; kind < 0.75f -> 1; else -> 2 }
                val len2 = when (type) { 3 -> 10.8f; 0 -> 6.6f; 1 -> 5.4f; else -> 7.6f }
                val beam = when (type) { 3 -> 1.4f; 0 -> 1.25f; 1 -> 2.0f; else -> 2.3f }
                val off = 0.35f + beam / 2
                val cx = ax + tx * u + nx * off; val cy = ay + ty * u + ny * off
                if (rnd(seed, 2) < 0.5f && type != 3 || !mooringClear(t, cx, cy, nx, ny, tx, ty, len2)) { u += 4f + rnd(seed, 3) * 5f; continue }
                boat(cx, cy, atan2(ty, tx) + (if (rnd(seed, 4) < 0.5f) PI.toFloat() else 0f), type, len2, beam, seed)
                u += len2 + 0.8f + rnd(seed, 5) * 3f
            }
            acc += len
        }
    }

    private fun mooringClear(t: TileData, cx: Float, cy: Float, nx: Float, ny: Float, tx: Float, ty: Float, len: Float): Boolean {
        // stays inside this tile, open water under the hull, and the canal is wide enough to still get past
        for (k in -1..1) for (o in floatArrayOf(-0.2f, 1.2f, 4f, 8.5f)) {
            val x = cx + tx * k * len * 0.5f + nx * o; val y = cy + ty * k * len * 0.5f + ny * o
            if (x < 1f || y < 1f || x > TILE_SIZE - 1f || y > TILE_SIZE - 1f) return false
            if (t.solidAt(x, y) > 0.15f) return false
        }
        for (br in t.bridges) for (j in 0 until br.xy.size / 2) if (hypot(br.xy[j * 2] - cx, br.xy[j * 2 + 1] - cy) < 14f + br.width) return false
        for (br in t.bridges) for (j in 0 until br.xy.size / 2 - 1) {
            val ax = br.xy[j * 2]; val ay = br.xy[j * 2 + 1]; val ex = br.xy[j * 2 + 2] - ax; val ey = br.xy[j * 2 + 3] - ay
            val l2 = ex * ex + ey * ey; if (l2 < 1e-4f) continue
            val k = (((cx - ax) * ex + (cy - ay) * ey) / l2).coerceIn(0f, 1f)
            if (hypot(ax + ex * k - cx, ay + ey * k - cy) < len * 0.5f + 6f) return false
        }
        for (pt in t.points) if (pt.type == Feature.STOP && hypot(pt.x - cx, pt.y - cy) < 40f) return false
        if (Exclusions.zones.isNotEmpty() && Exclusions.inside(t.ox + cx, t.oy + cy)) return false
        return true
    }

    /** type 0 sandolo (dark wood), 1 fibreglass barchino, 2 varnished wooden topo, 3 gondola */
    private fun boat(cx: Float, cy: Float, rot: Float, type: Int, len: Float, beam: Float, seed: Int) {
        val c = cos(rot); val s = sin(rot)
        fun P(lx: Float, ly: Float, z: Float) = Vec3(cx + c * lx - s * ly, cy + s * lx + c * ly, z)
        val N = 10
        val sheer = when (type) { 3 -> 0.55f; 1 -> 0.6f; 2 -> 0.75f; else -> 0.4f }
        val bottom = -0.22f
        // plan: pointed bow (+x), fuller stern; gondola and sandolo pointed at both ends, ends sweeping up
        fun hw(f: Float): Float {
            val a = abs(f)
            return when (type) { 0, 3 -> beam / 2 * (1f - a.pow(2.2f)); else -> beam / 2 * (if (f > 0) 1f - f.pow(2.0f) else 1f - (a.pow(4f)) * 0.35f) }
        }
        fun zs(f: Float) = sheer + when (type) { 3 -> 0.55f * abs(f).pow(3f); 0 -> 0.25f * abs(f).pow(3f); else -> 0.18f * max(f, 0f).pow(2f) }
        val hull = when (type) {
            3 -> floatArrayOf(0.04f, 0.04f, 0.045f); 0 -> floatArrayOf(0.20f, 0.15f, 0.11f)
            1 -> if (rnd(seed, 20) < 0.5f) floatArrayOf(0.88f, 0.89f, 0.88f) else floatArrayOf(0.80f, 0.84f, 0.86f)
            else -> floatArrayOf(0.48f, 0.30f, 0.16f)
        }
        val stripe = Palette.SHUTTER[1 + (rnd(seed, 21) * 3).toInt().coerceAtMost(2)]
        for (k in 0 until N) {
            val f0 = -1f + 2f * k / N; val f1 = -1f + 2f * (k + 1) / N
            val x0 = f0 * len / 2; val x1 = f1 * len / 2
            for (sd in intArrayOf(-1, 1)) {
                det.color(hull[0], hull[1], hull[2], if (type == 2 || type == 3) 0.6f else 0.3f)
                det.quad(P(x0, sd * hw(f0) * 0.55f, bottom), P(x1, sd * hw(f1) * 0.55f, bottom), P(x1, sd * hw(f1), zs(f1)), P(x0, sd * hw(f0), zs(f0)))
                if (type == 1) { det.color(stripe[0], stripe[1], stripe[2], 0.4f); det.quad(P(x0, sd * hw(f0) * 1.01f, zs(f0) - 0.14f), P(x1, sd * hw(f1) * 1.01f, zs(f1) - 0.14f), P(x1, sd * hw(f1) * 1.01f, zs(f1) - 0.04f), P(x0, sd * hw(f0) * 1.01f, zs(f0) - 0.04f)) }
            }
            // deck / floorboards seen from above
            if (type == 2 || type == 1) det.mat(L.PLANKS, 0.8f, 0.72f, 0.62f) else det.color(hull[0] * 1.4f + 0.05f, hull[1] * 1.4f + 0.04f, hull[2] * 1.4f + 0.03f, 0.2f)
            det.quad(P(x0, -hw(f0) * 0.92f, zs(f0) - 0.25f), P(x1, -hw(f1) * 0.92f, zs(f1) - 0.25f), P(x1, hw(f1) * 0.92f, zs(f1) - 0.25f), P(x0, hw(f0) * 0.92f, zs(f0) - 0.25f))
        }
        // gunwale cap
        det.color(0.55f, 0.42f, 0.28f, 0.5f)
        if (type == 3) {
            // gondola: the steel ferro on the bow, the forcola, red-velvet seat
            det.color(0.85f, 0.86f, 0.88f, 0.8f); det.box(P(len / 2 - 0.1f, 0f, 0f).x, P(len / 2 - 0.1f, 0f, 0f).y, zs(1f) + 0.35f, 0.04f, 0.18f, 0.4f, rot)
            det.color(0.30f, 0.20f, 0.12f, 0.4f); val fo = P(-len * 0.3f, hw(-0.6f) * 0.8f, 0f); det.box(fo.x, fo.y, sheer + 0.45f, 0.08f, 0.08f, 0.35f, rot)
            det.color(0.50f, 0.06f, 0.06f, 0.2f); val se = P(-0.5f, 0f, 0f); det.box(se.x, se.y, sheer - 0.05f, 0.7f, 0.5f, 0.15f, rot)
        } else {
            // a tarp over the boat (blue, green or grey) or an outboard on the transom
            if (rnd(seed, 30) < 0.4f) {
                val tc = arrayOf(floatArrayOf(0.12f, 0.25f, 0.55f), floatArrayOf(0.15f, 0.35f, 0.22f), floatArrayOf(0.45f, 0.47f, 0.48f))[(rnd(seed, 31) * 3).toInt().coerceAtMost(2)]
                det.color(tc[0], tc[1], tc[2], 0.3f)
                for (k in 0 until N) {
                    val f0 = -0.85f + 1.7f * k / N; val f1 = -0.85f + 1.7f * (k + 1) / N
                    val x0 = f0 * len / 2; val x1 = f1 * len / 2
                    for (sd in intArrayOf(-1, 1)) det.quad(P(x0, sd * hw(f0), zs(f0)), P(x1, sd * hw(f1), zs(f1)), P(x1, 0f, zs(f1) + 0.22f), P(x0, 0f, zs(f0) + 0.22f))
                }
            } else if (type != 0) {
                det.color(0.10f, 0.11f, 0.12f, 0.5f); val o = P(-len / 2 - 0.15f, 0f, 0f); det.box(o.x, o.y, sheer + 0.2f, 0.18f, 0.22f, 0.35f, rot)
                if (type == 2) { det.color(0.30f, 0.32f, 0.34f, 0.5f); val cs = P(len * 0.05f, 0f, 0f); det.box(cs.x, cs.y, sheer + 0.15f, 0.35f, 0.4f, 0.4f, rot) }
            }
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
            gen.mat(L.PLANKS, 0.85f, 0.78f, 0.70f)
            gen.quad(Vec3(ax - nx * hw, ay - ny * hw, z), Vec3(bx - nx * hw, by - ny * hw, z), Vec3(bx + nx * hw, by + ny * hw, z), Vec3(ax + nx * hw, ay + ny * hw, z))
            gen.color(Palette.WOOD_DARK)
            gen.quad(Vec3(ax - nx * hw, ay - ny * hw, z - 0.2f), Vec3(bx - nx * hw, by - ny * hw, z - 0.2f), Vec3(bx - nx * hw, by - ny * hw, z), Vec3(ax - nx * hw, ay - ny * hw, z))
            gen.quad(Vec3(bx + nx * hw, by + ny * hw, z - 0.2f), Vec3(ax + nx * hw, ay + ny * hw, z - 0.2f), Vec3(ax + nx * hw, ay + ny * hw, z), Vec3(bx + nx * hw, by + ny * hw, z))
            val posts = max(1, (len / 2.4f).toInt())
            for (k in 0..posts) {
                val f = k.toFloat() / posts
                val x = ax + (bx - ax) * f; val y = ay + (by - ay) * f
                // tarred piles in pairs, the outer ones taller (mooring posts), green slime at the waterline
                for (s in intArrayOf(-1, 1)) {
                    gen.color(0.20f, 0.17f, 0.14f, 0.1f)
                    gen.frustum(x + nx * (hw + 0.1f) * s, y + ny * (hw + 0.1f) * s, Z.WALL_BOTTOM, 0.13f, 0.12f, 1.3f - Z.WALL_BOTTOM + (if (k % 2 == 0) 0.9f else 0.15f), 7)
                    gen.color(0.16f, 0.24f, 0.13f, 0f)
                    gen.frustum(x + nx * (hw + 0.1f) * s, y + ny * (hw + 0.1f) * s, -0.15f, 0.145f, 0.14f, 0.35f, 7, capTop = false)
                }
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

    // ------------------------------------------------------------------ trees
    private fun treeKind(wx: Float, wy: Float, s: Int): Int = when {
        Places.inside(Places.santElena, wx, wy) -> if (rnd(s, 9) < 0.85f) 1 else 0     // umbrella pines
        Places.inside(Places.giardini, wx, wy) || Places.inside(Places.napoleonici, wx, wy) -> if (rnd(s, 9) < 0.12f) 1 else 0
        else -> if (rnd(s, 9) < 0.3f) 2 else 0
    }

    /** Parks: planted on a jittered grid over the grass (dense in the Giardini, tall stone pines at Sant'Elena). */
    /** Trees in the private courtyards and gardens enclosed by buildings (the holes of building outlines): from the
     *  canals you see their crowns over the walls, a very Venetian sight. */
    private fun courtyardTrees(t: TileData) {
        for ((bi, b) in t.buildings.withIndex()) {
            if (b.ringSizes.size < 2) continue
            var start = b.ringSizes[0]
            for (ri in 1 until b.ringSizes.size) {
                val n = b.ringSizes[ri]
                var area = 0f; var cx = 0f; var cy = 0f
                for (i in 0 until n) {
                    val ax = b.xy[(start + i) * 2]; val ay = b.xy[(start + i) * 2 + 1]
                    val bx = b.xy[(start + (i + 1) % n) * 2]; val by = b.xy[(start + (i + 1) % n) * 2 + 1]
                    area += ax * by - bx * ay; cx += ax; cy += ay
                }
                area = abs(area) / 2; cx /= n; cy /= n
                start += n
                val s = b.seed * 31 + ri
                if (area < 35f || rnd(s, 1) < 0.35f) continue
                if (cx < 1f || cy < 1f || cx > TILE_SIZE - 1f || cy > TILE_SIZE - 1f) continue
                val wx = t.ox + cx; val wy = t.oy + cy
                tree(cx, cy, Z.GROUND, treeKind(wx, wy, s), s)
                if (area > 160f) {
                    val ang = rnd(s, 2) * 6.28f; val d = sqrt(area) * 0.25f
                    tree(cx + cos(ang) * d, cy + sin(ang) * d, Z.GROUND, treeKind(wx, wy, s + 7), s + 7)
                }
            }
        }
    }

    private fun parkTrees(t: TileData) {
        val cls = t.cls ?: return
        val cxw = t.ox + 128f; val cyw = t.oy + 128f
        val spacing = when {
            Places.inside(Places.giardini, cxw, cyw) || Places.inside(Places.napoleonici, cxw, cyw) -> 7.0f
            Places.inside(Places.santElena, cxw, cyw) -> 10.0f
            Places.inside(Places.reali, cxw, cyw) -> 8f
            else -> 14f
        }
        val n = (TILE_SIZE / spacing).toInt()
        for (j in 0 until n) for (i in 0 until n) {
            val s = (t.tx * 7919 + t.ty * 104729) * 31 + j * 977 + i * 131
            val x = (i + 0.2f + rnd(s, 1) * 0.6f) * spacing; val y = (j + 0.2f + rnd(s, 2) * 0.6f) * spacing
            val ci = (floor(x).toInt() + 1).coerceIn(0, MASK_N - 1); val cj = (floor(y).toInt() + 1).coerceIn(0, MASK_N - 1)
            if (cls[cj * MASK_N + ci].toInt() != 2) continue
            if (occAt(x, y) != 0 || occAt(x + 2.5f, y) != 0 || occAt(x - 2.5f, y) != 0 || occAt(x, y + 2.5f) != 0 || occAt(x, y - 2.5f) != 0) continue
            val wx = t.ox + x; val wy = t.oy + y
            // sparse lawns outside the big gardens
            if (spacing > 13f && rnd(s, 3) < 0.45f) continue
            tree(x, y, Z.GROUND, treeKind(wx, wy, s), s)
        }
    }

    private fun tree(x: Float, y: Float, gz: Float, kind: Int, s: Int) {
        if (veg.count > VEG_MAX) return
        val saved = gen; gen = veg
        try { treeBody(x, y, gz, kind, s) } finally { gen = saved }
    }

    private fun treeBody(x: Float, y: Float, gz: Float, kind: Int, s: Int) {
        val r = { k: Int -> rnd(s, 200 + k) }
        when (kind) {
            1 -> {
                // umbrella (stone) pine: tall bare trunk leaning a little, limbs fanning out to a flat, wide canopy
                val h = 12f + r(1) * 5f
                val lx = (r(2) - 0.5f) * 0.12f; val ly = (r(3) - 0.5f) * 0.12f
                gen.color(0.42f, 0.30f, 0.23f, 0f)
                gen.frustum(x, y, gz, 0.34f, 0.22f, h * 0.78f, 7, lx, ly)
                val tx = x + lx * h * 0.78f; val ty = y + ly * h * 0.78f; val tz = gz + h * 0.78f
                val crown = 3.8f + r(4) * 2.2f
                for (k in 0 until 3) {
                    val a = r(10 + k) * 6.283f
                    gen.frustum(tx, ty, tz, 0.16f, 0.08f, h * 0.14f, 5, cos(a) * crown * 0.35f / (h * 0.14f), sin(a) * crown * 0.35f / (h * 0.14f))
                }
                val c = floatArrayOf(0.13f, 0.21f, 0.09f)
                gen.mat(L.GRASS, c[0] * 2.3f, c[1] * 2.1f, c[2] * 2.3f)
                gen.blob(tx, ty, gz + h * 0.93f, crown * 0.55f, 0.42f, s)
                val nb = 6 + (r(5) * 3).toInt()
                for (k in 0 until nb) {
                    val a = k * 6.283f / nb + r(20 + k) * 0.6f; val d = crown * (0.45f + 0.35f * r(30 + k))
                    gen.blob(tx + cos(a) * d, ty + sin(a) * d, gz + h * (0.88f + 0.06f * r(40 + k)), crown * (0.42f + 0.15f * r(50 + k)), 0.38f, s + k)
                }
            }
            2 -> {
                // small ornamental tree
                val h = 4f + r(1) * 2.5f
                gen.color(0.36f, 0.30f, 0.24f, 0f)
                gen.frustum(x, y, gz, 0.13f, 0.09f, h * 0.55f, 6)
                val c = Palette.LEAF[(r(2) * 3).toInt().coerceAtMost(2)]
                gen.mat(L.GRASS, c[0] * 2.2f, c[1] * 2.0f, c[2] * 2.2f)
                gen.blob(x, y, gz + h * 0.72f, 1.3f + r(3) * 0.6f, 0.9f, s)
                gen.blob(x + 0.6f, y + 0.3f, gz + h * 0.62f, 1.0f, 0.85f, s + 1)
            }
            else -> {
                // broad-leaved park tree (plane, lime, horse chestnut): trunk, a fork, a full rounded crown of clumps
                val h = 8f + r(1) * 6f
                gen.color(0.38f, 0.34f, 0.28f, 0f)
                gen.frustum(x, y, gz, 0.3f, 0.2f, h * 0.45f, 7)
                for (k in 0 until 2) { val a = r(10 + k) * 6.283f; gen.frustum(x, y, gz + h * 0.42f, 0.16f, 0.09f, h * 0.25f, 5, cos(a) * 0.5f, sin(a) * 0.5f) }
                val c = Palette.LEAF[(r(2) * 3).toInt().coerceAtMost(2)]
                val crown = 2.6f + r(4) * 1.6f
                gen.mat(L.GRASS, c[0] * 2.4f, c[1] * 2.2f, c[2] * 2.3f)
                gen.blob(x, y, gz + h * 0.74f, crown, 0.85f, s)
                for (k in 0 until 5) {
                    val a = k * 1.257f + r(20 + k) * 0.5f; val d = crown * 0.6f
                    gen.blob(x + cos(a) * d, y + sin(a) * d, gz + h * (0.6f + 0.18f * r(30 + k)), crown * (0.55f + 0.2f * r(40 + k)), 0.8f, s + k)
                }
            }
        }
    }

    /** Is the tile-local point on a pier deck (line pier within its width, or inside a pier polygon)? */
    private fun onPier(t: TileData, x: Float, y: Float): Boolean {
        for (p in t.piers) {
            val hw = (p.width / 2).coerceIn(0.6f, 4f) + 0.6f
            for (i in 0 until p.xy.size / 2 - 1) {
                val ax = p.xy[i * 2]; val ay = p.xy[i * 2 + 1]; val ex = p.xy[i * 2 + 2] - ax; val ey = p.xy[i * 2 + 3] - ay
                val l2 = ex * ex + ey * ey; if (l2 < 1e-6f) continue
                val k = (((x - ax) * ex + (y - ay) * ey) / l2).coerceIn(0f, 1f)
                if (hypot(ax + ex * k - x, ay + ey * k - y) < hw) return true
            }
        }
        for (pp in t.pierPolys) {
            val n = pp.xy.size / 2; var inside = false; var j = n - 1
            for (i in 0 until n) {
                val xi = pp.xy[i * 2]; val yi = pp.xy[i * 2 + 1]; val xj = pp.xy[j * 2]; val yj = pp.xy[j * 2 + 1]
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi + 1e-9f) + xi) inside = !inside
                j = i
            }
            if (inside) return true
        }
        return false
    }

    // ------------------------------------------------------------------ street furniture & lagoon props
    private fun prop(p: PointData, t: TileData) {
        if (Exclusions.zones.isNotEmpty() && Exclusions.inside(t.ox + p.x, t.oy + p.y)) return
        val onLand = t.solidAt(p.x, p.y) > 0.5f
        // street furniture OSM puts a few metres into a canal (lamps, benches, bins, trees...) is not built,
        // unless it stands on a pier or pontoon deck
        if (p.type != Feature.BRICOLA && p.type != Feature.STOP && t.solidAt(p.x, p.y) < 0.3f && !onPier(t, p.x, p.y)) return
        val gz = if (onLand) Z.GROUND else 0f
        val s = (p.x * 73.1f + p.y * 19.7f).toInt()
        val dir = if (p.dir >= 0) Math.toRadians(90.0 - p.dir).toFloat() else rnd(s, 7) * PI.toFloat()
        when (p.type) {
            Feature.BRICOLA -> {
                // OSM sometimes puts mooring piles a metre inland (by Rialto they stood in the pavement): only in water
                if (t.solidAt(p.x, p.y) > 0.25f) return
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
            Feature.TREE -> tree(p.x, p.y, gz, treeKind(t.ox + p.x, t.oy + p.y, s), s)
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
