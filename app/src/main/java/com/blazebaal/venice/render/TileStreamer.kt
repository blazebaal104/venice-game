// Streams the city in chunks (one chunk = one 256 m map tile) around the camera, like Minecraft's render distance:
// tiles inside `radius` are meshed on background threads and uploaded on the GL thread, tiles outside it are
// released from the GPU and from RAM. Built meshes are cached on the phone's storage, so the expensive meshing only
// ever happens once per tile; afterwards a tile is a straight file read (and the whole city can be pre-built
// from the settings page).
package com.blazebaal.venice.render

import android.opengl.GLES30.*
import com.blazebaal.venice.core.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.math.*

class GpuTile(
    val tx: Int, val ty: Int, val ox: Float, val oy: Float,
    val facade: Mesh?, val generic: Mesh?, val detail: Mesh?, val vegetation: Mesh?,
    val maskTex: Int, val classTex: Int, val hasMarsh: Boolean,
) {
    val model = Mat4.translation(ox, oy, 0f)
    val cx = ox + TILE_SIZE / 2f; val cy = oy + TILE_SIZE / 2f
    fun release() {
        facade?.release(); generic?.release(); detail?.release(); vegetation?.release()
        if (maskTex != 0) Tex.release(maskTex); if (classTex != 0) Tex.release(classTex)
    }
}

private class Built(val t: TileData, val hasMarsh: Boolean, val fac: FloatBuffer?, val gen: FloatBuffer?, val det: FloatBuffer?, val veg: FloatBuffer?)

/** On-disk cache of meshed tiles. Invalidated automatically when the map or the mesher version changes. */
/** Tile meshes, from (in order) the pre-baked assets in the APK (baker/), the phone's own cache, or nothing. */
class TileCache(root: File, key: String, private val assets: android.content.res.AssetManager? = null) {
    private val dir = File(root, "tiles-$key")
    init {
        try {
            dir.mkdirs()
            root.listFiles()?.forEach { if (it.isDirectory && it.name.startsWith("tiles-") && it.name != dir.name) it.deleteRecursively() }
        } catch (_: Exception) { }
    }
    private fun file(tx: Int, ty: Int) = File(dir, "${tx}_$ty.bin")
    fun has(tx: Int, ty: Int) = file(tx, ty).exists()

