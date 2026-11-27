// The ACTV fleet on the water.
// LIVE (ACTV GTFS-Realtime reachable): every 15 s a position fix arrives per boat. A boat never jumps to it: a water
// path from where the boat is now to the fix is planned on a background thread (core/Navigator: follows the canals,
// keeps clear of the quays), and the boat sails along it at the speed needed to get there by the next fix,
// accelerating and braking smoothly and turning at a realistic rate. A fix slightly *behind* the boat (GPS noise)
// does not make it reverse. A stopped boat at a stop docks on the water side of its pontoon.
// OFFLINE (no live data): the same boats run the ACTV timetable (GTFS cached from the last connection), or built-in
// lines 1 and 2 if the timetable was never downloaded: deterministic positions from the schedule, docking at every
// stop for its dwell time.
// Boats never physically collide with each other; overlaps (only possible through bad data) ease apart.
package com.blazebaal.venice.render

import com.blazebaal.venice.core.*
import com.blazebaal.venice.live.Vaporetti
import com.blazebaal.venice.ui.GameState
import java.util.Calendar
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.math.*

class LiveBoats(private val world: WorldMap) {
    enum class Kind(val len: Float, val beam: Float, val maxSpeed: Float, val yawRate: Float) {
        BATTELLO(23.9f, 4.2f, 6.0f, 0.20f), SCAFO(21.1f, 3.75f, 6.0f, 0.24f), NAVE(31f, 5.5f, 7.0f, 0.14f)
    }

    class Berth(val x: Float, val y: Float, val axis: Float, val name: String, val key: String)

    class Boat(val id: String) {
        var kind = Kind.BATTELLO; var line = ""; var head = ""
        var x = 0f; var y = 0f; var h = 0f; var speed = 0f; var targetSpeed = 0f
        var offX = 0f; var offY = 0f
        var path: FloatArray? = null; var seg = 0; var segT = 0f
        var dock: Berth? = null; var dockSlot = 0; var docked = false
        var seen = 0L; var fresh = true; var sim = false
        var reqId = 0
        val px get() = x + offX
        val py get() = y + offY
    }

