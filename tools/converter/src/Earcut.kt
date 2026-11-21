// Polygon triangulation with holes (ear clipping with hole bridging), written from scratch.
package converter

import kotlin.math.*

object Earcut {
    private class N(val i: Int, val x: Double, val y: Double) {
        lateinit var prev: N; lateinit var next: N
        var steiner = false
    }

    /** data = flat [x0,y0,x1,y1,...], holeStarts = vertex index where each hole begins. Returns triangle vertex indices. */
    fun triangulate(data: DoubleArray, holeStarts: IntArray): IntArray {
        val n = data.size / 2
        val outerEnd = if (holeStarts.isNotEmpty()) holeStarts[0] else n
        var outer = linked(data, 0, outerEnd, true) ?: return IntArray(0)
        val tris = ArrayList<Int>()
        if (holeStarts.isNotEmpty()) outer = eliminateHoles(data, holeStarts, n, outer)
        earcut(outer, tris, 0)
        return tris.toIntArray()
    }

    private fun area(p: N, q: N, r: N) = (q.y - p.y) * (r.x - q.x) - (q.x - p.x) * (r.y - q.y)
    private fun eq(a: N, b: N) = a.x == b.x && a.y == b.y

    private fun linked(d: DoubleArray, start: Int, end: Int, clockwise: Boolean): N? {
        var sum = 0.0
        var j = end - 1
        for (i in start until end) { sum += (d[j * 2] - d[i * 2]) * (d[i * 2 + 1] + d[j * 2 + 1]); j = i }
        var last: N? = null
        if (clockwise == (sum > 0)) for (i in start until end) last = insert(i, d[i * 2], d[i * 2 + 1], last)
        else for (i in end - 1 downTo start) last = insert(i, d[i * 2], d[i * 2 + 1], last)
        if (last != null && eq(last, last.next)) { remove(last); last = last.next }
        return last
    }

    private fun insert(i: Int, x: Double, y: Double, last: N?): N {
        val p = N(i, x, y)
        if (last == null) { p.prev = p; p.next = p } else { p.next = last.next; p.prev = last; last.next.prev = p; last.next = p }
        return p
    }

    private fun remove(p: N) { p.next.prev = p.prev; p.prev.next = p.next }

    private fun filter(start: N, endIn: N? = null): N {
        var end = endIn ?: start
        var p = start
        var again: Boolean
        do {
            again = false
            if (!p.steiner && (eq(p, p.next) || area(p.prev, p, p.next) == 0.0)) {
                remove(p); p = p.prev; end = p
                if (p == p.next) break
                again = true
            } else p = p.next
        } while (again || p != end)
        return end
    }

    private fun earcut(earIn: N?, tris: ArrayList<Int>, pass: Int) {
        var ear = earIn ?: return
        var stop = ear
        var guard = 0
        while (ear.prev != ear.next && guard++ < 1_000_000) {
            val prev = ear.prev; val next = ear.next
            if (isEar(ear)) {
                tris.add(prev.i); tris.add(ear.i); tris.add(next.i)
                remove(ear)
                ear = next.next; stop = next.next
                continue
            }
            ear = next
            if (ear == stop) {
                when (pass) {
                    0 -> earcut(filter(ear), tris, 1)
                    1 -> { val e = cureLocal(filter(ear), tris); earcut(e, tris, 2) }
                    2 -> splitEarcut(ear, tris)
                }
                break
            }
        }
    }

    private fun isEar(ear: N): Boolean {
        val a = ear.prev; val b = ear; val c = ear.next
        if (area(a, b, c) >= 0) return false
        var p = ear.next.next
        while (p != ear.prev) {
            if (pointInTri(a.x, a.y, b.x, b.y, c.x, c.y, p.x, p.y) && area(p.prev, p, p.next) >= 0) return false
            p = p.next
        }
        return true
    }

    private fun cureLocal(startIn: N, tris: ArrayList<Int>): N {
        var start = startIn
        var p = start
        do {
            val a = p.prev; val b = p.next.next
            if (!eq(a, b) && intersects(a, p, p.next, b) && locallyInside(a, b) && locallyInside(b, a)) {
                tris.add(a.i); tris.add(p.i); tris.add(b.i)
                remove(p); remove(p.next)
                p = b; start = b
            }
            p = p.next
        } while (p != start)
        return filter(p)
    }

    private fun splitEarcut(start: N, tris: ArrayList<Int>) {
        var a = start
        do {
            var b = a.next.next
            while (b != a.prev) {
                if (a.i != b.i && isValidDiagonal(a, b)) {
                    var c = split(a, b)
                    a = filter(a, a.next); c = filter(c, c.next)
                    earcut(a, tris, 0); earcut(c, tris, 0)
                    return
                }
                b = b.next
            }
            a = a.next
        } while (a != start)
    }

