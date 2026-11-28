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
        /** a second boat at the same pontoon waits just behind the first (eased) */
        var slotX = 0f; var slotY = 0f
        var state = State.UNDERWAY; var stateT = 0f
        var trip: FleetPlan.Trip? = null; var stopIdx = -1; var berthIdx = -1
        var visible = false
        // schedule following: the boat's "effective timetable time" (seconds since midnight) and how late it runs
        var tEff = Float.NaN; var delay = 0f; var delayKnown = false; var fixTime = 0L; var matricola = ""
        // engine
        var cmd = 0f; var rpm = 0f; var rpmRise = 0f
        var seen = 0L
        var smokeAcc = 0f
        var wakePick = false
        // horn: cooldowns, the smoothed rate of turn; wake reaching the player
        var hornCd = 0f; var hornTurnCd = 0f; var turnRate = 0f; var lastH = Float.NaN; var wakeCd = 0f; var blockT = 0f
        val trail = FloatArray(TRAIL_N * 4); var trailHead = 0; var trailCount = 0; var trailX = 0f; var trailY = 0f
        val px get() = x + offX + slotX
        val py get() = y + offY + slotY
        val docked get() = state == State.DOCKED
    }

    val boats = HashMap<String, Boat>()
    /** the boats on the water this frame (planned, within their trip) */
    val visibleBoats: List<Boat> get() = list
    private val list = ArrayList<Boat>()
    private var berths: List<Berth> = emptyList()
    private val nav = Navigator(world)
    private var plan: FleetPlan? = null
    private var planFrom: Any? = null
    private var simClock = 0f
    private var publishClock = 0f
    private val tmp2 = FloatArray(2)
    private val pose = FloatArray(7)
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
        nav.setBlockers(Obstacles.stops.ifEmpty { emptyList() })
        plan?.shutdown(); plan = FleetPlan(nav, berths, cacheDir); planFrom = null
    }

    // ================================================================ per frame
    private var lastWallNs = 0L
    /** dt: game time (0 while paused) for the effects. The fleet itself runs on the wall clock: nothing pauses it. */
    fun update(dt: Float, camX: Float, camY: Float) {
        time += dt
        val wall = System.nanoTime(); val gap = if (lastWallNs == 0L) 0f else (wall - lastWallNs) / 1e9f; lastWallNs = wall
        val wdt = min(gap, 0.25f)
        if (gap > 1.5f) catchUp()
        if (!Vaporetti.enabled) { boats.clear(); list.clear(); publish(); return }
        ensurePlan()
        // every boat is a trip of today's timetable on its planned route; ACTV's live positions only say which boat
        // is which and how late it runs (see schedule()). Its effective timetable time only ever moves forward: a
        // late boat waits at the pontoon or runs a little faster, it never turns round because of one odd report.
        val now = secsNow()
        for (b in boats.values) {
            if (b.tEff.isNaN()) continue
            val target = now - b.delay
            if (abs(target - b.tEff) > 240f) b.tEff = target
            else b.tEff += wdt * (1f + (target - b.tEff) / 25f).coerceIn(0f, 2f)
        }
        simClock -= wdt
        if (simClock <= 0f) { simClock = 0.5f; schedule() }
        list.clear(); for (b in boats.values) { drive(b, wdt); if (b.visible) list.add(b) }
        slots(wdt)
        for (b in list) { engine(b, wdt); trail(b, camX, camY); horns(b, dt) }
        separate(wdt)
        smoke(dt, camX, camY)
        publishClock -= wdt
        if (publishClock <= 0f) { publishClock = 0.05f; publish() }      // collisions, minimap, audio: 20 Hz is plenty
    }

    // ================================================================ the plan
    private val berthCache = HashMap<Long, Int>()
    /** the berth of a GTFS stop: the pontoon nearest to the stop's own coordinates (Rialto A, B, C... are separate) */
    private fun berthIdx(lat: Double, lon: Double): Int {
        if (lat.isNaN() || lon.isNaN()) return -1
        val key = ((lat * 1e5).roundToLong() shl 32) xor (lon * 1e5).roundToLong()
        return berthCache.getOrPut(key) {
            val p = Places.project(lat, lon, GameState.lat0, GameState.lon0)
            var best = -1; var bd = 75f
            for ((i, be) in berths.withIndex()) { val d = hypot(be.x - p[0], be.y - p[1]); if (d < bd) { bd = d; best = i } }
            best
        }
    }

    /** today's timetable (or, never online, a built-in lines 1 and 2) as trips between the pontoons on the map */
    private fun ensurePlan() {
        val p = plan ?: return
        val src: Any = if (Vaporetti.today.isNotEmpty()) Vaporetti.today else FALLBACK
        if (planFrom === src) return
        planFrom = src
        val trips = if (src === FALLBACK) fallbackTrips() else Vaporetti.today.mapNotNull { tripOf(it.id, it.line, it.headsign, it.stops, it.arr, it.dep) }
        boats.clear()
        p.setTrips(trips, secsNow())
        tripById = trips.associateBy { it.id }
    }
    private var tripById: Map<String, FleetPlan.Trip> = emptyMap()

    private fun tripOf(id: String, line: String, head: String, stops: List<Vaporetti.StopRef>, arr: IntArray, dep: IntArray): FleetPlan.Trip? {
        val bi = ArrayList<Int>(); val a = ArrayList<Int>(); val d = ArrayList<Int>()
        for ((i, st) in stops.withIndex()) {
            val idx = berthIdx(st.lat, st.lon); if (idx < 0) continue
            if (bi.isNotEmpty() && bi.last() == idx) { d[d.size - 1] = max(d.last(), dep[i]); continue }
            bi.add(idx); a.add(arr[i]); d.add(max(dep[i], arr[i]))
        }
        if (bi.size < 2) return null
        return FleetPlan.Trip(id, line, head, bi.toIntArray(), a.toIntArray(), d.toIntArray()).also { sane(it) }
    }

    /** timetables round to the minute and often give no dwell: at least 20 s alongside, and never a leg faster than
     *  the boat can sail it (the time comes out of the dwell first, then the following times move on) */
    private fun sane(t: FleetPlan.Trip) {
        val vmax = kindFor(t.line).maxSpeed * 0.85f
        for (i in t.berth.indices) {
            if (t.dep[i] < t.arr[i] + 20) t.dep[i] = t.arr[i] + 20
            if (i + 1 < t.berth.size) {
                val need = (plan!!.legLength(t.berth[i], t.berth[i + 1]) / vmax).toInt() + 10
                if (t.arr[i + 1] - t.dep[i] < need) {
                    t.dep[i] = max(t.arr[i] + 15, t.arr[i + 1] - need)
                    if (t.arr[i + 1] - t.dep[i] < need) t.arr[i + 1] = t.dep[i] + need
                }
            }
        }
    }

    private fun fallbackTrips(): List<FleetPlan.Trip> {
        val out = ArrayList<FleetPlan.Trip>()
        fun line(name: String, head: String, stops: List<String>, headway: Int) {
            val ids = stops.mapNotNull { n -> val k = norm(n); berths.indices.firstOrNull { berths[it].key == k } ?: berths.indices.firstOrNull { berths[it].key.startsWith(k) } }
            if (ids.size < 2) return
            for (dir in 0..1) {
                val seq = if (dir == 0) ids else ids.reversed()
                val a = IntArray(seq.size); val d = IntArray(seq.size)
                d[0] = 25
                for (i in 1 until seq.size) {
                    val dist = hypot(berths[seq[i]].x - berths[seq[i - 1]].x, berths[seq[i]].y - berths[seq[i - 1]].y) * 1.15f
                    a[i] = d[i - 1] + (dist / 3.2f).toInt() + 35; d[i] = a[i] + 25
                }
                for (st in (6 * 3600 until 24 * 3600 step headway * 60).map { it + dir * headway * 30 })
                    out.add(FleetPlan.Trip("f:$name:$dir:$st", name, if (dir == 0) head else stops.first(), seq.toIntArray(),
                        IntArray(a.size) { a[it] + st }, IntArray(d.size) { d[it] + st }).also { sane(it) })
            }
        }
        line("1", "Lido", listOf("Piazzale Roma Santa Chiara", "Ferrovia", "Riva de Biasio", "San Marcuola", "San Stae", "Ca' D'oro", "Rialto Mercato", "Rialto",
            "San Silvestro", "Sant'Angelo", "San Tomà", "Ca' Rezzonico", "Accademia", "Santa Maria del Giglio", "Salute", "San Marco Vallaresso", "San Zaccaria", "Arsenale", "Giardini", "Sant'Elena", "Lido Santa Maria Elisabetta"), 12)
        line("2", "San Zaccaria", listOf("Tronchetto", "Piazzale Roma", "Ferrovia", "San Marcuola", "Rialto", "San Tomà", "San Samuele", "Accademia", "San Marco Giardinetti", "San Zaccaria"), 15)
        return out
    }

    private fun secsNow(): Float {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 3600 + c.get(Calendar.MINUTE) * 60 + c.get(Calendar.SECOND) + c.get(Calendar.MILLISECOND) / 1000f
    }

    // ================================================================ ACTV live reports -> how late each trip is
    private var matchedAt = -1L
    private val liveOf = HashMap<String, Vaporetti.Vessel>()

    /** the timetable time between t0 and t1 whose planned position best matches (x, y), preferring the one closest
     *  to `near`; NaN if nothing on the route comes within maxD */
    private fun bestTime(tr: FleetPlan.Trip, x: Float, y: Float, t0: Float, t1: Float, near: Float, maxD: Float): Float {
        val p = plan ?: return Float.NaN
        var best = Float.NaN; var bd = maxD; var t = t0
        while (t <= t1) {
            if (p.pose(tr, t, pose) >= 0) {
                val d = hypot(pose[0] - x, pose[1] - y)
                // ties (the dwell at a pontoon, where every second of the stop matches): the one nearest `near`
                if (d < bd - 6f || (d < bd + 6f && !best.isNaN() && abs(t - near) < abs(best - near))) { bd = min(bd, d); best = t }
            }
            t += 8f
        }
        return best
    }

    private fun schedule() {
        val p = plan ?: return
        if (berths.isEmpty()) return
        val now = secsNow()
        val live = Vaporetti.isLive() && Vaporetti.vessels.size >= 3
        val lat0 = GameState.lat0; val lon0 = GameState.lon0
        val trips = p.trips
        // ---- a new ACTV report: which trip is each vessel
        if (live && Vaporetti.updatedAt != matchedAt) {
            matchedAt = Vaporetti.updatedAt
            liveOf.clear()
            val unknown = ArrayList<Vaporetti.Vessel>()
            for (v in Vaporetti.vessels) {
                val r = tripById[v.tripId]
                if (r != null && now >= r.start - 1800 && now <= r.end + 3600) liveOf[r.id] = v else unknown.add(v)
            }
            // vessels with no trip in the report: the timetable knows which boat should be there at this hour
            for (v in unknown) {
                val q = Places.project(v.lat, v.lon, lat0, lon0)
                var best: FleetPlan.Trip? = null; var bd = 300f
                for (r in trips) {
                    if (liveOf.containsKey(r.id) || now < r.start - 600 || now > r.end + 1800 || !p.ready(r)) continue
                    if (p.pose(r, now, pose) >= 0 && hypot(pose[0] - q[0], pose[1] - q[1]) > 2500f) continue      // cheap reject first
                    val t = bestTime(r, q[0], q[1], now - 900f, now + 300f, now, bd)
                    if (!t.isNaN()) { p.pose(r, t, pose); val d = hypot(pose[0] - q[0], pose[1] - q[1]); if (d < bd) { bd = d; best = r } }
                }
                best?.let { liveOf[it.id] = v }
            }
        }
        // ---- the boats: trips under way now (when live, only the ones ACTV reports)
        val alive = HashSet<String>()
        for (r in trips) {
            val v = if (live) liveOf[r.id] else null
            val existing = boats[r.id]
            // a boat missing from one report keeps going on its timetable for a while (reports drop boats now and then)
            if (live && v == null && (existing == null || System.currentTimeMillis() - existing.seen > 90_000)) continue
            val tGuess = existing?.tEff ?: now
            if (tGuess < r.start - 60 || tGuess > r.end + 60) continue
            if (!p.ready(r)) continue
            alive.add(r.id)
            val b = existing ?: Boat(r.id).also { it.trip = r; it.kind = kindFor(r.line); it.line = r.line; it.head = r.head; it.seen = System.currentTimeMillis(); boats[r.id] = it }
            if (v != null) {
                b.matricola = v.id; b.seen = System.currentTimeMillis()
                if (v.time != b.fixTime) {
                    b.fixTime = v.time
                    val q = Places.project(v.lat, v.lon, lat0, lon0)
                    val fixAge = ((System.currentTimeMillis() - v.time) / 1000f).coerceIn(0f, 120f)
                    // never look back more than 2 minutes from where the boat already is: no U-turns on one odd fix
                    val from = if (b.tEff.isNaN()) now - 1800f else b.tEff - 120f
                    val t = bestTime(r, q[0], q[1], from, now + 600f, if (b.tEff.isNaN()) now else b.tEff, 220f)
                    if (!t.isNaN()) {
                        val d = now - (t + fixAge)
                        b.delay = if (!b.delayKnown) d else b.delay * 0.4f + d * 0.6f
                        b.delayKnown = true
                    }
                }
            }
            if (b.tEff.isNaN()) b.tEff = now - b.delay
        }
        boats.values.removeAll { it.id !in alive }
        publishMap(now, live)
    }

    // ================================================================ the boat on its route
    private fun drive(b: Boat, dt: Float) {
        val p = plan; val tr = b.trip
        if (p == null || tr == null || b.tEff.isNaN()) { b.visible = false; return }
        val st = p.pose(tr, b.tEff, pose)
        if (st < 0) { b.visible = false; return }
        val first = !b.visible
        b.visible = true
        b.stateT += dt
        val oldState = b.state
        b.x = pose[0]; b.y = pose[1]; b.speed = pose[3]
        // the hull yaws at its own rate towards the route's heading (no snapping round a corner of the route)
        val dh = wrap(pose[2] - b.h)
        b.h = if (first || st == FleetPlan.DOCKED && b.stateT > 20f) pose[2] else wrap(b.h + dh.coerceIn(-(0.35f + abs(dh) * 0.6f) * dt, (0.35f + abs(dh) * 0.6f) * dt))
        val stop = pose[6].toInt()
        b.berthIdx = tr.berth[stop.coerceIn(0, tr.berth.size - 1)]
        if (st == FleetPlan.DOCKED) {
            if (oldState != State.DOCKED) { b.state = State.DOCKED; b.stateT = 0f; if (!first) cues.add(floatArrayOf(CUE_DOCK, b.x, b.y)) }
            b.cmd = 0.12f
        } else {
            if (oldState == State.DOCKED) { b.state = State.DEPARTING; b.stateT = 0f; if (!first) cues.add(floatArrayOf(CUE_DEPART, b.x, b.y)) }
            if (b.state == State.DEPARTING && b.stateT > 8f) b.state = State.UNDERWAY
            // the throttle that would produce this motion: ahead to accelerate or hold speed, astern braking into the stop
            val acc = pose[4]; val vmax = b.kind.maxSpeed
            b.cmd = when {
                acc < -0.05f && pose[5] < 45f && b.speed > 0.5f -> (-0.3f + acc * 1.2f).coerceIn(-1f, -0.2f)
                acc < -0.05f -> (-0.15f + acc * 0.5f).coerceIn(-0.5f, -0.1f)
                else -> (b.speed / vmax + acc * 2.2f).coerceIn(0.1f, 1f)
            }
        }
        if (first) { b.rpm = abs(b.cmd); b.trailCount = 0; b.lastH = b.h }
        b.stopIdx = stop
    }

    /** two boats alongside the same pontoon: the later one lies just behind the first */
    private fun slots(dt: Float) {
        val k = 1f - exp(-dt * 0.5f)
        val first = HashMap<Int, Boat>()
        for (b in list) if (b.docked) { val o = first[b.berthIdx]; if (o == null || o.stateT < b.stateT) first[b.berthIdx] = b }
        for (b in list) {
            var tx = 0f; var ty = 0f
            if (b.docked && first[b.berthIdx] !== b) { val be = berths[b.berthIdx]; tx = -cos(b.h) * (b.kind.len + 2f); ty = -sin(b.h) * (b.kind.len + 2f); if (be.key.isEmpty()) { tx = 0f; ty = 0f } }
            else if (!b.docked) { tx = b.slotX * (1f - min(1f, b.stateT / 6f)); ty = b.slotY * (1f - min(1f, b.stateT / 6f)) }
            b.slotX += (tx - b.slotX) * k; b.slotY += (ty - b.slotY) * k
        }
    }

    /** diesel: rpm follows the throttle with lag (drives smoke, prop wash, wake and sound) */
    private fun engine(b: Boat, dt: Float) {
        val target = abs(b.cmd).coerceAtLeast(0.1f)
        val old = b.rpm
        b.rpm += (target - b.rpm).coerceIn(-0.45f * dt, 0.22f * dt)
        b.rpmRise = max(0f, (b.rpm - old) / max(dt, 1e-3f))
    }

    // ================================================================ the map: every trip, moving, even while nothing renders
    private class MapSnap(val plan: FleetPlan, val trips: List<FleetPlan.Trip>, val delay: Map<String, Float>, val shown: Set<String>, val live: Boolean)
    private fun publishMap(now: Float, live: Boolean) {
        val p = plan ?: return
        mapSnap = MapSnap(p, p.trips, boats.mapValues { it.value.delay }, boats.keys.toHashSet(), live)
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

    /** the app was away or a page was open: the fleet has kept its timetable time on the wall clock, so it only
     *  needs the trails and smoke of where it was cleared */
    private fun catchUp() {
        val now = secsNow()
        for (b in boats.values) if (!b.tEff.isNaN()) b.tEff = now - b.delay
        smokeN = 0
        for (b in boats.values) { b.trailCount = 0; b.lastH = Float.NaN }
        simClock = 0f
    }

    fun shutdown() { plan?.shutdown() }

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
        /** where the fleet plan's leg cache lives (the app's files dir) */
        @Volatile var cacheDir: java.io.File? = null
        private val FALLBACK = Any()
        @Volatile private var mapSnap: MapSnap? = null
        private val mapPose = FloatArray(7)
        /** Every vaporetto on its planned route right now, for the full-screen map. Computed from the plan and each
         *  trip's last known delay, so it keeps moving while the game itself is frozen behind the map. UI thread. */
        fun mapMarkers(): List<Marker> {
            val sn = mapSnap ?: return markers
            val c = Calendar.getInstance()
            val now = c.get(Calendar.HOUR_OF_DAY) * 3600 + c.get(Calendar.MINUTE) * 60 + c.get(Calendar.SECOND) + c.get(Calendar.MILLISECOND) / 1000f
            val out = ArrayList<Marker>()
            for (t in sn.trips) {
                if (now < t.start - 300 || now > t.end + 300) continue
                val shown = t.id in sn.shown
                if (sn.live && !shown) continue
                val st = sn.plan.pose(t, now - (sn.delay[t.id] ?: 0f), mapPose)
                if (st < 0) continue
                out.add(Marker(mapPose[0], mapPose[1], mapPose[2], t.line, t.head))
            }
            return out
        }
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
