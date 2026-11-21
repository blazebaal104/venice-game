// Reader for the Copernicus Building Block Height GeoTIFF (tiled, LZW, horizontal predictor, uint16)
// plus the ETRS89-LAEA (EPSG:3035) projection. Own code, JDK only.
package converter

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class HeightRaster(file: File) {
    val w: Int; val h: Int
    val originX: Double; val originY: Double; val px: Double
    val data: ShortArray

    init {
        val b = ByteBuffer.wrap(file.readBytes())
        b.order(if (b.get(0).toInt() == 'I'.code) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        val ifd = b.getInt(4)
        val n = b.getShort(ifd).toInt() and 0xffff
        val tags = HashMap<Int, LongArray>()
        val dtags = HashMap<Int, DoubleArray>()
        for (k in 0 until n) {
            val e = ifd + 2 + k * 12
            val tag = b.getShort(e).toInt() and 0xffff
            val type = b.getShort(e + 2).toInt() and 0xffff
            val cnt = b.getInt(e + 4)
            val size = when (type) { 3 -> 2; 4 -> 4; 12 -> 8; 16 -> 8; else -> 1 }
            val off = if (size * cnt <= 4) e + 8 else b.getInt(e + 8)
            if (type == 12) dtags[tag] = DoubleArray(cnt) { b.getDouble(off + it * 8) }
            else if (type == 3) tags[tag] = LongArray(cnt) { (b.getShort(off + it * 2).toInt() and 0xffff).toLong() }
            else if (type == 4) tags[tag] = LongArray(cnt) { (b.getInt(off + it * 4).toLong() and 0xffffffffL) }
            else if (type == 16) tags[tag] = LongArray(cnt) { b.getLong(off + it * 8) }
        }
        w = tags[256]!![0].toInt(); h = tags[257]!![0].toInt()
        require(tags[259]!![0] == 5L) { "expected LZW" }
        val predictor = tags[317]?.get(0) ?: 1L
        val tw = tags[322]!![0].toInt(); val th = tags[323]!![0].toInt()
        val offs = tags[324]!!; val cnts = tags[325]!!
        px = dtags[33550]!![0]
        originX = dtags[33922]!![3]; originY = dtags[33922]!![4]
        data = ShortArray(w * h) { -1 }
        val tilesAcross = (w + tw - 1) / tw
        for (t in offs.indices) {
            val raw = ByteArray(cnts[t].toInt()); b.position(offs[t].toInt()); b.get(raw)
            val dec = lzw(raw, tw * th * 2)
            val tb = ByteBuffer.wrap(dec).order(b.order())
            val tx0 = (t % tilesAcross) * tw; val ty0 = (t / tilesAcross) * th
            for (yy in 0 until th) {
                var prev = 0
                for (xx in 0 until tw) {
                    var v = tb.getShort((yy * tw + xx) * 2).toInt() and 0xffff
                    if (predictor == 2L) { v = (v + prev) and 0xffff; prev = v }
                    val gx = tx0 + xx; val gy = ty0 + yy
                    if (gx < w && gy < h) data[gy * w + gx] = v.toShort()
                }
            }
        }
    }

    private fun lzw(src: ByteArray, expected: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(expected)
        val dict = arrayOfNulls<ByteArray>(4096)
        for (i in 0 until 256) dict[i] = byteArrayOf(i.toByte())
        var next = 258; var bits = 9
        var bitPos = 0L
        var prev: ByteArray? = null
        fun read(): Int {
            var v = 0
            for (k in 0 until bits) {
                val byteIdx = (bitPos shr 3).toInt()
                if (byteIdx >= src.size) return 257
                val bit = (src[byteIdx].toInt() shr (7 - (bitPos and 7).toInt())) and 1
                v = (v shl 1) or bit; bitPos++
            }
            return v
        }
        while (true) {
            val code = read()
            if (code == 257) break
            if (code == 256) { next = 258; bits = 9; prev = null; continue }
            val entry: ByteArray = if (code < next && dict[code] != null) dict[code]!! else prev!! + prev!![0]
            out.write(entry)
            if (prev != null && next < 4096) { dict[next++] = prev!! + entry[0] }
            prev = entry
            if (next + 1 >= (1 shl bits) && bits < 12) bits++
        }
        return out.toByteArray()
    }

    /** Height in metres at lon/lat (WGS84 ~ ETRS89), or -1 for no data. */
    fun heightAt(lon: Double, lat: Double): Int {
        val (x, y) = Laea.forward(lon, lat)
        val c = floor((x - originX) / px).toInt(); val r = floor((originY - y) / px).toInt()
        if (c < 0 || r < 0 || c >= w || r >= h) return -1
        val v = data[r * w + c].toInt() and 0xffff
        return if (v == 65535) -1 else v
    }
}

/** EPSG:3035 ETRS89 / LAEA Europe, ellipsoidal forward (Snyder). */
object Laea {
    private const val a = 6378137.0
    private const val e2 = 0.00669438002290
    private val e = sqrt(e2)
    private val lat0 = Math.toRadians(52.0); private val lon0 = Math.toRadians(10.0)
    private const val FE = 4321000.0; private const val FN = 3210000.0
    private fun q(phi: Double): Double { val s = sin(phi); return (1 - e2) * (s / (1 - e2 * s * s) - (1 / (2 * e)) * ln((1 - e * s) / (1 + e * s))) }
    private val qp = q(PI / 2)
    private val beta0 = asin(q(lat0) / qp)
    private val rq = a * sqrt(qp / 2)
    private val d = a * (cos(lat0) / sqrt(1 - e2 * sin(lat0).pow(2))) / (rq * cos(beta0))
    fun forward(lonDeg: Double, latDeg: Double): Pair<Double, Double> {
        val phi = Math.toRadians(latDeg); val lam = Math.toRadians(lonDeg)
        val beta = asin(q(phi) / qp)
        val bb = rq * sqrt(2 / (1 + sin(beta0) * sin(beta) + cos(beta0) * cos(beta) * cos(lam - lon0)))
        val x = FE + bb * d * cos(beta) * sin(lam - lon0)
        val y = FN + (bb / d) * (cos(beta0) * sin(beta) - sin(beta0) * cos(beta) * cos(lam - lon0))
        return Pair(x, y)
    }
}