    private fun eliminateHoles(d: DoubleArray, holeStarts: IntArray, n: Int, outerIn: N): N {
        var outer = outerIn
        val queue = ArrayList<N>()
        for (h in holeStarts.indices) {
            val s = holeStarts[h]; val e = if (h < holeStarts.size - 1) holeStarts[h + 1] else n
            val list = linked(d, s, e, false) ?: continue
            if (list == list.next) list.steiner = true
            var left = list; var p = list
            do { if (p.x < left.x || (p.x == left.x && p.y < left.y)) left = p; p = p.next } while (p != list)
            queue.add(left)
        }
        queue.sortBy { it.x }
        for (h in queue) {
            outer = eliminateHole(h, outer)
        }
        return outer
    }

    private fun eliminateHole(hole: N, outer: N): N {
        val bridge = findHoleBridge(hole, outer) ?: return outer
        val bridgeReverse = split(bridge, hole)
        filter(bridgeReverse, bridgeReverse.next)
        return filter(bridge, bridge.next)
    }

    private fun findHoleBridge(hole: N, outer: N): N? {
        var p = outer
        val hx = hole.x; val hy = hole.y
        var qx = -Double.MAX_VALUE
        var m: N? = null
        do {
            if (hy <= p.y && hy >= p.next.y && p.next.y != p.y) {
                val x = p.x + (hy - p.y) * (p.next.x - p.x) / (p.next.y - p.y)
                if (x <= hx && x > qx) { qx = x; m = if (p.x < p.next.x) p else p.next; if (x == hx) return m }
            }
            p = p.next
        } while (p != outer)
        if (m == null) return null
        val stop = m
        val mx = m.x; val my = m.y
        var tanMin = Double.MAX_VALUE
        p = m
        do {
            if (hx >= p.x && p.x >= mx && hx != p.x &&
                pointInTri(if (hy < my) hx else qx, hy, mx, my, if (hy < my) qx else hx, hy, p.x, p.y)) {
                val tan = abs(hy - p.y) / (hx - p.x)
                if (locallyInside(p, hole) && (tan < tanMin || (tan == tanMin && (p.x > m!!.x)))) { m = p; tanMin = tan }
            }
            p = p.next
        } while (p != stop)
        return m
    }

    private fun pointInTri(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, px: Double, py: Double) =
        (cx - px) * (ay - py) >= (ax - px) * (cy - py) && (ax - px) * (by - py) >= (bx - px) * (ay - py) && (bx - px) * (cy - py) >= (cx - px) * (by - py)

    private fun isValidDiagonal(a: N, b: N) =
        a.next.i != b.i && a.prev.i != b.i && !intersectsPolygon(a, b) &&
            (locallyInside(a, b) && locallyInside(b, a) && middleInside(a, b) && (area(a.prev, a, b.prev) != 0.0 || area(a, b.prev, b) != 0.0) ||
                eq(a, b) && area(a.prev, a, a.next) > 0 && area(b.prev, b, b.next) > 0)

    private fun sign(v: Double) = if (v > 0) 1 else if (v < 0) -1 else 0
    private fun onSeg(p: N, q: N, r: N) = q.x <= max(p.x, r.x) && q.x >= min(p.x, r.x) && q.y <= max(p.y, r.y) && q.y >= min(p.y, r.y)
    private fun intersects(p1: N, q1: N, p2: N, q2: N): Boolean {
        val o1 = sign(area(p1, q1, p2)); val o2 = sign(area(p1, q1, q2)); val o3 = sign(area(p2, q2, p1)); val o4 = sign(area(p2, q2, q1))
        if (o1 != o2 && o3 != o4) return true
        if (o1 == 0 && onSeg(p1, p2, q1)) return true
        if (o2 == 0 && onSeg(p1, q2, q1)) return true
        if (o3 == 0 && onSeg(p2, p1, q2)) return true
        if (o4 == 0 && onSeg(p2, q1, q2)) return true
        return false
    }
    private fun intersectsPolygon(a: N, b: N): Boolean {
        var p = a
        do {
            if (p.i != a.i && p.next.i != a.i && p.i != b.i && p.next.i != b.i && intersects(p, p.next, a, b)) return true
            p = p.next
        } while (p != a)
        return false
    }
    private fun locallyInside(a: N, b: N) =
        if (area(a.prev, a, a.next) < 0) area(a, b, a.next) >= 0 && area(a, a.prev, b) >= 0 else area(a, b, a.prev) < 0 || area(a, a.next, b) < 0
    private fun middleInside(a: N, b: N): Boolean {
        var p = a; var inside = false
        val px = (a.x + b.x) / 2; val py = (a.y + b.y) / 2
        do {
            if (((p.y > py) != (p.next.y > py)) && p.next.y != p.y && (px < (p.next.x - p.x) * (py - p.y) / (p.next.y - p.y) + p.x)) inside = !inside
            p = p.next
        } while (p != a)
        return inside
    }
    private fun split(a: N, b: N): N {
        val a2 = N(a.i, a.x, a.y); val b2 = N(b.i, b.x, b.y)
        val an = a.next; val bp = b.prev
        a.next = b; b.prev = a
        a2.next = an; an.prev = a2
        b2.next = a2; a2.prev = b2
        bp.next = b2; b2.prev = bp
        return b2
    }
}
