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
    enum class State { DOCKED, DEPARTING, UNDERWAY }

    class Berth(val x: Float, val y: Float, val axis: Float, val name: String, val key: String, val nx: Float, val ny: Float)

    class Boat(val id: String) {
        var kind = Kind.BATTELLO; var line = ""; var head = ""
        var x = 0f; var y = 0f; var h = 0f; var speed = 0f
        var offX = 0f; var offY = 0f
        var state = State.UNDERWAY; var stateT = 0f
        /** berth it is docked at / heading for (null: heading for a point) */
        var dock: Berth? = null; var dockSlot = 0
        // the leg it is sailing: a smoothed water path resampled every LEG_STEP m, and how far along it is
        var leg: FloatArray? = null; var legLen = 0f; var s = 0f; var legTo: Berth? = null
        var planning = false; var reqId = 0
        // where ACTV says it is along the leg (live) / should be (timetable): sFix at fixT, moving at vEst
        var sFix = 0f; var fixT = 0f; var vEst = 0f; var hasFix = false
        var simGoal = -1f
        // schedule following: the boat's "effective timetable time" (seconds since midnight) and how late it runs
        var tEff = Float.NaN; var delay = 0f; var delayKnown = false; var fixTime = 0L; var matricola = ""
        // engine
        var cmd = 0f; var rpm = 0f; var rpmRise = 0f
        var seen = 0L; var fresh = true; var sim = false
        var simStop = -1
        var smokeAcc = 0f
        var clearT = -1f; var clearAhead = true; var wakePick = false
        // horn: cooldowns, the smoothed rate of turn; wake reaching the player
        var hornCd = 0f; var hornTurnCd = 0f; var turnRate = 0f; var lastH = Float.NaN; var wakeCd = 0f; var blockT = 0f
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
    private class LegResult(val id: String, val req: Int, val path: FloatArray)
    private val results = ConcurrentLinkedQueue<LegResult>()
    private var lastFeed = -1L
    private var simClock = 0f
    private var publishClock = 0f
    private val tmp2 = FloatArray(2)
    private var time = 0f

    fun setStops(stops: List<StopLayout.Stop>) {
        // berth: alongside the pontoon on the side that faces open water (tested on the map, both sides)
        berths = stops.map { st ->
            var nx = sin(st.rot); var ny = -cos(st.rot)
            fun water(sx: Float, sy: Float): Int { var n = 0; for (d in floatArrayOf(5f, 8f, 12f, 16f)) if (world.solid(st.x + sx * d, st.y + sy * d) < 0.5f) n++; return n }
            if (water(-nx, -ny) > water(nx, ny)) { nx = -nx; ny = -ny }
            val off = 2.75f + 2.1f
            Berth(st.x + nx * off, st.y + ny * off, st.rot, st.name, norm(st.name), nx, ny)
        }
        pontoons = FloatArray(stops.size * 2).also { a -> for ((i, st) in stops.withIndex()) { a[i * 2] = st.x; a[i * 2 + 1] = st.y } }
    }

    // ================================================================ per frame
    /** camX/camY: smoke and trails are only made near the camera */
    private var blockersSet = false
    private var lastWallNs = 0L
    fun update(dt: Float, camX: Float, camY: Float) {
        time += dt
        // the app was away (another app, a notification, a page open): the real boats kept going. Put every boat
        // where its timetable time says it is now, along its own path, keeping its speed - as if you never left
        val wall = System.nanoTime(); val gap = if (lastWallNs == 0L) 0f else (wall - lastWallNs) / 1e9f; lastWallNs = wall
        if (gap > 1.5f) catchUp()
        if (!blockersSet && Obstacles.stops.isNotEmpty()) { blockersSet = true; val st = Obstacles.stops; worker.execute { nav.setBlockers(st) } }
        // every boat is a trip of today's timetable; ACTV's live positions say which boat is which and how late
        // it runs (see schedule()). Its effective timetable time only ever moves forward: a late boat waits at the
        // pontoon or runs a little faster, it never turns round because of one odd report.
        boats.values.removeAll { !it.sim }
        val now = secsNow()
        for (b in boats.values) {
            if (b.tEff.isNaN()) continue
            val target = now - b.delay
            b.tEff += dt * (1f + (target - b.tEff) / 25f).coerceIn(0f, 2f)
        }
        simClock -= dt
        if (!Vaporetti.enabled) boats.clear()
        else if (simClock <= 0f) { simClock = 0.5f; if (Vaporetti.today.isNotEmpty()) schedule() else simulate() }
        while (true) {
            val r = results.poll() ?: break
            val b = boats[r.id] ?: continue
            if (r.req != b.reqId) continue
            setLeg(b, r.path)
        }
        list.clear(); list.addAll(boats.values)
        for (b in list) { behave(b, dt); engine(b, dt); trail(b, camX, camY); horns(b, dt) }
        separate(dt)
        smoke(dt, camX, camY)
        publishClock -= dt
        if (publishClock <= 0f) { publishClock = 0.05f; publish() }      // collisions, minimap, audio: 20 Hz is plenty
    }

    // ================================================================ LIVE: ACTV fixes -> where along its leg it should be
    private val berthCache = HashMap<Long, Berth?>()
    /** the berth of a GTFS stop: the pontoon nearest to the stop's own coordinates (Rialto A, B, C... are separate) */
    private fun berthAt(lat: Double, lon: Double): Berth? {
        if (lat.isNaN() || lon.isNaN()) return null
        val key = ((lat * 1e5).roundToLong() shl 32) xor (lon * 1e5).roundToLong()
        return berthCache.getOrPut(key) {
            val p = Places.project(lat, lon, GameState.lat0, GameState.lon0)
            var best: Berth? = null; var bd = 75f
            for (be in berths) { val d = hypot(be.x - p[0], be.y - p[1]); if (d < bd) { bd = d; best = be } }
            best
        }
    }

    private fun applyFeed() {
        val lat0 = GameState.lat0; val lon0 = GameState.lon0
        val now = System.currentTimeMillis()
        for (v in Vaporetti.vessels) {
            val p = Places.project(v.lat, v.lon, lat0, lon0)
            val b = boats.getOrPut(v.id) { Boat(v.id) }
            b.kind = kindFor(v.line); b.line = v.line; b.head = v.headsign; b.seen = now
            // the stop the feed names: by its GTFS coordinates (exact pontoon), by name only as a fallback
            val named = berthAt(v.stopLat, v.stopLon) ?: (if (v.stopName.isNotEmpty()) namedBerth(v.stopName, p[0], p[1], 600f) else null)
            val reported = if (v.speed >= 0f) v.speed else -1f
            var stopped = v.status == 1
            var target: Berth? = named ?: nextOnTrip(v.tripStops, p[0], p[1], b)
            // behaviour inference: slow and close to a pontoon of its trip (or the one the feed names) = it's coming
            // alongside there, even before the feed says STOPPED_AT
            val slow = (reported in 0f..1.2f) || (b.hasFix && b.vEst < 1.0f && b.legTo != null)
            if (!stopped && slow) {
                val near = nearbyTripBerth(v.tripStops, p[0], p[1], 45f) ?: target?.takeIf { hypot(it.x - p[0], it.y - p[1]) < 45f }
                if (near != null) { target = near; stopped = reported in 0f..0.4f || hypot(near.x - p[0], near.y - p[1]) < 25f }
            }
            if (stopped && target != null && hypot(target.x - p[0], target.y - p[1]) > 120f) target = nearestBerth(p[0], p[1], 60f, "")
            if (b.fresh) {
                b.fresh = false
                if (stopped && target != null) { placeDocked(b, target); continue }
                val w = nav.nearestWater(p[0], p[1]); b.x = w[0]; b.y = w[1]
                b.h = if (!v.bearing.isNaN()) Math.toRadians(90.0 - v.bearing).toFloat() else if (target != null) atan2(target.y - b.y, target.x - b.x) else 0f
                b.speed = if (reported > 0f) min(reported, b.kind.maxSpeed) else 3f; b.rpm = 0.5f; b.state = State.UNDERWAY
                if (target != null) planLeg(b, target, null) else planLegTo(b, p[0], p[1])
                continue
            }
            if (hypot(p[0] - b.x, p[1] - b.y) > 1200f) { b.fresh = true; continue }        // lost for a long time: start over
            if (b.docked) {
                val d = b.dock
                // stays alongside until ACTV says it has left: a new stop ahead, or a fix clearly away from the pontoon
                val left = d != null && ((target != null && target !== d && !stopped) || hypot(p[0] - d.x, p[1] - d.y) > 40f)
                if (!left) continue
                if (target != null && target !== d) planLeg(b, target, d) else planLegTo(b, p[0], p[1], d)
                depart(b)
            } else if (target != null && b.legTo !== target) {
                planLeg(b, target, null)                       // a new stop to head for: one new leg, not one per fix
            } else if (target == null && (b.leg == null || b.legTo != null || legEndFar(b, p[0], p[1]))) {
                planLegTo(b, p[0], p[1])
            }
            // where the fix puts it along the leg, and how fast it's progressing
            val leg = b.leg ?: continue
            if (b.planning) continue
            val sNew = if (stopped && target != null && target === b.legTo) b.legLen else project(b, leg, p[0], p[1])
            if (sNew < 0f) { if (b.legTo != null) planLeg(b, b.legTo!!, null) else planLegTo(b, p[0], p[1]); continue }  // off the leg: replan
            if (b.hasFix) {
                val dtf = max(1f, time - b.fixT)
                val v1 = ((sNew - b.sFix) / dtf).coerceIn(0f, b.kind.maxSpeed)
                b.vEst = if (reported >= 0f) (v1 * 0.6f + min(reported, b.kind.maxSpeed) * 0.4f) else b.vEst * 0.4f + v1 * 0.6f
            } else b.vEst = if (reported > 0f) reported else 3f
            b.sFix = max(sNew, if (b.hasFix) b.sFix else 0f); b.fixT = time; b.hasFix = true
        }
        boats.values.removeAll { !it.sim && now - it.seen > 90_000 }
    }

    /** a pontoon of its trip within r of (x, y) */
    private fun nearbyTripBerth(stops: List<Vaporetti.StopRef>, x: Float, y: Float, r: Float): Berth? {
        var best: Berth? = null; var bd = r
        for (st in stops) { val be = berthAt(st.lat, st.lon) ?: continue; val d = hypot(be.x - x, be.y - y); if (d < bd) { bd = d; best = be } }
        return best
    }

    /** the next stop of its trip: the nearest trip stop if it's still ahead of the bow, otherwise the one after it */
    private fun nextOnTrip(stops: List<Vaporetti.StopRef>, x: Float, y: Float, b: Boat): Berth? {
        if (stops.size < 2) return null
        val bs = stops.map { berthAt(it.lat, it.lon) }
        var bi = -1; var bd = Float.MAX_VALUE
        for ((i, be) in bs.withIndex()) if (be != null) { val d = hypot(be.x - x, be.y - y); if (d < bd) { bd = d; bi = i } }
        if (bi < 0) return null
        val be = bs[bi]!!
        val ahead = (be.x - x) * cos(b.h) + (be.y - y) * sin(b.h)
        if (ahead > 15f || bi + 1 >= bs.size) return be
        for (k in bi + 1 until bs.size) bs[k]?.let { return it }
        return be
    }

    private fun legEndFar(b: Boat, x: Float, y: Float): Boolean {
        val l = b.leg ?: return true
        return hypot(l[l.size - 2] - x, l[l.size - 1] - y) > 30f && (project(b, l, x, y) < 0f || project(b, l, x, y) >= b.legLen - 4f)
    }

    // ================================================================ legs
    private fun depart(b: Boat) {
        b.state = State.DEPARTING; b.stateT = 0f
        cues.add(floatArrayOf(CUE_DEPART, b.x, b.y))
    }

    /** a leg to a berth: from the boat (or from its berth, swinging out from the pontoon first) */
    private fun planLeg(b: Boat, to: Berth, leaving: Berth?) {
        dockSlotFor(b, to)
        val slot = b.dockSlot
        val tx = to.x - cos(to.axis) * slot * (b.kind.len + 2f); val ty = to.y - sin(to.axis) * slot * (b.kind.len + 2f)
        b.legTo = to; b.hasFix = false; b.simGoal = -1f
        request(b, tx, ty, false, leaving, to)
    }
    private fun planLegTo(b: Boat, x: Float, y: Float, leaving: Berth? = null) {
        b.legTo = null; b.hasFix = false; b.simGoal = -1f
        request(b, x, y, true, leaving, null)
    }

    private fun request(b: Boat, tx: Float, ty: Float, water: Boolean, leaving: Berth?, to: Berth?) {
        val id = b.id; val req = ++b.reqId; val sx = b.x; val sy = b.y
        b.planning = true
        var exX = Float.NaN; var exY = Float.NaN
        if (leaving != null) {
            // out from the pontoon (its water side) and forward the way the bow points
            val fx = cos(b.h); val fy = sin(b.h)
            exX = b.x + leaving.nx * 9f + fx * 16f; exY = b.y + leaving.ny * 9f + fy * 16f
            if (!nav.clearLine(b.x, b.y, exX, exY, 2f)) { exX = b.x + leaving.nx * 7f + fx * 8f; exY = b.y + leaving.ny * 7f + fy * 8f }
            if (!nav.clearLine(b.x, b.y, exX, exY, 1.5f)) exX = Float.NaN
        }
        // coming into a berth: the last stretch runs parallel to the pontoon, so it comes alongside instead of nosing in
        var inX = Float.NaN; var inY = Float.NaN
        if (to != null) {
            val ax = cos(to.axis); val ay = sin(to.axis)
            val sgn = if ((tx - b.x) * ax + (ty - b.y) * ay >= 0f) 1f else -1f
            inX = tx - ax * sgn * 22f + to.nx * 4f; inY = ty - ay * sgn * 22f + to.ny * 4f
            if (!nav.clearLine(inX, inY, tx, ty, 1.5f)) inX = Float.NaN
        }
        worker.execute {
            try {
                val t = if (water) nav.nearestWater(tx, ty) else floatArrayOf(tx, ty)
                val pts = ArrayList<Float>()
                pts.add(sx); pts.add(sy)
                val from = if (!exX.isNaN()) floatArrayOf(exX, exY).also { pts.add(exX); pts.add(exY) } else floatArrayOf(sx, sy)
                val mid = if (!inX.isNaN()) nav.path(from[0], from[1], inX, inY) else nav.path(from[0], from[1], t[0], t[1])
                for (i in 2 until mid.size) pts.add(mid[i])
                if (!inX.isNaN()) { pts.add(t[0]); pts.add(t[1]) }
                results.add(LegResult(id, req, smooth(pts.toFloatArray())))
            } catch (_: Throwable) { }
        }
    }

    /** Chaikin corner cutting (3 passes, ends kept) then resampling every LEG_STEP m: a curve a hull can follow */
    private fun smooth(p0: FloatArray): FloatArray {
        var p = p0
        repeat(3) {
            val n = p.size / 2; if (n < 3) return@repeat
            val o = FloatArray((2 * (n - 1)) * 2 + 4); var k = 0
            o[k++] = p[0]; o[k++] = p[1]
            for (i in 0 until n - 1) {
                val ax = p[i * 2]; val ay = p[i * 2 + 1]; val bx = p[i * 2 + 2]; val by = p[i * 2 + 3]
                if (i > 0) { o[k++] = ax * 0.75f + bx * 0.25f; o[k++] = ay * 0.75f + by * 0.25f }
                if (i < n - 2) { o[k++] = ax * 0.25f + bx * 0.75f; o[k++] = ay * 0.25f + by * 0.75f }
            }
            o[k++] = p[p.size - 2]; o[k++] = p[p.size - 1]
            p = o.copyOf(k)
        }
        val out = ArrayList<Float>(); out.add(p[0]); out.add(p[1])
        var carry = 0f
        for (i in 0 until p.size / 2 - 1) {
            val ax = p[i * 2]; val ay = p[i * 2 + 1]; val bx = p[i * 2 + 2]; val by = p[i * 2 + 3]
            val l = hypot(bx - ax, by - ay); var d = LEG_STEP - carry
            while (d <= l) { out.add(ax + (bx - ax) * d / l); out.add(ay + (by - ay) * d / l); d += LEG_STEP }
            carry = l - (d - LEG_STEP)
        }
        out.add(p[p.size - 2]); out.add(p[p.size - 1])
        return out.toFloatArray()
    }

    private fun setLeg(b: Boat, path: FloatArray) {
        b.planning = false
        if (path.size < 4) return
        b.leg = path; b.legLen = (path.size / 2 - 1) * LEG_STEP
        b.hasFix = false
        // the boat moved on while the leg was being planned: pick it up where it is now
        b.s = max(0f, project(b, path, b.x, b.y, 0f, 60f).takeIf { it >= 0f } ?: 0f)
        if (b.legTo != null) b.dock = b.legTo
    }

    /** arc length of the leg point nearest to (x, y), searched from sFrom on; -1 if the point is > 70 m off the leg */
    private fun project(b: Boat, leg: FloatArray, x: Float, y: Float, sFrom: Float = max(0f, b.s - 120f), sTo: Float = Float.MAX_VALUE): Float {
        val n = leg.size / 2
        val i0 = (sFrom / LEG_STEP).toInt().coerceIn(0, n - 1); val i1 = min(n - 1, (sTo / LEG_STEP).toInt())
        var bi = -1; var bd = 70f * 70f
        for (i in i0..i1) { val dx = leg[i * 2] - x; val dy = leg[i * 2 + 1] - y; val d = dx * dx + dy * dy; if (d < bd) { bd = d; bi = i } }
        return if (bi < 0) -1f else bi * LEG_STEP
    }

    private fun Boat.dockX() = dock!!.x - cos(dock!!.axis) * dockSlot * (kind.len + 2f)
    private fun Boat.dockY() = dock!!.y - sin(dock!!.axis) * dockSlot * (kind.len + 2f)
    /** berth heading: along the pontoon, whichever way is closer to the boat's current heading */
    private fun Boat.dockH(cur: Float): Float { val a = dock!!.axis; return if (cos(cur - a) >= 0f) a else a + PI.toFloat() }

    private fun dockSlotFor(b: Boat, berth: Berth) {
        // a second boat at the same pontoon waits just behind the first
        b.dockSlot = if (boats.values.any { it !== b && it.dock === berth && it.dockSlot == 0 && (it.docked || it.legTo === berth) }) 1 else 0
    }

    private fun placeDocked(b: Boat, be: Berth) {
        dockSlotFor(b, be); b.dock = be; b.legTo = be
        b.x = b.dockX(); b.y = b.dockY(); b.h = b.dockH(b.h)
        b.speed = 0f; b.state = State.DOCKED; b.leg = null; b.rpm = 0.12f; b.cmd = 0.12f
    }

    // ================================================================ behaviour: its own sailing, bound to the reports
    private fun behave(b: Boat, dt: Float) {
        b.stateT += dt
        if (b.state == State.DOCKED) {
            // held against the pontoon with a little ahead throttle; settles onto the exact berth
            if (b.dock != null) {
                val k = 1f - exp(-dt * 0.6f)
                b.x += (b.dockX() - b.x) * k; b.y += (b.dockY() - b.y) * k
                b.h += wrap(b.dockH(b.h) - b.h) * k
            }
            b.speed *= exp(-dt * 2f); b.cmd = 0.12f
            return
        }
        val leg = b.leg
        if (leg == null && b.state == State.DEPARTING) { b.cmd = 0.15f; b.stateT = 0f; return }   // leg still being planned: wait alongside
        if (leg == null) {
            // no leg yet: keep going gently on the current heading while the water ahead is clear (a guess)
            if (b.stateT - b.clearT > 0.5f || b.stateT < b.clearT) { b.clearT = b.stateT; b.clearAhead = nav.clearLine(b.x, b.y, b.x + cos(b.h) * 14f, b.y + sin(b.h) * 14f, 2f) }
            b.cmd = control(b, if (b.clearAhead) min(max(b.speed, 1.5f), 3f) else 0f)
            b.x += cos(b.h) * b.speed * dt; b.y += sin(b.h) * b.speed * dt
            return
        }
        // where it should be along the leg now: the last fix extrapolated at the speed it's been making (live), or
        // the timetable's position (offline). Its own speed closes the gap gradually; it never backs up.
        val goal = when {
            b.simGoal >= 0f -> b.simGoal
            b.hasFix -> min(b.legLen, b.sFix + b.vEst * min(time - b.fixT, 25f))
            else -> b.s + b.speed * 3f
        }
        val gap = goal - b.s
        if (gap > 400f) { b.s = goal - 60f; b.speed = b.kind.maxSpeed * 0.7f }     // far behind (missed fixes): catch up off-screen-ish
        var want = when {
            gap < -6f -> b.vEst * 0.5f                                           // ahead of the reports: ease off
            else -> (b.vEst.coerceAtLeast(if (b.simGoal >= 0f) 0f else 1f) + gap * 0.07f).coerceIn(0f, b.kind.maxSpeed)
        }
        if (b.simGoal >= 0f) want = (gap * 0.12f + 0.4f).coerceIn(0f, b.kind.maxSpeed)
        val remaining = b.legLen - b.s
        if (b.state == State.DEPARTING) {
            want = min(want, 0.8f + b.stateT * 0.22f).coerceAtLeast(0.6f)       // slow throttle-up, bow swinging out
            if (b.stateT > 8f && b.s > 20f) b.state = State.UNDERWAY
        }
        if (b.legTo != null) want = min(want, sqrt(2f * 0.42f * max(remaining - 1.5f, 0f)) + 0.25f)   // braking into the stop: full astern at the end
        else if (remaining < 25f) want = min(want, remaining * 0.08f)                               // end of a guessed leg: slow, wait
        b.cmd = control(b, want, b.legTo != null && remaining < 45f)
        b.s = min(b.legLen, b.s + max(0f, b.speed) * dt)
        if (b.s >= b.legLen - 0.3f && b.legTo != null) {
            b.state = State.DOCKED; b.stateT = 0f; b.dock = b.legTo; b.leg = null
            cues.add(floatArrayOf(CUE_DOCK, b.x, b.y)); return
        }
        pointAt(leg, b.s, tmp2); b.x = tmp2[0]; b.y = tmp2[1]
        // heading: towards a point ahead on the leg (further when faster), turning no faster than the hull can
        pointAt(leg, b.s + 6f + b.speed * 1.5f, tmp2)
        var wantH = if (hypot(tmp2[0] - b.x, tmp2[1] - b.y) > 0.5f) atan2(tmp2[1] - b.y, tmp2[0] - b.x) else b.h
        if (b.legTo != null && remaining < 10f) wantH = b.dockH(b.h)
        val rate = b.kind.yawRate * (0.3f + 0.7f * min(1f, b.speed / 3f)) + 0.05f * b.rpm
        b.h += wrap(wantH - b.h).coerceIn(-rate * dt, rate * dt)
    }

    private fun pointAt(leg: FloatArray, s: Float, out: FloatArray) {
        val n = leg.size / 2
        val f = (s / LEG_STEP).coerceIn(0f, (n - 1).toFloat()); val i = min(f.toInt(), n - 2); val k = f - i
        out[0] = leg[i * 2] + (leg[i * 2 + 2] - leg[i * 2]) * k; out[1] = leg[i * 2 + 1] + (leg[i * 2 + 3] - leg[i * 2 + 1]) * k
    }

    /** throttle (-1 astern .. 1 ahead) that brings the boat to `want` m/s */
    private fun control(b: Boat, want: Float, docking: Boolean = false): Float {
        val vmax = b.kind.maxSpeed
        val err = want - b.speed
        return if (docking && err < -0.35f && b.speed > 0.5f) (-0.3f + err * 0.7f).coerceIn(-1f, -0.2f)     // coming alongside: astern, hard
        else if (err < -0.9f && b.speed > 0.6f) (-0.25f + err * 0.25f).coerceIn(-0.6f, -0.15f)              // easing off under way
        else (want / vmax + err * 1.8f / vmax).coerceIn(0f, 1f)
    }

    /** diesel + prop + hull: rpm follows the throttle with lag; thrust ~ rpm^2, drag ~ v^2 */
    private fun engine(b: Boat, dt: Float) {
        val target = abs(b.cmd).coerceAtLeast(0.1f)
        val old = b.rpm
        b.rpm += (target - b.rpm).coerceIn(-0.45f * dt, 0.22f * dt)
        b.rpmRise = max(0f, (b.rpm - old) / max(dt, 1e-3f))
        if (b.state == State.DOCKED) return
        val vmax = b.kind.maxSpeed
        val dir = if (b.cmd < 0f) -1.25f else 1f       // the prop astern at high revs brakes hard (and churns the water)
        val thrust = dir * b.rpm * b.rpm * vmax * vmax
        val drag = b.speed * abs(b.speed) + 0.4f * b.speed
        b.speed += b.kind.accel * (thrust - drag) / (vmax * vmax) * dt
        b.speed = b.speed.coerceIn(0f, vmax)
    }

    // ================================================================ OFFLINE: the timetable drives the same behaviour
    private class SimRoute(val line: String, val head: String, val berth: IntArray, val arr: IntArray, val dep: IntArray, val starts: IntArray)
    private var simRoutes: List<SimRoute>? = null; private var simFrom: Any? = null

    private fun secsNow(): Float {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 3600 + c.get(Calendar.MINUTE) * 60 + c.get(Calendar.SECOND) + c.get(Calendar.MILLISECOND) / 1000f
    }

    /** a timetable trip reduced to the pontoons that exist on the map (index into berths), absolute times */
    private class Run(val s: Vaporetti.Sched, val berth: IntArray, val arr: IntArray, val dep: IntArray) {
        val sim = SimRoute(s.line, s.headsign, berth, IntArray(arr.size) { arr[it] - dep[0] }, IntArray(dep.size) { dep[it] - dep[0] }, intArrayOf(dep[0]))
    }
    private var runs: List<Run> = emptyList(); private var runsFrom: Any? = null
    private var runById: Map<String, Run> = emptyMap()
    private var matchedAt = -1L
    private val liveOf = HashMap<String, Vaporetti.Vessel>()

    private fun buildRuns() {
        val out = ArrayList<Run>()
        for (s in Vaporetti.today) {
            val bi = ArrayList<Int>(); val a = ArrayList<Int>(); val d = ArrayList<Int>()
            for ((i, st) in s.stops.withIndex()) {
                val be = berthAt(st.lat, st.lon) ?: continue
                val idx = berths.indexOf(be)
                if (bi.isNotEmpty() && bi.last() == idx) continue
                bi.add(idx); a.add(s.arr[i]); d.add(max(s.dep[i], s.arr[i] + 20))
            }
            if (bi.size >= 2) out.add(Run(s, bi.toIntArray(), a.toIntArray(), d.toIntArray()))
        }
        runs = out; runById = out.associateBy { it.s.id }
    }

    /** where the timetable puts a run at time t (straight between pontoons: good enough to compare with a GPS fix) */
    private fun schedPos(r: Run, t: Float, out: FloatArray) {
        val n = r.berth.size
        var i = 0
        while (i < n - 1 && t >= r.arr[i + 1]) i++
        val a = berths[r.berth[i]]
        if (t <= r.dep[i] || i == n - 1) { out[0] = a.x; out[1] = a.y; return }
        val b = berths[r.berth[i + 1]]
        val f = ((t - r.dep[i]) / max(1, r.arr[i + 1] - r.dep[i]).toFloat()).coerceIn(0f, 1f)
        out[0] = a.x + (b.x - a.x) * f; out[1] = a.y + (b.y - a.y) * f
    }

    /** the timetable time between t0 and t1 whose position best matches (x, y), preferring the one closest to `near`;
     *  NaN if nothing on the run comes within maxD */
    private fun bestTime(r: Run, x: Float, y: Float, t0: Float, t1: Float, near: Float, maxD: Float): Float {
        var best = Float.NaN; var bd = maxD; var t = t0
        val p = tmp2
        while (t <= t1) {
            schedPos(r, t, p)
            val d = hypot(p[0] - x, p[1] - y)
            // ties (the dwell at a pontoon, where every second of the stop matches): the one nearest `near`
            if (d < bd - 6f || (d < bd + 6f && !best.isNaN() && abs(t - near) < abs(best - near))) { bd = min(bd, d); best = t }
            t += 10f
        }
        return best
    }

    private fun schedule() {
        if (berths.isEmpty()) return
        if (runsFrom !== Vaporetti.today) { buildRuns(); runsFrom = Vaporetti.today; boats.clear() }
        val now = secsNow()
        val live = Vaporetti.isLive() && Vaporetti.vessels.size >= 3
        val lat0 = GameState.lat0; val lon0 = GameState.lon0
        // ---- a new ACTV report: which run is each vessel, and how late is it
        if (live && Vaporetti.updatedAt != matchedAt) {
            matchedAt = Vaporetti.updatedAt
            liveOf.clear()
            val unknown = ArrayList<Vaporetti.Vessel>()
            for (v in Vaporetti.vessels) {
                val r = runById[v.tripId]
                if (r != null && now >= r.dep[0] - 1800 && now <= r.arr.last() + 3600) liveOf[r.s.id] = v else unknown.add(v)
            }
            // vessels with no trip in the report: the timetable knows which boat should be there at this hour
            for (v in unknown) {
                val p = Places.project(v.lat, v.lon, lat0, lon0)
                var best: Run? = null; var bd = 300f
                for (r in runs) {
                    if (liveOf.containsKey(r.s.id) || now < r.dep[0] - 600 || now > r.arr.last() + 1800) continue
                    schedPos(r, now, tmp2); if (hypot(tmp2[0] - p[0], tmp2[1] - p[1]) > 2000f) continue      // cheap reject first
                    val t = bestTime(r, p[0], p[1], now - 900f, now + 300f, now, bd)
                    if (!t.isNaN()) { schedPos(r, t, tmp2); val d = hypot(tmp2[0] - p[0], tmp2[1] - p[1]); if (d < bd) { bd = d; best = r } }
                }
                best?.let { liveOf[it.s.id] = v }
            }
        }
        // ---- the boats: runs under way now (when live, only the ones ACTV reports)
        val alive = HashSet<String>()
        for (r in runs) {
            val v = if (live) liveOf[r.s.id] else null
            val id = "t:" + r.s.id
            val existing = boats[id]
            // a boat missing from one report keeps going on its timetable for a while (reports drop boats now and then)
            if (live && v == null && (existing == null || System.currentTimeMillis() - existing.seen > 90_000)) continue
            val tGuess = existing?.tEff ?: (now - (existing?.delay ?: 0f))
            if (tGuess < r.dep[0] - 60 || tGuess > r.arr.last() + 120) continue
            alive.add(id)
            val b = existing ?: Boat(id).also { it.sim = true; it.kind = kindFor(r.s.line); it.line = r.s.line; it.head = r.s.headsign; boats[id] = it }
            if (v != null) {
                b.matricola = v.id; b.seen = System.currentTimeMillis()
                if (v.time != b.fixTime) {
                    b.fixTime = v.time
                    val p = Places.project(v.lat, v.lon, lat0, lon0)
                    val fixAge = ((System.currentTimeMillis() - v.time) / 1000f).coerceIn(0f, 120f)
                    // never look back more than 2 minutes from where the boat already is: no U-turns on one odd fix
                    val from = if (b.tEff.isNaN()) now - 1800f else b.tEff - 120f
                    val t = bestTime(r, p[0], p[1], from, now + 600f, if (b.tEff.isNaN()) now else b.tEff, 220f)
                    if (!t.isNaN()) {
                        val d = now - (t + fixAge)
                        b.delay = if (!b.delayKnown) d else b.delay * 0.4f + d * 0.6f
                        b.delayKnown = true
                    }
                }
            }
            if (b.tEff.isNaN()) b.tEff = now - b.delay
            follow(b, r.sim, b.tEff - r.dep[0])
        }
        boats.values.removeAll { it.sim && it.id.startsWith("t:") && it.id !in alive }
    }

    private fun simulate() {
        if (berths.isEmpty()) return
        val src = Vaporetti.routes
        if (simRoutes == null || simFrom !== src) { simRoutes = buildSimRoutes(src); simFrom = src }
        val cal = Calendar.getInstance()
        val secs = cal.get(Calendar.HOUR_OF_DAY) * 3600 + cal.get(Calendar.MINUTE) * 60 + cal.get(Calendar.SECOND) + cal.get(Calendar.MILLISECOND) / 1000f
        val alive = HashSet<String>()
        for (r in simRoutes!!) {
            val dur = r.arr.last()
            for (st in r.starts) {
                var t = secs - st; if (t < -3600f) t += 86400f
                if (t < 0f || t > dur + 30) continue
                val id = "sim:${r.line}:${r.berth[0]}:$st"
                alive.add(id)
                val b = boats.getOrPut(id) { Boat(id).also { it.sim = true; it.kind = kindFor(r.line); it.line = r.line; it.head = r.head } }
                follow(b, r, t)
            }
        }
        boats.values.removeAll { it.sim && it.id !in alive }
    }

    /** the timetable says where the boat should be at trip time t: docked at stop i, or a fraction of the leg to i+1 */
    private fun follow(b: Boat, r: SimRoute, t: Float) {
        val n = r.berth.size
        var i = 0
        while (i < n - 1 && t >= r.arr[i + 1]) i++
        val dwelling = t <= r.dep[i] || i == n - 1
        if (b.fresh) {
            b.fresh = false
            val a = berths[r.berth[i]]
            placeDocked(b, a); b.simStop = i
            if (!dwelling) {
                // mid-leg: start on the straight line between the stops, already under way, leg planned from there
                val c = berths[r.berth[i + 1]]
                val f = ((t - r.dep[i]) / max(1, r.arr[i + 1] - r.dep[i]).toFloat()).coerceIn(0.1f, 0.9f)
                val w = nav.nearestWater(a.x + (c.x - a.x) * f, a.y + (c.y - a.y) * f)
                b.x = w[0]; b.y = w[1]; b.h = atan2(c.y - a.y, c.x - a.x); b.speed = 3f; b.rpm = 0.5f
                b.state = State.UNDERWAY; b.simStop = i + 1
                planLeg(b, c, null)
            }
            return
        }
        val want = if (dwelling) i else i + 1
        if (b.simStop >= 0 && want - b.simStop >= 2) { b.fresh = true; b.leg = null; b.planning = false; b.reqId++; follow(b, r, t); return }
        if (b.simStop != want) {
            b.simStop = want
            val be = berths[r.berth[want]]
            if (b.docked) { planLeg(b, be, b.dock); depart(b) } else planLeg(b, be, null)
        }
        // progress the timetable expects along the current leg (eased out of / into the stops)
        if (!b.docked && b.leg != null && !dwelling) {
            val f = ((t - r.dep[i]) / max(1, r.arr[i + 1] - r.dep[i]).toFloat()).coerceIn(0f, 1f)
            b.simGoal = b.legLen * easeTrip(f, min(0.25f, 40f / max(b.legLen, 1f)))
        } else if (!b.docked && b.leg != null) b.simGoal = b.legLen
    }

    private fun easeTrip(f: Float, a: Float): Float {
        val v = 1f / (1f - a)
        return when {
            f < a -> v * f * f / (2f * a)
            f > 1f - a -> 1f - v * (1f - f) * (1f - f) / (2f * a)
            else -> v * (f - a / 2f)
        }.coerceIn(0f, 1f)
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
            if (dx * dx + dy * dy > 160f * 160f) continue
            // a thin haze at idle, more with rpm, dark puffs when the throttle opens (the diesel under load)
            val load = min(1f, b.rpmRise * 4f)
            val astern = if (b.cmd < -0.1f && b.speed > 0.3f) b.rpm else 0f
            b.smokeAcc += dt * (0.6f + 3.5f * b.rpm + 12f * load + 18f * astern)
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
                smokeP[o + 5] = 0f; smokeP[o + 6] = 2.2f + 2f * b.rpm + rnd() * 1.0f
                smokeP[o + 7] = 0.3f + 0.3f * b.rpm
                smokeP[o + 8] = (0.15f + 0.85f * max(load, astern)).coerceIn(0f, 1f)
                if (astern > 0f) { smokeP[o + 6] += 1.5f * astern; smokeP[o + 7] += 0.25f * astern }   // bigger, longer-lived puffs
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
        // the 4 nearest (selection without allocating: the fleet is small)
        for (b in list) b.wakePick = false
        while (n < 4) {
            var best: Boat? = null; var bd = r2
            for (c in list) { if (c.wakePick) continue; val dx = c.px - x; val dy = c.py - y; val d = dx * dx + dy * dy; if (d < bd) { bd = d; best = c } }
            val b = best ?: break
            b.wakePick = true
            // a gentle displacement, only when actually under way: a soft V like a boat's, no pumping trains of waves
            pose[n * 4] = b.px; pose[n * 4 + 1] = b.py; pose[n * 4 + 2] = b.h
            pose[n * 4 + 3] = if (b.speed < 0.6f || b.docked) 0f else b.kind.draft * 0.3f * min(1f, (b.speed - 0.6f) / 3f)
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


    private var seed = 12345
    private fun rnd(): Float { seed = seed * 1103515245 + 12345; return ((seed ushr 8) and 0xFFFF) / 65535f }

    private fun publish() {
        while (cues.size > 40) cues.poll()
        Obstacles.boats = list.map { floatArrayOf(it.px, it.py, it.h, it.kind.len / 2f, it.kind.beam / 2f) }
        markers = list.map { Marker(it.px, it.py, it.h, it.line, it.head) }
        // x, y, speed, kind, docked, rpm
        audio = list.map { floatArrayOf(it.px, it.py, it.speed, it.kind.ordinal.toFloat(), if (it.docked) 1f else 0f, it.rpm, it.h, (it.id.hashCode() and 0xFFFFFF).toFloat(), it.cmd) }
        wakeHits()
    }

    /** Vaporetti use the horn like the real ones: one long blast swinging round a bend or a blind corner (once per
     *  turn), and when you sit in their way they don't stop - two warnings, then an angry burst, again and again
     *  while you stay there. */
    private fun horns(b: Boat, dt: Float) {
        if (dt <= 0f) return
        if (b.lastH.isNaN()) b.lastH = b.h
        var dh = b.h - b.lastH
        while (dh > PI.toFloat()) dh -= 2f * PI.toFloat()
        while (dh < -PI.toFloat()) dh += 2f * PI.toFloat()
        b.lastH = b.h
        b.turnRate += ((dh / dt).coerceIn(-1f, 1f) - b.turnRate) * min(1f, dt * 1.5f)
        b.hornCd -= dt; b.hornTurnCd -= dt
        val px = playerX; val py = playerY
        if (px.isNaN() || b.docked || b.speed < 1.2f) { b.blockT = 0f; return }
        if (b.hornCd > 0f) return
        val dx = px - b.px; val dy = py - b.py
        val d2 = dx * dx + dy * dy
        if (d2 > 350f * 350f) return
        val c = cos(b.h); val s = sin(b.h)
        val ahead = dx * c + dy * s; val side = -dx * s + dy * c
        val half = b.kind.len * 0.5f
        val reach = half + 12f + b.speed * 7f
        if (ahead > half - 1f && ahead < reach && abs(side) < b.kind.beam * 0.5f + 3.5f + (ahead - half) * 0.06f) {
            // crossing its bow is fine; sitting in its way is not: it only sounds once you've been blocking it a moment
            b.blockT += dt
            if (b.blockT < 1.6f) return
            val close = 1f - ((ahead - half) / (reach - half)).coerceIn(0f, 1f)
            val angry = close > 0.55f || b.blockT > 5f
            cues.add(floatArrayOf(CUE_HORN, b.px, b.py, if (angry) 3f else 2f, b.kind.ordinal.toFloat()))
            b.hornCd = if (angry) 3.8f else 4.5f
            return
        }
        b.blockT = max(0f, b.blockT - dt * 2f)
        if (abs(b.turnRate) > 0.09f && b.hornTurnCd <= 0f && d2 < 300f * 300f) {
            cues.add(floatArrayOf(CUE_HORN, b.px, b.py, 1f, b.kind.ordinal.toFloat()))
            b.hornTurnCd = 40f + rnd() * 30f; b.hornCd = 4f
        }
    }

    /** a boat's wake reaching the player: the diverging crest (the edge of the foam trail, which widens with age)
     *  passing under the hull - one cue per boat per crossing, stronger for big boats under power */
    private fun wakeHits() {
        val px = playerX; val py = playerY
        if (px.isNaN()) return
        for (b in list) {
            b.wakeCd -= 0.05f
            if (b.trailCount < 2 || b.wakeCd > 0f) continue
            if (abs(b.px - px) > 160f || abs(b.py - py) > 160f) continue
            var prevX = b.px - cos(b.h) * b.kind.len * 0.5f; var prevY = b.py - sin(b.h) * b.kind.len * 0.5f; var prevAge = 0f
            for (k in 1..b.trailCount) {
                val o = ((b.trailHead - k + TRAIL_N * 2) % TRAIL_N) * 4
                val x = b.trail[o]; val y = b.trail[o + 1]; val age = time - b.trail[o + 2]; val str = b.trail[o + 3]
                if (age > 30f) break
                val ex = x - prevX; val ey = y - prevY; val l2 = ex * ex + ey * ey
                val t = if (l2 > 1e-4f) (((px - prevX) * ex + (py - prevY) * ey) / l2).coerceIn(0f, 1f) else 0f
                val a = prevAge + (age - prevAge) * t
                val dist = hypot(px - (prevX + ex * t), py - (prevY + ey * t))
                val crest = b.kind.beam * 0.35f + a * 0.35f
                if (a > 1.5f && abs(dist - crest) < 1.6f) {
                    val st = str * (1f - a / 30f) * (b.kind.len / 20f)
                    if (st > 0.05f) { cues.add(floatArrayOf(CUE_WAKE, px, py, st, 0f)); b.wakeCd = 4f }
                    break
                }
                prevX = x; prevY = y; prevAge = age
            }
        }
    }

    /** model matrix (translation * rotZ(heading) * rotY(squat)) written into out, no allocation */
    fun model(b: Boat, time: Float, waveAmp: Float, out: FloatArray) {
        val z = Waves.height(b.px, b.py, time, waveAmp) * 0.5f
        val pitch = -0.012f * b.rpm * min(1f, b.speed / 3f)      // squats by the stern under power
        val ch = cos(b.h); val sh = sin(b.h); val cp = cos(pitch); val sp = sin(pitch)
        out[0] = ch * cp; out[1] = sh * cp; out[2] = -sp; out[3] = 0f
        out[4] = -sh; out[5] = ch; out[6] = 0f; out[7] = 0f
        out[8] = ch * sp; out[9] = sh * sp; out[10] = cp; out[11] = 0f
        out[12] = b.px; out[13] = b.py; out[14] = z; out[15] = 1f
    }

    private fun catchUp() {
        val now = secsNow()
        for (b in boats.values) if (!b.tEff.isNaN()) b.tEff = now - b.delay
        if (Vaporetti.enabled && Vaporetti.today.isNotEmpty()) schedule()
        for (b in boats.values) if (b.leg != null && b.simGoal >= 0f && !b.docked) {
            b.s = min(b.simGoal, b.legLen)
            pointAt(b.leg!!, b.s, tmp2); b.x = tmp2[0]; b.y = tmp2[1]
            pointAt(b.leg!!, b.s + 6f, tmp2); b.h = atan2(tmp2[1] - b.y, tmp2[0] - b.x)
        }
        smokeN = 0
        for (b in boats.values) b.trailCount = 0
        simClock = 0f
    }

    /** after a pause: every boat is placed again from the latest ACTV report (or the timetable), as a real
     *  boat would have moved on meanwhile; nothing is extrapolated from the moment the game froze */
    fun resync() {
        for (b in boats.values) { b.fresh = true; b.leg = null; b.planning = false; b.reqId++; b.hasFix = false; b.simStop = -1; b.tEff = Float.NaN }
        lastFeed = -1L; simClock = 0f
        smokeN = 0
        for (b in boats.values) b.trailCount = 0
    }

    fun shutdown() { worker.shutdownNow() }

    class Marker(val x: Float, val y: Float, val h: Float, val line: String, val head: String)
    companion object {
        const val CUE_DEPART = 1f; const val CUE_DOCK = 2f; const val CUE_HORN = 3f; const val CUE_WAKE = 4f
        /** where the player's boat is (set by the renderer): vaporetti honk at you, their wakes reach you */
        @Volatile var playerX = Float.NaN; @Volatile var playerY = Float.NaN
        /** pontoon positions (x, y pairs): the pontoons creak and the ropes groan near them */
        @Volatile var pontoons = FloatArray(0)
        const val MAX_SMOKE = 260
        const val TRAIL_N = 32
        const val TRAIL_LIFE = 45f
        const val LEG_STEP = 2f
        @Volatile var markers: List<Marker> = emptyList()
        /** x, y, speed, kind, docked, rpm, heading, id, throttle command - for the audio engine */
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
