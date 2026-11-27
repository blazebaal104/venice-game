// The ACTV fleet on the water: where each boat is comes from ACTV (live GTFS-Realtime fixes every ~15 s, or the
// timetable when offline); *how* it gets there comes from a behaviour layer that sails it like a real vaporetto.
//
// Behaviour (per boat):
//   DOCKED      alongside the pontoon, engine idling ahead against it. It stays there until ACTV says it has left
//               (live: a fix away from the stop / not STOPPED_AT any more; offline: the timetable departure time).
//   DEPARTING   it never just slides away at speed: it turns its bow out from the pontoon on a short exit leg and
//               opens the throttle slowly (diesel smoke puffs as the load comes on).
//   CRUISING    following a water path (core/Navigator) to the next fix, at the speed that gets it there in time.
//   APPROACHING the next stop of its trip is ahead (live: INCOMING_AT / STOPPED_AT, offline: the next stop of the
//               trip): it slows down on a braking curve, goes astern for the last metres (white churn at the
//               stern) and comes alongside.
// Every boat has an engine model: throttle -> rpm (with lag) -> thrust against hull drag, so it accelerates and
// brakes like a 50-tonne displacement hull; rpm drives the smoke, the prop wash, the wake and the engine sound.
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
    /** sizes match assets/live/fleet.txt (tools/procedural/vaporetti.py, built at 0.9 scale);
     *  ex* = exhaust outlet in boat space; accel = full-throttle acceleration from rest (m/s2) */
    enum class Kind(val len: Float, val beam: Float, val maxSpeed: Float, val yawRate: Float, val accel: Float,
                    val exX: Float, val exY: Float, val exZ: Float, val draft: Float) {
        BATTELLO(21.5f, 3.78f, 6.0f, 0.20f, 0.30f, -4.68f, 0.81f, 3.39f, 0.55f),
        SCAFO(19.0f, 3.38f, 6.4f, 0.24f, 0.36f, -5.85f, 0.72f, 2.63f, 0.45f),
        NAVE(27.9f, 4.95f, 7.0f, 0.14f, 0.22f, -6.12f, 0f, 5.04f, 0.70f),
        LANCIONE(17.6f, 3.87f, 6.6f, 0.26f, 0.38f, -2.16f, -0.81f, 2.90f, 0.45f)
    }
    enum class State { DOCKED, DEPARTING, CRUISING, APPROACHING }

    class Berth(val x: Float, val y: Float, val axis: Float, val name: String, val key: String, val nx: Float, val ny: Float)

    class Boat(val id: String) {
        var kind = Kind.BATTELLO; var line = ""; var head = ""
        var x = 0f; var y = 0f; var h = 0f; var speed = 0f
        var offX = 0f; var offY = 0f
        var path: FloatArray? = null; var seg = 0; var segT = 0f; var pathLen = 0f
        var state = State.CRUISING; var stateT = 0f
        var dock: Berth? = null; var dockSlot = 0
        /** speed asked for by the feed / timetable (m/s) */
        var cruise = 3f
        // engine
        var cmd = 0f; var rpm = 0f; var rpmRise = 0f
        var seen = 0L; var fresh = true; var sim = false
        var reqId = 0
        // simulation: which trip and stop it is on
        var simRoute = -1; var simStop = -1
        // smoke + foam trail
        var smokeAcc = 0f
        val trail = FloatArray(TRAIL_N * 4); var trailHead = 0; var trailCount = 0; var trailX = 0f; var trailY = 0f
        val px get() = x + offX
        val py get() = y + offY
        val docked get() = state == State.DOCKED
    }

    val boats = HashMap<String, Boat>()
    private val list = ArrayList<Boat>()
    private var berths: List<Berth> = emptyList()
    private val nav = Navigator(world)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "fleet-paths").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private class PathResult(val id: String, val req: Int, val path: FloatArray)
    private val results = ConcurrentLinkedQueue<PathResult>()
    private var lastFeed = -1L
    private var simClock = 0f
    private var publishClock = 0f
    private val tmp2 = FloatArray(2)
    private var time = 0f

    fun setStops(stops: List<StopLayout.Stop>) {
        // berth: on the water side of the pontoon (its local -Y), parallel to it
        berths = stops.map { s ->
            val nx = sin(s.rot); val ny = -cos(s.rot)
            val off = 2.75f + 2.1f
            Berth(s.x + nx * off, s.y + ny * off, s.rot, s.name, norm(s.name), nx, ny)
        }
    }

    // ================================================================ per frame
    /** camX/camY: smoke and trails are only made near the camera */
    fun update(dt: Float, camX: Float, camY: Float) {
        time += dt
        val live = Vaporetti.isLive()
        if (live) {
            if (Vaporetti.updatedAt != lastFeed) { lastFeed = Vaporetti.updatedAt; applyFeed() }
            boats.values.removeAll { it.sim }
        } else {
            boats.values.removeAll { !it.sim }
            simClock -= dt
            if (!Vaporetti.enabled) boats.clear()
            else if (simClock <= 0f) { simClock = 0.5f; simulate() }
        }
        while (true) {
            val r = results.poll() ?: break
            val b = boats[r.id] ?: continue
            if (r.req != b.reqId) continue
            setPath(b, r.path)
        }
        list.clear(); list.addAll(boats.values)
        for (b in list) { behave(b, dt); engine(b, dt); trail(b, camX, camY) }
        separate(dt)
        smoke(dt, camX, camY)
        publishClock -= dt
        if (publishClock <= 0f) { publishClock = 0.05f; publish() }
        else Obstacles.boats = list.map { floatArrayOf(it.px, it.py, it.h, it.kind.len / 2f, it.kind.beam / 2f) }
    }

    // ================================================================ LIVE feed -> intentions
    private fun applyFeed() {
        val lat0 = GameState.lat0; val lon0 = GameState.lon0
        val now = System.currentTimeMillis()
        for (v in Vaporetti.vessels) {
            val p = Places.project(v.lat, v.lon, lat0, lon0)
            val b = boats.getOrPut(v.id) { Boat(v.id) }
            b.kind = kindFor(v.line); b.line = v.line; b.head = v.headsign; b.seen = now
            val stoppedAt = v.status == 1 || (v.speed in 0f..0.45f)
            val incoming = v.status == 0
            val atStop = if (stoppedAt) nearestBerth(p[0], p[1], 70f, v.stopName) else null
            val nextStop = if (incoming && v.stopName.isNotEmpty()) namedBerth(v.stopName, p[0], p[1], 320f) else null
            val bearing = if (v.bearing.isNaN()) Float.NaN else Math.toRadians(90.0 - v.bearing).toFloat()
            if (b.fresh) {
                b.fresh = false
                if (atStop != null) placeDocked(b, atStop)
                else { val w = nav.nearestWater(p[0], p[1]); b.x = w[0]; b.y = w[1]; b.h = if (bearing.isNaN()) 0f else bearing; b.speed = max(0f, min(v.speed, b.kind.maxSpeed)); b.state = State.CRUISING }
                continue
            }
            val d = hypot(p[0] - b.x, p[1] - b.y)
            if (d > 900f) { val w = nav.nearestWater(p[0], p[1]); b.x = w[0]; b.y = w[1]; b.path = null; b.dock = null; b.state = State.CRUISING; continue }
            when {
                // still at the same stop: stays docked until a fix says otherwise
                b.docked && atStop != null && (atStop === b.dock || hypot(atStop.x - b.x, atStop.y - b.y) < 45f) -> {}
                b.docked && d < 25f && !incoming -> {}      // fix jitter around the pontoon: still there
                atStop != null -> { if (b.dock !== atStop || b.state != State.APPROACHING) goTo(b, atStop, max(1.5f, d / 14f)) }
                nextStop != null -> { if (b.dock !== nextStop || b.state != State.APPROACHING) goTo(b, nextStop, max(2f, d / 14f)) }
                else -> {
                    val ahead = (p[0] - b.x) * cos(b.h) + (p[1] - b.y) * sin(b.h)
                    if (!b.docked && ahead < 0f && d < 45f) { b.cruise = min(b.cruise, 1.2f); continue }   // GPS jitter behind: never reverse
                    sailTo(b, p[0], p[1], ((d / 15f) * 1.05f).coerceIn(1.0f, b.kind.maxSpeed), water = true)
                }
            }
        }
        boats.values.removeAll { !it.sim && now - it.seen > 90_000 }
    }

    // ================================================================ intentions -> plans
    /** head for a berth and come alongside */
    private fun goTo(b: Boat, be: Berth, cruise: Float) {
        val wasDocked = b.docked
        val from = b.dock
        dockAt(b, be)
        b.cruise = cruise.coerceIn(1.2f, b.kind.maxSpeed)
        startPlan(b, b.dockX(), b.dockY(), false, if (wasDocked) from else null)
        if (wasDocked) depart(b) else b.state = State.APPROACHING
    }

    /** sail to a point (not a stop) */
    private fun sailTo(b: Boat, tx: Float, ty: Float, cruise: Float, water: Boolean) {
        val wasDocked = b.docked
        val from = b.dock
        b.cruise = cruise
        if (!wasDocked) b.dock = null
        startPlan(b, tx, ty, water, if (wasDocked) from else null)
        if (wasDocked) { depart(b); b.dock = null } else b.state = State.CRUISING
    }

    private fun depart(b: Boat) {
        b.state = State.DEPARTING; b.stateT = 0f
        cues.add(floatArrayOf(CUE_DEPART, b.x, b.y))
    }

    /** Plans a path on the worker. Leaving a pontoon, the path first goes out to a point off the pontoon (bow turned
     *  away from it) so the boat swings out instead of scraping along it. */
    private fun startPlan(b: Boat, tx: Float, ty: Float, water: Boolean, leaving: Berth?) {
        val id = b.id; val req = ++b.reqId; val sx = b.x; val sy = b.y
        var exX = Float.NaN; var exY = Float.NaN
        if (leaving != null) {
            // out from the pontoon (its water-side normal) and forward along the way the bow points
            val fx = cos(b.h); val fy = sin(b.h)
            exX = b.x + leaving.nx * 9f + fx * 16f; exY = b.y + leaving.ny * 9f + fy * 16f
            if (!nav.clearLine(b.x, b.y, exX, exY, 2f)) { exX = b.x + leaving.nx * 7f + fx * 8f; exY = b.y + leaving.ny * 7f + fy * 8f }
            if (!nav.clearLine(b.x, b.y, exX, exY, 1.5f)) exX = Float.NaN
        }
        worker.execute {
            try {
                val t = if (water) nav.nearestWater(tx, ty) else floatArrayOf(tx, ty)
                val path = if (!exX.isNaN()) {
                    val rest = nav.path(exX, exY, t[0], t[1])
                    FloatArray(rest.size + 2).also { a -> a[0] = sx; a[1] = sy; rest.copyInto(a, 2) }
                } else nav.path(sx, sy, t[0], t[1])
                results.add(PathResult(id, req, path))
            } catch (_: Throwable) { }
        }
    }

    private fun setPath(b: Boat, path: FloatArray) {
        val p = path.copyOf(); p[0] = b.x; p[1] = b.y
        b.path = p; b.seg = 0; b.segT = 0f; b.pathLen = pathLength(p)
    }

    private fun Boat.dockX() = dock!!.x - cos(dock!!.axis) * dockSlot * (kind.len + 2f)
    private fun Boat.dockY() = dock!!.y - sin(dock!!.axis) * dockSlot * (kind.len + 2f)
    /** berth heading: along the pontoon, whichever way is closer to the boat's current heading */
    private fun Boat.dockH(cur: Float): Float { val a = dock!!.axis; return if (cos(cur - a) >= 0f) a else a + PI.toFloat() }

    private fun dockAt(b: Boat, berth: Berth) {
        if (b.dock === berth) return
        b.dock = berth
        // a second boat at the same pontoon waits just behind the first
        b.dockSlot = if (boats.values.any { it !== b && it.dock === berth && it.dockSlot == 0 }) 1 else 0
    }

    private fun placeDocked(b: Boat, be: Berth) {
        dockAt(b, be); b.x = b.dockX(); b.y = b.dockY(); b.h = b.dockH(b.h)
        b.speed = 0f; b.state = State.DOCKED; b.path = null; b.rpm = 0.12f; b.cmd = 0.12f
    }

    // ================================================================ behaviour: what speed / heading it wants
    private fun behave(b: Boat, dt: Float) {
        b.stateT += dt
        if (b.state == State.DOCKED) {
            // held against the pontoon with a little ahead throttle; settles onto the exact berth
            val be = b.dock
            if (be != null) {
                val k = 1f - exp(-dt * 0.6f)
                b.x += (b.dockX() - b.x) * k; b.y += (b.dockY() - b.y) * k
                b.h += wrap(b.dockH(b.h) - b.h) * k
            }
            b.speed *= exp(-dt * 2f); b.cmd = 0.12f
            return
        }
        val p = b.path
        if (p == null) {
            // waiting for a plan: carry on gently on the current heading if the water ahead is clear
            val clear = b.speed > 0.2f && nav.clearLine(b.x, b.y, b.x + cos(b.h) * 14f, b.y + sin(b.h) * 14f, 2f)
            b.cmd = if (clear) control(b, min(b.speed, b.cruise)) else control(b, 0f)
            b.x += cos(b.h) * b.speed * dt; b.y += sin(b.h) * b.speed * dt
            return
        }
        val n = p.size / 2
        val travelled = travelledOn(p, b.seg, b.segT)
        val remaining = max(0f, b.pathLen - travelled)
        // ---- the speed it wants
        var want = b.cruise
        if (b.state == State.DEPARTING) {
            // slow throttle-up while turning out from the pontoon
            want = min(want, 0.8f + b.stateT * 0.22f)
            if (b.stateT > 8f && travelled > 20f) b.state = if (b.dock != null) State.APPROACHING else State.CRUISING
        }
        if (b.dock != null && b.state != State.DEPARTING) {
            // braking curve into the berth (~0.18 m/s2), dead slow for the last few metres
            want = min(want, sqrt(2f * 0.18f * max(remaining - 3f, 0f)) + 0.35f)
        }
        b.cmd = control(b, want)
        // ---- move along the path
        var move = b.speed * dt
        while (move > 0f && b.seg < n - 1) {
            val segLen = hypot(p[b.seg * 2 + 2] - p[b.seg * 2], p[b.seg * 2 + 3] - p[b.seg * 2 + 1])
            val left = segLen - b.segT
            if (move < left) { b.segT += move; move = 0f } else { move -= left; b.seg++; b.segT = 0f }
        }
        if (b.seg >= n - 1) {
            b.x = p[(n - 1) * 2]; b.y = p[(n - 1) * 2 + 1]; b.path = null
            if (b.dock != null) { b.state = State.DOCKED; b.stateT = 0f; cues.add(floatArrayOf(CUE_DOCK, b.x, b.y)) }
            else b.state = State.CRUISING
            return
        }
        val ax = p[b.seg * 2]; val ay = p[b.seg * 2 + 1]; val bx = p[b.seg * 2 + 2]; val by = p[b.seg * 2 + 3]
        val segLen = max(hypot(bx - ax, by - ay), 1e-3f)
        b.x = ax + (bx - ax) * b.segT / segLen; b.y = ay + (by - ay) * b.segT / segLen
        // ---- heading: towards a point ahead on the path (further when faster), no faster than the hull can turn;
        // the last metres into a berth line up with the pontoon
        lookAhead(p, b.seg, b.segT, 8f + b.speed * 2f, tmp2)
        val wantH = if (b.dock != null && remaining < 14f && b.state != State.DEPARTING) b.dockH(b.h) else atan2(tmp2[1] - b.y, tmp2[0] - b.x)
        // rudder authority grows with speed and with prop wash (a vaporetto can pivot slowly on its prop)
        val rate = b.kind.yawRate * (0.25f + 0.75f * min(1f, b.speed / 3f)) + 0.06f * b.rpm
        b.h += wrap(wantH - b.h).coerceIn(-rate * dt, rate * dt)
    }

    /** throttle (-0.6 astern .. 1 ahead) that brings the boat to `want` m/s */
    private fun control(b: Boat, want: Float): Float {
        val vmax = b.kind.maxSpeed
        val err = want - b.speed
        return if (err < -0.9f && b.speed > 0.6f) (-0.25f + err * 0.25f).coerceIn(-0.6f, -0.15f)     // astern to stop her
        else (want / vmax + err * 0.6f / vmax * 3f).coerceIn(0f, 1f)
    }

    /** diesel + prop + hull: rpm follows the throttle with lag; thrust ~ rpm^2, drag ~ v^2 */
    private fun engine(b: Boat, dt: Float) {
        val target = abs(b.cmd).coerceAtLeast(0.1f)
        val old = b.rpm
        b.rpm += (target - b.rpm).coerceIn(-0.45f * dt, 0.22f * dt)
        b.rpmRise = max(0f, (b.rpm - old) / max(dt, 1e-3f))
        if (b.state == State.DOCKED) return
        val vmax = b.kind.maxSpeed
        val dir = if (b.cmd < 0f) -0.65f else 1f
        val thrust = dir * b.rpm * b.rpm * vmax * vmax
        val drag = b.speed * abs(b.speed) + 0.4f * b.speed
        b.speed += b.kind.accel * (thrust - drag) / (vmax * vmax) * dt
        b.speed = b.speed.coerceIn(-0.6f, vmax)
        if (b.speed < 0f && b.path != null) b.speed = max(b.speed, 0f)          // astern only stops her, never backs along the path
    }

    // ================================================================ OFFLINE: the timetable drives the same behaviour
    private class SimRoute(val line: String, val head: String, val berth: IntArray, val arr: IntArray, val dep: IntArray, val starts: IntArray)
    private var simRoutes: List<SimRoute>? = null; private var simFrom: Any? = null

    private fun simulate() {
        if (berths.isEmpty()) return
        val src = Vaporetti.routes
        if (simRoutes == null || simFrom !== src) { simRoutes = buildSimRoutes(src); simFrom = src }
        val cal = Calendar.getInstance()
        val secs = cal.get(Calendar.HOUR_OF_DAY) * 3600 + cal.get(Calendar.MINUTE) * 60 + cal.get(Calendar.SECOND) + cal.get(Calendar.MILLISECOND) / 1000f
        val alive = HashSet<String>()
        for ((ri, r) in simRoutes!!.withIndex()) {
            val dur = r.arr.last()
            for (st in r.starts) {
                var t = secs - st; if (t < -3600f) t += 86400f
                if (t < 0f || t > dur + 30) continue
                val id = "sim:${r.line}:${r.berth[0]}:$st"
                alive.add(id)
                val b = boats.getOrPut(id) { Boat(id).also { it.sim = true; it.kind = kindFor(r.line); it.line = r.line; it.head = r.head } }
                b.simRoute = ri
                follow(b, r, t)
            }
        }
        boats.values.removeAll { it.sim && it.id !in alive }
    }

    /** where the timetable says the boat should be at trip time t -> what the behaviour layer should do */
    private fun follow(b: Boat, r: SimRoute, t: Float) {
        val n = r.berth.size
        var i = 0
        while (i < n - 1 && t >= r.arr[i + 1]) i++
        val dwelling = t <= r.dep[i] || i == n - 1
        if (b.fresh) {
            b.fresh = false
            if (dwelling) { placeDocked(b, berths[r.berth[i]]); b.simStop = i; return }
            // mid-leg: start on the straight line between the stops, already under way
            val a = berths[r.berth[i]]; val c = berths[r.berth[i + 1]]
            val f = ((t - r.dep[i]) / max(1, r.arr[i + 1] - r.dep[i]).toFloat()).coerceIn(0.1f, 0.9f)
            val w = nav.nearestWater(a.x + (c.x - a.x) * f, a.y + (c.y - a.y) * f)
            b.x = w[0]; b.y = w[1]; b.h = atan2(c.y - a.y, c.x - a.x); b.speed = 3f; b.rpm = 0.5f
            b.simStop = i + 1
            goTo(b, c, legSpeed(b, c, r.arr[i + 1] - t))
            return
        }
        // the stop it should be at, or heading for
        val want = if (dwelling) i else i + 1
        if (b.simStop == want) {
            if (!b.docked && b.dock != null) b.cruise = legSpeed(b, b.dock!!, r.arr[want] - t)
            return
        }
        // time to leave for the next stop (or running so late the timetable moved on: head for where it should be)
        val be = berths[r.berth[want]]
        b.simStop = want
        goTo(b, be, legSpeed(b, be, r.arr[want] - t))
    }

    private fun legSpeed(b: Boat, be: Berth, secsLeft: Float): Float {
        val d = hypot(be.x - b.x, be.y - b.y) * 1.2f
        return (d / max(secsLeft, 10f) * 1.15f).coerceIn(2.2f, b.kind.maxSpeed)
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
                    a[i] = d[i - 1] + (dist / 3.2f).toInt() + 35; d[i] = a[i] + 25
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

    // ================================================================ effects: exhaust smoke and foam trails
    /** smoke particles: x, y, z, vx, vy, age, life, size0, dark (9 floats) */
    val smokeP = FloatArray(MAX_SMOKE * 9); var smokeN = 0

    private fun smoke(dt: Float, camX: Float, camY: Float) {
        // age and drift (wind from the weather, slow rise, growing puffs)
        val w = com.blazebaal.venice.live.Weather.windVec()
        var i = 0
        while (i < smokeN) {
            val o = i * 9
            smokeP[o + 5] += dt
            if (smokeP[o + 5] >= smokeP[o + 6]) {
                val last = (smokeN - 1) * 9
                if (o != last) smokeP.copyInto(smokeP, o, last, last + 9)
                smokeN--; continue
            }
            val drag = exp(-dt * 0.6f)
            smokeP[o + 3] = smokeP[o + 3] * drag + w[0] * 0.25f * (1f - drag)
            smokeP[o + 4] = smokeP[o + 4] * drag + w[1] * 0.25f * (1f - drag)
            smokeP[o] += smokeP[o + 3] * dt; smokeP[o + 1] += smokeP[o + 4] * dt
            smokeP[o + 2] += (0.55f / (1f + smokeP[o + 5])) * dt
            i++
        }
        for (b in list) {
            val dx = b.px - camX; val dy = b.py - camY
            if (dx * dx + dy * dy > 260f * 260f) continue
            // a thin haze at idle, more with rpm, dark puffs when the throttle opens (the diesel under load)
            val load = min(1f, b.rpmRise * 4f)
            b.smokeAcc += dt * (1.5f + 7f * b.rpm + 22f * load)
            while (b.smokeAcc >= 1f && smokeN < MAX_SMOKE) {
                b.smokeAcc -= 1f
                val c = cos(b.h); val s = sin(b.h)
                val o = smokeN * 9
                val j = rnd()
                smokeP[o] = b.px + c * b.kind.exX - s * b.kind.exY + (j - 0.5f) * 0.2f
                smokeP[o + 1] = b.py + s * b.kind.exX + c * b.kind.exY + (rnd() - 0.5f) * 0.2f
                smokeP[o + 2] = b.kind.exZ
                // left behind by the boat: starts with part of its velocity
                smokeP[o + 3] = c * b.speed * 0.3f; smokeP[o + 4] = s * b.speed * 0.3f
                smokeP[o + 5] = 0f; smokeP[o + 6] = 2.5f + 3f * b.rpm + rnd() * 1.5f
                smokeP[o + 7] = 0.25f + 0.25f * b.rpm
                smokeP[o + 8] = (0.15f + 0.85f * load).coerceIn(0f, 1f)
                smokeN++
            }
            if (b.smokeAcc > 1f) b.smokeAcc = 0f
        }
    }

    /** Foam trail: points dropped behind the stern as the boat moves (x, y, time, strength). */
    private fun trail(b: Boat, camX: Float, camY: Float) {
        val dx = b.px - camX; val dy = b.py - camY
        if (dx * dx + dy * dy > 700f * 700f) { b.trailCount = 0; return }
        val c = cos(b.h); val s = sin(b.h)
        val sx = b.px - c * b.kind.len * 0.5f; val sy = b.py - s * b.kind.len * 0.5f
        if (b.trailCount > 0 && hypot(sx - b.trailX, sy - b.trailY) < 2.2f) return
        b.trailX = sx; b.trailY = sy
        val o = b.trailHead * 4
        b.trail[o] = sx; b.trail[o + 1] = sy; b.trail[o + 2] = time
        // white water from the prop: grows with rpm, strongest going astern; little at dead slow
        b.trail[o + 3] = (0.25f + 0.75f * b.rpm) * min(1f, 0.25f + abs(b.speed) / 3f) * (if (b.cmd < 0f) 1.4f else 1f)
        b.trailHead = (b.trailHead + 1) % TRAIL_N
        b.trailCount = min(TRAIL_N, b.trailCount + 1)
    }

    /** Triangle strip vertices for all foam trails into out (x, y, along 0..1 age, across -1..1, strength):
     *  returns the vertex count. Trails widen and fade with age; drawn as separate triangle lists. */
    fun buildTrails(out: FloatArray, maxDist: Float, camX: Float, camY: Float): Int {
        var n = 0
        for (b in list) {
            if (b.trailCount < 1) continue
            val dx = b.px - camX; val dy = b.py - camY
            if (dx * dx + dy * dy > maxDist * maxDist) continue
            // newest point = the stern now, then back through the history
            var prevX = b.px - cos(b.h) * b.kind.len * 0.5f; var prevY = b.py - sin(b.h) * b.kind.len * 0.5f
            var prevAge = 0f; var prevStr = b.trail[((b.trailHead - 1 + TRAIL_N) % TRAIL_N) * 4 + 3]
            for (k in 1..b.trailCount) {
                val o = ((b.trailHead - k + TRAIL_N * 2) % TRAIL_N) * 4
                val x = b.trail[o]; val y = b.trail[o + 1]; val age = time - b.trail[o + 2]; val str = b.trail[o + 3]
                if (age > TRAIL_LIFE) break
                if (n + 6 > out.size / 5) return n
                val ex = x - prevX; val ey = y - prevY; val l = max(hypot(ex, ey), 1e-3f)
                val nx = -ey / l; val ny = ex / l
                val w0 = b.kind.beam * 0.35f + prevAge * 0.35f; val w1 = b.kind.beam * 0.35f + age * 0.35f
                val a0 = prevAge / TRAIL_LIFE; val a1 = age / TRAIL_LIFE
                fun v(px: Float, py: Float, a: Float, side: Float, st: Float) { val q = n * 5; out[q] = px; out[q + 1] = py; out[q + 2] = a; out[q + 3] = side; out[q + 4] = st; n++ }
                v(prevX - nx * w0, prevY - ny * w0, a0, -1f, prevStr); v(prevX + nx * w0, prevY + ny * w0, a0, 1f, prevStr); v(x + nx * w1, y + ny * w1, a1, 1f, str)
                v(prevX - nx * w0, prevY - ny * w0, a0, -1f, prevStr); v(x + nx * w1, y + ny * w1, a1, 1f, str); v(x - nx * w1, y - ny * w1, a1, -1f, str)
                prevX = x; prevY = y; prevAge = age; prevStr = str
            }
        }
        return n
    }

    /** Up to 4 hulls for the wake simulation (the ones nearest to (x, y)): x, y, heading, depth | throttle, 0, speed, 0 |
     *  half length, half beam. Returns how many. */
    fun wakeHulls(x: Float, y: Float, radius: Float, pose: FloatArray, info: FloatArray, size: FloatArray): Int {
        var n = 0
        val r2 = radius * radius
        // nearest first (simple selection; the fleet is small)
        val cand = list.filter { val dx = it.px - x; val dy = it.py - y; dx * dx + dy * dy < r2 }.sortedBy { hypot(it.px - x, it.py - y) }
        for (b in cand) {
            if (n >= 4) break
            pose[n * 4] = b.px; pose[n * 4 + 1] = b.py; pose[n * 4 + 2] = b.h; pose[n * 4 + 3] = b.kind.draft
            info[n * 4] = b.cmd.coerceIn(-1f, 1f) * b.rpm; info[n * 4 + 1] = 0f; info[n * 4 + 2] = b.speed; info[n * 4 + 3] = 0f
            size[n * 2] = b.kind.len / 2f; size[n * 2 + 1] = b.kind.beam / 2f
            n++
        }
        return n
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
    /** the berth of a named stop near (x, y) */
    private fun namedBerth(stopName: String, x: Float, y: Float, r: Float): Berth? {
        val k = norm(stopName); if (k.isEmpty()) return null
        var best: Berth? = null; var bd = r
        for (b in berths) if (b.key.startsWith(k) || k.startsWith(b.key)) { val d = hypot(b.x - x, b.y - y); if (d < bd) { bd = d; best = b } }
        return best
    }

    /** Moving boats that overlap (only possible through bad data) slide apart; docked ones stay put. */
    private fun separate(dt: Float) {
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
        val ux = cos(b.h); val uy = sin(b.h); val ca = cos(a.h); val sa = sin(a.h)
        for (k in 0..4) {
            val ta = (k / 4f - 0.5f) * a.kind.len * 0.9f
            val x = a.px + ca * ta; val y = a.py + sa * ta
            val t = ((x - b.px) * ux + (y - b.py) * uy).coerceIn(-b.kind.len * 0.45f, b.kind.len * 0.45f)
            best = min(best, hypot(b.px + ux * t - x, b.py + uy * t - y))
        }
        return best
    }

    private fun travelledOn(p: FloatArray, seg: Int, segT: Float): Float {
        var s = segT
        for (i in 0 until seg) s += hypot(p[i * 2 + 2] - p[i * 2], p[i * 2 + 3] - p[i * 2 + 1])
        return s
    }
    private fun lookAhead(p: FloatArray, seg: Int, segT: Float, dist: Float, out: FloatArray) {
        var s = seg; var t = segT + dist; val n = p.size / 2
        while (s < n - 1) {
            val l = hypot(p[s * 2 + 2] - p[s * 2], p[s * 2 + 3] - p[s * 2 + 1])
            if (t <= l) { val k = t / max(l, 1e-3f); out[0] = p[s * 2] + (p[s * 2 + 2] - p[s * 2]) * k; out[1] = p[s * 2 + 1] + (p[s * 2 + 3] - p[s * 2 + 1]) * k; return }
            t -= l; s++
        }
        out[0] = p[(n - 1) * 2]; out[1] = p[(n - 1) * 2 + 1]
    }
    private fun pathLength(p: FloatArray): Float { var s = 0f; for (i in 0 until p.size / 2 - 1) s += hypot(p[i * 2 + 2] - p[i * 2], p[i * 2 + 3] - p[i * 2 + 1]); return s }

    private var seed = 12345
    private fun rnd(): Float { seed = seed * 1103515245 + 12345; return ((seed ushr 8) and 0xFFFF) / 65535f }

    private fun publish() {
        while (cues.size > 40) cues.poll()
        Obstacles.boats = list.map { floatArrayOf(it.px, it.py, it.h, it.kind.len / 2f, it.kind.beam / 2f) }
        markers = list.map { Marker(it.px, it.py, it.h, it.line, it.head) }
        // x, y, speed, kind, docked, rpm
        audio = list.map { floatArrayOf(it.px, it.py, it.speed, it.kind.ordinal.toFloat(), if (it.docked) 1f else 0f, it.rpm) }
    }

    fun model(b: Boat, time: Float, waveAmp: Float): FloatArray {
        val z = Waves.height(b.px, b.py, time, waveAmp) * 0.5f
        // squat by the stern and a little heel into turns when under power
        val pitch = -0.012f * b.rpm * min(1f, b.speed / 3f)
        return Mat4.mul(Mat4.mul(Mat4.translation(b.px, b.py, z), Mat4.rotZ(b.h)), Mat4.rotY(pitch))
    }

    fun shutdown() { worker.shutdownNow() }

    class Marker(val x: Float, val y: Float, val h: Float, val line: String, val head: String)
    companion object {
        const val CUE_DEPART = 1f; const val CUE_DOCK = 2f
        const val MAX_SMOKE = 900
        const val TRAIL_N = 48
        const val TRAIL_LIFE = 45f
        @Volatile var markers: List<Marker> = emptyList()
        /** x, y, speed, kind, docked, rpm - for the audio engine */
        @Volatile var audio: List<FloatArray> = emptyList()
        /** one-shot sound cues (type, x, y) for the audio engine */
        val cues = ConcurrentLinkedQueue<FloatArray>()

        /** which boat type runs which line (ACTV assigns types by line: the big-bridge Grand Canal lines get the
         *  motobattelli, the circular lines under low bridges the motoscafi, the lagoon lines the motonavi,
         *  the island tourist lines the lancioni "Gran Turismo") */
        fun kindFor(line: String): Kind {
            val l = line.trim().uppercase()
            if (l in setOf("3", "4.1", "4.2", "5.1", "5.2", "6", "7", "10", "N.")) return Kind.SCAFO
            if (l in setOf("9", "20", "16", "19", "21")) return Kind.LANCIONE
            val n = l.toFloatOrNull()
            if (n != null && n >= 11f && n < 20f) return Kind.NAVE
            return Kind.BATTELLO
        }
        fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "").replace(Regex("\\(.*?\\)|\".*?\""), "").replace(Regex("[^a-z0-9]"), "")
        fun wrap(a: Float): Float { var x = a; while (x > PI) x -= (2 * PI).toFloat(); while (x < -PI) x += (2 * PI).toFloat(); return x }
    }
}
