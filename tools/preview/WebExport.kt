// Dev tool: exports the game's tile meshes around a point, plus a simulated boat run (pose, wake, spray),
// for the WebGL2 preview that uses the same shaders as the app.
import com.blazebaal.venice.core.*
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.InflaterInputStream
import kotlin.math.*

fun writeF(f: File, a: FloatArray, n: Int = a.size) {
    val b = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN); b.asFloatBuffer().put(a, 0, n); f.writeBytes(b.array())
}
fun readMesh(f: File): FloatArray = DataInputStream(InflaterInputStream(f.inputStream()).buffered()).use { d -> val n = d.readInt(); FloatArray(n * STRIDE) { d.readFloat() } }

fun main(args: Array<String>) {
    val map = MapData(File(args[0]).inputStream())
    val sx = args[1].toFloat(); val sy = args[2].toFloat(); val heading = Math.toRadians(args[3].toDouble()).toFloat()
    val simSeconds = args[4].toFloat(); val steer = args[5].toFloat()
    val out = File(args[6]).apply { mkdirs() }
    val assets = File(args[7])
    val world = WorldMap(map)
    // drive the boat with the real physics
    val boat = Boat(sx, sy, heading); val fx = Effects(); val wsim = WakeSim()
    boat.wakeHeight = { x, y -> wsim.height(x, y) }
    val dt = 1f / 60f
    var t = 0f
    while (t < simSeconds) { for (k in 0 until 3) boat.update(dt / 3, 1f, steer, world); fx.update(dt, boat, 1f); wsim.update(dt, boat, 1f, world); t += dt }
    val cx = boat.x; val cy = boat.y
    val mesher = CityMesher()
    val sb = StringBuilder("{\"tiles\":[")
    var first = true
    for (key in map.tileKeys()) {
        val tx = key % map.tilesX; val ty = key / map.tilesX
        val (ox, oy) = map.tileOrigin(tx, ty)
        if (hypot(ox + 128 - cx, oy + 128 - cy) > 950) continue
        val tile = map.load(tx, ty) ?: continue
        val m = mesher.build(tile)
        val id = "${tx}_$ty"
        writeF(File(out, "f_$id.bin"), m.facade); writeF(File(out, "g_$id.bin"), m.generic)
        if (tile.hard != null) {
            val rg = ByteArray(MASK_N * MASK_N * 2); for (i in 0 until MASK_N * MASK_N) { rg[i * 2] = tile.hard[i]; rg[i * 2 + 1] = tile.marsh!![i] }
            File(out, "m_$id.bin").writeBytes(rg); File(out, "c_$id.bin").writeBytes(tile.cls!!)
        }
        if (!first) sb.append(","); first = false
        sb.append("{\"id\":\"$id\",\"ox\":$ox,\"oy\":$oy,\"mask\":${tile.hard != null},\"marsh\":${m.hasMarsh}}")
    }
    sb.append("],\"boat\":{\"x\":${boat.x},\"y\":${boat.y},\"z\":${boat.z},\"h\":${boat.heading},\"pitch\":${boat.pitch},\"roll\":${boat.roll},\"t\":${boat.time},\"amp\":${boat.waveAmp},\"kmh\":${boat.speedKmh()}}")
    val wake = FloatArray(Effects.MAX_WAKE * 3 * 36); val nw = fx.buildWake(boat.time, boat.waveAmp, wake); writeF(File(out, "wake.bin"), wake, nw)
    val spray = FloatArray(2400 * 60); val ns = fx.buildSpray(spray); writeF(File(out, "spray.bin"), spray, ns)
    val wk = FloatArray(wsim.n * wsim.n * 4); wsim.pack(wk); writeF(File(out, "wake_sim.bin"), wk)
    sb.append(",\"wake\":{\"ox\":${wsim.ox},\"oy\":${wsim.oy},\"size\":${wsim.size},\"n\":${wsim.n},\"dx\":${wsim.dirX},\"dy\":${wsim.dirY}}")
    // landmarks
    sb.append(",\"landmarks\":[")
    val lm = File(assets, "landmarks/landmarks.txt").readLines().filter { it.isNotBlank() && !it.startsWith("#") }
    for ((i, line) in lm.withIndex()) {
        val p = line.trim().split(Regex("\\s+"))
        writeF(File(out, "lm_${p[0]}.bin"), readMesh(File(assets, "landmarks/${p[0]}.mesh")))
        val atlas = File(assets, "landmarks/${p[0]}_atlas.jpg")
        if (atlas.exists()) atlas.copyTo(File(out, "lm_${p[0]}_atlas.jpg"), true)
        if (i > 0) sb.append(","); sb.append("{\"name\":\"${p[0]}\",\"x\":${p[1]},\"y\":${p[2]},\"rot\":${p[3]},\"atlas\":${atlas.exists()}}")
    }
    sb.append("]")
    // ACTV stops (shared pontoon model, per-stop length, side and name slot)
    sb.append(",\"stops\":[")
    val actv = File(assets, "landmarks/actv.txt")
    if (actv.exists()) {
        for (l in listOf(12, 16, 22)) {
            writeF(File(out, "lm_actv$l.bin"), readMesh(File(assets, "landmarks/actv$l.mesh")))
            File(assets, "landmarks/actv${l}_atlas.jpg").copyTo(File(out, "lm_actv${l}_atlas.jpg"), true)
        }
        File(assets, "landmarks/actv_signs.png").copyTo(File(out, "actv_signs.png"), true)
        var firstS = true
        for (line in actv.readLines()) {
            val p = line.split('\t'); if (p.size < 7) continue
            val x = p[1].toFloat(); val y = p[2].toFloat()
            if (hypot(x - cx, y - cy) > 950) continue
            if (!firstS) sb.append(","); firstS = false
            sb.append("{\"x\":$x,\"y\":$y,\"rot\":${p[3].toFloat() + (if (p[5].toInt() < 0) 180f else 0f)},\"v\":${listOf(12, 16, 22).minByOrNull { kotlin.math.abs(it - p[4].toFloat()) }},\"slot\":${p[6]}}")
        }
    }
    sb.append("]}")
    File(out, "scene.json").writeText(sb.toString())
    writeF(File(out, "boat.bin"), readMesh(File(assets, "boat.mesh")))
    println("boat at ${boat.x}, ${boat.y} speed ${boat.speedKmh()} km/h z ${boat.z} pitch ${boat.pitch} amp ${boat.waveAmp}; wake ${nw / 6} verts, spray ${ns / 60}, wake max ${wsim.u.maxOrNull()} foam max ${wsim.foam.maxOrNull()}")
}
