package baker

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

// Diagnostic run during the build: fetches ACTV's live GTFS-Realtime vehicle positions exactly like the game does
// (same URL, same User-Agent, same decoding), names lines/stops from the static GTFS and prints what came back.
// Never fails the build.

private class Pb(val b: ByteArray, var p: Int = 0, val end: Int = b.size) {
    fun more() = p < end
    fun varint(): Long { var r = 0L; var s = 0; while (p < end) { val x = b[p++].toInt() and 0xff; r = r or ((x and 0x7f).toLong() shl s); if (x < 0x80) break; s += 7 }; return r }
    fun tag(): Pair<Int, Int> { val t = varint(); return Pair((t ushr 3).toInt(), (t and 7).toInt()) }
    fun len(): Int { val n = varint().toInt(); return if (n < 0 || p + n > end) end - p else n }
    fun sub(): Pb { val n = len(); val s = Pb(b, p, p + n); p += n; return s }
    fun string(): String { val n = len(); val s = String(b, p, n, Charsets.UTF_8); p += n; return s }
    fun float(): Float { if (p + 4 > end) { p = end; return 0f }; val v = (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8) or ((b[p + 2].toInt() and 0xff) shl 16) or ((b[p + 3].toInt() and 0xff) shl 24); p += 4; return java.lang.Float.intBitsToFloat(v) }
    fun skip(w: Int) { when (w) { 0 -> varint(); 1 -> p += 8; 2 -> { val n = len(); p += n }; 5 -> p += 4; else -> p = end } }
}

private fun get(url: String): Pair<Int, ByteArray> {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 15_000; c.readTimeout = 30_000
    c.setRequestProperty("User-Agent", "GTACTV/1.0 (Android game; ACTV open data)")
    return try { val code = c.responseCode; Pair(code, if (code == 200) c.inputStream.readBytes() else (c.errorStream?.readBytes() ?: ByteArray(0))) } finally { c.disconnect() }
}

private fun csv(line: String): List<String> {
    val out = ArrayList<String>(); val sb = StringBuilder(); var q = false
    for (ch in line) { when { ch == '"' -> q = !q; ch == ',' && !q -> { out.add(sb.toString()); sb.setLength(0) }; else -> sb.append(ch) } }
    out.add(sb.toString()); return out
}

fun main() {
    val url = "https://tpl.actv.it/nav/GTFSRT/vehiclePositions"
    try {
        // names from the static timetable
        val route = HashMap<String, String>(); val tripRoute = HashMap<String, String>(); val tripHead = HashMap<String, String>(); val stop = HashMap<String, String>()
        try {
            val (zc, zip) = get("https://actv.avmspa.it/sites/default/files/attachments/opendata/navigazione/actv_nav.zip")
            println("ACTV-FEED static GTFS: HTTP $zc, ${zip.size / 1024} KB")
            ZipInputStream(ByteArrayInputStream(zip)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val lines = z.readBytes().toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
                    if (lines.isEmpty()) continue
                    val h = csv(lines[0].trimStart('﻿'))
                    fun col(n: String) = h.indexOf(n)
                    when (e.name) {
                        "routes.txt" -> for (l in lines.drop(1)) { val f = csv(l); route[f[col("route_id")]] = f.getOrElse(col("route_short_name")) { "" } }
                        "trips.txt" -> for (l in lines.drop(1)) { val f = csv(l); tripRoute[f[col("trip_id")]] = f[col("route_id")]; if (col("trip_headsign") >= 0) tripHead[f[col("trip_id")]] = f.getOrElse(col("trip_headsign")) { "" } }
                        "stops.txt" -> for (l in lines.drop(1)) { val f = csv(l); stop[f[col("stop_id")]] = f.getOrElse(col("stop_name")) { "" } }
                    }
                }
            }
        } catch (e: Exception) { println("ACTV-FEED static GTFS failed: $e") }

        val (code, bytes) = get(url)
        println("ACTV-FEED $url -> HTTP $code, ${bytes.size} bytes")
        if (code != 200) { println("ACTV-FEED body: " + String(bytes.copyOf(minOf(300, bytes.size)))); return }
        val feed = Pb(bytes); var headerTs = 0L; val rows = ArrayList<String>()
        val statusName = arrayOf("IN ARRIVO A", "FERMO A", "IN VIAGGIO VERSO")
        while (feed.more()) {
            val (f, w) = feed.tag()
            if (f == 1 && w == 2) { val h = feed.sub(); while (h.more()) { val (g, x) = h.tag(); if (g == 3 && x == 0) headerTs = h.varint() else h.skip(x) } }
            else if (f == 2 && w == 2) {
                val ent = feed.sub(); var veh: Pb? = null; var id = ""
                while (ent.more()) { val (g, x) = ent.tag(); when { g == 1 && x == 2 -> id = ent.string(); g == 4 && x == 2 -> veh = ent.sub(); else -> ent.skip(x) } }
                val v = veh ?: continue
                var trip = ""; var rid = ""; var lat = Float.NaN; var lon = Float.NaN; var brg = Float.NaN; var spd = -1f; var st = -1; var sid = ""; var ts = 0L; var label = ""
                while (v.more()) {
                    val (g, x) = v.tag()
                    when {
                        g == 1 && x == 2 -> { val t = v.sub(); while (t.more()) { val (a, y) = t.tag(); when { a == 1 && y == 2 -> trip = t.string(); a == 5 && y == 2 -> rid = t.string(); else -> t.skip(y) } } }
                        g == 2 && x == 2 -> { val p = v.sub(); while (p.more()) { val (a, y) = p.tag(); when { a == 1 && y == 5 -> lat = p.float(); a == 2 && y == 5 -> lon = p.float(); a == 3 && y == 5 -> brg = p.float(); a == 5 && y == 5 -> spd = p.float(); else -> p.skip(y) } } }
                        g == 4 && x == 0 -> st = v.varint().toInt()
                        g == 5 && x == 0 -> ts = v.varint()
                        g == 7 && x == 2 -> sid = v.string()
                        g == 8 && x == 2 -> { val d = v.sub(); while (d.more()) { val (a, y) = d.tag(); if ((a == 1 || a == 2) && y == 2) { val s = d.string(); if (a == 2 || label.isEmpty()) label = s } else d.skip(y) } }
                        else -> v.skip(x)
                    }
                }
                val r = rid.ifEmpty { tripRoute[trip] ?: "" }
                val line = route[r] ?: r.ifEmpty { "?" }
                val age = if (ts > 0) (System.currentTimeMillis() / 1000 - ts) else -1
                rows.add(String.format("ACTV-FEED  linea %-5s → %-28s %-16s %-26s  %.5f, %.5f  rotta %s  vel %s  (%s s fa)  mezzo %s",
                    line, (tripHead[trip] ?: "").take(28), statusName.getOrElse(st) { "-" }, (stop[sid] ?: sid).take(26), lat, lon,
                    if (brg.isNaN()) "-" else "${brg.toInt()}°", if (spd < 0) "-" else String.format("%.1f m/s", spd), age, label.ifEmpty { id }))
            }
        }
        println("ACTV-FEED ${rows.size} battelli nel feed, timestamp del feed ${if (headerTs > 0) java.time.Instant.ofEpochSecond(headerTs) else "?"}")
        rows.sorted().forEach { println(it) }
    } catch (e: Exception) { println("ACTV-FEED failed: $e") }
}
