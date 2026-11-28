// The moored boats' catalogue (assets/boats/catalog.txt, written by tools/procedural/boats.py): ~120 variants built
// from 10 hull types and a kit of parts. Each variant = a shared hull mesh + its own parts (outboard with its power
// on the cowling, cover, bimini, console, lights, antenna, fenders, mooring lines, registration plates), repainted
// through six paint slots (topsides, stripe, interior, bottom, canvas, cushions) set as uniforms when drawn.
package com.blazebaal.venice.core

import kotlin.math.*

class BoatCatalog(lines: List<String>) {
    class Part(val name: String, val x: Float, val y: Float, val z: Float, val yaw: Float, val pitch: Float)
    class Variant(val id: Int, val base: String, val len: Float, val beam: Float, val material: String, val propulsion: String,
                  val hp: Float, val paint: FloatArray, val parts: List<Part>) {
        val covered = parts.any { it.name.startsWith("cover_") }
        /** what you hear when you hit it (Audio impact kinds) */
        val hitKind get() = when (material) { "plastic" -> 5; "wood" -> 6; "rubber" -> 7; else -> 8 }
    }

    val variants: List<Variant>
    val byBase: Map<String, List<Variant>>

    init {
        val out = ArrayList<Variant>()
        for (l in lines) {
            if (l.isBlank() || l.startsWith("#")) continue
            val sec = l.split("|")
            if (sec.size < 3) continue
            val h = sec[0].trim().split(Regex("\\s+"))
            if (h.size < 7) continue
            val paints = sec[1].trim().split(Regex("\\s+")).flatMap { it.split(",").map { v -> v.toFloat() } }
            if (paints.size != 24) continue
            val parts = sec[2].trim().split(Regex("\\s+")).filter { "@" in it }.map { p ->
                val (n, t) = p.split("@"); val v = t.split(",").map { it.toFloat() }
                Part(n, v[0], v[1], v[2], v[3], v[4])
            }
            out.add(Variant(h[0].toInt(), h[1], h[2].toFloat(), h[3].toFloat(), h[4], h[5], h[6].toFloat(), paints.toFloatArray(), parts))
        }
        variants = out
        byBase = out.groupBy { it.base }
    }

    companion object {
        /** the variant's parts (everything but the hull) merged into one vertex array, in boat space */
        fun mergeParts(v: Variant, load: (String) -> FloatArray?, skipCover: Boolean = false): FloatArray {
            val pieces = ArrayList<Pair<Part, FloatArray>>()
            var total = 0
            for (p in v.parts) {
                if (skipCover && p.name.startsWith("cover_")) continue
                val d = load(p.name) ?: continue
                pieces.add(p to d); total += d.size
            }
            val out = FloatArray(total); var o = 0
            for ((p, d) in pieces) {
                val cy = cos(p.yaw); val sy = sin(p.yaw); val cp = cos(p.pitch); val sp = sin(p.pitch)
                var i = 0
                while (i < d.size) {
                    // pitch about Y, then yaw about Z, then move into place (positions); normals rotate only
                    for (k in 0..1) {
                        val b = i + k * 3
                        val x0 = d[b]; val y0 = d[b + 1]; val z0 = d[b + 2]
                        val x1 = x0 * cp + z0 * sp; val z1 = -x0 * sp + z0 * cp
                        val x2 = x1 * cy - y0 * sy; val y2 = x1 * sy + y0 * cy
                        if (k == 0) { out[o + b - i] = x2 + p.x; out[o + b - i + 1] = y2 + p.y; out[o + b - i + 2] = z1 + p.z }
                        else { out[o + b - i] = x2; out[o + b - i + 1] = y2; out[o + b - i + 2] = z1 }
                    }
                    for (k in 6 until STRIDE) out[o + k] = d[i + k]
                    i += STRIDE; o += STRIDE
                }
            }
            return out
        }
    }
}

/** Where boats are tied up along the fondamenta of one map tile (world coordinates): x, y, heading, variant index. */
object Moorings {
    // gondola stations: San Marco / the Molo, Rialto
    private val gondolaZones = floatArrayOf(0f, 0f, 700f, -240f, 454f, 350f)

