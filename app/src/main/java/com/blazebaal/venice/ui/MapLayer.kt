// The map of Venice used by the minimap and the full-screen map: assets/minimap.png (3 m/px, drawn by the converter
// from the same OpenStreetMap data the game world is built from, so the map matches the world 1:1 and works
// offline). A half-resolution copy stays in memory for overviews; when zoomed in, the visible area is decoded at
// full resolution with BitmapRegionDecoder so it stays sharp. Labels: sestieri, islands, landmarks, ACTV stops.
package com.blazebaal.venice.ui

import android.content.Context
import android.graphics.*
import com.blazebaal.venice.core.Places
import kotlin.math.*

object MapLayer {
    const val M_PER_PX = 3f            // full-resolution asset
    @Volatile var half: Bitmap? = null; private set     // 6 m/px
    var fullW = 0; var fullH = 0
    private var decoder: BitmapRegionDecoder? = null
    class Stop(val name: String, val x: Float, val y: Float)
    @Volatile var stops: List<Stop> = emptyList(); private set

    // sharp region cache (full-res pixels)
    @Volatile var region: Bitmap? = null; private set
    @Volatile var regionRect = Rect(); private set
    @Volatile private var regionBusy = false

    // satellite-style imagery of the historic centre (assets/map_aerial.jpg, 1.5 m/px, rendered from the game's own
    // map data: roofs with height shadows, canals, campi, gardens) drawn over the plain map where it exists
    const val AX0 = -2950f; const val AY1 = 1450f; const val A_M_PER_PX = 1.5f
    @Volatile var aerialSmall: Bitmap? = null; private set           // 1/4 size overview
    private var aerialDecoder: BitmapRegionDecoder? = null
    var aerialW = 0; var aerialH = 0
    @Volatile var aRegion: Bitmap? = null; private set
    @Volatile var aRect = Rect(); private set
    @Volatile private var aBusy = false