    val boats = HashMap<String, Boat>()
    private var berths: List<Berth> = emptyList()
    private val nav = Navigator(world)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "fleet-paths").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private class PathResult(val id: String, val req: Int, val path: FloatArray)
    private val results = ConcurrentLinkedQueue<PathResult>()
    private var lastFeed = -1L

    fun setStops(stops: List<StopLayout.Stop>) {
        // berth: on the water side of the pontoon (its local -Y), parallel to it
        berths = stops.map { s ->
            val nx = sin(s.rot); val ny = -cos(s.rot)
            val off = 2.75f + 2.3f
            Berth(s.x + nx * off, s.y + ny * off, s.rot, s.name, norm(s.name))
        }
    }

    // ================================================================ per frame
    fun update(dt: Float) {
        val live = Vaporetti.isLive()
        if (live) {
            if (Vaporetti.updatedAt != lastFeed) { lastFeed = Vaporetti.updatedAt; applyFeed() }
            boats.values.removeAll { it.sim }
        } else {
            boats.values.removeAll { !it.sim }
            if (Vaporetti.enabled) simulate() else boats.clear()
        }
        while (true) {
            val r = results.poll() ?: break
            val b = boats[r.id] ?: continue
            if (r.req != b.reqId) continue
            val p = r.path.copyOf(); p[0] = b.x; p[1] = b.y
            b.path = p; b.seg = 0; b.segT = 0f
        }
        for (b in boats.values) if (!b.sim) advance(b, dt)
        separate(dt)
        publish()
    }

    // ================================================================ LIVE
    private fun applyFeed() {
        val lat0 = GameState.lat0; val lon0 = GameState.lon0
        val now = System.currentTimeMillis()
        for (v in Vaporetti.vessels) {
            val p = Places.project(v.lat, v.lon, lat0, lon0)
            val b = boats.getOrPut(v.id) { Boat(v.id) }
            b.kind = kindFor(v.line); b.line = v.line; b.head = v.headsign; b.seen = now
            val stopped = v.status == 1 || (v.speed in 0f..0.45f)
            val near = if (stopped) nearestBerth(p[0], p[1], 70f, v.stopName) else null
            val bearing = if (v.bearing.isNaN()) Float.NaN else Math.toRadians(90.0 - v.bearing).toFloat()
            if (b.fresh) {
                b.fresh = false
                if (near != null) { dockAt(b, near); b.x = b.dockX(); b.y = b.dockY(); b.h = b.dockH(b.h); b.docked = true }
                else { val w = nav.nearestWater(p[0], p[1]); b.x = w[0]; b.y = w[1]; b.h = if (bearing.isNaN()) 0f else bearing; b.speed = max(0f, v.speed) }
                continue
            }
            val d = hypot(p[0] - b.x, p[1] - b.y)
            if (d > 900f) { b.x = p[0]; b.y = p[1]; b.path = null; b.docked = false; b.dock = null; continue }
            if (near != null) {
                if (b.dock !== near || !b.docked) {
                    if (b.dock !== near) dockAt(b, near)
                    if (!b.docked) plan(b, b.dockX(), b.dockY(), max(1.2f, min(b.kind.maxSpeed, hypot(b.dockX() - b.x, b.dockY() - b.y) / 12f)))
                }
                continue
            }
            // underway
            if (b.docked) { b.docked = false; b.dock = null; cues.add(floatArrayOf(CUE_DEPART, b.x, b.y)) }
            val ahead = (p[0] - b.x) * cos(b.h) + (p[1] - b.y) * sin(b.h)
            if (ahead < 0f && d < 45f) { b.targetSpeed = min(b.targetSpeed, 0.8f); continue }      // GPS jitter behind us: don't reverse
            plan(b, p[0], p[1], ((d / 15f) * 1.05f).coerceIn(0.6f, b.kind.maxSpeed), water = true)
        }
        boats.values.removeAll { !it.sim && now - it.seen > 90_000 }
    }

    private fun Boat.dockX() = dock!!.x - cos(dock!!.axis) * dockSlot * (kind.len + 2f)
    private fun Boat.dockY() = dock!!.y - sin(dock!!.axis) * dockSlot * (kind.len + 2f)
    /** berth heading: along the pontoon, whichever way is closer to the boat's current heading */
    private fun Boat.dockH(cur: Float): Float { val a = dock!!.axis; return if (cos(cur - a) >= 0f) a else a + PI.toFloat() }

    private fun dockAt(b: Boat, berth: Berth) {
        b.dock = berth
        // a second boat at the same pontoon waits just behind the first
        b.dockSlot = if (boats.values.any { it !== b && it.dock === berth && it.dockSlot == 0 }) 1 else 0
    }

    private fun plan(b: Boat, tx: Float, ty: Float, speed: Float, water: Boolean = false) {
        b.targetSpeed = speed
        val id = b.id; val req = ++b.reqId; val sx = b.x; val sy = b.y
        worker.execute {
            try {
                val t = if (water) nav.nearestWater(tx, ty) else floatArrayOf(tx, ty)
                results.add(PathResult(id, req, nav.path(sx, sy, t[0], t[1])))
            } catch (_: Throwable) { }
        }
    }

    /** Sail along the planned path: smooth speed, realistic turning, braking into a berth. */
    private fun advance(b: Boat, dt: Float) {
        val p = b.path
        if (p == null) {
            if (b.docked && b.dock != null) {
                val k = 1f - exp(-dt * 0.8f)
                b.x += (b.dockX() - b.x) * k; b.y += (b.dockY() - b.y) * k
                b.h += wrap(b.dockH(b.h) - b.h) * k; b.speed = 0f
            } else {
                // coasting on the last heading while waiting for the next fix, only if the water ahead is clear
                val clear = b.speed > 0.2f && nav.clearLine(b.x, b.y, b.x + cos(b.h) * 14f, b.y + sin(b.h) * 14f, 2f)
                b.speed = if (clear) b.speed * exp(-dt * 0.15f) else b.speed * exp(-dt * 1.5f)
                b.x += cos(b.h) * b.speed * dt; b.y += sin(b.h) * b.speed * dt
            }
            return
        }
        val n = p.size / 2
        var remaining = 0f
        if (b.seg < n - 1) remaining += hypot(p[b.seg * 2 + 2] - p[b.seg * 2], p[b.seg * 2 + 3] - p[b.seg * 2 + 1]) - b.segT
        for (i in b.seg + 1 until n - 1) remaining += hypot(p[i * 2 + 2] - p[i * 2], p[i * 2 + 3] - p[i * 2 + 1])
        var want = b.targetSpeed
        if (b.dock != null) want = min(want, sqrt(2f * 0.3f * max(remaining, 0f)) + 0.15f)          // braking into the berth
        b.speed += (want - b.speed).coerceIn(-0.6f * dt, 0.4f * dt)
        var move = b.speed * dt
        while (move > 0f && b.seg < n - 1) {
            val segLen = hypot(p[b.seg * 2 + 2] - p[b.seg * 2], p[b.seg * 2 + 3] - p[b.seg * 2 + 1])
            val left = segLen - b.segT
            if (move < left) { b.segT += move; move = 0f } else { move -= left; b.seg++; b.segT = 0f }
        }
        if (b.seg >= n - 1) {
            b.x = p[(n - 1) * 2]; b.y = p[(n - 1) * 2 + 1]; b.path = null
            if (b.dock != null && !b.docked) { b.docked = true; cues.add(floatArrayOf(CUE_DOCK, b.x, b.y)) }
            return
        }
        val ax = p[b.seg * 2]; val ay = p[b.seg * 2 + 1]; val bx = p[b.seg * 2 + 2]; val by = p[b.seg * 2 + 3]
        val segLen = max(hypot(bx - ax, by - ay), 1e-3f)
        b.x = ax + (bx - ax) * b.segT / segLen; b.y = ay + (by - ay) * b.segT / segLen
        // heading towards a point ~10 m ahead along the path, turning no faster than the hull can
        val look = lookAhead(p, b.seg, b.segT, 10f)
        val want2 = if (b.dock != null && remaining < 12f) b.dockH(b.h) else atan2(look[1] - b.y, look[0] - b.x)
        val rate = b.kind.yawRate * (0.5f + b.speed / b.kind.maxSpeed)
        b.h += wrap(want2 - b.h).coerceIn(-rate * dt, rate * dt)
    }

    private fun lookAhead(p: FloatArray, seg: Int, segT: Float, dist: Float): FloatArray {
        var s = seg; var t = segT + dist; val n = p.size / 2
        while (s < n - 1) {
            val l = hypot(p[s * 2 + 2] - p[s * 2], p[s * 2 + 3] - p[s * 2 + 1])
            if (t <= l) { val k = t / max(l, 1e-3f); return floatArrayOf(p[s * 2] + (p[s * 2 + 2] - p[s * 2]) * k, p[s * 2 + 1] + (p[s * 2 + 3] - p[s * 2 + 1]) * k) }
            t -= l; s++
        }
        return floatArrayOf(p[(n - 1) * 2], p[(n - 1) * 2 + 1])
    }

    // ================================================================ OFFLINE: the timetable
    private class SimRoute(val line: String, val head: String, val berth: IntArray, val arr: IntArray, val dep: IntArray, val starts: IntArray)
    private var simRoutes: List<SimRoute>? = null; private var simFrom: Any? = null
    private val legPaths = HashMap<Long, FloatArray>(); private val legPending = HashSet<Long>()
    private val legResults = ConcurrentLinkedQueue<Pair<Long, FloatArray>>()

    private fun simulate() {
        if (berths.isEmpty()) return
        val src = Vaporetti.routes
        if (simRoutes == null || simFrom !== src) { simRoutes = buildSimRoutes(src); simFrom = src }
        while (true) { val r = legResults.poll() ?: break; legPaths[r.first] = r.second; legPending.remove(r.first) }
        val cal = Calendar.getInstance()
        val secs = cal.get(Calendar.HOUR_OF_DAY) * 3600 + cal.get(Calendar.MINUTE) * 60 + cal.get(Calendar.SECOND) + cal.get(Calendar.MILLISECOND) / 1000f
        val alive = HashSet<String>()
        for (r in simRoutes!!) {
            val dur = r.arr.last()
            for (st in r.starts) {
                var t = secs - st; if (t < -3600f) t += 86400f
                if (t < 0f || t > dur + 5) continue
                val id = "sim:${r.line}:${r.berth[0]}:$st"
                alive.add(id)
                val b = boats.getOrPut(id) { Boat(id).also { it.sim = true; it.kind = kindFor(r.line); it.line = r.line; it.head = r.head } }
                poseFromSchedule(b, r, t)
            }
        }
        boats.values.removeAll { it.sim && it.id !in alive }
    }

    private fun poseFromSchedule(b: Boat, r: SimRoute, t: Float) {
        val n = r.berth.size
        var i = 0
        while (i < n - 1 && t >= r.arr[i + 1]) i++
        val prevX = b.x; val prevY = b.y
        if (t <= r.dep[i] || i == n - 1) {
            // dwelling at stop i
            val be = berths[r.berth[i]]
            if (b.dock !== be) { dockAt(b, be); if (!b.fresh) cues.add(floatArrayOf(CUE_DOCK, be.x, be.y)) }
            b.docked = true
            if (b.fresh) { b.x = be.x; b.y = be.y; b.h = be.axis }
            val k = 0.08f
            b.x += (b.dockX() - b.x) * k; b.y += (b.dockY() - b.y) * k; b.h += wrap(b.dockH(b.h) - b.h) * k
            b.speed = 0f; b.fresh = false
            return
        }
        if (b.docked) { b.docked = false; b.dock = null; cues.add(floatArrayOf(CUE_DEPART, b.x, b.y)) }
        val path = legPath(r.berth[i], r.berth[i + 1])
        val total = pathLength(path)
        // ease out of the stop and into the next one, steady in between
        val f = ((t - r.dep[i]) / max(1, r.arr[i + 1] - r.dep[i]).toFloat()).coerceIn(0f, 1f)
        val acc = min(0.2f, 25f / max(total, 1f))
        val e = easeTrip(f, acc)
        val pt = pointAt(path, e * total)
        b.x = pt[0]; b.y = pt[1]
        val la = pointAt(path, min(total, e * total + 10f))
        val wantH = if (hypot(la[0] - b.x, la[1] - b.y) > 0.5f) atan2(la[1] - b.y, la[0] - b.x) else b.h
        if (b.fresh) { b.h = wantH; b.fresh = false } else b.h += wrap(wantH - b.h) * 0.06f
        b.speed = hypot(b.x - prevX, b.y - prevY) * 60f
    }

    /** constant-speed travel with smooth acceleration over the first/last `a` fraction */
    private fun easeTrip(f: Float, a: Float): Float {
        val v = 1f / (1f - a)
        return when {
            f < a -> v * f * f / (2f * a)
            f > 1f - a -> 1f - v * (1f - f) * (1f - f) / (2f * a)
            else -> v * (f - a / 2f)
        }.coerceIn(0f, 1f)
    }

    private fun legPath(a: Int, c: Int): FloatArray {
        val key = (a.toLong() shl 32) or c.toLong()
        legPaths[key]?.let { return it }
        val ba = berths[a]; val bc = berths[c]
        if (legPending.add(key)) worker.execute { try { legResults.add(key to nav.path(ba.x, ba.y, bc.x, bc.y)) } catch (_: Throwable) { } }
        return floatArrayOf(ba.x, ba.y, bc.x, bc.y)
    }
    private fun pathLength(p: FloatArray): Float { var s = 0f; for (i in 0 until p.size / 2 - 1) s += hypot(p[i * 2 + 2] - p[i * 2], p[i * 2 + 3] - p[i * 2 + 1]); return s }
    private fun pointAt(p: FloatArray, d: Float): FloatArray {
        var left = d
        for (i in 0 until p.size / 2 - 1) {
            val l = hypot(p[i * 2 + 2] - p[i * 2], p[i * 2 + 3] - p[i * 2 + 1])
            if (left <= l) { val k = left / max(l, 1e-3f); return floatArrayOf(p[i * 2] + (p[i * 2 + 2] - p[i * 2]) * k, p[i * 2 + 1] + (p[i * 2 + 3] - p[i * 2 + 1]) * k) }
            left -= l
        }
        return floatArrayOf(p[p.size - 2], p[p.size - 1])
    }

    private fun buildSimRoutes(src: List<Vaporetti.Route>): List<SimRoute> {
        val out = ArrayList<SimRoute>()
        val lat0 = GameState.lat0; val lon0 = GameState.lon0
        for (r in src) {
            val idx = ArrayList<Int>(); val arr = ArrayList<Int>(); val dep = ArrayList<Int>()
            for (s in r.stops) {
                val p = Places.project(s.lat, s.lon, lat0, lon0)
                val bi = berths.indices.minByOrNull { hypot(berths[it].x - p[0], berths[it].y - p[1]) } ?: continue
                if (hypot(berths[bi].x - p[0], berths[bi].y - p[1]) > 90f) continue
                if (idx.isNotEmpty() && idx.last() == bi) continue
                idx.add(bi); arr.add(s.arr); dep.add(max(s.dep, s.arr + 20))
            }
            if (idx.size >= 2) out.add(SimRoute(r.line, r.headsign, idx.toIntArray(), arr.toIntArray(), dep.toIntArray(), r.starts))
        }
        if (out.isNotEmpty()) return out
        // never online: lines 1 and 2 down the Grand Canal, both ways, every 12 / 15 minutes from 6:00 to midnight
        fun line(name: String, head: String, stops: List<String>, headway: Int): List<SimRoute> {
            val ids = stops.mapNotNull { n -> val k = norm(n); berths.indices.firstOrNull { berths[it].key == k } ?: berths.indices.firstOrNull { berths[it].key.startsWith(k) } }
            if (ids.size < 2) return emptyList()
            val res = ArrayList<SimRoute>()
            for (dir in 0..1) {
                val seq = if (dir == 0) ids else ids.reversed()
                val a = IntArray(seq.size); val d = IntArray(seq.size)
                d[0] = 25
                for (i in 1 until seq.size) {
                    val dist = hypot(berths[seq[i]].x - berths[seq[i - 1]].x, berths[seq[i]].y - berths[seq[i - 1]].y) * 1.15f
                    a[i] = d[i - 1] + (dist / 3.2f).toInt() + 20; d[i] = a[i] + 25
                }
                val starts = (6 * 3600 until 24 * 3600 step headway * 60).map { it + dir * headway * 30 }.toIntArray()
                res.add(SimRoute(name, if (dir == 0) head else stops.first(), seq.toIntArray(), a, d, starts))
            }
            return res
        }
        val l1 = listOf("Piazzale Roma Santa Chiara", "Ferrovia", "Riva de Biasio", "San Marcuola", "San Stae", "Ca' D'oro", "Rialto Mercato", "Rialto",
            "San Silvestro", "Sant'Angelo", "San Tomà", "Ca' Rezzonico", "Accademia", "Santa Maria del Giglio", "Salute", "San Marco Vallaresso", "San Zaccaria", "Arsenale", "Giardini", "Sant'Elena", "Lido Santa Maria Elisabetta")
        val l2 = listOf("Tronchetto", "Piazzale Roma", "Ferrovia", "San Marcuola", "Rialto", "San Tomà", "San Samuele", "Accademia", "San Marco Giardinetti", "San Zaccaria")
        return line("1", "Lido", l1, 12) + line("2", "San Zaccaria", l2, 15)
    }

    // ================================================================ shared
    private fun nearestBerth(x: Float, y: Float, r: Float, stopName: String): Berth? {
        val k = norm(stopName)
        var best: Berth? = null; var bd = r
        for (b in berths) {
            var d = hypot(b.x - x, b.y - y)
            if (k.isNotEmpty() && b.key.startsWith(k)) d *= 0.5f
            if (d < bd) { bd = d; best = b }
        }
        return best
    }

    /** Moving boats that overlap (only possible through bad data) slide apart; docked ones stay put. */
    private fun separate(dt: Float) {
        val list = boats.values.toList()
        for (b in list) { b.offX *= exp(-dt * 0.3f); b.offY *= exp(-dt * 0.3f) }
        for (i in list.indices) for (j in i + 1 until list.size) {
            val a = list[i]; val b = list[j]
            val dx = b.px - a.px; val dy = b.py - a.py
            val reach = (a.kind.len + b.kind.len) / 2f
            if (abs(dx) > reach || abs(dy) > reach) continue
            val d = segDist(a, b)
            val need = (a.kind.beam + b.kind.beam) / 2f + 0.6f
            if (d >= need) continue
            val l = max(hypot(dx, dy), 0.1f)
            val push = (need - d) * min(1f, dt * 1.2f)
            val wa = if (a.docked) 0f else if (b.docked) 1f else 0.5f
            val wb = 1f - wa
            a.offX -= dx / l * push * wa; a.offY -= dy / l * push * wa
            b.offX += dx / l * push * wb; b.offY += dy / l * push * wb
        }
    }
    private fun segDist(a: Boat, b: Boat): Float {
        var best = Float.MAX_VALUE
        for (k in 0..4) {
            val ta = (k / 4f - 0.5f) * a.kind.len * 0.9f
            val x = a.px + cos(a.h) * ta; val y = a.py + sin(a.h) * ta
            val ux = cos(b.h); val uy = sin(b.h)
            val t = ((x - b.px) * ux + (y - b.py) * uy).coerceIn(-b.kind.len * 0.45f, b.kind.len * 0.45f)
            best = min(best, hypot(b.px + ux * t - x, b.py + uy * t - y))
        }
        return best
    }

    private fun publish() {
        while (cues.size > 40) cues.poll()
        Obstacles.boats = boats.values.map { floatArrayOf(it.px, it.py, it.h, it.kind.len / 2f, it.kind.beam / 2f) }
        markers = boats.values.map { Marker(it.px, it.py, it.h, it.line, it.head) }
        audio = boats.values.map { floatArrayOf(it.px, it.py, it.speed, it.kind.ordinal.toFloat(), if (it.docked) 1f else 0f) }
    }

    fun model(b: Boat, time: Float, waveAmp: Float): FloatArray {
        val z = Waves.height(b.px, b.py, time, waveAmp) * 0.5f
        return Mat4.mul(Mat4.translation(b.px, b.py, z), Mat4.rotZ(b.h))
    }

    fun shutdown() { worker.shutdownNow() }

    class Marker(val x: Float, val y: Float, val h: Float, val line: String, val head: String)
    companion object {
        const val CUE_DEPART = 1f; const val CUE_DOCK = 2f
        @Volatile var markers: List<Marker> = emptyList()
        /** x, y, speed, kind, docked - for the audio engine */
        @Volatile var audio: List<FloatArray> = emptyList()
        /** one-shot sound cues (type, x, y) for the audio engine */
        val cues = ConcurrentLinkedQueue<FloatArray>()

        fun kindFor(line: String): Kind {
            val l = line.trim().uppercase()
            if (l in setOf("3", "4.1", "4.2", "5.1", "5.2", "6", "10", "9")) return Kind.SCAFO
            val n = l.toFloatOrNull()
            if (n != null && n >= 11f && n < 20f) return Kind.NAVE
            return Kind.BATTELLO
        }
        fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "").replace(Regex("\\(.*?\\)|\".*?\""), "").replace(Regex("[^a-z0-9]"), "")
        fun wrap(a: Float): Float { var x = a; while (x > PI) x -= (2 * PI).toFloat(); while (x < -PI) x += (2 * PI).toFloat(); return x }
    }
}
