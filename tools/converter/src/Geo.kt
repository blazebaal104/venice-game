// Geometry helpers: projection to local metres, rings, ring assembly, rasterisation.
package converter

import kotlin.math.*

/** Local tangent-plane projection centred on the Campanile di San Marco. x = east (m), y = north (m). */
object Proj {
    const val LAT0 = 45.43397
    const val LON0 = 12.33901
    private val phi = Math.toRadians(LAT0)
    val mPerDegLat = 111132.954 - 559.822 * cos(2 * phi) + 1.175 * cos(4 * phi)
    val mPerDegLon = 111412.84 * cos(phi) - 93.5 * cos(3 * phi) + 0.118 * cos(5 * phi)
    fun x(lon: Double) = (lon - LON0) * mPerDegLon
    fun y(lat: Double) = (lat - LAT0) * mPerDegLat
    fun lon(x: Double) = x / mPerDegLon + LON0
    fun lat(y: Double) = y / mPerDegLat + LAT0
}

class Ring(val x: DoubleArray, val y: DoubleArray) {
    val n get() = x.size
    fun signedArea(): Double {
        var a = 0.0
        for (i in 0 until n) { val j = (i + 1) % n; a += x[i] * y[j] - x[j] * y[i] }
        return a / 2
    }
    fun area() = abs(signedArea())
    fun reversed() = Ring(x.reversedArray(), y.reversedArray())
    fun ccw() = if (signedArea() < 0) reversed() else this
    fun cw() = if (signedArea() > 0) reversed() else this
    fun contains(px: Double, py: Double): Boolean {
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            if ((y[i] > py) != (y[j] > py) && px < (x[j] - x[i]) * (py - y[i]) / (y[j] - y[i]) + x[i]) inside = !inside
            j = i
        }
        return inside
    }
    fun centroid(): Pair<Double, Double> {
        var cx = 0.0; var cy = 0.0; var a = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            val f = x[i] * y[j] - x[j] * y[i]
            cx += (x[i] + x[j]) * f; cy += (y[i] + y[j]) * f; a += f
        }
        if (abs(a) < 1e-9) return Pair(x.average(), y.average())
        return Pair(cx / (3 * a), cy / (3 * a))
    }
    fun bbox() = doubleArrayOf(x.min(), y.min(), x.max(), y.max())
    /** Douglas-Peucker simplification (closed ring). */
    fun simplify(tol: Double): Ring {
        if (n <= 4) return this
        val keep = BooleanArray(n); keep[0] = true; keep[n - 1] = true
        // split at farthest point from vertex 0 to handle closed rings
        var far = 0; var fd = -1.0
        for (i in 1 until n) { val d = hypot(x[i] - x[0], y[i] - y[0]); if (d > fd) { fd = d; far = i } }
        keep[far] = true
        fun rec(a: Int, b: Int) {
            if (b <= a + 1) return
            var md = -1.0; var mi = -1
            val dx = x[b] - x[a]; val dy = y[b] - y[a]; val len = hypot(dx, dy)
            for (i in a + 1 until b) {
                val d = if (len < 1e-9) hypot(x[i] - x[a], y[i] - y[a]) else abs(dy * x[i] - dx * y[i] + x[b] * y[a] - y[b] * x[a]) / len
                if (d > md) { md = d; mi = i }
            }
            if (md > tol) { keep[mi] = true; rec(a, mi); rec(mi, b) }
        }
        rec(0, far); rec(far, n - 1)
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        for (i in 0 until n) if (keep[i]) { xs.add(x[i]); ys.add(y[i]) }
        if (xs.size < 3) return this
        return Ring(xs.toDoubleArray(), ys.toDoubleArray())
    }
}

class Poly(val outer: Ring, val holes: List<Ring>) {
    fun rings() = listOf(outer) + holes
    fun area() = outer.area() - holes.sumOf { it.area() }
    fun contains(px: Double, py: Double) = outer.contains(px, py) && holes.none { it.contains(px, py) }
}

object Assemble {
    /** Way node list -> ring in metres (drops closing duplicate). Returns null if nodes missing. */
    fun ringFromNodes(osm: OsmData, nodes: LongArray): Ring? {
        val m = if (nodes.size > 1 && nodes.first() == nodes.last()) nodes.size - 1 else nodes.size
        if (m < 3) return null
        val xs = DoubleArray(m); val ys = DoubleArray(m)
        for (i in 0 until m) {
            val la = osm.nodeLat[nodes[i]] ?: return null
            val lo = osm.nodeLon[nodes[i]] ?: return null
            xs[i] = Proj.x(lo); ys[i] = Proj.y(la)
        }
        return Ring(xs, ys)
    }

