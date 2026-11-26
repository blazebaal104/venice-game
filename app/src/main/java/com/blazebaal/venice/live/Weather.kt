// Real Venice weather from Open-Meteo (https://open-meteo.com): free, no API key, CC BY 4.0 data.
// Polled every 10 minutes on a background thread; the renderer and HUD read the @Volatile fields.
// What it drives today: HUD temperature + icon, cloud cover in the sky shader, fog density (fog/mist codes and
// visibility), the wind direction the clouds drift with and wave choppiness. Rain/snow particles are the next step.
package com.blazebaal.venice.live

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import kotlin.math.*

object Weather {
    const val LAT = 45.4371; const val LON = 12.3326      // Rialto

    @Volatile var live = false               // true once a real reading arrived
    @Volatile var temperature = 12f          // °C
    @Volatile var code = 1                   // WMO weather code
    @Volatile var cloudCover = 0.45f         // 0..1
    @Volatile var windKmh = 8f
    @Volatile var windDirDeg = 60f           // where the wind comes FROM (bora ~ 60°, scirocco ~ 135°)
    @Volatile var gustKmh = 12f
    @Volatile var precipitationMm = 0f
    @Volatile var visibilityM = 20000f
    @Volatile var isDay = true
    @Volatile var updatedAt = 0L

    private var thread: Thread? = null

    /** Typical afternoon temperature in Venice for the month, used until (or if) the network answers. */
    fun climateFallback() {
        val m = Calendar.getInstance().get(Calendar.MONTH)
        temperature = intArrayOf(7, 9, 13, 17, 22, 26, 28, 28, 24, 18, 12, 8)[m].toFloat()
    }

    fun start() {
        if (thread != null) return
        climateFallback()
        thread = Thread({
            while (!Thread.currentThread().isInterrupted) {
                try { fetch() } catch (_: Exception) { }
                try { Thread.sleep(if (live) 10 * 60_000L else 60_000L) } catch (_: InterruptedException) { break }
            }
        }, "weather").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    fun stop() { thread?.interrupt(); thread = null }

    private fun fetch() {
        val url = URL("https://api.open-meteo.com/v1/forecast?latitude=$LAT&longitude=$LON" +
            "&current=temperature_2m,weather_code,cloud_cover,wind_speed_10m,wind_direction_10m,wind_gusts_10m,precipitation,visibility,is_day" +
            "&timezone=Europe%2FRome")
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "GTACTV/1.0 (Android game)")
        try {
            if (c.responseCode != 200) return
            val cur = JSONObject(c.inputStream.bufferedReader().readText()).getJSONObject("current")
            temperature = cur.optDouble("temperature_2m", temperature.toDouble()).toFloat()
            code = cur.optInt("weather_code", code)
            cloudCover = (cur.optDouble("cloud_cover", cloudCover * 100.0) / 100.0).toFloat().coerceIn(0f, 1f)
            windKmh = cur.optDouble("wind_speed_10m", windKmh.toDouble()).toFloat()
            windDirDeg = cur.optDouble("wind_direction_10m", windDirDeg.toDouble()).toFloat()
            gustKmh = cur.optDouble("wind_gusts_10m", gustKmh.toDouble()).toFloat()
            precipitationMm = cur.optDouble("precipitation", 0.0).toFloat()
            visibilityM = cur.optDouble("visibility", visibilityM.toDouble()).toFloat()
            isDay = cur.optInt("is_day", 1) == 1
            updatedAt = System.currentTimeMillis()
            live = true
        } finally { c.disconnect() }
    }

    // ---- what the game does with it
    fun isFog() = code == 45 || code == 48 || visibilityM < 2500f
    fun isRain() = code in 51..67 || code in 80..82 || precipitationMm > 0.1f
    fun isSnow() = code in 71..77 || code in 85..86
    fun isStorm() = code in 95..99

    /** Extra fog multiplier for the renderer: 1 = clear, up to ~3 in thick lagoon fog. */
    fun fogFactor(): Float {
        if (!live) return 1f
        val vis = visibilityM.coerceIn(150f, 30000f)
        val byVis = (3000f / vis).coerceIn(0f, 2.2f)
        return 1f + max(byVis, if (isFog()) 1.6f else 0f) + (if (isRain()) 0.35f else 0f)
    }
    /** Cloud cover for the sky shader. */
    fun clouds() = if (live) (cloudCover * 0.95f + (if (isRain() || isStorm()) 0.15f else 0f)).coerceIn(0.05f, 1f) else 0.55f
    /** Choppiness multiplier for open-water waves: calm at 5 km/h, rough in a 50 km/h bora. */
    fun waveFactor() = if (live) (0.75f + windKmh / 40f).coerceIn(0.7f, 2.2f) else 1f
    /** Wind vector (where it blows TO), unit-ish, for cloud drift. */
    fun windX() = -sin(Math.toRadians(windDirDeg.toDouble())).toFloat() * (windKmh / 20f).coerceIn(0.2f, 3f)
    fun windY() = -cos(Math.toRadians(windDirDeg.toDouble())).toFloat() * (windKmh / 20f).coerceIn(0.2f, 3f)

    fun label(): String = when {
        isStorm() -> "TEMPORALE"; isSnow() -> "NEVE"; isRain() -> "PIOGGIA"; isFog() -> "NEBBIA"
        code == 0 -> if (isDay) "SERENO" else "NOTTE SERENA"
        code in 1..2 -> "POCO NUVOLOSO"; code == 3 -> "COPERTO"
        else -> "VARIABILE"
    }
    /** 0 sun, 1 sun+cloud, 2 cloud, 3 rain, 4 fog, 5 storm, 6 snow, 7 moon */
    fun icon(): Int = when {
        isStorm() -> 5; isSnow() -> 6; isRain() -> 3; isFog() -> 4
        code == 3 -> 2; code in 1..2 -> if (isDay) 1 else 7
        else -> if (isDay) 0 else 7
    }
}
