// Tile cache + collision queries for the map. Pure Kotlin, thread-safe loading.
package com.blazebaal.venice.core

import kotlin.math.*

class WorldMap(val map: MapData) {
    init { Places.init(map.lat0, map.lon0) }
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

    /** Bricole near a point (world coords). Piles OSM placed on land are not built, so they do not collide either. */
    fun posts(x: Float, y: Float, r: Float, out: ArrayList<FloatArray>) {
        out.clear()
        val (tx, ty) = tileCoords(x, y)
        for (dy in -1..1) for (dx in -1..1) {
            val t = tile(tx + dx, ty + dy) ?: continue
            for (p in t.points) if (p.type == Feature.BRICOLA && t.solidAt(p.x, p.y) <= 0.25f) {
                val px = p.x + t.ox; val py = p.y + t.oy
                if (abs(px - x) < r && abs(py - y) < r) out.add(floatArrayOf(px, py))
            }
        }
    }

    // ---------------------------------------------------------------- piers and wooden docks (solid for the boat)
    private class PierGeo(val boxes: List<FloatArray>, val polys: List<FloatArray>)
    private val pierGeo = HashMap<Int, PierGeo>()

    private fun pierGeo(tx: Int, ty: Int): PierGeo? {
        val t = tile(tx, ty) ?: return null
        val k = ty * map.tilesX + tx
        synchronized(lock) { pierGeo[k]?.let { return it } }
        val boxes = ArrayList<FloatArray>(); val polys = ArrayList<FloatArray>()
        // line piers: one oriented box per segment (x, y, rotation, half length, half width), deck plus its piles
        for (p in t.piers) {
            val hw = (p.width / 2).coerceIn(0.6f, 4f) + 0.25f
            val n = p.xy.size / 2
            for (i in 0 until n - 1) {
                val ax = p.xy[i * 2] + t.ox; val ay = p.xy[i * 2 + 1] + t.oy; val bx = p.xy[i * 2 + 2] + t.ox; val by = p.xy[i * 2 + 3] + t.oy
                val len = hypot(bx - ax, by - ay); if (len < 0.05f) continue
                boxes.add(floatArrayOf((ax + bx) / 2, (ay + by) / 2, atan2(by - ay, bx - ax), len / 2 + 0.2f, hw))
            }
        }
        // area piers / pontoons: the outline polygon in world coordinates
        for (pp in t.pierPolys) {
            if (pp.xy.size < 6) continue
            polys.add(FloatArray(pp.xy.size) { i -> pp.xy[i] + if (i % 2 == 0) t.ox else t.oy })
        }
        val g = PierGeo(boxes, polys)
        synchronized(lock) { pierGeo[k] = g }
        return g
    }

    /** Pier boxes and pontoon polygons whose tile is within one tile of (x, y). */
    fun piers(x: Float, y: Float, boxesOut: ArrayList<FloatArray>, polysOut: ArrayList<FloatArray>) {
        boxesOut.clear(); polysOut.clear()
        val (tx, ty) = tileCoords(x, y)
        for (dy in -1..1) for (dx in -1..1) {
            val g = pierGeo(tx + dx, ty + dy) ?: continue
            for (b in g.boxes) if (abs(b[0] - x) < b[3] + 12f && abs(b[1] - y) < b[3] + 12f) boxesOut.add(b)
            for (p in g.polys) polysOut.add(p)
        }
    }

    fun evictFar(x: Float, y: Float, keepRadiusTiles: Int) {
        val (tx, ty) = tileCoords(x, y)
        synchronized(lock) {
            val it = tiles.keys.iterator()
            while (it.hasNext()) {
                val k = it.next(); val kx = k % map.tilesX; val ky = k / map.tilesX
                if (abs(kx - tx) > keepRadiusTiles || abs(ky - ty) > keepRadiusTiles) { it.remove(); pierGeo.remove(k) }
            }
        }
    }
}
