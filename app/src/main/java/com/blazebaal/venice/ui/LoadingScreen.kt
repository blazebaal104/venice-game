// The loading screen: Venice at sunset, drawn live on the Canvas while the game loads underneath.
// Sky gradient with a low sun and drifting clouds, the San Marco basin skyline (Campanile, the Basilica's domes,
// Palazzo Ducale, the Salute, San Giorgio) in two depth layers, the lagoon below mirroring it all with a wavy
// reflection and a shimmering sun path, a few gulls, and now and then a gondola or a vaporetto crossing with its wake.
// Progress bar + what is loading right now at the bottom. Everything is procedural (no image assets).
package com.blazebaal.venice.ui

import android.graphics.*
import kotlin.math.*

class LoadingScreen(private val dp: Float) {
    private var w = 0f; private var h = 0f
    private val far = Path(); private val near = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private var skyShader: Shader? = null; private var waterShader: Shader? = null; private var sunGlow: Shader? = null
    private var horizon = 0f
    private val rnd = java.util.Random(11)
    private val glints = FloatArray(90 * 3)
    // the boat currently crossing: type 0 gondola, 1 vaporetto; x from the left edge, direction
    private var boatT = -1f; private var boatType = 1; private var boatDir = 1; private var boatSpeed = 0f; private var nextBoat = 0.25f; private var firstBoat = true
    private var shown = 0f

