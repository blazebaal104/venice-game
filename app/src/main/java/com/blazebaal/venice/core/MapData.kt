// Reads venice.map (format written by tools/converter, "VMAP" v1). Pure Kotlin + java.io/java.util.zip.
package com.blazebaal.venice.core

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.util.zip.InflaterInputStream

const val TILE_SIZE = 256
const val MASK_N = TILE_SIZE + 2

object Feature { const val BENCH = 1; const val WELL = 2; const val BRICOLA = 3; const val TREE = 4; const val LAMP = 5; const val STOP = 6; const val FLAGPOLE = 7; const val BIN = 8; const val FOUNTAIN = 9 }

class BuildingData(val kind: Int, val height: Float, val seed: Int, val ringSizes: IntArray, val xy: FloatArray, val roofTris: ShortArray)
class PointData(val type: Int, val x: Float, val y: Float, val dir: Float, val name: String)
class LineData(val width: Float, val steps: Boolean, val xy: FloatArray)
class PolyData(val xy: FloatArray, val tris: ShortArray)

class TileData(
    val tx: Int, val ty: Int, val ox: Float, val oy: Float,
    /** land coverage 0..255 (hard ground), marsh coverage, ground class; MASK_N x MASK_N, sample i at local (i-0.5) m */
    val hard: ByteArray?, val marsh: ByteArray?, val cls: ByteArray?,
    val walls: List<FloatArray>,
    val buildings: List<BuildingData>,
    val points: List<PointData>,
    val bridges: List<LineData>,
    val piers: List<LineData>,
    val pierPolys: List<PolyData>,
) {
    /** bilinear coverage (0..1) of the barene (salt marsh, vegetation in shallow water) at tile-local metres */
    fun marshAt(lx: Float, ly: Float): Float {
        val m = marsh ?: return 0f
        val fx = lx + 0.5f; val fy = ly + 0.5f
        val i = fx.toInt().coerceIn(0, MASK_N - 2); val j = fy.toInt().coerceIn(0, MASK_N - 2)
        val ax = (fx - i).coerceIn(0f, 1f); val ay = (fy - j).coerceIn(0f, 1f)
        fun s(ii: Int, jj: Int) = (m[jj * MASK_N + ii].toInt() and 0xff) / 255f
        return (s(i, j) * (1 - ax) + s(i + 1, j) * ax) * (1 - ay) + (s(i, j + 1) * (1 - ax) + s(i + 1, j + 1) * ax) * ay
    }

    /** bilinear coverage (0..1) of solid ground at tile-local metres. The barene are NOT solid: a boat can go into
     *  them (and get its prop tangled), see marshAt */
    fun solidAt(lx: Float, ly: Float): Float {
        if (hard == null) return 0f
        val fx = lx + 0.5f; val fy = ly + 0.5f
        val i = fx.toInt().coerceIn(0, MASK_N - 2); val j = fy.toInt().coerceIn(0, MASK_N - 2)
        val ax = (fx - i).coerceIn(0f, 1f); val ay = (fy - j).coerceIn(0f, 1f)
        fun s(ii: Int, jj: Int): Float {
            val k = jj * MASK_N + ii
            return (hard[k].toInt() and 0xff) / 255f
        }
        val a = s(i, j) * (1 - ax) + s(i + 1, j) * ax
        val b = s(i, j + 1) * (1 - ax) + s(i + 1, j + 1) * ax
        return a * (1 - ay) + b * ay
    }
}

class MapData(input: InputStream) {
    val lat0: Double; val lon0: Double
    val minX: Float; val minY: Float
    val tilesX: Int; val tilesY: Int
    private val dir = HashMap<Int, IntArray>()
    private val blob: ByteArray

    init {
        val all = input.readBytes()
        val d = DataInputStream(ByteArrayInputStream(all))
        require(d.readInt() == 0x564D4150) { "not a VMAP file" }
        require(d.readInt() == 1)
        lat0 = d.readDouble(); lon0 = d.readDouble()
        minX = d.readFloat(); minY = d.readFloat()
        tilesX = d.readInt(); tilesY = d.readInt(); d.readInt()
        val n = d.readInt()
        for (i in 0 until n) { val tx = d.readInt(); val ty = d.readInt(); val off = d.readInt(); val len = d.readInt(); dir[ty * tilesX + tx] = intArrayOf(off, len) }
        val headerLen = 4 * 2 + 8 * 2 + 4 * 2 + 4 * 3 + 4 + n * 16
        blob = all.copyOfRange(headerLen, all.size)
    }

    fun hasTile(tx: Int, ty: Int) = tx in 0 until tilesX && ty in 0 until tilesY && dir.containsKey(ty * tilesX + tx)
    fun tileKeys(): Set<Int> = dir.keys
    fun tileOrigin(tx: Int, ty: Int) = Pair(minX + tx * TILE_SIZE, minY + ty * TILE_SIZE)

    fun load(tx: Int, ty: Int): TileData? {
        val e = dir[ty * tilesX + tx] ?: return null
        val d = DataInputStream(InflaterInputStream(ByteArrayInputStream(blob, e[0], e[1])).buffered(65536))
        val (ox, oy) = tileOrigin(tx, ty)
        val maskN = d.readInt()
        var hard: ByteArray? = null; var marsh: ByteArray? = null; var cls: ByteArray? = null
        if (maskN > 0) {
            hard = ByteArray(maskN * maskN); d.readFully(hard)
            marsh = ByteArray(maskN * maskN); d.readFully(marsh)
            cls = ByteArray(maskN * maskN); d.readFully(cls)
        }
        val walls = List(d.readInt()) { val n = d.readInt(); FloatArray(n * 2) { d.readFloat() } }
        val buildings = List(d.readInt()) {
            val kind = d.readByte().toInt(); val h = d.readFloat(); val seed = d.readInt()
            val nr = d.readShort().toInt()
            val sizes = IntArray(nr) { d.readShort().toInt() }
            val xy = FloatArray(sizes.sum() * 2) { d.readFloat() }
            val tris = ShortArray(d.readInt()) { d.readShort() }
            BuildingData(kind, h, seed, sizes, xy, tris)
        }
        val points = List(d.readInt()) { PointData(d.readByte().toInt(), d.readFloat(), d.readFloat(), d.readFloat(), d.readUTF()) }
        val bridges = List(d.readInt()) { val w = d.readFloat(); val st = d.readByte().toInt() == 1; val n = d.readShort().toInt(); LineData(w, st, FloatArray(n * 2) { d.readFloat() }) }
        val piers = List(d.readInt()) { val w = d.readFloat(); val n = d.readShort().toInt(); LineData(w, false, FloatArray(n * 2) { d.readFloat() }) }
        val pierPolys = List(d.readInt()) { val n = d.readShort().toInt(); val xy = FloatArray(n * 2) { d.readFloat() }; PolyData(xy, ShortArray(d.readInt()) { d.readShort() }) }
        d.close()
        return TileData(tx, ty, ox, oy, hard, marsh, cls, walls, buildings, points, bridges, piers, pierPolys)
    }
}
