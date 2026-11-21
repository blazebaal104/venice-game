// Builds tile meshes on a background thread and uploads them on the GL thread.
package com.blazebaal.venice.render

import android.opengl.GLES30.*
import com.blazebaal.venice.core.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.math.*

class GpuTile(
    val tx: Int, val ty: Int, val ox: Float, val oy: Float,
    val facade: Mesh?, val generic: Mesh?,
    val maskTex: Int, val classTex: Int, val hasMarsh: Boolean,
) {
    val model = Mat4.translation(ox, oy, 0f)
    fun release() {
        facade?.release(); generic?.release()
        if (maskTex != 0) Tex.release(maskTex); if (classTex != 0) Tex.release(classTex)
    }
}

private class Built(val t: TileData, val m: TileMeshes)

class TileStreamer(private val world: WorldMap, var radius: Float = 950f) {
    val tiles = HashMap<Int, GpuTile>()
    private val pending = HashSet<Int>()
    private val ready = ConcurrentLinkedQueue<Built>()
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "tile-builder").apply { priority = Thread.MIN_PRIORITY + 1 } }
    private val meshers = ThreadLocal.withInitial { CityMesher() }
    private val map = world.map

    /** Call on the GL thread each frame. */
    fun update(cx: Float, cy: Float, fx: Float, fy: Float, maxUploads: Int = 2) {
        val (ctx, cty) = world.tileCoords(cx, cy)
        val rt = ceil(radius / TILE_SIZE).toInt() + 1
        // request nearest first (and a bit ahead of the camera)
        val want = ArrayList<Pair<Int, Float>>()
        for (dy in -rt..rt) for (dx in -rt..rt) {
            val tx = ctx + dx; val ty = cty + dy
            if (!map.hasTile(tx, ty)) continue
            val k = ty * map.tilesX + tx
            if (tiles.containsKey(k) || pending.contains(k)) continue
            val (ox, oy) = map.tileOrigin(tx, ty)
            val mx = ox + TILE_SIZE / 2f - cx; val my = oy + TILE_SIZE / 2f - cy
            val d = hypot(mx, my)
            if (d > radius + TILE_SIZE) continue
            val ahead = (mx * fx + my * fy) / max(d, 1f)
            want.add(Pair(k, d - ahead * 120f))
        }
        want.sortBy { it.second }
        for ((k, _) in want.take(6)) {
            pending.add(k)
            val tx = k % map.tilesX; val ty = k / map.tilesX
            pool.execute {
                try {
                    val t = world.tile(tx, ty)
                    if (t != null) ready.add(Built(t, meshers.get()!!.build(t)))
                } catch (e: Throwable) { android.util.Log.e("Venice", "tile $tx,$ty failed", e) }
            }
        }
        // uploads
        var n = 0
        while (n < maxUploads) {
            val b = ready.poll() ?: break
            val k = b.t.ty * map.tilesX + b.t.tx
            pending.remove(k)
            tiles[k]?.release()
            tiles[k] = upload(b)
            n++
        }
        // unload far tiles
        val it = tiles.entries.iterator()
        while (it.hasNext()) {
            val e = it.next(); val t = e.value
            if (hypot(t.ox + TILE_SIZE / 2f - cx, t.oy + TILE_SIZE / 2f - cy) > radius + TILE_SIZE * 2.2f) { t.release(); it.remove() }
        }
        world.evictFar(cx, cy, rt + 3)
    }

    private fun upload(b: Built): GpuTile {
        val t = b.t; val m = b.m
        var mask = 0; var cls = 0
        if (t.hard != null) {
            val n = MASK_N
            val rg = ByteArray(n * n * 2)
            for (i in 0 until n * n) { rg[i * 2] = t.hard[i]; rg[i * 2 + 1] = t.marsh!![i] }
            mask = Tex.create(n, n, GL_RG, rg, true, GL_RG8)
            cls = Tex.create(n, n, GL_RED, t.cls!!, false, GL_R8)
        }
        return GpuTile(
            t.tx, t.ty, t.ox, t.oy,
            if (m.facade.isNotEmpty()) Mesh(m.facade) else null,
            if (m.generic.isNotEmpty()) Mesh(m.generic) else null,
            mask, cls, m.hasMarsh,
        )
    }

    fun pendingCount() = pending.size
    fun shutdown() { pool.shutdownNow() }
}
