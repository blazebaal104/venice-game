// The vaporetto timetable turned into fixed paths. Every trip of the day (ACTV's official GTFS timetable) gets its
// whole route planned once, pontoon to pontoon through the canals (core/Navigator, legs shared between trips and
// cached on disk), with the timetable's arrival and departure time at every stop. Where a boat is at timetable time
// t is then a pure function: docked at a pontoon, or a known distance along a known leg with a smooth speed
// profile (pulls away, cruises, brakes into the stop). Nothing is steered or re-planned at run time, so no boat can
// wander off: ACTV's live positions only say how early or late each trip is running (render/LiveBoats).
package com.blazebaal.venice.render

import com.blazebaal.venice.core.Navigator
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

class FleetPlan(private val nav: Navigator, private val berths: List<LiveBoats.Berth>, private val cacheDir: File?) {
    /** one trip of the day: the pontoons it calls at (indices into berths) and absolute times (s since midnight) */
    class Trip(val id: String, val line: String, val head: String, val berth: IntArray, val arr: IntArray, val dep: IntArray) {
        val legKeys = LongArray(berth.size - 1) { key(berth[it], berth[it + 1]) }
        val start get() = dep[0]
        val end get() = arr[arr.size - 1]
    }
    /** a planned leg: points every STEP m from pontoon a to pontoon b, heading when leaving / when coming alongside */
    class Leg(val pts: FloatArray, val len: Float, val startH: Float, val endH: Float)

    @Volatile var trips: List<Trip> = emptyList(); private set
    private val legs = ConcurrentHashMap<Long, Leg>()
    @Volatile var pending = 0; private set
    private var worker: Thread? = null
    private val sig: Long = berths.fold(LEG_VERSION.toLong()) { h, b -> h * 31 + (b.x * 10).roundToLong() * 7 + (b.y * 10).roundToLong() }

    init { loadCache() }

    fun ready(t: Trip): Boolean { for (k in t.legKeys) if (!legs.containsKey(k)) return false; return true }

    /** new timetable: the trips, then every leg not planned yet, the ones needed soonest first */
    fun setTrips(list: List<Trip>, nowSecs: Float) {
        trips = list
        val need = HashMap<Long, Float>()
        for (t in list) {
            if (t.end < nowSecs - 60) continue
            val urgency = max(0f, t.start - nowSecs)
            for (k in t.legKeys) if (!legs.containsKey(k)) need[k] = min(need[k] ?: Float.MAX_VALUE, urgency)
        }
        val order = need.entries.sortedBy { it.value }.map { it.key }
        pending = order.size
        worker?.interrupt()
        if (order.isEmpty()) return
        worker = Thread({
            var since = 0
            for (k in order) {
                if (Thread.currentThread().isInterrupted) return@Thread
                if (!legs.containsKey(k)) try { legs[k] = build((k ushr 32).toInt(), (k and 0xffffffffL).toInt()) } catch (_: Throwable) { }
                pending--
                if (++since >= 40) { since = 0; saveCache() }
            }
            saveCache()
        }, "fleet-plan").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    fun shutdown() { worker?.interrupt() }

    // ---------------------------------------------------------------- where a trip is at timetable time t
    /** out: x, y, heading, speed (m/s), acceleration (m/s2), metres to the next stop, stop index.
     *  Returns DOCKED, UNDERWAY, or -1 when t is outside the trip or its route isn't planned yet. */
    fun pose(t: Trip, time: Float, out: FloatArray): Int {
        val n = t.berth.size
        if (time < t.dep[0] - 90 || time > t.arr[n - 1] + 90) return -1
        var i = 0
        while (i < n - 1 && time >= t.arr[i + 1]) i++
        if (time <= t.dep[i] || i == n - 1) {
            val b = berths[t.berth[i]]
            out[0] = b.x; out[1] = b.y; out[3] = 0f; out[4] = 0f; out[5] = 0f; out[6] = i.toFloat()
            // alongside, pointing the way it came in (or the way it will leave, at the first stop)
            out[2] = when {
                i > 0 -> legs[t.legKeys[i - 1]]?.endH ?: return -1
                else -> legs[t.legKeys[0]]?.startH ?: return -1
            }
            return DOCKED
        }
        val leg = legs[t.legKeys[i]] ?: return -1
        val dur = max(1, t.arr[i + 1] - t.dep[i]).toFloat()
        val f = ((time - t.dep[i]) / dur).coerceIn(0f, 1f)
        // pulls away, cruises, brakes in: a trapezoid speed profile (a = share of the leg spent accelerating)
        val a = (45f / max(leg.len, 1f)).coerceIn(0.12f, 0.4f)
        val vp = 1f / (1f - a)
        val e: Float; val de: Float; val dde: Float
        when {
            f < a -> { e = vp * f * f / (2f * a); de = vp * f / a; dde = vp / a }
            f > 1f - a -> { val g = 1f - f; e = 1f - vp * g * g / (2f * a); de = vp * g / a; dde = -vp / a }
            else -> { e = vp * (f - a / 2f); de = vp; dde = 0f }
        }
        val s = (e.coerceIn(0f, 1f)) * leg.len
        at(leg, s, out)
        out[3] = leg.len * de / dur; out[4] = leg.len * dde / (dur * dur); out[5] = leg.len - s; out[6] = (i + 1).toFloat()
        // heading: the path's direction, eased out of the berth heading and into the next berth heading
        val px = out[0]; val py = out[1]
        at(leg, s + 7f, out); val ax = out[0]; val ay = out[1]
        at(leg, s - 5f, out)
        var h = atan2(ay - out[1], ax - out[0])
        val dockIn = if (i > 0) legs[t.legKeys[i - 1]]?.endH ?: leg.startH else leg.startH
        val wOut = smooth01(s / 22f); h = dockIn + LiveBoats.wrap(h - dockIn) * wOut
        val wIn = 1f - smooth01((leg.len - s) / 10f); h += LiveBoats.wrap(leg.endH - h) * wIn
        out[0] = px; out[1] = py; out[2] = h
        return UNDERWAY
    }

    private fun smooth01(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3f - 2f * c) }

