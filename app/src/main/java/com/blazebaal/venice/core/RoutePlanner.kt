// The navigator: a route along the canals from the boat to the waypoint, like a sat-nav for the water.
// A* over a 5 m water grid covering both ends plus a margin. Every step costs more the narrower the water is
// (distance to the nearest bank, from a breadth-first distance transform), so the route takes the Canal Grande,
// the Giudecca or the open lagoon whenever they are a sensible way round, and only threads the narrow rii when they
// save a lot. The barene and the quays are walls. The cell path is then pulled straight wherever the line of
// sight stays in water with some room either side. Runs on a worker thread (tens of ms; a few hundred at most).
package com.blazebaal.venice.core

import kotlin.math.*

class RoutePlanner(private val world: WorldMap) {
    private val cell = 5f

    /** world-space polyline (x0, y0, x1, y1, ...) from (sx, sy) to (tx, ty), or null if unreachable */
    fun plan(sx: Float, sy: Float, tx: Float, ty: Float): FloatArray? {
        val margin = 550f
        val x0 = min(sx, tx) - margin; val y0 = min(sy, ty) - margin
        val nx = (((max(sx, tx) + margin) - x0) / cell).toInt().coerceIn(8, 1200)
        val ny = (((max(sy, ty) + margin) - y0) / cell).toInt().coerceIn(8, 1200)
        val n = nx * ny
        // water mask
        val water = BooleanArray(n)
        for (j in 0 until ny) for (i in 0 until nx) {
            val x = x0 + (i + 0.5f) * cell; val y = y0 + (j + 0.5f) * cell
            water[j * nx + i] = world.solid(x, y) < 0.35f && world.marsh(x, y) < 0.5f
        }
        // distance to the bank in cells (BFS from every land cell), capped
        val clear = ShortArray(n) { if (water[it]) Short.MAX_VALUE else 0 }
        val q = IntArray(n); var qh = 0; var qt = 0
        for (k in 0 until n) if (!water[k]) q[qt++] = k
        while (qh < qt) {
            val k = q[qh++]; val d = clear[k] + 1
            if (d > 40) continue
            val i = k % nx; val j = k / nx
            if (i > 0 && clear[k - 1] > d) { clear[k - 1] = d.toShort(); q[qt++] = k - 1 }
            if (i < nx - 1 && clear[k + 1] > d) { clear[k + 1] = d.toShort(); q[qt++] = k + 1 }
            if (j > 0 && clear[k - nx] > d) { clear[k - nx] = d.toShort(); q[qt++] = k - nx }
            if (j < ny - 1 && clear[k + nx] > d) { clear[k + nx] = d.toShort(); q[qt++] = k + nx }
        }
        fun idx(x: Float, y: Float): Int {
            val i = ((x - x0) / cell).toInt().coerceIn(0, nx - 1); val j = ((y - y0) / cell).toInt().coerceIn(0, ny - 1)
            // snap to the nearest water cell (the boat might be against a quay, the pin on a fondamenta)
            for (r in 0..24) for (dj in -r..r) for (di in -r..r) {
                if (max(abs(di), abs(dj)) != r) continue
                val ii = i + di; val jj = j + dj
                if (ii in 0 until nx && jj in 0 until ny && water[jj * nx + ii]) return jj * nx + ii
            }
            return -1
        }
        val s = idx(sx, sy); val t = idx(tx, ty)
        if (s < 0 || t < 0) return null
        // cost of entering a cell: 1 in open water, rising in narrow canals, a lot right against the walls
        fun cost(k: Int): Float { val c = min(clear[k].toInt(), 40); return 1f + 2.6f / (0.6f + c) + (if (c <= 1) 1.5f else 0f) }
        val g = FloatArray(n) { Float.MAX_VALUE }; val from = IntArray(n) { -1 }
        val closed = BooleanArray(n)
        // binary heap of (f, cell)
        var hs = 0; val hk = IntArray(n.coerceAtMost(1 shl 20)); val hf = FloatArray(hk.size)
        fun push(k: Int, f: Float) {
            if (hs >= hk.size) return
            var i = hs++; hk[i] = k; hf[i] = f
            while (i > 0) { val p = (i - 1) / 2; if (hf[p] <= hf[i]) break; val tk = hk[p]; hk[p] = hk[i]; hk[i] = tk; val tf = hf[p]; hf[p] = hf[i]; hf[i] = tf; i = p }
        }
        fun pop(): Int {
            val top = hk[0]; hs--; hk[0] = hk[hs]; hf[0] = hf[hs]
            var i = 0
            while (true) { val l = i * 2 + 1; val r = l + 1; var m = i; if (l < hs && hf[l] < hf[m]) m = l; if (r < hs && hf[r] < hf[m]) m = r; if (m == i) break; val tk = hk[m]; hk[m] = hk[i]; hk[i] = tk; val tf = hf[m]; hf[m] = hf[i]; hf[i] = tf; i = m }
            return top
        }
        val ti = t % nx; val tj = t / nx
        fun h(k: Int): Float { val dx = abs(k % nx - ti).toFloat(); val dy = abs(k / nx - tj).toFloat(); return (max(dx, dy) + 0.4142f * min(dx, dy)) }
        g[s] = 0f; push(s, h(s))
        val di = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1); val dj = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
        var found = false
        while (hs > 0) {
            val k = pop()
            if (closed[k]) continue
            if (k == t) { found = true; break }
            closed[k] = true
            val i = k % nx; val j = k / nx
            for (d in 0 until 8) {
                val ii = i + di[d]; val jj = j + dj[d]
                if (ii < 0 || jj < 0 || ii >= nx || jj >= ny) continue
                val kk = jj * nx + ii
                if (!water[kk] || closed[kk]) continue
                if (d >= 4 && (!water[j * nx + ii] || !water[jj * nx + i])) continue      // no cutting corners
                val ng = g[k] + (if (d >= 4) 1.4142f else 1f) * cost(kk)
                if (ng < g[kk]) { g[kk] = ng; from[kk] = k; push(kk, ng + h(kk)) }
            }
        }
        if (!found) return null
        val cells = ArrayList<Int>(); var k = t
        while (k >= 0) { cells.add(k); k = from[k] }
        cells.reverse()
        // pull it straight: from each kept point, jump to the furthest later cell still in sight through roomy water
        fun sight(a: Int, b: Int): Boolean {
            val ax = a % nx + 0.5f; val ay = a / nx + 0.5f; val bx = b % nx + 0.5f; val by = b / nx + 0.5f
            val steps = (max(abs(bx - ax), abs(by - ay)) * 2).toInt() + 1
            val need = min(clear[a].toInt(), clear[b].toInt()).coerceAtMost(2)
            for (st in 0..steps) {
                val f = st.toFloat() / steps
                val c = ((ay + (by - ay) * f).toInt()) * nx + (ax + (bx - ax) * f).toInt()
                if (!water[c] || clear[c] < need) return false
            }
            return true
        }
        val keep = ArrayList<Int>(); var a = 0; keep.add(cells[0])
        while (a < cells.size - 1) {
            var b = cells.size - 1
            while (b > a + 1 && !sight(cells[a], cells[b])) b = if (b - a > 40) a + (b - a) / 2 else b - 1
            keep.add(cells[b]); a = b
        }
        val out = FloatArray(keep.size * 2 + 2)
        out[0] = sx; out[1] = sy
        for ((m, c) in keep.withIndex()) { if (m == 0) continue; out[m * 2] = x0 + (c % nx + 0.5f) * cell; out[m * 2 + 1] = y0 + (c / nx + 0.5f) * cell }
        out[keep.size * 2] = tx; out[keep.size * 2 + 1] = ty
        return out
    }
}
