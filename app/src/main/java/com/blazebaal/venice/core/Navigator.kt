// Water routing for the vaporetti: finds a path that stays in water with some clearance from the quays, so a boat
// moving between two positions follows the canals instead of cutting across land. Straight line when it is clear,
// otherwise A* on a 3 m grid around the two points, then the path is pulled tight (line-of-sight smoothing).
// Thread-safe; meant to run on a background thread.
package com.blazebaal.venice.core

import java.util.PriorityQueue
import kotlin.math.*

class Navigator(private val world: WorldMap) {
    private val cache = object : LinkedHashMap<Long, FloatArray>(128, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, FloatArray>) = size > 400
    }

    // vaporetto pontoons: boats go round them, never through (oriented boxes, bucketed on a 32 m grid)
    private var boxes = FloatArray(0)            // x, y, cos, sin, half length, half width (inflated)
    private var grid = HashMap<Long, IntArray>()
    fun setBlockers(list: List<FloatArray>, inflate: Float = 0.4f) {
        val b = FloatArray(list.size * 6); val g = HashMap<Long, ArrayList<Int>>()
        for ((i, o) in list.withIndex()) {
            b[i * 6] = o[0]; b[i * 6 + 1] = o[1]; b[i * 6 + 2] = cos(o[2]); b[i * 6 + 3] = sin(o[2]); b[i * 6 + 4] = o[3] + inflate; b[i * 6 + 5] = o[4] + inflate
            val r = hypot(o[3], o[4]) + inflate
            for (gx in floor((o[0] - r) / 32f).toInt()..floor((o[0] + r) / 32f).toInt())
                for (gy in floor((o[1] - r) / 32f).toInt()..floor((o[1] + r) / 32f).toInt())
                    g.getOrPut((gx.toLong() shl 32) or (gy.toLong() and 0xffffffffL)) { ArrayList() }.add(i)
        }
        val gg = HashMap<Long, IntArray>(); for ((k, v) in g) gg[k] = v.toIntArray()
        synchronized(this) { boxes = b; grid = gg }
        synchronized(cache) { cache.clear() }
    }
    private fun blocked(x: Float, y: Float): Boolean {
        val ids = grid[(floor(x / 32f).toLong() shl 32) or (floor(y / 32f).toLong() and 0xffffffffL)] ?: return false
        val b = boxes
        for (i in ids) {
            val dx = x - b[i * 6]; val dy = y - b[i * 6 + 1]
            if (abs(dx * b[i * 6 + 2] + dy * b[i * 6 + 3]) < b[i * 6 + 4] && abs(-dx * b[i * 6 + 3] + dy * b[i * 6 + 2]) < b[i * 6 + 5]) return true
        }
        return false
    }
    private fun water(x: Float, y: Float) = world.solid(x, y) < 0.3f && !blocked(x, y)
    private fun free(x: Float, y: Float, cl: Float) =
        water(x, y) && water(x + cl, y) && water(x - cl, y) && water(x, y + cl) && water(x, y - cl)

    /** Straight segment stays in water with `cl` metres of clearance. */
    fun clearLine(ax: Float, ay: Float, bx: Float, by: Float, cl: Float = 2.5f): Boolean {
        val d = hypot(bx - ax, by - ay); val n = max(1, (d / 2f).toInt())
        for (i in 0..n) { val t = i.toFloat() / n; if (!free(ax + (bx - ax) * t, ay + (by - ay) * t, cl)) return false }
        return true
    }

    /** Nearest point with water and clearance, searching outwards up to `r` metres; the point itself if none. */
    fun nearestWater(x: Float, y: Float, cl: Float = 2.5f, r: Float = 40f): FloatArray {
        if (free(x, y, cl)) return floatArrayOf(x, y)
        var d = 2f
        while (d <= r) {
            for (k in 0 until 24) { val a = k * PI.toFloat() / 12; val px = x + cos(a) * d; val py = y + sin(a) * d; if (free(px, py, cl)) return floatArrayOf(px, py) }
            d += 2f
        }
        return floatArrayOf(x, y)
    }

    /** Polyline (x0, y0, x1, y1, ...) from a to b through water. Falls back to the straight line. */
    fun path(ax: Float, ay: Float, bx: Float, by: Float): FloatArray {
        if (clearLine(ax, ay, bx, by)) return floatArrayOf(ax, ay, bx, by)
        val key = (((ax / 6).roundToInt().toLong() and 0xffff) shl 48) or (((ay / 6).roundToInt().toLong() and 0xffff) shl 32) or
            (((bx / 6).roundToInt().toLong() and 0xffff) shl 16) or ((by / 6).roundToInt().toLong() and 0xffff)
        synchronized(cache) { cache[key]?.let { p -> return floatArrayOf(ax, ay) + p.copyOfRange(2, p.size - 2) + floatArrayOf(bx, by) } }
        val p = astar(ax, ay, bx, by) ?: return floatArrayOf(ax, ay, bx, by)
        synchronized(cache) { cache[key] = p }
        return p
    }

    private fun astar(ax: Float, ay: Float, bx: Float, by: Float): FloatArray? {
        val cell = 3f; val margin = 150f
        val x0 = min(ax, bx) - margin; val y0 = min(ay, by) - margin
        val w = ((abs(bx - ax) + 2 * margin) / cell).toInt() + 1; val h = ((abs(by - ay) + 2 * margin) / cell).toInt() + 1
        if (w.toLong() * h > 1_200_000L) return null
        val state = ByteArray(w * h)       // 0 unknown, 1 free, 2 blocked
        fun ok(i: Int, j: Int): Boolean {
            if (i < 0 || j < 0 || i >= w || j >= h) return false
            val k = j * w + i
            if (state[k] == 0.toByte()) state[k] = if (free(x0 + (i + 0.5f) * cell, y0 + (j + 0.5f) * cell, 2.5f)) 1 else 2
            return state[k] == 1.toByte()
        }
        fun snap(x: Float, y: Float): Int {
            val ci = ((x - x0) / cell).toInt(); val cj = ((y - y0) / cell).toInt()
            for (r in 0..12) for (dj in -r..r) for (di in -r..r) { if (max(abs(di), abs(dj)) != r) continue; if (ok(ci + di, cj + dj)) return (cj + dj) * w + ci + di }
            return -1
        }
        val s = snap(ax, ay); val g = snap(bx, by)
        if (s < 0 || g < 0) return null
        val gi = g % w; val gj = g / w
        val cost = FloatArray(w * h) { Float.MAX_VALUE }; val from = IntArray(w * h) { -1 }
        val open = PriorityQueue<Long>(1024)
        fun hcost(k: Int): Float { val dx = abs(k % w - gi).toFloat(); val dy = abs(k / w - gj).toFloat(); return (dx + dy) + (1.4142f - 2f) * min(dx, dy) }
        fun push(k: Int, f: Float) { open.add((java.lang.Float.floatToIntBits(f).toLong() shl 32) or k.toLong()) }
        cost[s] = 0f; push(s, hcost(s))
        var found = false; var steps = 0
        while (open.isNotEmpty()) {
            val top = open.poll()!!; val k = (top and 0xffffffffL).toInt()
            if (k == g) { found = true; break }
            if (++steps > 400_000) break
            val i = k % w; val j = k / w
            for (dj in -1..1) for (di in -1..1) {
                if (di == 0 && dj == 0) continue
                val ni = i + di; val nj = j + dj
                if (!ok(ni, nj)) continue
                if (di != 0 && dj != 0 && (!ok(i + di, j) || !ok(i, j + dj))) continue
                val nk = nj * w + ni
                val nc = cost[k] + if (di != 0 && dj != 0) 1.4142f else 1f
                if (nc < cost[nk]) { cost[nk] = nc; from[nk] = k; push(nk, nc + hcost(nk)) }
            }
        }
        if (!found) return null
        // back-track, then pull the path tight
        val pts = ArrayList<Float>()
        var k = g
        while (k >= 0) { pts.add(x0 + (k % w + 0.5f) * cell); pts.add(y0 + (k / w + 0.5f) * cell); if (k == s) break; k = from[k] }
        val raw = ArrayList<FloatArray>()
        raw.add(floatArrayOf(ax, ay))
        for (q in pts.size / 2 - 1 downTo 0) raw.add(floatArrayOf(pts[q * 2], pts[q * 2 + 1]))
        raw.add(floatArrayOf(bx, by))
        val out = ArrayList<FloatArray>(); out.add(raw[0])
        var a = 0
        while (a < raw.size - 1) {
            var b = raw.size - 1
            while (b > a + 1 && !clearLine(raw[a][0], raw[a][1], raw[b][0], raw[b][1], 2.0f)) b--
            out.add(raw[b]); a = b
        }
        val f = FloatArray(out.size * 2); for ((i, p) in out.withIndex()) { f[i * 2] = p[0]; f[i * 2 + 1] = p[1] }
        return f
    }
}