    /** Returns (hasMarsh, facade, generic, detail, vegetation) or null. Buffers are direct, native order. */
    /** the APK's pre-baked copy of a tile (deflated, little-endian); null if the build didn't bake it */
    private fun readBaked(tx: Int, ty: Int): Array<Any?>? {
        val am = assets ?: return null
        return try {
            val bytes = java.util.zip.InflaterInputStream(am.open("tiles/${tx}_$ty.gtc"), java.util.zip.Inflater(), 1 shl 16).use { it.readBytes() }
            val head = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val size = if (head.getInt(0) == MAGIC3) 24 + (0 until 4).sumOf { head.getInt(8 + it * 4) } * 4 else bytes.size
            val buf = ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN)
            if (head.getInt(0) == MAGIC3) {
                // columns back to interleaved floats: positions from cm, normals from int8
                buf.putInt(MAGIC); buf.putInt(head.getInt(4))
                val n = IntArray(4) { head.getInt(8 + it * 4) }
                for (k in 0 until 4) buf.putInt(n[k])
                var src = 24 + 16
                for (k in 0 until 4) {
                    val sf = head.getInt(24 + k * 4); val s = sf and 0xff; val qz = sf and 0x100 != 0
                    val verts = n[k] / s
                    val out = FloatArray(n[k])
                    for (c in 0 until s) {
                        when {
                            c < 3 && qz -> { for (v in 0 until verts) out[v * s + c] = head.getShort(src + v * 2) / 100f; src += verts * 2 }
                            c in 3..5 -> { for (v in 0 until verts) out[v * s + c] = bytes[src + v] / 127f; src += verts }
                            else -> { for (v in 0 until verts) out[v * s + c] = head.getFloat(src + v * 4); src += verts * 4 }
                        }
                    }
                    buf.asFloatBuffer().put(out); buf.position(buf.position() + out.size * 4)
                }
                buf.flip()
            } else if (head.getInt(0) == MAGIC2) {
                // un-shuffle the byte planes back into little-endian floats
                buf.put(bytes, 0, 24)
                var src = 24
                for (i in 0 until 4) {
                    val n = head.getInt(8 + i * 4)
                    val tmp = ByteArray(n * 4)
                    for (k in 0 until n) { tmp[k * 4] = bytes[src + k]; tmp[k * 4 + 1] = bytes[src + n + k]; tmp[k * 4 + 2] = bytes[src + 2 * n + k]; tmp[k * 4 + 3] = bytes[src + 3 * n + k] }
                    buf.put(tmp); src += n * 4
                }
                buf.flip(); buf.putInt(0, MAGIC)
            } else { buf.put(bytes); buf.flip() }
            parse(buf, ByteOrder.LITTLE_ENDIAN)
        } catch (e: java.io.IOException) { null }
    }

    private fun parse(buf: ByteBuffer, order: ByteOrder): Array<Any?>? {
        if (buf.int != MAGIC) return null
        val hasMarsh = buf.int == 1
        val n = IntArray(4) { buf.int }
        var pos = buf.position()
        val out = arrayOfNulls<Any>(5); out[0] = hasMarsh
        for (i in 0 until 4) {
            if (n[i] == 0) { out[i + 1] = null; continue }
            buf.position(pos); buf.limit(pos + n[i] * 4)
            out[i + 1] = buf.slice().order(order).asFloatBuffer()
            buf.limit(buf.capacity()); pos += n[i] * 4
        }
        return out
    }

    /** true when this tile came pre-baked (nothing to build or to write) */
    fun baked(tx: Int, ty: Int): Boolean = assets != null && try { assets.open("tiles/${tx}_$ty.gtc").close(); true } catch (e: java.io.IOException) { false }

    fun read(tx: Int, ty: Int): Array<Any?>? {
        readBaked(tx, ty)?.let { return it }
        val f = file(tx, ty)
        if (!f.exists()) return null
        return try {
            RandomAccessFile(f, "r").use { raf ->
                val ch = raf.channel
                val buf = ByteBuffer.allocateDirect(ch.size().toInt()).order(ByteOrder.nativeOrder())
                while (buf.hasRemaining()) if (ch.read(buf) < 0) break
                buf.flip()
                if (buf.int != MAGIC) return null
                val hasMarsh = buf.int == 1
                val n = IntArray(4) { buf.int }
                var pos = buf.position()
                val out = arrayOfNulls<Any>(5); out[0] = hasMarsh
                for (i in 0 until 4) {
                    if (n[i] == 0) { out[i + 1] = null; continue }
                    buf.position(pos); buf.limit(pos + n[i] * 4)
                    out[i + 1] = buf.slice().order(ByteOrder.nativeOrder()).asFloatBuffer()
                    buf.limit(buf.capacity()); pos += n[i] * 4
                }
                out
            }
        } catch (e: Exception) { f.delete(); null }
    }

    fun write(tx: Int, ty: Int, m: TileMeshes) {
        try {
            val arrays = arrayOf(m.facade, m.generic, m.detail, m.vegetation)
            val bytes = 4 * 6 + arrays.sumOf { it.size } * 4
            val buf = ByteBuffer.allocate(bytes).order(ByteOrder.nativeOrder())
            buf.putInt(MAGIC); buf.putInt(if (m.hasMarsh) 1 else 0)
            for (a in arrays) buf.putInt(a.size)
            for (a in arrays) buf.asFloatBuffer().put(a).also { buf.position(buf.position() + a.size * 4) }
            val tmp = File(dir, "${tx}_$ty.${Thread.currentThread().id}.tmp")
            tmp.writeBytes(buf.array())
            tmp.renameTo(file(tx, ty))
        } catch (_: Exception) { }
    }

    fun clear() { try { dir.listFiles()?.forEach { it.delete() } } catch (_: Exception) { } }
    private var counted = 0; private var countedAt = 0L
    fun count(): Int {
        val now = System.currentTimeMillis()
        if (now - countedAt > 1000) { counted = dir.list()?.count { it.endsWith(".bin") } ?: 0; countedAt = now }
        return counted
    }

    companion object { const val MAGIC = 0x47544331; const val MAGIC2 = 0x47544332; const val MAGIC3 = 0x47544333 }  // "GTC1", "GTC2" (shuffled)
}

