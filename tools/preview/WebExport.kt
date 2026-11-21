// Dev tool: exports the game's tile meshes around a point for a WebGL2 preview (same shaders as the app).
import com.blazebaal.venice.core.*
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.InflaterInputStream
import kotlin.math.*

fun writeF(f: File, a: FloatArray) {
    val b = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN); b.asFloatBuffer().put(a); f.writeBytes(b.array())
}

fun main(args: Array<String>) {
    val map = MapData(File(args[0]).inputStream())
    val cx = args[1].toFloat(); val cy = args[2].toFloat(); val radius = args[3].toFloat()
    val out = File(args[4]).apply { mkdirs() }
    val boatFile = File(args[5])
    val mesher = CityMesher()
    val sb = StringBuilder("{\"tiles\":[")
    var first = true
    for (key in map.tileKeys()) {
        val tx = key % map.tilesX; val ty = key / map.tilesX
        val (ox, oy) = map.tileOrigin(tx, ty)
        if (hypot(ox + 128 - cx, oy + 128 - cy) > radius + 200) continue
        val t = map.load(tx, ty) ?: continue
        val m = mesher.build(t)
        val id = "${tx}_$ty"
        writeF(File(out, "f_$id.bin"), m.facade); writeF(File(out, "g_$id.bin"), m.generic)
        if (t.hard != null) {
            val rg = ByteArray(MASK_N * MASK_N * 2); for (i in 0 until MASK_N * MASK_N) { rg[i * 2] = t.hard[i]; rg[i * 2 + 1] = t.marsh!![i] }
            File(out, "m_$id.bin").writeBytes(rg); File(out, "c_$id.bin").writeBytes(t.cls!!)
        }
        if (!first) sb.append(","); first = false
        sb.append("{\"id\":\"$id\",\"ox\":$ox,\"oy\":$oy,\"mask\":${t.hard != null},\"marsh\":${m.hasMarsh}}")
    }
    sb.append("]}")
    File(out, "tiles.json").writeText(sb.toString())
    DataInputStream(InflaterInputStream(boatFile.inputStream()).buffered()).use { d ->
        val n = d.readInt(); writeF(File(out, "boat.bin"), FloatArray(n * STRIDE) { d.readFloat() })
    }
    println("exported to $out")
}
