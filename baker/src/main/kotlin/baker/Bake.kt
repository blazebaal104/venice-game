package baker

import com.blazebaal.venice.core.CityMesher
import com.blazebaal.venice.core.MapData
import com.blazebaal.venice.core.WorldMap
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.Deflater

/** args: venice.map, output dir. Same layout as the phone's TileCache ("GTC1", little-endian), deflated. */
fun main(args: Array<String>) {
    val map = MapData(File(args[0]).inputStream().buffered(1 shl 16))
    val world = WorldMap(map)
    val out = File(args[1]); out.deleteRecursively(); out.mkdirs()
    val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
    val meshers = ThreadLocal.withInitial { CityMesher() }
    val tiles = AtomicInteger(); val raw = AtomicLong(); val packed = AtomicLong()
    val t0 = System.currentTimeMillis()
    // the historic centre, the Giudecca and San Giorgio: where the game is played. Everything else is meshed on the
    // phone the first time it's seen and kept in its cache (which now survives app updates)
    val x0 = -2900f; val x1 = 2300f; val y0 = -1500f; val y1 = 1900f
    for (ty in 0 until map.tilesY) for (tx in 0 until map.tilesX) {
        if (!map.hasTile(tx, ty)) continue
        val cx = map.minX + (tx + 0.5f) * 256f; val cy = map.minY + (ty + 0.5f) * 256f
        if (cx < x0 || cx > x1 || cy < y0 || cy > y1) continue
        pool.execute {
            val t = world.tile(tx, ty) ?: return@execute
            val m = meshers.get().build(t)
            val arrays = arrayOf(m.facade, m.generic, m.detail, m.vegetation)
            val strides = intArrayOf(16, 12, 12, 12)
            val bytes = 4 * 6 + arrays.sumOf { it.size } * 4
            // "GTC3": per mesh, one column per vertex component; positions in centimetres (int16) when they fit,
            // normals in int8, everything else exact float. Columns of similar numbers deflate very well.
            val buf = ByteBuffer.allocate(bytes + 64).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(0x47544333); buf.putInt(if (m.hasMarsh) 1 else 0)
            for (a in arrays) buf.putInt(a.size)
            val q = BooleanArray(4) { k -> val a = arrays[k]; val s = strides[k]; var ok = true; var i = 0; while (i < a.size) { for (c in 0 until 3) if (kotlin.math.abs(a[i + c]) > 327f) ok = false; i += s }; ok }
            for (k in 0 until 4) buf.putInt(strides[k] or (if (q[k]) 0x100 else 0))
            for (k in 0 until 4) {
                val a = arrays[k]; val s = strides[k]; val n = a.size / s
                for (c in 0 until s) {
                    when {
                        c < 3 && q[k] -> for (v in 0 until n) buf.putShort(kotlin.math.round(a[v * s + c] * 100f).toInt().toShort())
                        c in 3..5 -> for (v in 0 until n) buf.put(kotlin.math.round(a[v * s + c].coerceIn(-1f, 1f) * 127f).toInt().toByte())
                        else -> for (v in 0 until n) buf.putFloat(a[v * s + c])
                    }
                }
            }
            buf.flip()
            val d = Deflater(6); d.setInput(buf.array(), 0, buf.limit()); d.finish()
            val o = java.io.ByteArrayOutputStream(bytes / 3); val chunk = ByteArray(1 shl 16)
            while (!d.finished()) { val k = d.deflate(chunk); o.write(chunk, 0, k) }
            d.end()
            File(out, "${tx}_$ty.gtc").writeBytes(o.toByteArray())
            tiles.incrementAndGet(); raw.addAndGet(bytes.toLong()); packed.addAndGet(o.size().toLong())
        }
    }
    pool.shutdown(); pool.awaitTermination(1, TimeUnit.HOURS)
    println("baked ${tiles.get()} tiles: ${raw.get() / 1048576} MB of meshes -> ${packed.get() / 1048576} MB in the APK, ${(System.currentTimeMillis() - t0) / 1000} s")
}