class TileStreamer(private val world: WorldMap, var radius: Float = 640f, private val cache: TileCache? = null) {
    val tiles = HashMap<Int, GpuTile>()
    /** bumped whenever a tile is added or removed (the cached city shadow map uses it) */
    @Volatile var version = 0
    /** how many tiles the current radius wants (for the loading bar) */
    var wanted = 1; private set
    private val pending = HashSet<Int>()
    private val ready = ConcurrentLinkedQueue<Built>()
    private val retry = ConcurrentLinkedQueue<Int>()
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "tile-builder").apply { priority = Thread.MIN_PRIORITY + 1 } }
    private val meshers = ThreadLocal.withInitial { CityMesher() }
    private val map = world.map
    @Volatile private var prebuildThread: Thread? = null

    private fun direct(a: FloatArray) = if (a.isEmpty()) null else floatBuffer(a)

    private val failures = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    private fun buildTile(tx: Int, ty: Int): Built? {
        val t = world.tile(tx, ty) ?: return null
        val k = ty * map.tilesX + tx
        val minimal = (failures[k] ?: 0) >= 2
        if (!minimal) cache?.read(tx, ty)?.let { c ->
            return Built(t, c[0] as Boolean, c[1] as FloatBuffer?, c[2] as FloatBuffer?, c[3] as FloatBuffer?, c[4] as FloatBuffer?)
        }
        val m = meshers.get()!!.build(t, minimal)
        if (!minimal) cache?.write(tx, ty, m)
        // vertex data copied into direct buffers here, off the GL thread
        return Built(t, m.hasMarsh, direct(m.facade), direct(m.generic), direct(m.detail), direct(m.vegetation))
    }

    /** Call on the GL thread each frame. */
    fun update(cx: Float, cy: Float, fx: Float, fy: Float, maxUploads: Int = 2) {
        val (ctx, cty) = world.tileCoords(cx, cy)
        val rt = ceil(radius / TILE_SIZE).toInt() + 1
        // request nearest first (and a bit ahead of the camera)
        val want = ArrayList<Pair<Int, Float>>()
        var total = 0
        for (dy in -rt..rt) for (dx in -rt..rt) {
            val tx = ctx + dx; val ty = cty + dy
            if (!map.hasTile(tx, ty)) continue
            val (ox, oy) = map.tileOrigin(tx, ty)
            val mx = ox + TILE_SIZE / 2f - cx; val my = oy + TILE_SIZE / 2f - cy
            val d = hypot(mx, my)
            // a tile is wanted when any part of it is inside the radius (its centre within radius + half diagonal)
            if (d > radius + TILE_SIZE * 0.71f) continue
            total++
            val k = ty * map.tilesX + tx
            if (tiles.containsKey(k) || pending.contains(k)) continue
            val ahead = (mx * fx + my * fy) / max(d, 1f)
            want.add(Pair(k, d - ahead * 120f))
        }
        wanted = max(1, total)
        want.sortBy { it.second }
        for ((k, _) in want.take(6)) {
            pending.add(k)
            val tx = k % map.tilesX; val ty = k / map.tilesX
            pool.execute {
                try { val b = buildTile(tx, ty); if (b != null) ready.add(b) }      // null = no data there (open lagoon): done
                catch (e: Throwable) {
                    // never leave a hole: retry, and after two failures build it with ground and quays only
                    android.util.Log.e("Venice", "tile $tx,$ty failed", e)
                    failures[k] = (failures[k] ?: 0) + 1
                    retry.add(k)
                }
            }
        }
        // failed builds go back in the queue
        while (true) { val k = retry.poll() ?: break; pending.remove(k) }
        // uploads
        var n = 0
        while (n < maxUploads) {
            val b = ready.poll() ?: break
            val k = b.t.ty * map.tilesX + b.t.tx
            pending.remove(k)
            tiles[k]?.release()
            tiles[k] = upload(b)
            version++
            n++
        }
        // unload tiles that left the render distance (with a little hysteresis so they don't flicker at the edge)
        val it = tiles.entries.iterator()
        while (it.hasNext()) {
            val e = it.next(); val t = e.value
            if (hypot(t.cx - cx, t.cy - cy) > radius + TILE_SIZE * 1.1f) { t.release(); it.remove(); version++ }
        }
        world.evictFar(cx, cy, rt + 2)
    }

    private fun upload(b: Built): GpuTile {
        val t = b.t
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
            b.fac?.let { Mesh(it, it.capacity() / FSTRIDE, FSTRIDE, intArrayOf(3, 3, 2, 4, 4)) },
            b.gen?.let { Mesh(it, it.capacity() / STRIDE) },
            b.det?.let { Mesh(it, it.capacity() / STRIDE) },
            b.veg?.let { Mesh(it, it.capacity() / STRIDE) },
            mask, cls, b.hasMarsh,
        )
    }

    /** Mesh every tile of the map into the storage cache on a background thread (progress 0..1 via callback). */
    fun prebuildAll(progress: (Float) -> Unit) {
        val c = cache ?: return
        if (prebuildThread != null) return
        prebuildThread = Thread({
            val keys = map.tileKeys().toList()
            val mesher = CityMesher()
            for ((i, k) in keys.withIndex()) {
                if (Thread.currentThread().isInterrupted) break
                val tx = k % map.tilesX; val ty = k / map.tilesX
                if (!c.has(tx, ty)) try { map.load(tx, ty)?.let { c.write(tx, ty, mesher.build(it)) } } catch (_: Throwable) { }
                progress((i + 1f) / keys.size)
            }
            progress(-1f)
            prebuildThread = null
        }, "prebuild").apply { priority = Thread.MIN_PRIORITY; isDaemon = true; start() }
    }

    fun cachedCount() = cache?.count() ?: 0
    fun clearCache() { cache?.clear() }
    fun totalTiles() = map.tileKeys().size
    fun pendingCount() = pending.size
    fun shutdown() { pool.shutdownNow(); prebuildThread?.interrupt() }
}