    private fun at(leg: Leg, s: Float, out: FloatArray) {
        val p = leg.pts; val n = p.size / 2
        val f = (s / STEP).coerceIn(0f, (n - 1).toFloat()); val i = min(f.toInt(), n - 2); val k = f - i
        out[0] = p[i * 2] + (p[i * 2 + 2] - p[i * 2]) * k; out[1] = p[i * 2 + 1] + (p[i * 2 + 3] - p[i * 2 + 1]) * k
    }

    /** the planned length of the leg between two pontoons (straight distance x1.2 while not planned yet) */
    fun legLength(a: Int, b: Int): Float = legs[key(a, b)]?.len ?: (hypot(berths[a].x - berths[b].x, berths[a].y - berths[b].y) * 1.2f)

    // ---------------------------------------------------------------- planning one leg
    private fun build(a: Int, b: Int): Leg {
        val A = berths[a]; val B = berths[b]
        val aax = cos(A.axis); val aay = sin(A.axis)
        val sa = if ((B.x - A.x) * aax + (B.y - A.y) * aay >= 0f) 1f else -1f
        // out from the pontoon: its water side, then forward along it the way the route goes
        var ex = A.x + A.nx * 9f + aax * sa * 16f; var ey = A.y + A.ny * 9f + aay * sa * 16f
        if (!nav.clearLine(A.x, A.y, ex, ey, 2f)) { ex = A.x + A.nx * 7f + aax * sa * 8f; ey = A.y + A.ny * 7f + aay * sa * 8f }
        val hasEx = nav.clearLine(A.x, A.y, ex, ey, 1.5f)
        val fx = if (hasEx) ex else A.x; val fy = if (hasEx) ey else A.y
        // into the next pontoon: the last stretch parallel to it, so it comes alongside instead of nosing in
        val bax = cos(B.axis); val bay = sin(B.axis)
        val sb = if ((B.x - fx) * bax + (B.y - fy) * bay >= 0f) 1f else -1f
        val ix = B.x - bax * sb * 24f + B.nx * 4f; val iy = B.y - bay * sb * 24f + B.ny * 4f
        val hasIn = nav.clearLine(ix, iy, B.x, B.y, 1.5f)
        val tx = if (hasIn) ix else B.x; val ty = if (hasIn) iy else B.y
        val pts = ArrayList<Float>()
        pts.add(A.x); pts.add(A.y)
        if (hasEx) { pts.add(ex); pts.add(ey) }
        val mid = nav.path(fx, fy, tx, ty)
        for (i in 2 until mid.size) pts.add(mid[i])
        if (hasIn) { pts.add(B.x); pts.add(B.y) } else if (pts[pts.size - 2] != B.x || pts[pts.size - 1] != B.y) { pts.add(B.x); pts.add(B.y) }
        val r = resample(chaikin(pts.toFloatArray()))
        return Leg(r, (r.size / 2 - 1) * STEP, atan2(aay * sa, aax * sa), atan2(bay * sb, bax * sb))
    }

