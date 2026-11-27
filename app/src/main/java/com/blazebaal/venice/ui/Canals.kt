// Canal names drawn on the water of the minimap and the big map, like a road map: light blue, along the canal.
// Anchor points are in game metres, checked against the map's water (tools: snapped to the middle of the canal).
package com.blazebaal.venice.ui

import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import kotlin.math.*

object Canals {
    class Canal(val name: String, val pts: FloatArray)
    val all = listOf(
        Canal("Canal Grande", floatArrayOf(-1616f, 578f, -1343f, 773f, -1007f, 896f, -770f, 980f, -596f, 791f, -392f, 725f, -296f, 524f, -443f, 203f, -617f, 65f, -908f, 5f, -935f, -184f, -821f, -256f, -551f, -328f, -332f, -295f)),
        Canal("Canale della Giudecca", floatArrayOf(-1724f, -550f, -1097f, -583f, -482f, -553f, -80f, -439f)),
        Canal("Bacino di San Marco", floatArrayOf(37f, -307f, 385f, -376f)),
        Canal("Canale di Cannaregio", floatArrayOf(-1253f, 830f, -1409f, 1148f, -1478f, 1352f)),
        Canal("Rio Novo", floatArrayOf(-1391f, 209f, -1163f, 152f, -956f, 50f)),
        Canal("Canale di San Pietro", floatArrayOf(1210f, 173f, 1489f, 56f)),
        Canal("Rio della Misericordia", floatArrayOf(-662f, 1205f, -407f, 1220f)),
    )
    val COLOR = Color.rgb(150, 214, 255)

    /** draws the names: toScreen maps world metres to the canvas; labels further apart than minGapPx on screen */
    fun draw(ink: Ink, size: Float, rotDeg: Float, minGapPx: Float, toScreen: (Float, Float) -> PointF, inView: (PointF) -> Boolean) {
        val c = ink.c
        for (cn in all) {
            val n = cn.pts.size / 2
            var lastX = Float.NaN; var lastY = Float.NaN
            for (i in 0 until n) {
                // label at each anchor, oriented along the canal there (previous -> next anchor)
                val a = max(0, i - 1); val b = min(n - 1, i + 1)
                if (n > 2 && (i == 0 || i == n - 1)) continue
                val p = toScreen(cn.pts[i * 2], cn.pts[i * 2 + 1]); if (!inView(p)) continue
                if (!lastX.isNaN() && hypot(p.x - lastX, p.y - lastY) < minGapPx) continue
                val pa = toScreen(cn.pts[a * 2], cn.pts[a * 2 + 1]); val pb = toScreen(cn.pts[b * 2], cn.pts[b * 2 + 1])
                var ang = Math.toDegrees(atan2((pb.y - pa.y).toDouble(), (pb.x - pa.x).toDouble())).toFloat()
                if (ang > 90f) ang -= 180f; if (ang < -90f) ang += 180f          // never upside down
                c.save(); c.rotate(ang, p.x, p.y)
                ink.text.setShadowLayer(3f * ink.dp, 0f, 0f, Color.argb(200, 10, 40, 60))
                ink.label(cn.name, p.x, p.y + size * 0.35f * ink.dp, size, COLOR, Paint.Align.CENTER, Theme.condensedBold, 0.06f)
                ink.text.clearShadowLayer()
                c.restore()
                lastX = p.x; lastY = p.y
            }
        }
    }
}
