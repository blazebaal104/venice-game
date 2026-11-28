// Boats tied up along the fondamenta (core/Moorings, core/BoatCatalog): placed per map tile around the camera,
// drawn as the shared hull of their type + their own merged parts, repainted through the generic shader's paint
// slots. They ride the waves at their lines, collide (core/Obstacles.moored) and sound like what they're made of.
package com.blazebaal.venice.render

import android.content.Context
import android.opengl.GLES30.*
import com.blazebaal.venice.core.*
import java.io.DataInputStream
import java.util.zip.InflaterInputStream
import kotlin.math.*

class MooredBoats(private val ctx: Context, private val world: WorldMap) {
    val catalog: BoatCatalog = try { BoatCatalog(ctx.assets.open("boats/catalog.txt").bufferedReader().readLines()) } catch (e: Exception) { BoatCatalog(emptyList()) }
    private val partData = HashMap<String, FloatArray?>()
    private val hulls = HashMap<String, Mesh?>()
    private val hullLods = HashMap<String, Mesh?>()
    private val covers = HashMap<String, Mesh?>()
    /** merged parts per variant (LRU: only the variants around the camera stay on the GPU) */
    private val kits = object : LinkedHashMap<Int, Mesh>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Int, Mesh>): Boolean { if (size > MAX_KITS) { e.value.release(); return true }; return false }
    }
    var decals = 0; private set
    private val tilePlaces = HashMap<Long, List<FloatArray>>()
    /** boats within reach this frame: x, y, heading, variant */
    private val near = ArrayList<FloatArray>()
    private var lastTx = Int.MIN_VALUE; private var lastTy = Int.MIN_VALUE
    private val model = FloatArray(16)
    private val paint = FloatArray(24)

    fun init(loadAtlas: (String) -> Int) { decals = loadAtlas("boats/decals.png") }

    private fun data(name: String): FloatArray? = partData.getOrPut(name) {
        try { DataInputStream(InflaterInputStream(ctx.assets.open("boats/parts/$name.mesh")).buffered(1 shl 15)).use { d -> val n = d.readInt(); FloatArray(n * STRIDE) { d.readFloat() } } }
        catch (e: Exception) { null }
    }
    private fun meshOf(map: HashMap<String, Mesh?>, name: String) = map.getOrPut(name) { data(name)?.let { Mesh(it) } }
    private fun kit(v: BoatCatalog.Variant): Mesh? {
        kits[v.id]?.let { return it }
        val d = BoatCatalog.mergeParts(v, { data(it) })
        if (d.isEmpty()) return null
        return Mesh(d).also { kits[v.id] = it }
    }

    /** the tiles around (x, y): placements computed once per tile; near-list and collision boxes refreshed */
    fun update(x: Float, y: Float, radius: Float) {
        if (catalog.variants.isEmpty()) return
        val (tx, ty) = world.tileCoords(x, y)
        val r = ceil(radius / TILE_SIZE).toInt()
        if (tx != lastTx || ty != lastTy) {
            lastTx = tx; lastTy = ty
            val keep = HashSet<Long>()
            for (j in ty - r..ty + r) for (i in tx - r..tx + r) {
                val k = (i.toLong() shl 32) or (j.toLong() and 0xffffffffL); keep.add(k)
                if (!tilePlaces.containsKey(k)) tilePlaces[k] = world.tile(i, j)?.let { try { Moorings.place(it, catalog) } catch (e: Throwable) { emptyList() } } ?: emptyList()
            }
            tilePlaces.keys.retainAll(keep)
        }
        near.clear()
        val r2 = radius * radius
        for (l in tilePlaces.values) for (b in l) { val dx = b[0] - x; val dy = b[1] - y; if (dx * dx + dy * dy < r2) near.add(b) }
        // collision boxes for the ones the player could reach: x, y, heading, half length, half beam, sound kind
        val boxes = ArrayList<FloatArray>()
        for (b in near) {
            if (abs(b[0] - x) > 120f || abs(b[1] - y) > 120f) continue
            val v = catalog.variants.getOrNull(b[3].toInt()) ?: continue
            boxes.add(floatArrayOf(b[0], b[1], b[2], v.len * 0.47f, v.beam * 0.5f, v.hitKind.toFloat()))
        }
        Obstacles.moored = boxes
    }

    /** generic program bound and common uniforms set by the caller; camera at (cx, cy) */
    fun draw(p: Program, cx: Float, cy: Float, time: Float, waveAmp: Float, maxDist: Float, lodDist: Float, reflection: Boolean,
             visible: (Float, Float, Float) -> Boolean) {
        if (near.isEmpty()) return
        glActiveTexture(GL_TEXTURE8); glBindTexture(GL_TEXTURE_2D, decals); p.i("uSign", 8)
        glUniform4f(p.loc("uSignRect"), 0f, 0f, 1f, 1f)
        p.f("uPaintOn", 1f)
        val loc = p.loc("uPaint")
        for (b in near) {
            val d = hypot(b[0] - cx, b[1] - cy)
            if (d > maxDist) continue
            val v = catalog.variants.getOrNull(b[3].toInt()) ?: continue
            if (!visible(b[0], b[1], v.len * 0.6f)) continue
            val lod = reflection || d > lodDist
            val hull = (if (lod) meshOf(hullLods, "hull_${v.base}_lod") else meshOf(hulls, "hull_${v.base}")) ?: continue
            pose(b, v, time, waveAmp)
            for (i in 0 until 24) paint[i] = v.paint[i]
            glUniform4fv(loc, 6, paint, 0)
            p.mat4("uModel", model)
            hull.draw()
            if (lod) { if (v.covered) meshOf(covers, "cover_${v.base}")?.draw() }
            else kit(v)?.draw()
        }
        p.f("uPaintOn", 0f)
    }

    /** riding at its lines: heave with the waves, a little roll and pitch, a slow yaw swing */
    private fun pose(b: FloatArray, v: BoatCatalog.Variant, time: Float, amp: Float) {
        val x = b[0]; val y = b[1]
        val ph = (x * 0.37f + y * 0.73f)
        val h0 = Waves.height(x, y, time, amp)
        val hb = Waves.height(x + cos(b[2]) * v.len * 0.4f, y + sin(b[2]) * v.len * 0.4f, time, amp)
        val hs = Waves.height(x - sin(b[2]) * v.beam * 0.5f, y + cos(b[2]) * v.beam * 0.5f, time, amp)
        val light = 1f / (1f + v.len * 0.12f)
        val z = h0 * 0.85f + 0.012f * sin(time * 1.1f + ph)
        val pitch = ((hb - h0) / (v.len * 0.4f)) * 0.6f + 0.004f * sin(time * 0.9f + ph) * light
        val roll = ((hs - h0) / (v.beam * 0.5f)) * 0.5f + 0.012f * sin(time * 1.3f + ph * 1.7f) * light
        val yaw = b[2] + 0.012f * sin(time * 0.21f + ph)
        val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = -sin(pitch); val cr = cos(roll); val sr = sin(roll)
        // R = Rz(yaw) * Ry(-pitch) * Rx(roll): bow up for positive pitch, port side up for positive roll
        val m00 = cp; val m01 = sp * sr; val m02 = sp * cr
        val m10 = 0f; val m11 = cr; val m12 = -sr
        val m20 = -sp; val m21 = cp * sr; val m22 = cp * cr
        model[0] = cy * m00 - sy * m10; model[1] = sy * m00 + cy * m10; model[2] = m20; model[3] = 0f
        model[4] = cy * m01 - sy * m11; model[5] = sy * m01 + cy * m11; model[6] = m21; model[7] = 0f
        model[8] = cy * m02 - sy * m12; model[9] = sy * m02 + cy * m12; model[10] = m22; model[11] = 0f
        model[12] = x; model[13] = y; model[14] = z; model[15] = 1f
    }

    companion object { const val MAX_KITS = 48 }
}
