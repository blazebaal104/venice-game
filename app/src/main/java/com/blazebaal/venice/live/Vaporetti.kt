// Real ACTV waterbuses from ACTV's open data (Comune di Venezia open data rules; attribution: ACTV S.p.A.):
//  - GTFS-Realtime vehicle positions  https://tpl.actv.it/nav/GTFSRT/vehiclePositions   (polled every 15 s)
//  - GTFS static timetable            https://actv.avmspa.it/.../opendata/navigazione/actv_nav.zip  (cached a week)
// From the timetable we keep line names, destinations, and for every line + direction its stop sequence with
// travel times and the day's departure times: that drives the offline simulation (render/LiveBoats.kt) when the
// live feed is not reachable. The protobuf is decoded by hand (only the fields we need).
package com.blazebaal.venice.live

import android.content.Context
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object Vaporetti {
    const val POSITIONS_URL = "https://tpl.actv.it/nav/GTFSRT/vehiclePositions"
    const val GTFS_URL = "https://actv.avmspa.it/sites/default/files/attachments/opendata/navigazione/actv_nav.zip"

    /** status: 0 incoming at, 1 stopped at, 2 in transit to (GTFS-RT VehicleStopStatus), -1 unknown */
    class Vessel(
        val id: String, val line: String, val headsign: String,
        val lat: Double, val lon: Double, val bearing: Float, val speed: Float, val time: Long,
        val status: Int, val stopName: String,
        /** the stops of its trip in order (its whole route today, GTFS coordinates), empty if the trip is unknown */
        val tripStops: List<StopRef> = emptyList(),
        /** GTFS coordinates of the stop in `stopName` (each pontoon - Rialto A, B, C... - is its own stop), NaN if none */
        val stopLat: Double = Double.NaN, val stopLon: Double = Double.NaN,
        val tripId: String = "",
    )
    /** one scheduled trip running today: stops with absolute times (seconds since today's midnight) */
    class Sched(val id: String, val line: String, val headsign: String, val stops: List<StopRef>, val arr: IntArray, val dep: IntArray)
    /** today's trips (plus last night's past midnight), from the timetable; refreshed daily */
    @Volatile var today: List<Sched> = emptyList(); private set
    @Volatile var tripById: Map<String, Sched> = emptyMap(); private set
    class StopRef(val name: String, val lat: Double, val lon: Double, val arr: Int, val dep: Int)
    /** one line in one direction: its stops with times relative to the first departure, and the day's departures */
    class Route(val line: String, val headsign: String, val stops: List<StopRef>, val starts: IntArray)

    @Volatile var vessels: List<Vessel> = emptyList(); private set
    @Volatile var updatedAt = 0L; private set
    @Volatile var status = "In attesa di connessione"; private set
    @Volatile var enabled = true
    /** timetable patterns (empty until the GTFS zip has been downloaded once) */
    @Volatile var routes: List<Route> = emptyList(); private set

    private var thread: Thread? = null
    private val routeName = HashMap<String, String>()
    private val tripRoute = HashMap<String, String>()
    private val tripHead = HashMap<String, String>()
    private val tripDir = HashMap<String, String>()
    private val stopInfo = HashMap<String, StopRef>()
    /** trip id -> its stop names in order (shared lists: one per distinct stop pattern) */
    private val tripPattern = java.util.concurrent.ConcurrentHashMap<String, List<StopRef>>()

    fun start(ctx: Context) {
        if (thread != null) return
        val dir = ctx.filesDir
        thread = Thread({
            try { loadStatic(dir) } catch (e: Throwable) { status = "Orari ACTV non disponibili" }
            var day = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
            while (!Thread.currentThread().isInterrupted) {
                // a new day: that day's trips (and a fresh timetable if the cached one is a day old)
                val d = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
                if (d != day) { day = d; try { loadStatic(dir) } catch (_: Throwable) { } }
                if (enabled) try { poll() } catch (e: Exception) { status = "Posizioni ACTV non raggiungibili · simulazione orari" }
                try { Thread.sleep(15_000) } catch (_: InterruptedException) { break }
            }
        }, "vaporetti").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }
    fun stop() { thread?.interrupt(); thread = null }
    fun isLive() = enabled && vessels.isNotEmpty() && System.currentTimeMillis() - updatedAt < 120_000

    // ---------------------------------------------------------------- static GTFS
    private fun loadStatic(dir: File) {
        val f = File(dir, "actv_nav.zip")
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 20L * 3600 * 1000) {      // fresh timetable every day
            try {
                val c = open(GTFS_URL)
                try { if (c.responseCode == 200) { val tmp = File(dir, "actv_nav.zip.tmp"); c.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it) } }; tmp.renameTo(f) } }
                finally { c.disconnect() }
            } catch (_: Exception) { }
        }
        if (!f.exists()) return
        // stop_times grouped by trip: stop ids + arrival/departure seconds
        val tripStops = HashMap<String, ArrayList<String>>(); val tripArr = HashMap<String, ArrayList<Int>>(); val tripDep = HashMap<String, ArrayList<Int>>()
        val tripService = HashMap<String, String>()
        // service calendars: which service ids run on a given date (yyyymmdd)
        val weekly = HashMap<String, Triple<BooleanArray, Int, Int>>(); val except = HashMap<Int, HashMap<String, Int>>()
        ZipInputStream(f.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                when (e.name.substringAfterLast('/')) {
                    "routes.txt" -> csv(z) { h, r ->
                        val id = r.getOrNull(h["route_id"] ?: -1) ?: return@csv
                        routeName[id] = (r.getOrNull(h["route_short_name"] ?: -1)).orEmpty().ifEmpty { r.getOrNull(h["route_long_name"] ?: -1).orEmpty() }
                    }
                    "trips.txt" -> csv(z) { h, r ->
                        val id = r.getOrNull(h["trip_id"] ?: -1) ?: return@csv
                        r.getOrNull(h["route_id"] ?: -1)?.let { tripRoute[id] = it }
                        r.getOrNull(h["trip_headsign"] ?: -1)?.takeIf { it.isNotEmpty() }?.let { tripHead[id] = it }
                        tripDir[id] = r.getOrNull(h["direction_id"] ?: -1).orEmpty()
                        r.getOrNull(h["service_id"] ?: -1)?.let { tripService[id] = it }
                    }
                    "calendar.txt" -> csv(z) { h, r ->
                        val id = r.getOrNull(h["service_id"] ?: -1) ?: return@csv
                        val days = BooleanArray(7) { d -> r.getOrNull(h[arrayOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")[d]] ?: -1) == "1" }
                        weekly[id] = Triple(days, r.getOrNull(h["start_date"] ?: -1)?.toIntOrNull() ?: 0, r.getOrNull(h["end_date"] ?: -1)?.toIntOrNull() ?: 99999999)
                    }
                    "calendar_dates.txt" -> csv(z) { h, r ->
                        val id = r.getOrNull(h["service_id"] ?: -1) ?: return@csv
                        val date = r.getOrNull(h["date"] ?: -1)?.toIntOrNull() ?: return@csv
                        except.getOrPut(date) { HashMap() }[id] = r.getOrNull(h["exception_type"] ?: -1)?.toIntOrNull() ?: 0
                    }
                    "stops.txt" -> csv(z) { h, r ->
                        val id = r.getOrNull(h["stop_id"] ?: -1) ?: return@csv
                        val lat = r.getOrNull(h["stop_lat"] ?: -1)?.toDoubleOrNull() ?: return@csv
                        val lon = r.getOrNull(h["stop_lon"] ?: -1)?.toDoubleOrNull() ?: return@csv
                        stopInfo[id] = StopRef(r.getOrNull(h["stop_name"] ?: -1).orEmpty(), lat, lon, 0, 0)
                    }
                    "stop_times.txt" -> csv(z) { h, r ->
                        val t = r.getOrNull(h["trip_id"] ?: -1) ?: return@csv
                        val s = r.getOrNull(h["stop_id"] ?: -1) ?: return@csv
                        val a = secs(r.getOrNull(h["arrival_time"] ?: -1)); val d = secs(r.getOrNull(h["departure_time"] ?: -1))
                        tripStops.getOrPut(t) { ArrayList() }.add(s); tripArr.getOrPut(t) { ArrayList() }.add(if (a >= 0) a else d)
                        tripDep.getOrPut(t) { ArrayList() }.add(if (d >= 0) d else a)
                    }
                }
            }
        }
        val patterns = HashMap<List<String>, List<StopRef>>()
        for ((t, ids) in tripStops) tripPattern[t] = patterns.getOrPut(ids) { ids.mapNotNull { stopInfo[it] } }
        // one stop pattern per line + direction (the trip with the most stops), plus every departure of that pattern
        val groups = HashMap<String, ArrayList<String>>()
        for (t in tripStops.keys) { val r = tripRoute[t] ?: continue; groups.getOrPut(r + "|" + tripDir[t].orEmpty()) { ArrayList() }.add(t) }
        val out = ArrayList<Route>()
        for ((_, trips) in groups) {
            val rep = trips.maxByOrNull { tripStops[it]!!.size } ?: continue
            val ids = tripStops[rep]!!; if (ids.size < 2) continue
            val a0 = tripDep[rep]!![0]
            val stops = ids.indices.mapNotNull { i -> stopInfo[ids[i]]?.let { s -> StopRef(s.name, s.lat, s.lon, tripArr[rep]!![i] - a0, tripDep[rep]!![i] - a0) } }
            if (stops.size < 2) continue
            val starts = trips.filter { tripStops[it]!!.size >= ids.size * 0.6 && tripStops[it]!![0] == ids[0] }
                .map { tripDep[it]!![0] }.filter { it >= 0 }.distinct().sorted()
            val line = routeName[tripRoute[rep]] ?: continue
            out.add(Route(line, tripHead[rep].orEmpty(), stops, starts.toIntArray()))
        }
        routes = out
        // ---- the trips running today (and last night's still running after midnight), absolute times
        fun ymd(c: java.util.Calendar) = c.get(java.util.Calendar.YEAR) * 10000 + (c.get(java.util.Calendar.MONTH) + 1) * 100 + c.get(java.util.Calendar.DAY_OF_MONTH)
        fun runs(service: String, c: java.util.Calendar): Boolean {
            val d = ymd(c)
            except[d]?.get(service)?.let { return it == 1 }
            val w = weekly[service] ?: return weekly.isEmpty()                 // no calendar at all: assume every trip runs
            val dow = (c.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7           // Monday = 0
            return w.first[dow] && d in w.second..w.third
        }
        val now = java.util.Calendar.getInstance(); val yday = (now.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
        val list = ArrayList<Sched>()
        for ((t, ids) in tripStops) {
            if (ids.size < 2) continue
            val svc = tripService[t].orEmpty()
            val line = routeName[tripRoute[t]] ?: continue
            val stops = ids.mapNotNull { stopInfo[it] }; if (stops.size != ids.size) continue
            val a = tripArr[t]!!.toIntArray(); val d = tripDep[t]!!.toIntArray()
            val head = tripHead[t] ?: stops.last().name
            if (runs(svc, now)) list.add(Sched(t, line, head, stops, a, d))
            if (d.last() >= 86400 && runs(svc, yday)) list.add(Sched(t, line, head, stops, IntArray(a.size) { a[it] - 86400 }, IntArray(d.size) { d[it] - 86400 }))
        }
        today = list; tripById = list.associateBy { it.id }
    }

    private fun secs(s: String?): Int {
        if (s.isNullOrBlank()) return -1
        val p = s.trim().split(':'); if (p.size < 2) return -1
        return (p[0].toIntOrNull() ?: return -1) * 3600 + (p[1].toIntOrNull() ?: 0) * 60 + (p.getOrNull(2)?.toIntOrNull() ?: 0)
    }

    /** Minimal CSV reader (quoted fields, no embedded newlines); does not close the stream. */
    private fun csv(input: InputStream, row: (Map<String, Int>, List<String>) -> Unit) {
        val rd = input.bufferedReader()
        val header = rd.readLine()?.removePrefix("﻿")?.let { split(it) } ?: return
        val h = header.withIndex().associate { it.value.trim() to it.index }
        while (true) { val l = rd.readLine() ?: break; if (l.isNotBlank()) row(h, split(l)) }
    }
    private fun split(l: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false; var i = 0
        while (i < l.length) {
            val ch = l[i]
            when {
                ch == '"' && q && i + 1 < l.length && l[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> q = !q
                ch == ',' && !q -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(ch)
            }
            i++
        }
        out.add(sb.toString()); return out
    }

    // ---------------------------------------------------------------- GTFS-Realtime vehicle positions
    private fun poll() {
        val c = open(POSITIONS_URL)
        try {
            if (c.responseCode != 200) { status = "ACTV: errore ${c.responseCode} · simulazione orari"; return }
            val bytes = c.inputStream.use { it.readBytes() }
            val list = ArrayList<Vessel>()
            val feed = Pb(bytes)
            while (feed.more()) {
                val (f, w) = feed.tag()
                if (f == 2 && w == 2) feed.sub()?.let { ent -> entity(ent)?.let { list.add(it) } } else feed.skip(w)
            }
            vessels = list; updatedAt = System.currentTimeMillis()
            status = if (list.isEmpty()) "Nessun battello in servizio · simulazione orari" else "${list.size} battelli in servizio (dal vivo)"
        } finally { c.disconnect() }
    }

    private fun entity(e: Pb): Vessel? {
        var id = ""; var veh: Pb? = null
        while (e.more()) {
            val (f, w) = e.tag()
            when {
                f == 1 && w == 2 -> id = e.string()
                f == 4 && w == 2 -> veh = e.sub()
                else -> e.skip(w)
            }
        }
        val v = veh ?: return null
        var tripId = ""; var routeId = ""; var lat = Double.NaN; var lon = Double.NaN; var bearing = Float.NaN; var speed = -1f; var time = 0L; var label = ""
        var st = -1; var stopId = ""
        while (v.more()) {
            val (f, w) = v.tag()
            when {
                f == 1 && w == 2 -> v.sub()?.let { t -> while (t.more()) { val (g, x) = t.tag(); when { g == 1 && x == 2 -> tripId = t.string(); g == 5 && x == 2 -> routeId = t.string(); else -> t.skip(x) } } }
                f == 2 && w == 2 -> v.sub()?.let { p ->
                    while (p.more()) { val (g, x) = p.tag(); when { g == 1 && x == 5 -> lat = p.float().toDouble(); g == 2 && x == 5 -> lon = p.float().toDouble(); g == 3 && x == 5 -> bearing = p.float(); g == 5 && x == 5 -> speed = p.float(); else -> p.skip(x) } }
                }
                f == 4 && w == 0 -> st = v.varint().toInt()
                f == 5 && w == 0 -> time = v.varint()
                f == 7 && w == 2 -> stopId = v.string()
                f == 8 && w == 2 -> v.sub()?.let { d -> while (d.more()) { val (g, x) = d.tag(); when { (g == 1 || g == 2) && x == 2 -> { val s = d.string(); if (g == 2 || label.isEmpty()) label = s }; else -> d.skip(x) } } }
                else -> v.skip(w)
            }
        }
        if (lat.isNaN() || lon.isNaN() || lat < 45.0 || lat > 46.0) return null
        val rid = routeId.ifEmpty { tripRoute[tripId] ?: "" }
        val line = routeName[rid] ?: rid.ifEmpty { "?" }
        return Vessel(label.ifEmpty { id }, line, tripHead[tripId] ?: "", lat, lon, bearing, speed,
            if (time > 0) time * 1000 else System.currentTimeMillis(), st, stopInfo[stopId]?.name ?: "", tripPattern[tripId] ?: emptyList(),
            stopInfo[stopId]?.lat ?: Double.NaN, stopInfo[stopId]?.lon ?: Double.NaN, tripId)
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000; readTimeout = 20_000
        setRequestProperty("User-Agent", "GTACTV/1.0 (Android game; ACTV open data)")
    }

    /** Just enough protobuf: tags, varints, fixed32/64, length-delimited sub-messages. */
    private class Pb(val b: ByteArray, var p: Int = 0, val end: Int = b.size) {
        fun more() = p < end
        fun varint(): Long { var r = 0L; var s = 0; while (p < end) { val x = b[p++].toInt() and 0xff; r = r or ((x and 0x7f).toLong() shl s); if (x < 0x80) break; s += 7 }; return r }
        fun tag(): Pair<Int, Int> { val t = varint(); return Pair((t ushr 3).toInt(), (t and 7).toInt()) }
        fun len(): Int { val n = varint().toInt(); return if (n < 0 || p + n > end) end - p else n }
        fun sub(): Pb? { val n = len(); val s = Pb(b, p, p + n); p += n; return s }
        fun string(): String { val n = len(); val s = String(b, p, n, Charsets.UTF_8); p += n; return s }
        fun float(): Float { if (p + 4 > end) { p = end; return 0f }; val v = (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8) or ((b[p + 2].toInt() and 0xff) shl 16) or ((b[p + 3].toInt() and 0xff) shl 24); p += 4; return java.lang.Float.intBitsToFloat(v) }
        fun skip(w: Int) { when (w) { 0 -> varint(); 1 -> p += 8; 2 -> { val n = len(); p += n }; 5 -> p += 4; else -> p = end } }
    }
}
