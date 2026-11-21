// Writes venice.map: tiled, deflate-compressed binary map (own format "VMAP" v1, big-endian).
package converter

import java.awt.Color
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import javax.imageio.ImageIO
import kotlin.math.*

const val TILE = 256
const val MASK = TILE + 2 // one metre of margin on each side

object MapWriter {
    fun write(w: World, f: File) {
        val g = w.grid
        val tilesX = ceil(g.w / TILE.toDouble()).toInt(); val tilesY = ceil(g.h / TILE.toDouble()).toInt()
        class TileContent { val buildings = ArrayList<Building>(); val points = ArrayList<PointFeature>(); val bridges = ArrayList<Bridge>(); val piers = ArrayList<LinePier>(); val pierPolys = ArrayList<Poly>() }
        val content = HashMap<Int, TileContent>()
        fun tileOf(x: Double, y: Double): Int? {
            val tx = floor((x - g.minX) / TILE).toInt(); val ty = floor((y - g.minY) / TILE).toInt()
            if (tx < 0 || ty < 0 || tx >= tilesX || ty >= tilesY) return null
            return ty * tilesX + tx
        }
        fun tc(k: Int) = content.getOrPut(k) { TileContent() }
        for (b in w.buildings) { val (cx, cy) = b.poly.outer.centroid(); tileOf(cx, cy)?.let { tc(it).buildings.add(b) } }
        for (p in w.points) tileOf(p.x, p.y)?.let { tc(it).points.add(p) }
        for (br in w.bridges) tileOf(br.xs[0], br.ys[0])?.let { tc(it).bridges.add(br) }
        for (lp in w.linePiers) tileOf(lp.xs[0], lp.ys[0])?.let { tc(it).piers.add(lp) }
        for (pp in w.piers) { val (cx, cy) = pp.outer.centroid(); tileOf(cx, cy)?.let { tc(it).pierPolys.add(pp) } }

        val dirEntries = ArrayList<IntArray>()
        val blobs = ByteArrayOutputStream()
        var nMask = 0; var nWallPts = 0; var nWalls = 0; var roofFail = 0
        for (ty in 0 until tilesY) for (tx in 0 until tilesX) {
            val key = ty * tilesX + tx
            val ox = g.minX + tx * TILE; val oy = g.minY + ty * TILE
            // any land in or right around this tile?
            var anyLand = false
            run {
                val c0 = tx * TILE - 2; val r0 = ty * TILE - 2
                for (r in r0 until r0 + TILE + 4) { for (c in c0 until c0 + TILE + 4) if (g[c, r] != Cell.WATER) { anyLand = true; break }; if (anyLand) break }
            }
            val tcont = content[key]
            if (!anyLand && tcont == null) continue
            val raw = ByteArrayOutputStream(); val o = DataOutputStream(raw)
            if (anyLand) {
                nMask++
                val (hard, marsh, cls) = coverage(w, ox, oy)
                o.writeInt(MASK); o.write(hard); o.write(marsh); o.write(cls)
                val walls = walls(hard)
                o.writeInt(walls.size)
                for (pl in walls) { o.writeInt(pl.size / 2); for (v in pl) o.writeFloat(v.toFloat()); nWallPts += pl.size / 2 }
                nWalls += walls.size
            } else { o.writeInt(0); o.writeInt(0) }
            val bl = tcont?.buildings ?: emptyList()
            o.writeInt(bl.size)
            for (b in bl) {
                o.writeByte(b.kind); o.writeFloat(b.height)
                val (cx, cy) = b.poly.outer.centroid()
                o.writeInt((((cx * 10).toLong() * 73856093L) xor ((cy * 10).toLong() * 19349663L) xor ((cx * 10).toLong() * (cy * 10).toLong())).toInt())
                val rings = b.poly.rings()
                o.writeShort(rings.size)
                for (r in rings) o.writeShort(r.n)
                val flat = ArrayList<Double>()
                val holeStarts = ArrayList<Int>()
                var count = 0
                for ((ri, r) in rings.withIndex()) {
                    if (ri > 0) holeStarts.add(count)
                    for (i in 0 until r.n) { o.writeFloat((r.x[i] - ox).toFloat()); o.writeFloat((r.y[i] - oy).toFloat()); flat.add(r.x[i] - ox); flat.add(r.y[i] - oy) }
                    count += r.n
                }
                var tris = try { Earcut.triangulate(flat.toDoubleArray(), holeStarts.toIntArray()) } catch (e: Exception) { IntArray(0) }
                if (tris.isEmpty()) { roofFail++; tris = fan(rings[0].n) }
                // make every triangle counter-clockwise (facing up)
                val d = flat.toDoubleArray()
                for (t in 0 until tris.size / 3) {
                    val a = tris[t * 3]; val bb = tris[t * 3 + 1]; val c = tris[t * 3 + 2]
                    val ar = (d[bb * 2] - d[a * 2]) * (d[c * 2 + 1] - d[a * 2 + 1]) - (d[c * 2] - d[a * 2]) * (d[bb * 2 + 1] - d[a * 2 + 1])
                    if (ar < 0) { tris[t * 3 + 1] = c; tris[t * 3 + 2] = bb }
                }
                o.writeInt(tris.size); for (i in tris) o.writeShort(i)
            }
            val pts = tcont?.points ?: emptyList()
            o.writeInt(pts.size)
            for (p in pts) { o.writeByte(p.type); o.writeFloat((p.x - ox).toFloat()); o.writeFloat((p.y - oy).toFloat()); o.writeFloat(p.dir); o.writeUTF(if (p.type == FeatureType.STOP) p.name else "") }
            val brs = tcont?.bridges ?: emptyList()
            o.writeInt(brs.size)
            for (br in brs) {
                o.writeFloat(br.width); o.writeByte(if (br.steps) 1 else 0); o.writeShort(br.xs.size)
                for (i in br.xs.indices) { o.writeFloat((br.xs[i] - ox).toFloat()); o.writeFloat((br.ys[i] - oy).toFloat()) }
            }
            val lps = tcont?.piers ?: emptyList()
            o.writeInt(lps.size)
            for (lp in lps) { o.writeFloat(lp.width); o.writeShort(lp.xs.size); for (i in lp.xs.indices) { o.writeFloat((lp.xs[i] - ox).toFloat()); o.writeFloat((lp.ys[i] - oy).toFloat()) } }
            val pps = tcont?.pierPolys ?: emptyList()
            o.writeInt(pps.size)
            for (pp in pps) {
                val r = pp.outer
                o.writeShort(r.n); for (i in 0 until r.n) { o.writeFloat((r.x[i] - ox).toFloat()); o.writeFloat((r.y[i] - oy).toFloat()) }
                val flat = DoubleArray(r.n * 2) { if (it % 2 == 0) r.x[it / 2] - ox else r.y[it / 2] - oy }
                var tris = try { Earcut.triangulate(flat, IntArray(0)) } catch (e: Exception) { IntArray(0) }
                if (tris.isEmpty()) tris = fan(r.n)
                o.writeInt(tris.size); for (i in tris) o.writeShort(i)
            }
            o.flush()
            val comp = ByteArrayOutputStream()
            DeflaterOutputStream(comp, Deflater(9)).use { it.write(raw.toByteArray()) }
            dirEntries.add(intArrayOf(tx, ty, blobs.size(), comp.size()))
            blobs.write(comp.toByteArray())
        }
        DataOutputStream(f.outputStream().buffered()).use { o ->
            o.writeInt(0x564D4150) // "VMAP"
            o.writeInt(1)
            o.writeDouble(Proj.LAT0); o.writeDouble(Proj.LON0)
            o.writeFloat(g.minX.toFloat()); o.writeFloat(g.minY.toFloat())
            o.writeInt(tilesX); o.writeInt(tilesY); o.writeInt(TILE)
            o.writeInt(dirEntries.size)
            for (e in dirEntries) for (v in e) o.writeInt(v)
            o.write(blobs.toByteArray())
        }
        log("map: ${dirEntries.size} tiles ($nMask with land), $nWalls wall lines / $nWallPts pts, roof fallbacks $roofFail, ${f.length() / 1024} KB")
        minimap(w, File(f.parentFile, "minimap.png"), 3.0)
    }

