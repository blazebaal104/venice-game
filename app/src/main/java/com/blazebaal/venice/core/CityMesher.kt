// Turns a decoded map tile into render meshes (tile-local coordinates). Pure Kotlin.
package com.blazebaal.venice.core

import kotlin.math.*

object Z {
    const val GROUND = 1.1f      // fondamenta / campi height above mean water
    const val MARSH = 0.32f      // barene
    const val WALL_BOTTOM = -1.8f
}

object Kind { const val WALL = 0f; const val ROOF = 1f; const val QUAY = 2f; const val CORNICE = 3f; const val TOWER = 4f }

class TileMeshes(val facade: FloatArray, val generic: FloatArray, val detail: FloatArray, val hasGround: Boolean, val hasMarsh: Boolean)

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
    private val gen = MeshBuilder(32768)
    private val det = MeshBuilder(32768)
    private val occ = ShortArray(TILE_SIZE * TILE_SIZE)

    fun build(t: TileData): TileMeshes {
        fac.clear(); gen.clear(); det.clear()
        rasterOccupancy(t)
        for ((i, b) in t.buildings.withIndex()) building(b, i, t)
        fac.style(0f, 0f, 0f, 0f)
        for (w in t.walls) quay(w)
        for (br in t.bridges) bridge(br, t)
        for (p in t.piers) linePier(p)
        for (p in t.pierPolys) polyPier(p)
        for (p in t.points) prop(p, t)
        parkTrees(t)
        val hasMarsh = t.marsh?.any { it != 0.toByte() } ?: false
        return TileMeshes(fac.floats(), gen.floats(), det.floats(), t.hard != null, hasMarsh)
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
                if (r(9) < 0.75f) 0 else 1, 0.38f + r(10) * 0.08f, 0.1f, 0.15f)
            2 -> Style(2, 3.9f + r(1) * 0.7f, 3.8f + r(2) * 0.6f, true, if (r(3) < 0.7f) 1 else 0,
                if (r(4) < 0.6f) 0 else 1, 1, 0.95f + r(5) * 0.2f, 2.1f + r(6) * 0.4f,
                if (r(7) < 0.55f) 3 else 0, 1, r(8) < 0.7f, 0, 0.38f + r(10) * 0.06f, 0.05f, 0.3f)
            3 -> Style(3, 3.9f + r(1) * 0.6f, 3.8f + r(2) * 0.5f, true, if (r(3) < 0.5f) 4 else 5,
                if (r(4) < 0.5f) 0 else 1, 1, 1.0f + r(5) * 0.2f, 2.1f + r(6) * 0.3f,
                if (r(7) < 0.5f) 3 else 0, 1, r(8) < 0.5f, 0, 0.36f + r(10) * 0.06f, 0.05f, 0.35f)
            4 -> Style(4, 3.3f + r(1) * 0.6f, 3.1f + r(2) * 0.4f, false, if (r(3) < 0.65f) 0 else 5,
                pick(r(4), 0.1f, 0.7f, 0.15f, 0.05f), if (r(5) < 0.8f) 1 else 0, 0.85f + r(6) * 0.2f, 1.65f + r(7) * 0.3f,
                if (r(8) < 0.85f) 0 else 1, if (r(9) < 0.7f) 1 else 2, r(10) < 0.25f, if (r(11) < 0.8f) 0 else 1, 0.36f + r(12) * 0.08f, 0.3f, 0.4f)
            else -> Style(0, 2.7f + r(1) * 0.7f, 2.75f + r(2) * 0.4f, false, pick(r(3), 0.74f, 0.16f, 0.10f),
                pick(r(4), 0.12f, 0.55f, 0.25f, 0.08f), pick(r(5), 0.3f, 0.6f, 0.0f, 0.1f), 0.72f + r(6) * 0.3f, 1.3f + r(7) * 0.35f,
                pick(r(8), 0.55f, 0.35f, 0.10f), if (r(9) < 0.6f) 0 else 2, false,
                pick(r(10), 0.72f, 0.2f, 0.08f), 0.36f + r(11) * 0.12f, 0.28f, 0.12f)
        }
    }

    private fun code1(st: Style, bay: Bay): Float =
        (st.winType + 8 * st.shutter + 32 * st.frame + 128 * bay.kind + 512 * bay.ground + 4096 * (if (st.pn) 1 else 0) +
            8192 * st.mat + 32768 * st.string + 131072 * (if (st.rust) 1 else 0)).toFloat()
    private fun code2(st: Style, bay: Bay): Float =
        ((st.winW * 100).roundToInt().coerceIn(30, 255) + 256 * (st.winH * 50).roundToInt().coerceIn(30, 255) + 65536 * bay.jseed + 1048576 * bay.bal).toFloat()

    // ------------------------------------------------------------------ buildings
    private fun building(b: BuildingData, bi: Int, t: TileData) {
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
            val waterDoorAt = if (water && nb >= 2 && rnd(es, 6) < 0.6f) nb / 2 else -1
            for (k in 0 until nb) {
                val bw = len * w[k] / ws
                val blank = st.type == 0 && !court && rnd(es, 70 + k) < 0.10f
                val ground = when {
                    court -> 0
                    k == waterDoorAt -> 3
                    k == doorAt -> 1
                    !water && rnd(es, 80 + k) < st.shopP -> 2
                    rnd(es, 90 + k) < 0.68f -> 0
                    else -> 4
                }
                // single balconies (poggioli): mostly on the first floor
                var bal = 0
                if (!court && !blank && rnd(es, 110 + k) < st.balP) bal = if (st.type == 0 && rnd(es, 120 + k) < 0.4f) 2 else 1
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
                    balcony(wx((u0 + u1) / 2, 0f), wy((u0 + u1) / 2, 0f), rot, nx, ny, (u1 - u0) / 2 + 0.25f, Z.GROUND + fs + 0.5f, true, st.type == 1)
                }
                g0 = -1
            }
        }
        val pnF = if (st.pn) 1.22f else 1f
        for (bay in bays) {
            if (bay.kind == 2) continue
            // ---- ground floor: paline in front of water doors, awnings over shops
            if (bay.ground == 3) {
                val cols = Palette.PALINA[(rnd(es, 7) * 4).toInt().coerceAtMost(3)]
                for (side in intArrayOf(-1, 1)) palina(wx(bay.c + side * (min(bay.w - 0.5f, 2.4f) / 2 + 0.5f), 1.3f), wy(bay.c + side * (min(bay.w - 0.5f, 2.4f) / 2 + 0.5f), 1.3f), cols)
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
                if (balHere) balcony(wx(cu, 0f), wy(cu, 0f), rot, nx, ny, ow / 2 + 0.28f, Z.GROUND + fs - 0.02f, st.type in 1..3, false)
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

    private fun balcony(cx: Float, cy: Float, rot: Float, nx: Float, ny: Float, hw: Float, z: Float, stone: Boolean, gothic: Boolean) {
        val d = if (stone) 0.55f else 0.42f
        val ox = cx + nx * d / 2; val oy = cy + ny * d / 2
        if (stone) det.color(Palette.ISTRIAN) else det.color(Palette.ISTRIAN)
        det.box(ox, oy, z - 0.07f, hw, d / 2, 0.08f, rot)
        // brackets (mensole)
        val tx = -ny; val ty = nx
        val nb = max(2, (hw * 2 / 0.9f).toInt())
        for (k in 0..nb) {
            val f = -hw + 0.12f + (2 * hw - 0.24f) * k / nb
            det.box(cx + tx * f + nx * d * 0.35f, cy + ty * f + ny * d * 0.35f, z - 0.25f, 0.07f, d * 0.35f, 0.11f, rot)
        }
        if (stone) {
            // balustrade: stone balusters under a rail (gothic: little columns and arches read the same at this scale)
            val n = max(3, (hw * 2 / 0.19f).toInt())
            for (k in 0..n) {
                val f = -hw + 0.06f + (2 * hw - 0.12f) * k / n
                val bx = ox + tx * f + nx * (d / 2 - 0.08f); val by = oy + ty * f + ny * (d / 2 - 0.08f)
                det.frustum(bx, by, z, 0.045f, 0.03f, 0.3f, 5, capTop = false)
                det.frustum(bx, by, z + 0.3f, 0.03f, if (gothic) 0.03f else 0.045f, 0.3f, 5, capTop = false)
            }
            for (side in intArrayOf(-1, 1)) det.box(ox + tx * side * (hw - 0.06f), oy + ty * side * (hw - 0.06f), z + 0.33f, 0.07f, d / 2 - 0.02f, 0.33f, rot)
            det.box(ox + nx * (d / 2 - 0.08f), oy + ny * (d / 2 - 0.08f), z + 0.66f, hw, 0.08f, 0.05f, rot)
            for (side in intArrayOf(-1, 1)) det.box(ox + tx * side * (hw - 0.05f), oy + ty * side * (hw - 0.05f), z + 0.66f, 0.05f, d / 2 - 0.02f, 0.05f, rot)
        } else {
            // wrought-iron railing
            det.color(Palette.IRON)
            val n = max(4, (hw * 2 / 0.13f).toInt())
            for (k in 0..n) {
                val f = -hw + 0.03f + (2 * hw - 0.06f) * k / n
                det.box(ox + tx * f + nx * (d / 2 - 0.03f), oy + ty * f + ny * (d / 2 - 0.03f), z + 0.48f, 0.009f, 0.009f, 0.48f, rot)
            }
            det.box(ox + nx * (d / 2 - 0.03f), oy + ny * (d / 2 - 0.03f), z + 0.97f, hw, 0.025f, 0.02f, rot)
            det.box(ox + nx * (d / 2 - 0.03f), oy + ny * (d / 2 - 0.03f), z + 0.12f, hw, 0.015f, 0.015f, rot)
            for (side in intArrayOf(-1, 1)) det.box(ox + tx * side * (hw - 0.02f), oy + ty * side * (hw - 0.02f), z + 0.97f, 0.02f, d / 2 - 0.02f, 0.02f, rot)
        }
    }

    /** Striped mooring pole (palina) in front of a palazzo's water door. */
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

    // ------------------------------------------------------------------ bridges
    private fun bridge(br: LineData, t: TileData) {
        val n = br.xy.size / 2
        if (n < 2) return
        val cum = FloatArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + hypot(br.xy[i * 2] - br.xy[i * 2 - 2], br.xy[i * 2 + 1] - br.xy[i * 2 - 1])
        val total = cum[n - 1]
        if (total < 1f) return
        val flat = total > 70f
        val midX = t.ox + br.xy[(n / 2) * 2]; val midY = t.oy + br.xy[(n / 2) * 2 + 1]
        if (!flat && total > 30f && hypot(midX - Places.accademia.x, midY - Places.accademia.y) < 90f) { woodBridge(br, total, cum); return }
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
                gen.mat((body[3] - 100f).toInt(), 0.75f, 0.75f, 0.72f)
                gen.quad(Vec3(ax + nx * hw, ay + ny * hw, prevU), Vec3(bx + nbx * hw, by + nby * hw, uz), Vec3(bx - nbx * hw, by - nby * hw, uz), Vec3(ax - nx * hw, ay - ny * hw, prevU))
                if (flat && k % 12 == 0) { // piers for long causeway bridges
                    gen.color(Palette.ISTRIAN)
                    gen.box(bx, by, (Z.WALL_BOTTOM + uz) / 2, hw * 0.9f, 0.6f, (uz - Z.WALL_BOTTOM) / 2, atan2(p[3], p[2]) + PI.toFloat() / 2)
                }
            }
            prev = p; prevZ = z; prevU = uz
        }
    }

    /** Ponte dell'Accademia: a wooden truss arch - stepped plank deck, open wooden railings, ribs and struts below. */
    private fun woodBridge(br: LineData, total: Float, cum: FloatArray) {
        val n = br.xy.size / 2
        fun pos(s: Float): FloatArray {
            var i = 0
            while (i < n - 2 && cum[i + 1] < s) i++
            val seg = (cum[i + 1] - cum[i]).coerceAtLeast(1e-6f)
            val f = ((s - cum[i]) / seg).coerceIn(0f, 1f)
            val dx = (br.xy[i * 2 + 2] - br.xy[i * 2]) / seg; val dy = (br.xy[i * 2 + 3] - br.xy[i * 2 + 1]) / seg
            return floatArrayOf(br.xy[i * 2] + dx * seg * f, br.xy[i * 2 + 1] + dy * seg * f, dx, dy)
        }
        val rise = 7.2f; val hw = 4.3f
        fun deck(s: Float) = Z.GROUND + rise * sin(PI.toFloat() * s / total)
        fun stepped(s: Float) = Z.GROUND + floor((deck(s) - Z.GROUND) / 0.17f + 0.5f) * 0.17f
        val m = (total / 0.42f).toInt()
        var prev: FloatArray? = null; var pz = 0f
        for (k in 0..m) {
            val s = total * k / m; val p = pos(s); val z = stepped(s)
            val nx = -p[3]; val ny = p[2]
            if (prev != null) {
                val ax = prev[0]; val ay = prev[1]
                gen.mat(L.PLANKS, 0.85f, 0.72f, 0.6f)
                gen.quad(Vec3(ax - nx * hw, ay - ny * hw, pz), Vec3(p[0] - nx * hw, p[1] - ny * hw, pz), Vec3(p[0] + nx * hw, p[1] + ny * hw, pz), Vec3(ax + nx * hw, ay + ny * hw, pz))
                if (abs(z - pz) > 0.01f) gen.quad(Vec3(p[0] - nx * hw, p[1] - ny * hw, pz), Vec3(p[0] - nx * hw, p[1] - ny * hw, z), Vec3(p[0] + nx * hw, p[1] + ny * hw, z), Vec3(p[0] + nx * hw, p[1] + ny * hw, pz))
                // deck edge beams
                gen.mat(L.PLANKS_OLD, 0.7f, 0.55f, 0.45f)
                for (side in intArrayOf(-1, 1)) {
                    val ex0 = ax + nx * hw * side; val ey0 = ay + ny * hw * side; val ex1 = p[0] + nx * hw * side; val ey1 = p[1] + ny * hw * side
                    gen.quad(Vec3(ex0, ey0, deck(s - total / m) - 0.45f), Vec3(ex1, ey1, deck(s) - 0.45f), Vec3(ex1, ey1, deck(s) + 0.05f), Vec3(ex0, ey0, deck(s - total / m) + 0.05f))
                }
            }
            prev = p; pz = z
        }
        // railings: posts every ~1.3 m, top rail and a mid rail following the arch; ribs and struts underneath
        val posts = (total / 1.3f).toInt()
        for (k in 0..posts) {
            val s = total * k / posts; val p = pos(s); val nx = -p[3]; val ny = p[2]; val z = deck(s)
            gen.mat(L.PLANKS_OLD, 0.75f, 0.6f, 0.5f)
            for (side in intArrayOf(-1, 1)) gen.box(p[0] + nx * (hw - 0.08f) * side, p[1] + ny * (hw - 0.08f) * side, z + 0.55f, 0.06f, 0.06f, 0.55f, atan2(p[3], p[2]))
            // under the deck: 5 arch ribs at fixed fractions of the width, linked to the deck by vertical struts
            val ribZ = Z.GROUND - 0.6f + (rise - 0.8f) * sin(PI.toFloat() * s / total)
            for (q in 0 until 5) {
                val off = (-0.85f + 0.425f * q) * hw
                if (k % 2 == 0) gen.box(p[0] + nx * off, p[1] + ny * off, (ribZ + z - 0.45f) / 2, 0.07f, 0.07f, max(0.05f, (z - 0.45f - ribZ) / 2), atan2(p[3], p[2]))
            }
        }
        for (side in intArrayOf(-1, 1)) for (hz in floatArrayOf(1.08f, 0.55f)) {
            var pp: FloatArray? = null; var pzz = 0f
            for (k in 0..posts) {
                val s = total * k / posts; val p = pos(s); val nx = -p[3]; val ny = p[2]; val z = deck(s) + hz
                val x = p[0] + nx * (hw - 0.08f) * side; val y = p[1] + ny * (hw - 0.08f) * side
                if (pp != null) { gen.quad(Vec3(pp[0], pp[1], pzz - 0.05f), Vec3(x, y, z - 0.05f), Vec3(x, y, z + 0.05f), Vec3(pp[0], pp[1], pzz + 0.05f)) }
                pp = floatArrayOf(x, y); pzz = z
            }
        }
        for (q in 0 until 5) {
            val off = (-0.85f + 0.425f * q) * hw
            var pp: FloatArray? = null; var pzz = 0f
            for (k in 0..posts) {
                val s = total * k / posts; val p = pos(s); val nx = -p[3]; val ny = p[2]
                val z = Z.GROUND - 0.6f + (rise - 0.8f) * sin(PI.toFloat() * s / total)
                val x = p[0] + nx * off; val y = p[1] + ny * off
                if (pp != null) {
                    gen.quad(Vec3(pp[0], pp[1], pzz - 0.25f), Vec3(x, y, z - 0.25f), Vec3(x, y, z + 0.12f), Vec3(pp[0], pp[1], pzz + 0.12f))
                    gen.quad(Vec3(pp[0], pp[1], pzz - 0.25f), Vec3(pp[0] + nx * 0.14f, pp[1] + ny * 0.14f, pzz - 0.25f), Vec3(x + nx * 0.14f, y + ny * 0.14f, z - 0.25f), Vec3(x, y, z - 0.25f))
                }
                pp = floatArrayOf(x, y); pzz = z
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
