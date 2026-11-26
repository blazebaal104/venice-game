// Solid things in the water that are not part of the map's land mask (vaporetto pontoons, gangways).
package com.blazebaal.venice.core

object Obstacles {
    /** oriented boxes: x, y, rotation (rad), half length, half width */
    @Volatile var stops: List<FloatArray> = emptyList()
}

/** Areas where a hand-built landmark replaces the map's generic buildings and street props (oriented rectangles:
 *  x, y, rotation rad, half length, half width). Filled from landmarks/landmarks.txt before any tile is meshed. */
object Exclusions {
    @Volatile var zones: List<FloatArray> = emptyList()
    fun inside(x: Float, y: Float): Boolean {
        for (z in zones) {
            val dx = x - z[0]; val dy = y - z[1]
            val c = kotlin.math.cos(z[2]); val s = kotlin.math.sin(z[2])
            if (kotlin.math.abs(dx * c + dy * s) < z[3] && kotlin.math.abs(-dx * s + dy * c) < z[4]) return true
        }
        return false
    }
}
