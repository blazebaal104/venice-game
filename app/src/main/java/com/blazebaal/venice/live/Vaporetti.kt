// Real ACTV waterbuses from ACTV's open data (Comune di Venezia open data rules; attribution: ACTV S.p.A.):
//  - GTFS-Realtime vehicle positions  https://tpl.actv.it/nav/GTFSRT/vehiclePositions   (polled every 15 s)
//  - GTFS static timetable            https://actv.avmspa.it/.../opendata/navigazione/actv_nav.zip
//    (only routes.txt + trips.txt are read, to turn a trip into its line number and destination; cached a week)
// The protobuf is decoded by hand (only the fields we need), so no extra library is required.
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

    class Vessel(
        val id: String, val line: String, val headsign: String, val routeColor: Int,
        val lat: Double, val lon: Double, val bearing: Float, val speed: Float, val time: Long,
    )

    @Volatile var vessels: List<Vessel> = emptyList(); private set
    @Volatile var updatedAt = 0L; private set
    @Volatile var status = "In attesa di connessione"; private set
    @Volatile var enabled = true

    private var thread: Thread? = null
    private var routeName = HashMap<String, String>()      // route_id -> short name ("1", "4.1", "N")
    private var routeColor = HashMap<String, Int>()
    private var tripRoute = HashMap<String, String>()      // trip_id -> route_id
    private var tripHead = HashMap<String, String>()       // trip_id -> headsign

    fun start(ctx: Context) {
        if (thread != null) return
        val dir = ctx.filesDir
        thread = Thread({
            try { loadStatic(dir) } catch (e: Exception) { status = "Orari ACTV non disponibili" }
            while (!Thread.currentThread().isInterrupted) {
                if (enabled) try { poll() } catch (e: Exception) { status = "Posizioni ACTV non raggiungibili" }
                try { Thread.sleep(15_000) } catch (_: InterruptedException) { break }
            }
        }, "vaporetti").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }
    fun stop() { thread?.interrupt(); thread = null }

    // ---------------------------------------------------------------- static GTFS (line names)
    private fun loadStatic(dir: File) {
        val f = File(dir, "actv_nav.zip")
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 7L * 24 * 3600 * 1000) {
            try {
                val c = open(GTFS_URL)
                try { if (c.responseCode == 200) { val tmp = File(dir, "actv_nav.zip.tmp"); c.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it) } }; tmp.renameTo(f) } }
                finally { c.disconnect() }
            } catch (_: Exception) { }
        }
        if (!f.exists()) return
        ZipInputStream(f.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                when (e.name.substringAfterLast('/')) {
                    "routes.txt" -> csv(z) { h, r ->
                        val id = r.getOrNull(h["route_id"] ?: -1) ?: return@csv
                        routeName[id] = (r.getOrNull(h["route_short_name"] ?: -1)).orEmpty().ifEmpty { r.getOrNull(h["route_long_name"] ?: -1).orEmpty() }
                        r.getOrNull(h["route_color"] ?: -1)?.takeIf { it.length == 6 }?.let { col -> routeColor[id] = (0xff000000 or col.toLong(16)).toInt() }
                    }
                    "trips.txt" -> csv(z) { h, r ->
                        val id = r.getOrNull(h["trip_id"] ?: -1) ?: return@csv
                        r.getOrNull(h["route_id"] ?: -1)?.let { tripRoute[id] = it }
                        r.getOrNull(h["trip_headsign"] ?: -1)?.takeIf { it.isNotEmpty() }?.let { tripHead[id] = it }
                    }
                }
            }
        }
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
            if (c.responseCode != 200) { status = "ACTV: errore ${c.responseCode}"; return }
            val bytes = c.inputStream.use { it.readBytes() }
            val list = ArrayList<Vessel>()
            val feed = Pb(bytes)
            while (feed.more()) {
                val (f, w) = feed.tag()
                if (f == 2 && w == 2) feed.sub()?.let { ent -> entity(ent)?.let { list.add(it) } } else feed.skip(w)
            }
            vessels = list; updatedAt = System.currentTimeMillis()
            status = "${list.size} battelli in servizio"
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
        var tripId = ""; var routeId = ""; var lat = Double.NaN; var lon = Double.NaN; var bearing = Float.NaN; var speed = 0f; var time = 0L; var label = ""
        while (v.more()) {
            val (f, w) = v.tag()
            when {
                f == 1 && w == 2 -> v.sub()?.let { t -> while (t.more()) { val (g, x) = t.tag(); when { g == 1 && x == 2 -> tripId = t.string(); g == 5 && x == 2 -> routeId = t.string(); else -> t.skip(x) } } }
                f == 2 && w == 2 -> v.sub()?.let { p ->
                    while (p.more()) { val (g, x) = p.tag(); when { g == 1 && x == 5 -> lat = p.float().toDouble(); g == 2 && x == 5 -> lon = p.float().toDouble(); g == 3 && x == 5 -> bearing = p.float(); g == 5 && x == 5 -> speed = p.float(); else -> p.skip(x) } }
                }
                f == 5 && w == 0 -> time = v.varint()
                f == 8 && w == 2 -> v.sub()?.let { d -> while (d.more()) { val (g, x) = d.tag(); when { (g == 1 || g == 2) && x == 2 -> { val s = d.string(); if (g == 2 || label.isEmpty()) label = s }; else -> d.skip(x) } } }
                else -> v.skip(w)
            }
        }
        if (lat.isNaN() || lon.isNaN() || lat < 45.0 || lat > 46.0) return null
        val rid = routeId.ifEmpty { tripRoute[tripId] ?: "" }
        val line = routeName[rid] ?: rid.ifEmpty { "?" }
        return Vessel(label.ifEmpty { id }, line, tripHead[tripId] ?: "", routeColor[rid] ?: 0, lat, lon, bearing, speed, if (time > 0) time * 1000 else System.currentTimeMillis())
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
        fun float(): Float { val v = (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8) or ((b[p + 2].toInt() and 0xff) shl 16) or ((b[p + 3].toInt() and 0xff) shl 24); p += 4; return java.lang.Float.intBitsToFloat(v) }
        fun skip(w: Int) { when (w) { 0 -> varint(); 1 -> p += 8; 2 -> { val n = len(); p += n }; 5 -> p += 4; else -> p = end } }
    }
}
