// Tile cache + collision queries for the map. Pure Kotlin, thread-safe loading.
package com.blazebaal.venice.core

import kotlin.math.*

class WorldMap(val map: MapData) {
    private val tiles = HashMap<Int, TileData?>()
    private val lock = Any()

    fun tile(tx: Int, ty: Int): TileData? {
        if (!map.hasTile(tx, ty)) return null
        val k = ty * map.tilesX + tx
        synchronized(lock) { if (tiles.containsKey(k)) return tiles[k] }
        val t = map.load(tx, ty)
        synchronized(lock) { tiles[k] = t }
        return t
    }

    fun tileCoords(x: Float, y: Float) = Pair(floor((x - map.minX) / TILE_SIZE).toInt(), floor((y - map.minY) / TILE_SIZE).toInt())

    /** 0 = open water, 1 = solid (quay, island, barena, building). Outside the map counts as open water. */
    fun solid(x: Float, y: Float): Float {
        val (tx, ty) = tileCoords(x, y)
        val t = tile(tx, ty) ?: return 0f
        return t.solidAt(x - t.ox, y - t.oy)
    }

    /** Gradient of solidity pointing towards water (unnormalised). */
    fun toWater(x: Float, y: Float): Pair<Float, Float> {
        val e = 0.6f
        val gx = solid(x - e, y) - solid(x + e, y)
        val gy = solid(x, y - e) - solid(x, y + e)
        return Pair(gx, gy)
    }

    /** Bricole near a point (world coords). */
    fun posts(x: Float, y: Float, r: Float, out: ArrayList<FloatArray>) {
        out.clear()
        val (tx, ty) = tileCoords(x, y)
        for (dy in -1..1) for (dx in -1..1) {
            val t = tile(tx + dx, ty + dy) ?: continue
            for (p in t.points) if (p.type == Feature.BRICOLA) {
                val px = p.x + t.ox; val py = p.y + t.oy
                if (abs(px - x) < r && abs(py - y) < r) out.add(floatArrayOf(px, py))
            }
        }
    }

    fun evictFar(x: Float, y: Float, keepRadiusTiles: Int) {
        val (tx, ty) = tileCoords(x, y)
        synchronized(lock) {
            val it = tiles.keys.iterator()
            while (it.hasNext()) {
                val k = it.next(); val kx = k % map.tilesX; val ky = k / map.tilesX
                if (abs(kx - tx) > keepRadiusTiles || abs(ky - ty) > keepRadiusTiles) it.remove()
            }
        }
    }
}
