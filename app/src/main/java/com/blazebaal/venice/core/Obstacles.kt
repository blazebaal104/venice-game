// Solid things in the water that are not part of the map's land mask (vaporetto pontoons, gangways).
package com.blazebaal.venice.core

object Obstacles {
    /** oriented boxes: x, y, rotation (rad), half length, half width */
    @Volatile var stops: List<FloatArray> = emptyList()
}