    private fun fan(n: Int) = IntArray((n - 2) * 3) { val t = it / 3; when (it % 3) { 0 -> 0; 1 -> t + 1; else -> t + 2 } }

    /** Per-tile land coverage at 1 m from 4x4 supersampling. Returns hard-land coverage, marsh coverage, class. */
    fun coverage(w: World, ox: Double, oy: Double): Triple<ByteArray, ByteArray, ByteArray> {
        val ss = 4
        val sub = Grid(ox - 1, oy - 1, MASK * ss, MASK * ss, 1.0 / ss)
        val x0 = ox - 1; val y0 = oy - 1; val x1 = x0 + MASK; val y1 = y0 + MASK
        for (op in w.ops) {
            if (op.bb[2] < x0 || op.bb[0] > x1 || op.bb[3] < y0 || op.bb[1] > y1) continue
            sub.fill(op.rings, op.fn)
        }
        val hard = ByteArray(MASK * MASK); val marsh = ByteArray(MASK * MASK); val cls = ByteArray(MASK * MASK)
        val cnt = IntArray(5)
        for (j in 0 until MASK) for (i in 0 until MASK) {
            cnt.fill(0)
            for (sj in 0 until ss) for (si in 0 until ss) cnt[sub.a[(j * ss + sj) * sub.w + i * ss + si].toInt()]++
            val h = cnt[1] + cnt[2] + cnt[3]
            hard[j * MASK + i] = (h * 255 / (ss * ss)).toByte()
            marsh[j * MASK + i] = (cnt[4] * 255 / (ss * ss)).toByte()
            cls[j * MASK + i] = when { h == 0 -> 0; cnt[2] >= cnt[1] && cnt[2] >= cnt[3] -> 2; cnt[3] > cnt[1] -> 3; else -> 1 }.toByte()
        }
        return Triple(hard, marsh, cls)
    }