    /** corner cutting (3 passes, ends kept): a curve a hull can follow. The route hugs the inside of bends, so a
     *  cut that would clip a quay corner is refused and that corner stays as it was. */
    private fun chaikin(p0: FloatArray): FloatArray {
        var p = p0
        repeat(3) {
            val n = p.size / 2; if (n < 3) return@repeat
            val o = ArrayList<Float>(n * 4)
            o.add(p[0]); o.add(p[1])
            for (i in 1 until n - 1) {
                val px = p[i * 2 - 2]; val py = p[i * 2 - 1]; val cx = p[i * 2]; val cy = p[i * 2 + 1]; val nx = p[i * 2 + 2]; val ny = p[i * 2 + 3]
                val qx = cx * 0.75f + px * 0.25f; val qy = cy * 0.75f + py * 0.25f
                val rx = cx * 0.75f + nx * 0.25f; val ry = cy * 0.75f + ny * 0.25f
                if (nav.clearLine(qx, qy, rx, ry, 1.0f)) { o.add(qx); o.add(qy); o.add(rx); o.add(ry) } else { o.add(cx); o.add(cy) }
            }
            o.add(p[p.size - 2]); o.add(p[p.size - 1])
            p = o.toFloatArray()
        }
        return p
    }

    private fun resample(p: FloatArray): FloatArray {
        val out = ArrayList<Float>(); out.add(p[0]); out.add(p[1])
        var carry = 0f
        for (i in 0 until p.size / 2 - 1) {
            val ax = p[i * 2]; val ay = p[i * 2 + 1]; val bx = p[i * 2 + 2]; val by = p[i * 2 + 3]
            val l = hypot(bx - ax, by - ay); if (l < 1e-4f) continue
            var d = STEP - carry
            while (d <= l) { out.add(ax + (bx - ax) * d / l); out.add(ay + (by - ay) * d / l); d += STEP }
            carry = l - (d - STEP)
        }
        out.add(p[p.size - 2]); out.add(p[p.size - 1])
        if (out.size < 4) { out.add(p[p.size - 2]); out.add(p[p.size - 1]) }
        return out.toFloatArray()
    }

    // ---------------------------------------------------------------- disk cache (legs survive restarts and new timetables)
    private fun file() = cacheDir?.let { File(it, "fleet_legs.bin") }
    private fun loadCache() {
        val f = file() ?: return
        if (!f.exists()) return
        try {
            DataInputStream(f.inputStream().buffered()).use { d ->
                if (d.readLong() != sig) return
                val n = d.readInt()
                repeat(n) {
                    val k = d.readLong(); val sh = d.readFloat(); val eh = d.readFloat(); val m = d.readInt()
                    val p = FloatArray(m) { d.readFloat() }
                    legs[k] = Leg(p, (m / 2 - 1) * STEP, sh, eh)
                }
            }
        } catch (_: Throwable) { legs.clear() }
    }
    @Synchronized private fun saveCache() {
        val f = file() ?: return
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { d ->
                val snap = HashMap(legs)
                d.writeLong(sig); d.writeInt(snap.size)
                for ((k, l) in snap) { d.writeLong(k); d.writeFloat(l.startH); d.writeFloat(l.endH); d.writeInt(l.pts.size); for (v in l.pts) d.writeFloat(v) }
            }
            tmp.renameTo(f)
        } catch (_: Throwable) { }
    }

    companion object {
        const val DOCKED = 0; const val UNDERWAY = 1
        const val STEP = 2f
        const val LEG_VERSION = 4
        fun key(a: Int, b: Int) = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)
    }
}
