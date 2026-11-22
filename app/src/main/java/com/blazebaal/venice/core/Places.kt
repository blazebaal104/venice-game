// A few named places (from their real coordinates), projected into map metres like the converter does.
// Used where the generic map data needs a hand: park planting, the wooden Accademia bridge.
package com.blazebaal.venice.core

import kotlin.math.*

object Places {
    class P(val x: Float, val y: Float, val r: Float)
    var santElena = P(1e9f, 1e9f, 0f); var giardini = P(1e9f, 1e9f, 0f); var napoleonici = P(1e9f, 1e9f, 0f); var reali = P(1e9f, 1e9f, 0f)
    var accademia = P(1e9f, 1e9f, 0f); var scalzi = P(1e9f, 1e9f, 0f); var costituzione = P(1e9f, 1e9f, 0f)
    private var ready = false

    fun init(lat0: Double, lon0: Double) {
        if (ready) return
        val phi = Math.toRadians(lat0)
        val mLat = 111132.954 - 559.822 * cos(2 * phi) + 1.175 * cos(4 * phi)
        val mLon = 111412.84 * cos(phi) - 93.5 * cos(3 * phi) + 0.118 * cos(5 * phi)
        fun p(lat: Double, lon: Double, r: Float) = P(((lon - lon0) * mLon).toFloat(), ((lat - lat0) * mLat).toFloat(), r)
        santElena = p(45.42935, 12.36170, 300f)      // Parco delle Rimembranze: stone (umbrella) pines
        giardini = p(45.42860, 12.35640, 280f)       // Giardini della Biennale: dense broad-leaved trees
        napoleonici = p(45.43070, 12.35290, 170f)    // Giardini Napoleonici / Viale Garibaldi
        reali = p(45.43270, 12.33760, 70f)           // Giardini Reali by San Marco
        accademia = p(45.43195, 12.32893, 0f)
        scalzi = p(45.44133, 12.32213, 0f)
        costituzione = p(45.43846, 12.32064, 0f)
        ready = true
    }
    fun inside(p: P, x: Float, y: Float) = hypot(x - p.x, y - p.y) < p.r
}