    /** Marching squares on the hard-land coverage; polylines (tile-local metres) with land on the left. */
    fun walls(hard: ByteArray): List<DoubleArray> {
        val t = 127.5
        fun v(i: Int, j: Int) = (hard[j * MASK + i].toInt() and 0xff).toDouble()
        fun px(i: Int) = i - 0.5 // sample i sits at tile-local x = i - 0.5
        val segs = ArrayList<DoubleArray>()
        for (j in 1..TILE) for (i in 1..TILE) {
            if (i + 1 >= MASK || j + 1 >= MASK) continue
            val a = v(i, j); val b = v(i + 1, j); val c = v(i + 1, j + 1); val d = v(i, j + 1)
            val code = (if (a > t) 1 else 0) or (if (b > t) 2 else 0) or (if (c > t) 4 else 0) or (if (d > t) 8 else 0)
            if (code == 0 || code == 15) continue
            val x = px(i); val y = px(j)
            fun lerp(p: Double, q: Double) = ((t - p) / (q - p)).coerceIn(0.0, 1.0)
            val B = doubleArrayOf(x + lerp(a, b), y)
            val R = doubleArrayOf(x + 1, y + lerp(b, c))
            val T = doubleArrayOf(x + 1 - lerp(c, d), y + 1)
            val L = doubleArrayOf(x, y + 1 - lerp(d, a))
            fun s(p: DoubleArray, q: DoubleArray) = segs.add(doubleArrayOf(p[0], p[1], q[0], q[1]))
            val center = (a + b + c + d) / 4 > t
            when (code) {
                1 -> s(B, L); 2 -> s(R, B); 4 -> s(T, R); 8 -> s(L, T)
                14 -> s(L, B); 13 -> s(B, R); 11 -> s(R, T); 7 -> s(T, L)
                3 -> s(R, L); 12 -> s(L, R); 6 -> s(T, B); 9 -> s(B, T)
                5 -> if (center) { s(B, R); s(T, L) } else { s(B, L); s(T, R) }
                10 -> if (center) { s(L, B); s(R, T) } else { s(R, B); s(L, T) }
            }
        }
        // chain segments into polylines
        fun k(x: Double, y: Double) = (Math.round(x * 1000)).shl(32) xor (Math.round(y * 1000) and 0xffffffffL)
        val byStart = HashMap<Long, ArrayList<Int>>()
        for ((i, sg) in segs.withIndex()) byStart.getOrPut(k(sg[0], sg[1])) { ArrayList() }.add(i)
        val hasPred = BooleanArray(segs.size)
        for (sg in segs) byStart[k(sg[2], sg[3])]?.forEach { hasPred[it] = true }
        val used = BooleanArray(segs.size)
        val out = ArrayList<DoubleArray>()
        fun follow(startIdx: Int) {
            val pts = ArrayList<Double>()
            var cur = startIdx
            pts.add(segs[cur][0]); pts.add(segs[cur][1])
            while (true) {
                used[cur] = true
                pts.add(segs[cur][2]); pts.add(segs[cur][3])
                val nxt = byStart[k(segs[cur][2], segs[cur][3])]?.firstOrNull { !used[it] } ?: break
                cur = nxt
            }
            out.add(simplifyLine(pts.toDoubleArray(), 0.22))
        }
        for (i in segs.indices) if (!used[i] && !hasPred[i]) follow(i)
        for (i in segs.indices) if (!used[i]) follow(i)
        return out
    }

