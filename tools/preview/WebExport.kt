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
    val boat = Boat(sx, sy, heading); val fx = Effects(); val drv = WakeDriver()
    val dt = 1f / 60f
    var t = 0f
    val steps = ArrayList<Float>()
    while (t < simSeconds) {
        for (k in 0 until 3) boat.update(dt / 3, 1f, steer, world); fx.update(dt, boat, 1f)
        drv.prepare(boat, 1f, dt)
        steps.addAll(drv.b0.toList()); steps.addAll(drv.b0b.toList()); steps.add(drv.nSplat.toFloat()); steps.addAll(drv.splats.toList())
        t += dt
    }
    writeF(File(out, "steps.bin"), steps.toFloatArray())
    val cx = boat.x; val cy = boat.y
    // wake window for the preview's GPU simulation: 256 m, most of it behind the boat (like the game)
    val wn = 512; val wcell = 0.5f
    val wox = floor((boat.x - drv.dirX * 70f - wn * wcell / 2) / wcell) * wcell; val woy = floor((boat.y - drv.dirY * 70f - wn * wcell / 2) / wcell) * wcell
    val solid = ByteArray(wn * wn) { k -> if (world.solid(wox + (k % wn + 0.5f) * wcell, woy + (k / wn + 0.5f) * wcell) > 0.5f) -1 else 0 }
    File(out, "solid.bin").writeBytes(solid)
    File(out, "water_n.bin").writeBytes(WaterNormalData.make(256))
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
        writeF(File(out, "f_$id.bin"), m.facade); writeF(File(out, "g_$id.bin"), m.generic); writeF(File(out, "d_$id.bin"), m.detail)
        if (tile.hard != null) {
            val rg = ByteArray(MASK_N * MASK_N * 2); for (i in 0 until MASK_N * MASK_N) { rg[i * 2] = tile.hard[i]; rg[i * 2 + 1] = tile.marsh!![i] }
            File(out, "m_$id.bin").writeBytes(rg); File(out, "c_$id.bin").writeBytes(tile.cls!!)
        }
        if (!first) sb.append(","); first = false
        sb.append("{\"id\":\"$id\",\"ox\":$ox,\"oy\":$oy,\"mask\":${tile.hard != null},\"marsh\":${m.hasMarsh}}")
    }
    sb.append("],\"boat\":{\"x\":${boat.x},\"y\":${boat.y},\"z\":${boat.z},\"h\":${boat.heading},\"pitch\":${boat.pitch},\"roll\":${boat.roll},\"t\":${boat.time},\"amp\":${boat.waveAmp},\"kmh\":${boat.speedKmh()}}")
    val spray = FloatArray(Effects.MAX_P * 8); val ns = fx.buildInstances(spray); writeF(File(out, "spray.bin"), spray, ns * 8)
    sb.append(",\"wake\":{\"ox\":$wox,\"oy\":$woy,\"size\":${wn * wcell},\"n\":$wn,\"dx\":${drv.dirX},\"dy\":${drv.dirY},\"steps\":${steps.size / 57},\"hl\":${Boat.HALF_LEN},\"hb\":${Boat.HALF_BEAM},\"surge\":${boat.surge}}")
    // landmarks
    sb.append(",\"landmarks\":[")
    val lm = File(assets, "landmarks/landmarks.txt").readLines().filter { it.isNotBlank() && !it.startsWith("#") }
    var nlm = 0
    for (line in lm) {
        val p = line.trim().split(Regex("\\s+"))
        if (!File(assets, "landmarks/${p[0]}.mesh").exists()) continue      // zone-only line
        val i = nlm++
        writeF(File(out, "lm_${p[0]}.bin"), readMesh(File(assets, "landmarks/${p[0]}.mesh")))
        val atlas = File(assets, "landmarks/${p[0]}_atlas.jpg")
        if (atlas.exists()) atlas.copyTo(File(out, "lm_${p[0]}_atlas.jpg"), true)
        if (i > 0) sb.append(","); sb.append("{\"name\":\"${p[0]}\",\"x\":${p[1]},\"y\":${p[2]},\"rot\":${p[3]},\"atlas\":${atlas.exists()}}")
    }
    sb.append("]")
    // hand-built bridges (assets/bridges/bridges.txt): mesh x y rot sx sy sz
    sb.append(",\"bridges\":[")
    val bf = File(assets, "bridges/bridges.txt")
    if (bf.exists()) {
        val done = HashSet<String>(); var fb = true
        File(assets, "bridges/bridges_atlas.jpg").copyTo(File(out, "bridges_atlas.jpg"), true)
        for (line in bf.readLines().filter { it.isNotBlank() && !it.startsWith("#") }) {
            val p = line.trim().split(Regex("\\s+"))
            if (hypot(p[1].toFloat() - cx, p[2].toFloat() - cy) > 950) continue
            if (done.add(p[0])) {
                writeF(File(out, "br_${p[0]}.bin"), readMesh(File(assets, "bridges/${p[0]}.mesh")))
                val own = File(assets, "bridges/${p[0]}_atlas.jpg"); if (own.exists()) own.copyTo(File(out, "br_${p[0]}_atlas.jpg"), true)   // models with their own sheet
            }
            if (!fb) sb.append(","); fb = false
            sb.append("{\"n\":\"${p[0]}\",\"x\":${p[1]},\"y\":${p[2]},\"rot\":${p[3]},\"sx\":${p[4]},\"sy\":${p[5]},\"sz\":${p[6]},\"own\":${File(assets, "bridges/${p[0]}_atlas.jpg").exists()}}")
        }
    }
    sb.append("]")
    // moored boats: the game's catalogue + placement, merged and painted on the CPU into one world-space mesh
    // (SHOWROOM=x,y lays every variant out in a grid on open water instead)
    run {
        val catF = File(assets, "boats/catalog.txt")
        if (!catF.exists()) return@run
        val cat = BoatCatalog(catF.readLines())
        val parts = HashMap<String, FloatArray?>()
        fun part(n: String) = parts.getOrPut(n) { File(assets, "boats/parts/$n.mesh").let { if (it.exists()) readMesh(it) else null } }
        val places = ArrayList<FloatArray>()
        val show = System.getenv("SHOWROOM")
        if (show != null) {
            val (gx, gy) = show.split(",").map { it.toFloat() }
            for ((i, v) in cat.variants.withIndex()) places.add(floatArrayOf(gx + (i % 12) * 13f, gy + (i / 12) * 5.5f, 0f, v.id.toFloat()))
        } else {
            val (tx, ty) = world.tileCoords(cx, cy)
            for (j in ty - 1..ty + 1) for (i in tx - 1..tx + 1) world.tile(i, j)?.let { places.addAll(Moorings.place(it, cat)) }
        }
        val all = ArrayList<Float>()
        val sentinels = arrayOf(floatArrayOf(1f, 0f, 1f), floatArrayOf(0f, 1f, 1f), floatArrayOf(1f, 1f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f))
        for (b in places) {
            if (show == null && hypot(b[0] - cx, b[1] - cy) > 300f) continue
            val v = cat.variants[b[3].toInt()]
            val d = (part("hull_" + v.base) ?: continue) + BoatCatalog.mergeParts(v, { part(it) })
            val c = cos(b[2]); val sn = sin(b[2])
            var i = 0
            while (i < d.size) {
                val x = d[i]; val y = d[i + 1]; val nx = d[i + 3]; val ny = d[i + 4]
                d[i] = b[0] + x * c - y * sn; d[i + 1] = b[1] + x * sn + y * c
                d[i + 3] = nx * c - ny * sn; d[i + 4] = nx * sn + ny * c
                if (d[i + 8] < 0f) for ((k, sv) in sentinels.withIndex()) if (abs(d[i + 9] - sv[0]) < 0.002f && abs(d[i + 10] - sv[1]) < 0.002f && abs(d[i + 11] - sv[2]) < 0.002f) {
                    for (q in 0..3) d[i + 8 + q] = v.paint[k * 4 + q]; break
                }
                i += STRIDE
            }
            for (f in d) all.add(f)
        }
        writeF(File(out, "moored.bin"), all.toFloatArray())
        File(assets, "boats/decals.png").copyTo(File(out, "boat_decals.png"), true)
        println("moored boats: ${places.size} placed, ${all.size / STRIDE / 3} tris exported")
    }
    // ACTV stops (shared pontoon model, per-stop length, side and name slot)
    sb.append(",\"stops\":[")
    val actv = File(assets, "landmarks/actv.txt")
    if (actv.exists()) {
        for (l in listOf(12, 16, 22)) {
            writeF(File(out, "lm_actv$l.bin"), readMesh(File(assets, "landmarks/actv$l.mesh")))
            File(assets, "landmarks/actv${l}_atlas.jpg").copyTo(File(out, "lm_actv${l}_atlas.jpg"), true)
        }
        File(assets, "landmarks/actv_signs.png").copyTo(File(out, "actv_signs.png"), true)
        val lay = StopLayout.build(actv.readLines(), world)
        var firstS = true
        for (st in lay.stops) {
            if (hypot(st.x - cx, st.y - cy) > 950) continue
            if (!firstS) sb.append(","); firstS = false
            sb.append("{\"x\":${st.x},\"y\":${st.y},\"rot\":${Math.toDegrees(st.rot.toDouble())},\"v\":${st.len},\"slot\":${st.slot}}")
        }
        writeF(File(out, "stop_extras.bin"), lay.extras.floats())
    }
    sb.append("]}")
    File(out, "scene.json").writeText(sb.toString())
    writeF(File(out, "boat.bin"), readMesh(File(assets, "boat.mesh")))
    println("boat at ${boat.x}, ${boat.y} speed ${boat.speedKmh()} km/h z ${boat.z} pitch ${boat.pitch} amp ${boat.waveAmp}; spray $ns particles; ${steps.size / 57} wake steps")
}