    fun load(ctx: Context, onReady: () -> Unit) {
        if (half != null) { onReady(); return }
        Thread {
            try {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.assets.open("minimap.png").use { BitmapFactory.decodeStream(it, null, o) }
                fullW = o.outWidth; fullH = o.outHeight
                val opts = BitmapFactory.Options().apply { inSampleSize = 2; inPreferredConfig = Bitmap.Config.RGB_565 }
                half = ctx.assets.open("minimap.png").use { BitmapFactory.decodeStream(it, null, opts) }
                @Suppress("DEPRECATION")
                decoder = try { ctx.assets.open("minimap.png").use { BitmapRegionDecoder.newInstance(it, false) } } catch (_: Exception) { null }
                try {
                    val ao = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    ctx.assets.open("map_aerial.jpg").use { BitmapFactory.decodeStream(it, null, ao) }
                    aerialW = ao.outWidth; aerialH = ao.outHeight
                    aerialSmall = ctx.assets.open("map_aerial.jpg").use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 4; inPreferredConfig = Bitmap.Config.RGB_565 }) }
                    @Suppress("DEPRECATION")
                    aerialDecoder = ctx.assets.open("map_aerial.jpg").use { BitmapRegionDecoder.newInstance(it, false) }
                } catch (_: Exception) { }
                stops = try {
                    ctx.assets.open("landmarks/actv.txt").bufferedReader().readLines().mapNotNull { l ->
                        val p = l.split('\t'); if (p.size < 3) null else Stop(p[0], p[1].toFloat(), p[2].toFloat())
                    }.distinctBy { it.name }
                } catch (_: Exception) { emptyList() }
            } catch (_: Exception) { }
            onReady()
        }.start()
    }

    /** world metres -> full-resolution map pixels */
    fun px(x: Float) = (x - GameState.minX) / M_PER_PX
    fun py(y: Float) = fullH - (y - GameState.minY) / M_PER_PX
    fun wx(px: Float) = px * M_PER_PX + GameState.minX
    fun wy(py: Float) = (fullH - py) * M_PER_PX + GameState.minY

    /** Ask for a sharp decode of `want` (full-res pixel rect); arrives asynchronously, then `done` is called. */
    fun requestRegion(want: Rect, done: () -> Unit) {
        requestAerial(want, done)
        val d = decoder ?: return
        if (regionBusy) return
        val r = Rect(max(0, want.left), max(0, want.top), min(fullW, want.right), min(fullH, want.bottom))
        if (r.width() <= 0 || r.height() <= 0) return
        if (region != null && regionRect.contains(r)) return
        // decode a margin around the view so small pans don't need a new decode
        val mx = r.width() / 2; val my = r.height() / 2
        val big = Rect(max(0, r.left - mx), max(0, r.top - my), min(fullW, r.right + mx), min(fullH, r.bottom + my))
        if (big.width().toLong() * big.height() > 9_000_000L) return
        regionBusy = true
        Thread {
            try {
                val b = d.decodeRegion(big, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
                val old = region
                region = b; regionRect = big
                old?.recycle()
            } catch (_: Exception) { }
            regionBusy = false
            done()
        }.start()
    }

    fun dropRegion() { val r = region; region = null; r?.recycle(); val a = aRegion; aRegion = null; a?.recycle() }

    /** map-pixel space (3 m/px) -> aerial pixel space (1.5 m/px) */
    private fun aOffX() = (AX0 - GameState.minX) / M_PER_PX
    private fun aOffY() = fullH - (AY1 - GameState.minY) / M_PER_PX
    private val aK = M_PER_PX / A_M_PER_PX

    private fun requestAerial(want: Rect, done: () -> Unit) {
        val d = aerialDecoder ?: return
        if (aBusy) return
        val l = ((want.left - aOffX()) * aK).toInt(); val t = ((want.top - aOffY()) * aK).toInt()
        val r0 = ((want.right - aOffX()) * aK).toInt(); val b0 = ((want.bottom - aOffY()) * aK).toInt()
        val r = Rect(max(0, l), max(0, t), min(aerialW, r0), min(aerialH, b0))
        if (r.width() <= 0 || r.height() <= 0) return
        if (aRegion != null && aRect.contains(r)) return
        val mx = r.width() / 2; val my = r.height() / 2
        val big = Rect(max(0, r.left - mx), max(0, r.top - my), min(aerialW, r.right + mx), min(aerialH, r.bottom + my))
        if (big.width().toLong() * big.height() > 6_000_000L) return
        aBusy = true
        Thread {
            try {
                val b = d.decodeRegion(big, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
                val old = aRegion; aRegion = b; aRect = big; old?.recycle()
            } catch (_: Exception) { }
            aBusy = false
            done()
        }.start()
    }

    // ---------------------------------------------------------------- drawing
    /** Draws the map so that world point (cx, cy) is at the canvas origin, `pxPerM` screen pixels per metre,
     *  rotated by `rotDeg` (screen up = north when 0). Call inside a clipped canvas. */
    fun draw(ink: Ink, cx: Float, cy: Float, pxPerM: Float, rotDeg: Float, sharp: Boolean) {
        val c = ink.c; val h = half ?: return
        val save = c.save()
        c.rotate(rotDeg)
        val s = pxPerM * M_PER_PX                // screen px per full-res map px
        c.scale(s, s)
        c.translate(-px(cx), -py(cy))
        // overview from the half-res bitmap
        c.save(); c.scale(2f, 2f); c.drawBitmap(h, 0f, 0f, ink.bmp); c.restore()
        // sharp overlay when zoomed in past the half-res detail
        val rg = region
        if (sharp && rg != null && !rg.isRecycled) c.drawBitmap(rg, regionRect.left.toFloat(), regionRect.top.toFloat(), ink.bmp)
        // aerial imagery on top where it exists
        aerialSmall?.let { a ->
            c.save(); c.translate(aOffX(), aOffY()); c.scale(1f / aK, 1f / aK)
            c.save(); c.scale(aerialW.toFloat() / a.width, aerialH.toFloat() / a.height); c.drawBitmap(a, 0f, 0f, ink.bmp); c.restore()
            val ar = aRegion
            if (sharp && ar != null && !ar.isRecycled) c.drawBitmap(ar, aRect.left.toFloat(), aRect.top.toFloat(), ink.bmp)
            c.restore()
        }
        c.restoreToCount(save)
    }

    /** Screen position of world point (x, y) for the same transform as draw(), relative to the canvas origin. */
    fun toScreen(x: Float, y: Float, cx: Float, cy: Float, pxPerM: Float, rotDeg: Float): PointF {
        val dx = (x - cx) * pxPerM; val dy = -(y - cy) * pxPerM
        val a = Math.toRadians(rotDeg.toDouble())
        return PointF((dx * cos(a) - dy * sin(a)).toFloat(), (dx * sin(a) + dy * cos(a)).toFloat())
    }

    fun named() = Places.named(GameState.lat0, GameState.lon0)
}