    fun simplifyLine(p: DoubleArray, tol: Double): DoubleArray {
        val n = p.size / 2
        if (n <= 2) return p
        val keep = BooleanArray(n); keep[0] = true; keep[n - 1] = true
        val stack = ArrayDeque<IntArray>(); stack.add(intArrayOf(0, n - 1))
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var md = 0.0; var mi = -1
            val ax = p[a * 2]; val ay = p[a * 2 + 1]; val dx = p[b * 2] - ax; val dy = p[b * 2 + 1] - ay; val len = hypot(dx, dy)
            for (i in a + 1 until b) {
                val d = if (len < 1e-9) hypot(p[i * 2] - ax, p[i * 2 + 1] - ay) else abs(dy * (p[i * 2] - ax) - dx * (p[i * 2 + 1] - ay)) / len
                if (d > md) { md = d; mi = i }
            }
            if (md > tol && mi > 0) { keep[mi] = true; stack.add(intArrayOf(a, mi)); stack.add(intArrayOf(mi, b)) }
        }
        val r = ArrayList<Double>()
        for (i in 0 until n) if (keep[i]) { r.add(p[i * 2]); r.add(p[i * 2 + 1]) }
        return r.toDoubleArray()
    }

    fun minimap(w: World, f: File, mPerPx: Double) {
        val g = w.grid
        val iw = (g.w / mPerPx).toInt(); val ih = (g.h / mPerPx).toInt()
        val img = BufferedImage(iw, ih, BufferedImage.TYPE_INT_RGB)
        val col = intArrayOf(0x1d4f57, 0xd8cdb8, 0x7d9a5c, 0xe6d6a4, 0x6f8762)
        for (py in 0 until ih) for (px in 0 until iw) img.setRGB(px, py, col[g.atWorld(g.minX + (px + 0.5) * mPerPx, g.minY + g.h - (py + 0.5) * mPerPx)])
        val gr = img.createGraphics()
        gr.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        gr.color = Color(0xb4, 0x9c, 0x88)
        for (b in w.buildings) {
            val r = b.poly.outer
            val p = Path2D.Double()
            p.moveTo((r.x[0] - g.minX) / mPerPx, ih - (r.y[0] - g.minY) / mPerPx)
            for (i in 1 until r.n) p.lineTo((r.x[i] - g.minX) / mPerPx, ih - (r.y[i] - g.minY) / mPerPx)
            p.closePath(); gr.fill(p)
        }
        gr.dispose()
        ImageIO.write(img, "png", f)
        log("minimap ${f.name} ${iw}x$ih ${f.length() / 1024} KB")
    }
}
