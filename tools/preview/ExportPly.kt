// Dev tool: builds tile meshes with the game's own core code and exports a PLY (vertex colours) for a Blender preview.
import com.blazebaal.venice.core.*
import java.io.File
import kotlin.math.*

fun main(args: Array<String>) {
    val map = MapData(File(args[0]).inputStream())
    val cx = args[1].toFloat(); val cy = args[2].toFloat(); val radius = args[3].toFloat()
    val out = File(args[4])
    val mesher = CityMesher()
    val verts = ArrayList<FloatArray>() // x y z r g b
    var nTiles = 0
    var gameTris = 0
    for (key in map.tileKeys()) {
        val tx = key % map.tilesX; val ty = key / map.tilesX
        val (ox, oy) = map.tileOrigin(tx, ty)
        if (ox + TILE_SIZE < cx - radius || ox > cx + radius || oy + TILE_SIZE < cy - radius || oy > cy + radius) continue
        val t = map.load(tx, ty) ?: continue
        nTiles++
        val m = mesher.build(t)
        fun emit(f: FloatArray, facade: Boolean) {
            for (i in 0 until f.size / STRIDE) {
                val o = i * STRIDE
                val x = f[o] + ox - cx; val y = f[o + 1] + oy - cy; val z = f[o + 2]
                val col = if (!facade) floatArrayOf(f[o + 8], f[o + 9], f[o + 10]) else when (f[o + 10]) {
                    Kind.ROOF -> floatArrayOf(0.55f, 0.27f, 0.2f)
                    Kind.QUAY -> if (z > 0.6f) floatArrayOf(0.8f, 0.78f, 0.72f) else floatArrayOf(0.35f, 0.33f, 0.28f)
                    Kind.CORNICE -> floatArrayOf(0.85f, 0.83f, 0.78f)
                    else -> { val s = f[o + 9]; floatArrayOf(0.75f + 0.15f * s, 0.55f + 0.2f * ((s * 7) % 1), 0.42f + 0.2f * ((s * 13) % 1)) }
                }
                verts.add(floatArrayOf(x, y, z, col[0], col[1], col[2]))
            }
        }
        emit(m.facade, true); emit(m.generic, false)
        gameTris += (m.facade.size + m.generic.size) / STRIDE / 3
        // ground: emit cells as quads where coverage >= 0.5 (preview only)
        val hard = t.hard ?: continue
        for (j in 1..TILE_SIZE) {
            var i = 1
            while (i <= TILE_SIZE) {
                val v = hard[j * MASK_N + i].toInt() and 0xff
                if (v < 128) { i++; continue }
                val c = t.cls!![j * MASK_N + i].toInt()
                var e = i
                while (e + 1 <= TILE_SIZE && (hard[j * MASK_N + e + 1].toInt() and 0xff) >= 128 && t.cls!![j * MASK_N + e + 1].toInt() == c) e++
                val col = when (c) { 2 -> floatArrayOf(0.35f, 0.45f, 0.25f); 3 -> floatArrayOf(0.85f, 0.78f, 0.6f); else -> floatArrayOf(0.5f, 0.49f, 0.47f) }
                val x0 = i - 1f + ox - cx; val x1 = e.toFloat() + ox - cx; val y0 = j - 1f + oy - cy; val z = Z.GROUND
                for ((px, py) in listOf(x0 to 0f, x1 to 0f, x1 to 1f, x0 to 0f, x1 to 1f, x0 to 1f)) verts.add(floatArrayOf(px, y0 + py, z, col[0], col[1], col[2]))
                i = e + 1
            }
        }
    }
    out.bufferedWriter().use { w ->
        w.write("ply\nformat ascii 1.0\nelement vertex ${verts.size}\nproperty float x\nproperty float y\nproperty float z\nproperty uchar red\nproperty uchar green\nproperty uchar blue\nelement face ${verts.size / 3}\nproperty list uchar int vertex_indices\nend_header\n")
        for (v in verts) w.write("%.3f %.3f %.3f %d %d %d\n".format(v[0], v[1], v[2], (v[3] * 255).toInt().coerceIn(0, 255), (v[4] * 255).toInt().coerceIn(0, 255), (v[5] * 255).toInt().coerceIn(0, 255)))
        for (i in 0 until verts.size / 3) w.write("3 ${i * 3} ${i * 3 + 1} ${i * 3 + 2}\n")
    }
    println("tiles $nTiles, game triangles $gameTris, preview triangles ${verts.size / 3}")
}
