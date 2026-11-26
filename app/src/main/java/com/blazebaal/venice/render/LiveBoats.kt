// Turns the ACTV feed (live/Vaporetti.kt) into boats on the water: projects each reported GPS fix into map metres,
// dead-reckons it forward along its bearing/speed between the 15 s updates, and eases the drawn boat towards that
// so it glides instead of jumping. Also publishes collision boxes for the player's boat and map markers for the UI.
package com.blazebaal.venice.render

import com.blazebaal.venice.core.Mat4
import com.blazebaal.venice.core.Obstacles
import com.blazebaal.venice.core.Places
import com.blazebaal.venice.core.Waves
import com.blazebaal.venice.live.Vaporetti
import com.blazebaal.venice.ui.GameState
import kotlin.math.*

class LiveBoats {
    class Boat(val id: String) {
        var x = 0f; var y = 0f; var h = 0f; var line = ""; var head = ""; var seen = 0L; var fresh = true
        var tx = 0f; var ty = 0f; var th = 0f
    }
    val boats = HashMap<String, Boat>()
    private var lastFeed = -1L

    fun update(dt: Float, time: Float) {
        val now = System.currentTimeMillis()
        val list = if (Vaporetti.enabled) Vaporetti.vessels else emptyList()
        val lat0 = GameState.lat0; val lon0 = GameState.lon0
        if (Vaporetti.updatedAt != lastFeed) {
            lastFeed = Vaporetti.updatedAt
            val ids = HashSet<String>()
            for (v in list) {
                ids.add(v.id)
                val p = Places.project(v.lat, v.lon, lat0, lon0)
                val b = boats.getOrPut(v.id) { Boat(v.id) }
                b.line = v.line; b.head = v.headsign; b.seen = v.time
                // bearing is degrees clockwise from north; our heading is radians counter-clockwise from east
                val hb = if (v.bearing.isNaN()) (if (b.fresh) 0f else atan2(p[1] - b.ty, p[0] - b.tx)) else Math.toRadians(90.0 - v.bearing).toFloat()
                b.tx = p[0]; b.ty = p[1]; b.th = hb
                speeds[v.id] = v.speed
                if (b.fresh) { b.x = b.tx; b.y = b.ty; b.h = b.th; b.fresh = false }
            }
            boats.keys.retainAll(ids)
        }
        val k = 1f - exp(-dt * 0.9f)
        for (b in boats.values) {
            // dead reckoning: keep moving along the last bearing for up to 25 s after the fix
            val age = ((now - b.seen) / 1000f).coerceIn(0f, 25f)
            val sp = (speeds[b.id] ?: 0f).coerceIn(0f, 12f)
            val px = b.tx + cos(b.th) * sp * age; val py = b.ty + sin(b.th) * sp * age
            if (hypot(px - b.x, py - b.y) > 250f) { b.x = px; b.y = py; b.h = b.th }
            b.x += (px - b.x) * k; b.y += (py - b.y) * k
            var dh = b.th - b.h; while (dh > PI) dh -= (2 * PI).toFloat(); while (dh < -PI) dh += (2 * PI).toFloat()
            b.h += dh * (1f - exp(-dt * 0.7f))
        }
        // solid for the player's boat: one oriented box per vaporetto (x, y, rotation, half length, half width)
        Obstacles.boats = boats.values.map { floatArrayOf(it.x, it.y, it.h, 12.0f, 2.35f) }
        // markers for the minimap / full map
        markers = boats.values.map { Marker(it.x, it.y, it.h, it.line, it.head) }
    }
    private val speeds = HashMap<String, Float>()

    fun model(b: Boat, time: Float, waveAmp: Float): FloatArray {
        val z = Waves.height(b.x, b.y, time, waveAmp) * 0.6f
        return Mat4.mul(Mat4.translation(b.x, b.y, z), Mat4.rotZ(b.h))
    }

    class Marker(val x: Float, val y: Float, val h: Float, val line: String, val head: String)
    companion object { @Volatile var markers: List<Marker> = emptyList() }
}