    private fun layout(width: Float, height: Float) {
        if (width == w && height == h) return
        w = width; h = height
        horizon = h * 0.64f
        skyShader = LinearGradient(0f, 0f, 0f, horizon, intArrayOf(
            Color.rgb(20, 18, 52), Color.rgb(74, 34, 88), Color.rgb(176, 64, 92), Color.rgb(246, 132, 72), Color.rgb(255, 196, 110)),
            floatArrayOf(0f, 0.32f, 0.62f, 0.86f, 1f), Shader.TileMode.CLAMP)
        waterShader = LinearGradient(0f, horizon, 0f, h, intArrayOf(
            Color.rgb(150, 70, 78), Color.rgb(70, 32, 62), Color.rgb(16, 14, 34)), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        buildSkyline()
        for (i in 0 until 90) { glints[i * 3] = rnd.nextFloat(); glints[i * 3 + 1] = rnd.nextFloat(); glints[i * 3 + 2] = rnd.nextFloat() * 6.28f }
    }

    /** skyline paths in screen space, standing on the horizon line */
    private fun buildSkyline() {
        val u = w / 100f            // one "unit" = 1% of the width
        val hz = horizon
        fun Path.box(x0: Float, x1: Float, top: Float) { lineTo(x0 * u, hz - top * u); lineTo(x1 * u, hz - top * u) }
        // far layer: the Giudecca / Lido haze - low roofs and a couple of towers
        far.reset(); far.moveTo(0f, hz)
        var x = 0f; val r = java.util.Random(5)
        while (x < 100f) {
            val bw = 2f + r.nextFloat() * 4f; val top = 1.2f + r.nextFloat() * 1.6f
            far.box(x, x + bw, top)
            if (r.nextFloat() < 0.12f) { val tx = x + bw * 0.5f; far.lineTo(tx * u, hz - top * u); far.lineTo(tx * u, hz - (top + 4.5f) * u); far.lineTo((tx + 0.8f) * u, hz - (top + 5.3f) * u); far.lineTo((tx + 1.6f) * u, hz - (top + 4.5f) * u); far.lineTo((tx + 1.6f) * u, hz - top * u) }
            x += bw
        }
        far.lineTo(w, hz); far.close()
        // near layer, left to right: houses, Palazzo Ducale, Basilica domes, the Campanile, the Procuratie,
        // the mouth of the Grand Canal, then the Salute and the Dogana, San Giorgio on its island to the right
        near.reset(); near.moveTo(0f, hz)
        x = 0f
        while (x < 14f) { val bw = 1.6f + r.nextFloat() * 2.2f; near.box(x, x + bw, 3.2f + r.nextFloat() * 2f); x += bw }
        // Palazzo Ducale: long block with crenellation teeth
        near.lineTo(14f * u, hz - 5.6f * u)
        var cx = 14f
        while (cx < 26f) { near.lineTo(cx * u, hz - 6.1f * u); near.lineTo((cx + 0.35f) * u, hz - 6.1f * u); near.lineTo((cx + 0.35f) * u, hz - 5.6f * u); cx += 0.7f; near.lineTo(cx * u, hz - 5.6f * u) }
        // Basilica: five domes with lanterns behind
        near.lineTo(26f * u, hz - 6.5f * u)
        for ((dx, rr) in listOf(27.2f to 1.3f, 29.6f to 1.7f, 32.0f to 1.3f)) {
            val ox = dx * u; val oy = hz - 6.5f * u; val rad = rr * u
            near.lineTo(ox - rad, oy)
            near.arcTo(RectF(ox - rad, oy - rad * 1.25f, ox + rad, oy + rad * 1.25f), 180f, 180f, false)
            near.lineTo(ox, oy - rad * 1.25f); near.lineTo(ox, oy - rad * 1.25f - 0.9f * u); near.lineTo(ox, oy - rad * 1.25f)
        }
        near.lineTo(33.5f * u, hz - 6.5f * u); near.lineTo(33.5f * u, hz - 5f * u)
        // the Campanile: shaft, belfry, the green pyramid and the angel
        near.lineTo(35f * u, hz - 5f * u); near.lineTo(35f * u, hz - 20f * u); near.lineTo(34.7f * u, hz - 20.2f * u); near.lineTo(34.7f * u, hz - 23.2f * u)
        near.lineTo(35f * u, hz - 23.4f * u); near.lineTo(36.6f * u, hz - 29.5f * u); near.lineTo(36.6f * u, hz - 30.6f * u)
        near.lineTo(36.6f * u, hz - 29.5f * u); near.lineTo(38.2f * u, hz - 23.4f * u); near.lineTo(38.5f * u, hz - 23.2f * u)
        near.lineTo(38.5f * u, hz - 20.2f * u); near.lineTo(38.2f * u, hz - 20f * u); near.lineTo(38.2f * u, hz - 5f * u)
        // Procuratie and the Zecca, then the low skyline down to the canal mouth
        near.box(38.2f, 47f, 5.2f); near.box(47f, 52f, 4.2f); near.lineTo(52f * u, hz - 1.2f * u)
        near.lineTo(56f * u, hz - 1.2f * u)
        // Salute: big dome with lantern, drum, the smaller dome and two bell towers behind
        near.lineTo(56f * u, hz - 4f * u); near.box(56f, 59f, 4f); near.box(59f, 60f, 7.5f); near.box(60f, 60.6f, 9f)
        run {
            val ox = 64f * u; val oy = hz - 9f * u; val rad = 3.6f * u
            near.lineTo(ox - rad, oy); near.arcTo(RectF(ox - rad, oy - rad * 1.15f, ox + rad, oy + rad * 1.15f), 180f, 180f, false)
            near.lineTo(ox + 0.6f * u, oy - rad * 1.15f); near.lineTo(ox + 0.6f * u, oy - rad * 1.15f - 1.6f * u)
            near.lineTo(ox, oy - rad * 1.15f - 2.4f * u); near.lineTo(ox - 0.6f * u, oy - rad * 1.15f - 1.6f * u); near.lineTo(ox - 0.6f * u, oy - rad * 1.15f)
            near.lineTo(ox + rad, oy)
        }
        near.box(67.6f, 68.4f, 9f); near.box(68.4f, 70f, 7f)
        run { val ox = 71.5f * u; val oy = hz - 7f * u; val rad = 1.6f * u; near.lineTo(ox - rad, oy); near.arcTo(RectF(ox - rad, oy - rad, ox + rad, oy + rad), 180f, 180f, false) }
        near.box(73.1f, 73.6f, 9.5f); near.box(73.6f, 76f, 4f)
        // Dogana point, then open water, then San Giorgio: church front + campanile
        near.box(76f, 79f, 2.4f); near.lineTo(79f * u, hz)
        near.lineTo(86f * u, hz); near.lineTo(86f * u, hz - 3f * u); near.box(86f, 90f, 4.5f)
        run { val ox = 91.5f * u; val oy = hz - 4.5f * u; val rad = 1.5f * u; near.lineTo(ox - rad, oy); near.arcTo(RectF(ox - rad, oy - rad * 1.2f, ox + rad, oy + rad * 1.2f), 180f, 180f, false) }
        near.box(93f, 95f, 4.5f); near.box(95f, 96.2f, 13.5f); near.lineTo(95.6f * u, hz - 15.5f * u); near.lineTo(96.2f * u, hz - 13.5f * u)
        near.box(96.2f, 100f, 2.5f); near.lineTo(w, hz); near.close()
    }

    /** draws one frame; t = seconds since shown, progress 0..1, alpha for the final fade */
    fun draw(c: Canvas, width: Float, height: Float, dt: Float, progress: Float, stage: String, alpha: Float, error: String) {
        layout(width, height)
        shown += dt
        val t = shown
        val save = c.saveLayerAlpha(0f, 0f, w, h, (alpha * 255).toInt())
        // ---- sky, sun, glow, clouds
        paint.shader = skyShader; c.drawRect(0f, 0f, w, horizon, paint); paint.shader = null
        val sunX = w * 0.70f; val sunY = horizon - h * 0.08f + sin(t * 0.15f) * h * 0.004f; val sunR = h * 0.085f
        if (sunGlow == null) sunGlow = RadialGradient(0f, 0f, sunR * 5f, intArrayOf(Color.argb(150, 255, 190, 110), Color.argb(50, 255, 140, 80), Color.argb(0, 255, 120, 80)), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        c.save(); c.translate(sunX, sunY); paint.shader = sunGlow; c.drawCircle(0f, 0f, sunR * 5f, paint); paint.shader = null; c.restore()
        paint.color = Color.rgb(255, 226, 160); c.drawCircle(sunX, sunY, sunR, paint)
        for (k in 0 until 5) {
            val cx = ((k * 0.27f + t * (0.006f + k * 0.0015f)) % 1.3f - 0.15f) * w
            val cy = h * (0.12f + k * 0.075f)
            paint.color = Color.argb(70 - k * 8, 255, 170 - k * 12, 150); c.drawRoundRect(RectF(cx, cy, cx + w * (0.22f - k * 0.02f), cy + h * 0.018f), h * 0.01f, h * 0.01f, paint)
            paint.color = Color.argb(40, 120, 50, 90); c.drawRoundRect(RectF(cx + w * 0.03f, cy + h * 0.014f, cx + w * (0.18f - k * 0.02f), cy + h * 0.024f), h * 0.01f, h * 0.01f, paint)
        }
        // gulls
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.6f * dp; paint.color = Color.argb(150, 40, 20, 45); paint.strokeCap = Paint.Cap.ROUND
        for (k in 0 until 4) {
            val gx = ((k * 0.31f + t * 0.018f * (1f + k * 0.2f)) % 1.2f - 0.1f) * w; val gy = h * (0.22f + 0.05f * k) + sin(t * 0.7f + k) * h * 0.01f
            val flap = sin(t * 5f + k * 1.7f) * 0.6f; val s = (5f + k) * dp
            val g = Path(); g.moveTo(gx - s, gy - s * 0.3f * (1 + flap)); g.quadTo(gx - s * 0.4f, gy - s * 0.5f, gx, gy); g.quadTo(gx + s * 0.4f, gy - s * 0.5f, gx + s, gy - s * 0.3f * (1 + flap))
            c.drawPath(g, paint)
        }
        paint.style = Paint.Style.FILL
        // ---- skyline
        paint.color = Color.rgb(112, 48, 82); c.drawPath(far, paint)
        paint.color = Color.rgb(44, 20, 46); c.drawPath(near, paint)
        // ---- water: gradient, the skyline's reflection broken into wavy bands, the sun path glinting
        paint.shader = waterShader; c.drawRect(0f, horizon, w, h, paint); paint.shader = null
        val bands = 14; val bandH = (h - horizon) * 0.5f / bands
        for (b in 0 until bands) {
            val y0 = horizon + b * bandH
            c.save(); c.clipRect(0f, y0, w, y0 + bandH + 1f)
            c.translate(sin(t * 1.3f + b * 0.9f) * (1.5f + b * 0.5f) * dp, 0f)
            c.scale(1f, -1f, 0f, horizon)
            paint.color = Color.argb(max(0, 120 - b * 8), 40, 18, 44); c.drawPath(near, paint)
            c.restore()
        }
        for (i in 0 until 90) {
            val fy = glints[i * 3 + 1]
            val y = horizon + (h - horizon) * (0.02f + fy * fy * 0.95f)
            val spread = (0.03f + fy * 0.1f) * w
            val x = sunX + (glints[i * 3] - 0.5f) * 2f * spread
            val a = (0.5f + 0.5f * sin(t * 3f + glints[i * 3 + 2])).pow(3f)
            val len = (6f + 22f * fy) * dp
            paint.color = Color.argb((a * 200 * (1f - fy * 0.6f)).toInt(), 255, 214, 150)
            c.drawRect(x - len / 2, y, x + len / 2, y + (1f + fy * 1.5f) * dp, paint)
        }
        // ---- a boat now and then: gondola or vaporetto, crossing with a little bow wave and a wake
        if (boatT < 0f) {
            nextBoat -= dt
            if (nextBoat <= 0f) {
                // the first one is a vaporetto already on its way across the middle of the lagoon, so it's always seen
                boatT = if (firstBoat) 3.5f else 0f
                boatType = if (firstBoat) 1 else if (rnd.nextFloat() < 0.5f) 0 else 1
                boatDir = if (firstBoat) 1 else if (rnd.nextBoolean()) 1 else -1
                boatSpeed = if (boatType == 0) 0.045f else 0.07f; firstBoat = false
            }
        }
        if (boatT >= 0f) {
            boatT += dt
            val f = boatT * boatSpeed
            val bx = if (boatDir > 0) -0.15f * w + f * w * 1.3f else 1.15f * w - f * w * 1.3f
            val by = horizon + (h - horizon) * (if (boatType == 0) 0.48f else 0.40f) + sin(boatT * 1.6f) * 1.2f * dp
            drawBoat(c, bx, by, boatType, boatDir, boatT)
            if (f > 1f) { boatT = -1f; nextBoat = 6f + rnd.nextFloat() * 8f }
        }
        // ---- title and progress
        val m = 26 * dp
        text.typeface = Theme.black; text.textSize = 46 * dp; text.color = Color.argb(235, 255, 244, 230); text.textScaleX = 1.0f
        text.setShadowLayer(10 * dp, 0f, 3 * dp, Color.argb(120, 40, 10, 30))
        c.drawText("GTACTV", m, m + 44 * dp, text)
        text.typeface = Theme.condensedBold; text.textSize = 11 * dp; text.color = Theme.YELLOW; text.letterSpacing = 0.2f
        c.drawText("VENEZIA · LAGUNA · SCALA 1:1", m + 2 * dp, m + 64 * dp, text)
        text.letterSpacing = 0f; text.clearShadowLayer()
        val barW = min(w * 0.5f, 520 * dp); val bx0 = (w - barW) / 2; val by0 = h - m - 6 * dp
        paint.color = Color.argb(70, 255, 255, 255); c.drawRoundRect(RectF(bx0, by0, bx0 + barW, by0 + 4 * dp), 2 * dp, 2 * dp, paint)
        paint.color = Theme.YELLOW; c.drawRoundRect(RectF(bx0, by0, bx0 + barW * progress.coerceIn(0.02f, 1f), by0 + 4 * dp), 2 * dp, 2 * dp, paint)
        // a little light running along the filled part
        val run = ((t * 0.6f) % 1f) * barW * progress
        paint.color = Color.argb(160, 255, 255, 255); c.drawRect(bx0 + run - 10 * dp, by0, bx0 + run + 10 * dp, by0 + 4 * dp, paint)
        text.typeface = Theme.condensedBold; text.textSize = 12 * dp; text.color = Color.argb(225, 255, 240, 225); text.textAlign = Paint.Align.LEFT
        text.letterSpacing = 0.08f
        c.drawText(stage.uppercase() + "…", bx0, by0 - 10 * dp, text)
        text.textAlign = Paint.Align.RIGHT
        c.drawText("${(progress * 100).roundToInt()}%", bx0 + barW, by0 - 10 * dp, text)
        text.textAlign = Paint.Align.LEFT; text.letterSpacing = 0f
        if (error.isNotEmpty()) {
            text.textSize = 10 * dp; text.color = Theme.RED; text.textAlign = Paint.Align.CENTER
            c.drawText(error.take(140), w / 2, by0 + 18 * dp, text); text.textAlign = Paint.Align.LEFT
        }
        c.restoreToCount(save)
    }

    private fun drawBoat(c: Canvas, x: Float, y: Float, type: Int, dir: Int, t: Float) {
        val s = dp * (if (type == 0) 1.35f else 1.8f)
        c.save(); c.translate(x, y); c.scale(dir.toFloat(), 1f)
        // the wake: a V of foam opening behind the stern, broken into dashes that shimmer, plus the bow wave
        for (k in 1..14) {
            val wx = -26f * s - k * 11f * s; val spread = k * 2.1f * s
            val a = ((150 - k * 9) * (0.75f + 0.25f * sin(t * 4f + k))).toInt().coerceAtLeast(0)
            paint.color = Color.argb(a, 255, 214, 175)
            c.drawRect(wx - 5f * s, 2f * s + spread, wx + 5f * s, 3.2f * s + spread, paint)
            c.drawRect(wx - 5f * s, 2f * s - spread * 0.25f, wx + 5f * s, 2.8f * s - spread * 0.25f, paint)
            if (k < 6) { paint.color = Color.argb(a / 2, 255, 230, 200); c.drawRect(wx - 7f * s, 2.2f * s, wx + 7f * s, 3.4f * s, paint) }
        }
        paint.color = Color.argb(170, 255, 225, 190); c.drawRect(24f * s, 1.5f * s, 30f * s, 3f * s, paint)
        paint.color = Color.rgb(30, 14, 34)
        if (type == 0) {
            // gondola: long crescent hull, the ferro at the bow, the gondolier rowing at the stern
            val p = Path()
            p.moveTo(-34f * s, -6f * s); p.quadTo(-20f * s, 3f * s, 0f, 3f * s); p.quadTo(20f * s, 3f * s, 34f * s, -7f * s)
            p.lineTo(32f * s, -6f * s); p.quadTo(18f * s, -0.5f * s, 0f, -0.5f * s); p.quadTo(-18f * s, -0.5f * s, -32f * s, -5f * s); p.close()
            c.drawPath(p, paint)
            c.drawRect(32f * s, -12f * s, 33.6f * s, -6f * s, paint)                                   // ferro
            val lean = sin(t * 1.4f) * 2f * s
            c.drawRect(-26f * s + lean, -15f * s, -23.5f * s + lean, -1f * s, paint)                 // gondolier
            c.drawCircle(-24.8f * s + lean, -17f * s, 1.8f * s, paint)
            paint.strokeWidth = 1.2f * s; paint.style = Paint.Style.STROKE
            c.drawLine(-24f * s + lean, -11f * s, -16f * s + lean * 2f, 2f * s, paint)                 // oar
            paint.style = Paint.Style.FILL
        } else {
            // vaporetto: hull, cabin with windows, wheelhouse, a puff of exhaust
            c.drawRoundRect(RectF(-26f * s, -3f * s, 26f * s, 3f * s), 3f * s, 3f * s, paint)
            c.drawRect(-15f * s, -11f * s, 12f * s, -3f * s, paint)
            c.drawRect(12f * s, -9f * s, 17f * s, -3f * s, paint)
            paint.color = Color.argb(200, 255, 190, 120)
            var wx = -13.5f * s; while (wx < 10f * s) { c.drawRect(wx, -9f * s, wx + 2.6f * s, -6.4f * s, paint); wx += 3.6f * s }
            paint.color = Color.argb(60, 120, 90, 110)
            for (k in 0..2) c.drawCircle(-14f * s - k * 5f * s - (t * 3f % 5f) * s, -13f * s - k * 2.5f * s, (2f + k) * s, paint)
        }
        c.restore()
    }
}