    fun line(osm: OsmData, nodes: LongArray): Pair<DoubleArray, DoubleArray>? {
        val xs = DoubleArray(nodes.size); val ys = DoubleArray(nodes.size)
        for (i in nodes.indices) {
            val la = osm.nodeLat[nodes[i]] ?: return null
            val lo = osm.nodeLon[nodes[i]] ?: return null
            xs[i] = Proj.x(lo); ys[i] = Proj.y(la)
        }
        return Pair(xs, ys)
    }

    /** Join open ways into closed rings by matching endpoints. */
    fun rings(osm: OsmData, wayNodeLists: List<LongArray>): List<Ring> {
        val pending = wayNodeLists.filter { it.size >= 2 }.map { it.toMutableList() }.toMutableList()
        val out = ArrayList<Ring>()
        while (pending.isNotEmpty()) {
            val cur = pending.removeAt(0)
            var guard = 0
            while (cur.first() != cur.last() && guard++ < 10000) {
                val end = cur.last()
                val idx = pending.indexOfFirst { it.first() == end || it.last() == end }
                if (idx < 0) break
                val nx = pending.removeAt(idx)
                if (nx.first() == end) cur.addAll(nx.drop(1)) else cur.addAll(nx.reversed().drop(1))
            }
            if (cur.first() == cur.last()) ringFromNodes(osm, cur.toLongArray())?.let { out.add(it) }
        }
        return out
    }

    /** Multipolygon relation -> polygons (outer rings with their holes). */
    fun multipolygon(osm: OsmData, rel: OsmRelation): List<Poly> {
        val outers = ArrayList<LongArray>(); val inners = ArrayList<LongArray>()
        for (m in rel.members) {
            if (m.type != "way") continue
            val w = osm.ways[m.ref] ?: continue
            if (m.role == "inner") inners.add(w.nodes) else outers.add(w.nodes)
        }
        val o = rings(osm, outers).map { it.ccw() }
        val h = rings(osm, inners).map { it.cw() }
        return o.map { ring ->
            Poly(ring, h.filter { hr -> ring.contains(hr.x[0], hr.y[0]) })
        }
    }
}

/** 1-byte-per-cell raster over the map extent. */
class Grid(val minX: Double, val minY: Double, val w: Int, val h: Int, val res: Double) {
    val a = ByteArray(w * h)
    operator fun get(cx: Int, cy: Int): Int = if (cx < 0 || cy < 0 || cx >= w || cy >= h) 0 else a[cy * w + cx].toInt()
    fun atWorld(x: Double, y: Double) = get(((x - minX) / res).toInt(), ((y - minY) / res).toInt())

    /** Even-odd scanline fill at cell centres. writer(oldValue) -> newValue (or -1 to keep). */
    fun fill(rings: List<Ring>, writer: (Int) -> Int) {
        var y0 = Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (r in rings) for (v in r.y) { y0 = min(y0, v); y1 = max(y1, v) }
        val r0 = max(0, floor((y0 - minY) / res).toInt()); val r1 = min(h - 1, ceil((y1 - minY) / res).toInt())
        if (r1 < r0) return
        // bucket edges by start row for speed
        val buckets = arrayOfNulls<ArrayList<DoubleArray>>(r1 - r0 + 1)
        for (r in rings) {
            for (i in 0 until r.n) {
                val j = (i + 1) % r.n
                var ax = r.x[i]; var ay = r.y[i]; var bx = r.x[j]; var by = r.y[j]
                if (ay == by) continue
                if (ay > by) { val tx = ax; ax = bx; bx = tx; val ty = ay; ay = by; by = ty }
                val rs = max(r0, ceil((ay - minY) / res - 0.5).toInt())
                if (rs > r1) continue
                (buckets[rs - r0] ?: ArrayList<DoubleArray>().also { buckets[rs - r0] = it }).add(doubleArrayOf(ax, ay, bx, by))
            }
        }
        val active = ArrayList<DoubleArray>()
        val xs = DoubleArray(4096)
        for (row in r0..r1) {
            buckets[row - r0]?.let { active.addAll(it) }
            val py = minY + (row + 0.5) * res
            active.removeAll { it[3] <= py }
            var k = 0
            for (e in active) {
                if (e[1] <= py && e[3] > py && k < xs.size) xs[k++] = e[0] + (py - e[1]) * (e[2] - e[0]) / (e[3] - e[1])
            }
            java.util.Arrays.sort(xs, 0, k)
            var i = 0
            while (i + 1 < k) {
                val c0 = max(0, ceil((xs[i] - minX) / res - 0.5).toInt())
                val c1 = min(w - 1, floor((xs[i + 1] - minX) / res - 0.5).toInt())
                val base = row * w
                for (c in c0..c1) { val nv = writer(a[base + c].toInt()); if (nv >= 0) a[base + c] = nv.toByte() }
                i += 2
            }
        }
    }
}