    fun place(t: TileData, cat: BoatCatalog): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        if (cat.variants.isEmpty()) return out
        for ((wi, p) in t.walls.withIndex()) {
            val n = p.size / 2
            if (n < 2) continue
            // walk the whole quay line by arc length: boats lie along any stretch that is straight enough for them
            val cum = FloatArray(n)
            for (i in 1 until n) cum[i] = cum[i - 1] + hypot(p[i * 2] - p[i * 2 - 2], p[i * 2 + 1] - p[i * 2 - 1])
            val total = cum[n - 1]
            fun at(s: Float, o: FloatArray) {
                var i = 0
                while (i < n - 2 && cum[i + 1] < s) i++
                val l = max(cum[i + 1] - cum[i], 1e-4f); val k = ((s - cum[i]) / l).coerceIn(0f, 1f)
                o[0] = p[i * 2] + (p[i * 2 + 2] - p[i * 2]) * k; o[1] = p[i * 2 + 1] + (p[i * 2 + 3] - p[i * 2 + 1]) * k
            }
            val pa = FloatArray(2); val pb = FloatArray(2); val pm = FloatArray(2)
            var u = 2f + rnd(n + wi * 7, 1) * 6f
            while (u < total - 2f) {
                val seed = ((t.tx * 7919 + t.ty * 104729) * 31 + wi * 131 + (u * 10).toInt()) and 0x7fffffff
                // gaps between boats: not every metre of quay has a boat
                if (rnd(seed, 2) < 0.35f) { u += 3f + rnd(seed, 3) * 6f; continue }
                at(u, pm)
                val wx = t.ox + pm[0]; val wy = t.oy + pm[1]
                // the variant first (its length decides how much straight quay it needs)
                at(max(0f, u - 2f), pa); at(min(total, u + 2f), pb)
                var tx = pb[0] - pa[0]; var ty = pb[1] - pa[1]; var tl = hypot(tx, ty)
                if (tl < 1f) { u += 2f; continue }
                tx /= tl; ty /= tl
                val width = canalWidth(t, pm[0], pm[1], ty, -tx)
                if (width < 9f) { u += 5f; continue }
                val v = pick(cat, seed, wx, wy, width)
                if (v == null) { u += 4f; continue }
                if (u - v.len / 2 < 0.5f || u + v.len / 2 > total - 0.5f) { u += 2f; continue }
                at(u - v.len / 2, pa); at(u + v.len / 2, pb)
                tx = pb[0] - pa[0]; ty = pb[1] - pa[1]; tl = hypot(tx, ty)
                if (tl < v.len * 0.93f) { u += 2f + rnd(seed, 4) * 2f; continue }         // the quay bends too much here
                tx /= tl; ty /= tl
                val nx = ty; val ny = -tx                                                 // water side (as the quay mesher)
                val off = 0.35f + v.beam / 2
                val cx = (pa[0] + pb[0]) / 2 + nx * off; val cy = (pa[1] + pb[1]) / 2 + ny * off
                if (!clear(t, cx, cy, nx, ny, tx, ty, v.len, v.beam, width)) { u += 3f + rnd(seed, 4) * 3f; continue }
                // the boat's +Y side (fenders, lines) faces the quay: heading along the wall
                out.add(floatArrayOf(t.ox + cx, t.oy + cy, atan2(ty, tx), v.id.toFloat()))
                u += v.len + 0.6f + rnd(seed, 5) * 2.5f
            }
        }
        return out
    }

    private fun pick(cat: BoatCatalog, seed: Int, wx: Float, wy: Float, width: Float): BoatCatalog.Variant? {
        var gz = false
        for (k in 0 until gondolaZones.size / 3) if (hypot(wx - gondolaZones[k * 3], wy - gondolaZones[k * 3 + 1]) < gondolaZones[k * 3 + 2]) gz = true
        // what you find tied up in Venice: work boats and barchini everywhere, gondolas at their stations, the big
        // ones only where the canal is wide enough for them to get out again
        val w = linkedMapOf(
            "gondola" to (if (gz) 45f else 2f), "sandolo" to 13f, "sciopon" to 4f, "dinghy" to 5f, "barchino" to 22f,
            "topa" to 16f, "motoscafo" to (if (width > 18f) 6f else 2f), "rib" to 7f,
            "cruiser" to (if (width > 24f) 5f else 0f), "barge" to (if (width > 20f) 6f else 0.5f))
        val tot = w.values.sum()
        var r = rnd(seed, 7) * tot
        var base = "barchino"
        for ((k, v) in w) { if (r < v) { base = k; break }; r -= v }
        val list = cat.byBase[base] ?: return null
        return list[(rnd(seed, 8) * list.size).toInt().coerceIn(0, list.size - 1)]
    }

    /** open water straight out from the quay (m), up to 40 */
    private fun canalWidth(t: TileData, x: Float, y: Float, nx: Float, ny: Float): Float {
        var d = 0.5f
        while (d < 40f) {
            val px = x + nx * d; val py = y + ny * d
            if (px < 0f || py < 0f || px > TILE_SIZE || py > TILE_SIZE) return d + 10f      // runs on into the next tile
            if (t.solidAt(px, py) > 0.5f) return d
            d += 1f
        }
        return 40f
    }

    private fun clear(t: TileData, cx: Float, cy: Float, nx: Float, ny: Float, tx: Float, ty: Float, len: Float, beam: Float, width: Float): Boolean {
        // inside this tile, open water under the whole hull, and room left to get past
        for (k in -2..2) for (o in floatArrayOf(-beam / 2 + 0.55f, 0f, beam / 2 + 0.3f)) {
            val x = cx + tx * k * len * 0.24f + nx * o; val y = cy + ty * k * len * 0.24f + ny * o
            if (x < 1f || y < 1f || x > TILE_SIZE - 1f || y > TILE_SIZE - 1f) return false
            if (t.solidAt(x, y) > 0.3f) return false
        }
        if (width - beam - 0.35f < 6f) return false
        // never under or next to a bridge, at a vaporetto stop or in a landmark area
        for (br in t.bridges) for (j in 0 until br.xy.size / 2 - 1) {
            val ax = br.xy[j * 2]; val ay = br.xy[j * 2 + 1]; val ex = br.xy[j * 2 + 2] - ax; val ey = br.xy[j * 2 + 3] - ay
            val l2 = ex * ex + ey * ey; if (l2 < 1e-4f) continue
            val k = (((cx - ax) * ex + (cy - ay) * ey) / l2).coerceIn(0f, 1f)
            if (hypot(ax + ex * k - cx, ay + ey * k - cy) < len * 0.5f + 7f + br.width) return false
        }
        // nor across someone's private landing stage
        for (pr in t.piers) for (j in 0 until pr.xy.size / 2 - 1) {
            val ax = pr.xy[j * 2]; val ay = pr.xy[j * 2 + 1]; val ex = pr.xy[j * 2 + 2] - ax; val ey = pr.xy[j * 2 + 3] - ay
            val l2 = ex * ex + ey * ey; if (l2 < 1e-4f) continue
            val k = (((cx - ax) * ex + (cy - ay) * ey) / l2).coerceIn(0f, 1f)
            if (hypot(ax + ex * k - cx, ay + ey * k - cy) < len * 0.5f + 1.5f) return false
        }
        for (pp in t.pierPolys) { val q = pp.xy; for (j in 0 until q.size / 2) if (hypot(q[j * 2] - cx, q[j * 2 + 1] - cy) < len * 0.5f + 2f) return false }
        for (pt in t.points) if (pt.type == Feature.STOP && hypot(pt.x - cx, pt.y - cy) < 45f) return false
        if (Exclusions.zones.isNotEmpty() && Exclusions.inside(t.ox + cx, t.oy + cy)) return false
        return true
    }

    private fun rnd(seed: Int, k: Int): Float {
        var h = seed * 374761393 + k * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xffffff) / 16777215f
    }
}
