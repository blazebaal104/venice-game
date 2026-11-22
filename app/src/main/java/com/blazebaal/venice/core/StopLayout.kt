// Where each ACTV stop goes (from landmarks/actv.txt): pontoon model by length, the gangway turned towards the quay,
// gangway extensions that reach the fondamenta, and the solid boxes the boat collides with. Pure Kotlin (preview too).
package com.blazebaal.venice.core

import kotlin.math.*

object StopLayout {
    class Stop(val x: Float, val y: Float, val rot: Float, val len: Int, val slot: Int)
    class Result(val stops: List<Stop>, val boxes: List<FloatArray>, val extras: MeshBuilder)

    fun build(lines: List<String>, world: WorldMap): Result {
        val lens = intArrayOf(12, 16, 22)
        val stops = ArrayList<Stop>(); val boxes = ArrayList<FloatArray>(); val extra = MeshBuilder(8192)
        for (line in lines) {
            val p = line.split('\t'); if (p.size < 7) continue
            val x = p[1].toFloat(); val y = p[2].toFloat(); val side = p[5].toInt(); val slot = p[6].toInt()
            var rot = Math.toRadians(p[3].toDouble() + (if (side < 0) 180.0 else 0.0)).toFloat()
            val len = p[4].toFloat()
            // the gangway side (+Y) must face the quay: flip the pontoon if the land is clearly on the other side
            fun landAt(dir: Float): Float { for (k in 0..80) { val d = 2f + k * 0.5f; if (world.solid(x - sin(dir) * d, y + cos(dir) * d) > 0.5f) return d }; return 99f }
            if (landAt(rot + PI.toFloat()) + 3f < landAt(rot)) rot += PI.toFloat()
            val lenM = lens.minByOrNull { abs(it - len) }!!
            stops.add(Stop(x, y, rot, lenM, slot))
            boxes.add(floatArrayOf(x, y, rot, lenM / 2f + 0.35f, 2.75f))
            val gx = lenM / 2f - min(5f, lenM * 0.3f) + 1.4f
            boxes.add(floatArrayOf(x + cos(rot) * gx - sin(rot) * 5.1f, y + sin(rot) * gx + cos(rot) * 5.1f, rot, 1.1f, 2.9f))
            // extend the gangway until it actually reaches the fondamenta
            val c = cos(rot); val sn = sin(rot)
            var reach = 7.9f
            while (reach < 40f && world.solid(x + c * gx - sn * reach, y + sn * gx + c * reach) < 0.5f) reach += 0.5f
            if (reach > 8.4f && reach < 40f) {
                val y0 = 7.8f; val y1 = reach + 0.6f; val z = 1.25f
                fun P(lx: Float, ly: Float, lz: Float) = Vec3(x + c * lx - sn * ly, y + sn * lx + c * ly, lz)
                extra.mat(L.PLANKS, 0.85f, 0.78f, 0.7f)
                extra.quad(P(gx - 1f, y0, z), P(gx + 1f, y0, z), P(gx + 1f, y1, z), P(gx - 1f, y1, z))
                extra.color(0.62f, 0.64f, 0.66f, 0.4f)
                for (sd in intArrayOf(-1, 1)) {
                    extra.quad(P(gx + sd * 1f, y0, z - 0.3f), P(gx + sd * 1f, y1, z - 0.3f), P(gx + sd * 1f, y1, z + 0.02f), P(gx + sd * 1f, y0, z + 0.02f))
                    var yy = y0
                    while (yy <= y1) { val q = P(gx + sd * 0.97f, yy, 0f); extra.box(q.x, q.y, z + 0.52f, 0.025f, 0.025f, 0.52f, rot); yy += 1.5f }
                    val a = P(gx + sd * 0.97f, (y0 + y1) / 2, 0f)
                    extra.box(a.x, a.y, z + 1.04f, 0.03f, (y1 - y0) / 2, 0.03f, rot)
                    extra.box(a.x, a.y, z + 0.52f, 0.02f, (y1 - y0) / 2, 0.02f, rot)
                }
                extra.color(0.20f, 0.17f, 0.14f, 0.1f)
                var yy = y0 + 1.5f
                while (yy < y1 - 0.5f) { for (sd in intArrayOf(-1, 1)) { val q = P(gx + sd * 1.1f, yy, 0f); extra.frustum(q.x, q.y, Z.WALL_BOTTOM, 0.12f, 0.11f, z - Z.WALL_BOTTOM, 6) }; yy += 3f }
                val mid = P(gx, (y0 + y1) / 2, 0f)
                boxes.add(floatArrayOf(mid.x, mid.y, rot, 1.3f, (y1 - y0) / 2))
            }
        }
        return Result(stops, boxes, extra)
    }
}
