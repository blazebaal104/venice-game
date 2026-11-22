// HUD + touch controls, drawn with Android Canvas over the GL view.
// Layout: round speedometer + square map (top left), place / temperature / clock pill and camera / pause buttons
// (top centre), trip distance (top right), settings, engine and trim buttons with the throttle lever ("manetta",
// right), left / right steering arrows (bottom left). Pause menu with the graphics settings.
package com.blazebaal.venice.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import java.util.Calendar
import kotlin.math.*

class HudView(ctx: Context) : View(ctx) {
    private val dp = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val bold = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = bold }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255); textSize = 8.5f * resources.displayMetrics.density }
    private val PANEL = Color.argb(205, 18, 30, 58)
    private val PANEL_EDGE = Color.argb(90, 255, 255, 255)
    private val ACCENT = Color.rgb(64, 196, 255)
    private var minimap: Bitmap? = null
    private val mmMetresPerPx = 6f // asset is 3 m/px, decoded at half size
    private val path = Path(); private val rect = RectF()

    // touch tracking
    private var leverId = -1; private var leftId = -1; private var rightId = -1; private var camId = -1
    private var camLx = 0f; private var camLy = 0f
    private val tempC: Int

    init {
        Thread {
            try {
                val opts = BitmapFactory.Options().apply { inSampleSize = 2; inPreferredConfig = Bitmap.Config.RGB_565 }
                minimap = ctx.assets.open("minimap.png").use { BitmapFactory.decodeStream(it, null, opts) }
                postInvalidate()
            } catch (_: Exception) { }
        }.start()
        // typical afternoon temperature in Venice for the current month
        val m = Calendar.getInstance().get(Calendar.MONTH)
        tempC = intArrayOf(7, 9, 13, 17, 22, 26, 28, 28, 24, 18, 12, 8)[m]
        val tick = object : Runnable { override fun run() { invalidate(); postDelayed(this, 33) } }
        post(tick)
    }

    // ---------------------------------------------------------------- layout
    private val margin get() = 14 * dp
    private fun speedoC() = PointF(margin + 46 * dp, margin + 46 * dp)
    private fun mapRect() = RectF(margin + 100 * dp, margin + 2 * dp, margin + 190 * dp, margin + 92 * dp)
    private fun leverRect() = RectF(width - margin - 46 * dp, height * 0.40f, width - margin, height - margin - 34 * dp)
    private fun leftArrow() = RectF(margin + 4 * dp, height - margin - 70 * dp, margin + 74 * dp, height - margin)
    private fun rightArrow() = RectF(margin + 86 * dp, height - margin - 70 * dp, margin + 156 * dp, height - margin)
    private fun roundBtn(i: Int) = PointF(width / 2f + 92 * dp + i * 46 * dp, margin + 22 * dp)   // camera, pause
    private fun sideBtn(i: Int): PointF { val x = width - margin - 23 * dp - 58 * dp; return PointF(x, height * 0.40f + 18 * dp + i * 46 * dp) } // gear, power, +, -
    private fun pauseItems(): List<Pair<RectF, String>> {
        val cx = width / 2f; val w = 300 * dp; val top = height / 2f - 120 * dp
        val out = ArrayList<Pair<RectF, String>>()
        out.add(Pair(RectF(cx - w / 2, top, cx + w / 2, top + 44 * dp), "resume"))
        val q = listOf("Prestazioni", "Bilanciata", "Qualità")
        for (i in 0..2) { val x0 = cx - w / 2 + i * (w / 3); out.add(Pair(RectF(x0 + 3 * dp, top + 84 * dp, x0 + w / 3 - 3 * dp, top + 124 * dp), "q$i:${q[i]}")) }
        for ((i, f) in listOf(60, 120).withIndex()) { val x0 = cx - w / 2 + i * (w / 2); out.add(Pair(RectF(x0 + 3 * dp, top + 162 * dp, x0 + w / 2 - 3 * dp, top + 202 * dp), "f$f")) }
        out.add(Pair(RectF(cx - w / 2, top + 214 * dp, cx + w / 2, top + 250 * dp), "fps"))
        return out
    }

    // ---------------------------------------------------------------- input
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val idx = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val id = e.getPointerId(idx); val x = e.getX(idx); val y = e.getY(idx)
                if (Controls.paused) { pauseTap(x, y); invalidate(); return true }
                val lr = leverRect()
                when {
                    RectF(lr.left - 16 * dp, lr.top - 20 * dp, lr.right + 8 * dp, lr.bottom + 20 * dp).contains(x, y) -> { leverId = id; setLever(y) }
                    leftArrow().contains(x, y) -> { leftId = id; Controls.leftHeld = true }
                    rightArrow().contains(x, y) -> { rightId = id; Controls.rightHeld = true }
                    hit(roundBtn(0), x, y) -> Controls.cameraMode = (Controls.cameraMode + 1) % 3
                    hit(roundBtn(1), x, y) || hit(sideBtn(0), x, y) -> Controls.paused = true
                    hit(sideBtn(1), x, y) -> { Controls.engineOn = !Controls.engineOn; if (!Controls.engineOn) Controls.lever = 0f }
                    hit(sideBtn(2), x, y) -> Controls.trim = (Controls.trim + 0.1f).coerceAtMost(1f)
                    hit(sideBtn(3), x, y) -> Controls.trim = (Controls.trim - 0.1f).coerceAtLeast(0f)
                    camId < 0 -> { camId = id; camLx = x; camLy = y }
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                val id = e.getPointerId(i); val x = e.getX(i); val y = e.getY(i)
                if (id == leverId) setLever(y)
                else if (id == camId) {
                    Controls.camYawDelta += -(x - camLx) * 0.006f
                    Controls.camPitchDelta += (y - camLy) * 0.004f
                    camLx = x; camLy = y
                } else if (id == leftId && !leftArrow().contains(x, y)) { leftId = -1; Controls.leftHeld = false }
                else if (id == rightId && !rightArrow().contains(x, y)) { rightId = -1; Controls.rightHeld = false }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val id = e.getPointerId(idx)
                if (e.actionMasked == MotionEvent.ACTION_CANCEL) release(-2) else release(id)
            }
        }
        return true
    }

    private fun hit(c: PointF, x: Float, y: Float) = hypot(x - c.x, y - c.y) < 24 * dp

    /** lever travel: top = full ahead, a neutral notch, bottom fifth = reverse */
    private fun setLever(y: Float) {
        if (!Controls.engineOn) return
        val r = leverRect()
        val t = ((r.bottom - y) / r.height()).coerceIn(0f, 1f)      // 0 bottom .. 1 top
        val n = 0.2f                                                   // neutral position
        var v = if (t >= n) (t - n) / (1f - n) else -(n - t) / n * 0.6f
        if (abs(t - n) < 0.025f) v = 0f
        Controls.lever = v.coerceIn(-0.6f, 1f)
    }

    private fun release(id: Int) {
        if (id == leftId || id == -2) { leftId = -1; Controls.leftHeld = false }
        if (id == rightId || id == -2) { rightId = -1; Controls.rightHeld = false }
        if (id == leverId || id == -2) leverId = -1
        if (id == camId || id == -2) camId = -1
    }

    private fun pauseTap(x: Float, y: Float) {
        for ((r, k) in pauseItems()) if (r.contains(x, y)) {
            when {
                k == "resume" -> Controls.paused = false
                k.startsWith("q") -> Settings.quality = k[1].digitToInt()
                k.startsWith("f") && k != "fps" -> Settings.fpsTarget = k.substring(1).toInt()
                k == "fps" -> Settings.showFps = !Settings.showFps
            }
            Settings.save(context)
            return
        }
    }

    // ---------------------------------------------------------------- drawing
    private fun panel(r: RectF, radius: Float) {
        fill.color = PANEL; fill.style = Paint.Style.FILL; c.drawRoundRect(r, radius, radius, fill)
        stroke.color = PANEL_EDGE; stroke.strokeWidth = 1.2f * dp; c.drawRoundRect(r, radius, radius, stroke)
    }
    private lateinit var c: Canvas

    override fun onDraw(canvas: Canvas) {
        c = canvas
        drawSpeedo()
        drawMinimap()
        drawTopCentre()
        drawDistance()
        drawRightColumn()
        drawArrows()
        small.textAlign = Paint.Align.CENTER
        c.drawText("Map data © OpenStreetMap contributors (ODbL) · Regione del Veneto – L.R. n. 28/76 · Copernicus Land Monitoring Service", width / 2f, height - 5 * dp, small)
        if (Settings.showFps) {
            small.textAlign = Paint.Align.LEFT
            c.drawText("${GameState.fps.roundToInt()} fps · ${(GameState.renderScale * 100).roundToInt()}%", margin + 4 * dp, margin + 108 * dp, small)
        }
        if (GameState.loading) { text.textSize = 20 * dp; text.textAlign = Paint.Align.CENTER; c.drawText("Caricamento di Venezia…", width / 2f, height / 2f, text) }
        if (Controls.paused) drawPause()
    }

    private fun drawSpeedo() {
        val ctr = speedoC(); val r = 46 * dp
        fill.color = PANEL; fill.style = Paint.Style.FILL; c.drawCircle(ctr.x, ctr.y, r, fill)
        stroke.color = PANEL_EDGE; stroke.strokeWidth = 1.2f * dp; c.drawCircle(ctr.x, ctr.y, r, stroke)
        // dial: 240 degree sweep, ticks every 10 km/h, progress arc
        val a0 = 150f; val sweep = 240f
        rect.set(ctr.x - r + 7 * dp, ctr.y - r + 7 * dp, ctr.x + r - 7 * dp, ctr.y + r - 7 * dp)
        stroke.strokeWidth = 3.5f * dp; stroke.color = Color.argb(60, 255, 255, 255); c.drawArc(rect, a0, sweep, false, stroke)
        val f = (GameState.speedKmh / 130f).coerceIn(0f, 1f)
        stroke.color = ACCENT; c.drawArc(rect, a0, sweep * f, false, stroke)
        stroke.strokeWidth = 1.2f * dp; stroke.color = Color.argb(150, 255, 255, 255)
        for (k in 0..13) {
            val a = Math.toRadians((a0 + sweep * k / 13f).toDouble())
            val r1 = r - 14 * dp; val r2 = r - (if (k % 2 == 0) 19 else 17) * dp
            c.drawLine(ctr.x + cos(a).toFloat() * r1, ctr.y + sin(a).toFloat() * r1, ctr.x + cos(a).toFloat() * r2, ctr.y + sin(a).toFloat() * r2, stroke)
        }
        text.textAlign = Paint.Align.CENTER; text.textSize = 26 * dp
        c.drawText("${GameState.speedKmh.roundToInt()}", ctr.x, ctr.y + 9 * dp, text)
        small.textAlign = Paint.Align.CENTER; small.typeface = bold
        c.drawText("KM/H", ctr.x, ctr.y + 24 * dp, small)
        small.typeface = Typeface.DEFAULT
    }

    private fun drawMinimap() {
        val r = mapRect(); val rad = 14 * dp
        panel(r, rad)
        val bmp = minimap ?: return
        val save = c.save()
        path.reset(); path.addRoundRect(RectF(r.left + 3 * dp, r.top + 3 * dp, r.right - 3 * dp, r.bottom - 3 * dp), rad - 3 * dp, rad - 3 * dp, Path.Direction.CW)
        c.clipPath(path)
        val cx = r.centerX(); val cy = r.centerY()
        val px = (GameState.x - GameState.minX) / mmMetresPerPx
        val py = bmp.height - (GameState.y - GameState.minY) / mmMetresPerPx
        c.translate(cx, cy)
        c.rotate(Math.toDegrees(GameState.heading.toDouble()).toFloat() - 90f) // heading-up
        val s = 0.95f * dp
        c.scale(s, s)
        c.drawBitmap(bmp, -px, -py, null)
        c.restoreToCount(save)
        // arrow
        fill.color = Color.rgb(255, 205, 40)
        path.reset(); path.moveTo(cx, cy - 9 * dp); path.lineTo(cx + 6 * dp, cy + 7 * dp); path.lineTo(cx, cy + 3 * dp); path.lineTo(cx - 6 * dp, cy + 7 * dp); path.close()
        c.drawPath(path, fill)
        stroke.color = Color.argb(160, 0, 0, 0); stroke.strokeWidth = 1f * dp; c.drawPath(path, stroke)
        // north marker on the rim
        val na = GameState.heading - PI.toFloat()
        val nx = (cx + cos(na) * 36 * dp).coerceIn(r.left + 9 * dp, r.right - 9 * dp); val ny = (cy + sin(na) * 36 * dp + 4 * dp).coerceIn(r.top + 12 * dp, r.bottom - 4 * dp)
        text.textSize = 11 * dp; text.textAlign = Paint.Align.CENTER; c.drawText("N", nx, ny, text)
    }

    private fun drawTopCentre() {
        val cx = width / 2f
        val r = RectF(cx - 62 * dp, margin, cx + 62 * dp, margin + 44 * dp)
        panel(r, 12 * dp)
        small.textAlign = Paint.Align.CENTER; small.typeface = bold
        c.drawText("VENEZIA · $tempC°", cx, r.top + 15 * dp, small)
        small.typeface = Typeface.DEFAULT
        val cal = Calendar.getInstance()
        text.textAlign = Paint.Align.CENTER; text.textSize = 19 * dp
        val t = String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        c.drawText(t, cx + 8 * dp, r.top + 37 * dp, text)
        // little sun
        val sx = cx - 30 * dp; val sy = r.top + 30 * dp
        fill.color = Color.WHITE; c.drawCircle(sx, sy, 3.5f * dp, fill)
        stroke.color = Color.WHITE; stroke.strokeWidth = 1.4f * dp
        for (k in 0 until 8) { val a = k * PI / 4; c.drawLine(sx + cos(a).toFloat() * 5.5f * dp, sy + sin(a).toFloat() * 5.5f * dp, sx + cos(a).toFloat() * 7.5f * dp, sy + sin(a).toFloat() * 7.5f * dp, stroke) }
        // round buttons: camera, pause
        for (i in 0..1) {
            val p = roundBtn(i)
            fill.color = PANEL; c.drawCircle(p.x, p.y, 20 * dp, fill)
            stroke.color = PANEL_EDGE; stroke.strokeWidth = 1.2f * dp; c.drawCircle(p.x, p.y, 20 * dp, stroke)
            stroke.color = Color.WHITE; stroke.strokeWidth = 2f * dp
            if (i == 0) {   // camera
                rect.set(p.x - 9 * dp, p.y - 5 * dp, p.x + 9 * dp, p.y + 7 * dp); c.drawRoundRect(rect, 2.5f * dp, 2.5f * dp, stroke)
                c.drawLine(p.x - 4 * dp, p.y - 5 * dp, p.x - 2 * dp, p.y - 8 * dp, stroke); c.drawLine(p.x - 2 * dp, p.y - 8 * dp, p.x + 3 * dp, p.y - 8 * dp, stroke); c.drawLine(p.x + 3 * dp, p.y - 8 * dp, p.x + 5 * dp, p.y - 5 * dp, stroke)
                c.drawCircle(p.x, p.y + 1 * dp, 3.2f * dp, stroke)
            } else {        // pause
                fill.color = Color.WHITE
                c.drawRect(p.x - 6 * dp, p.y - 7 * dp, p.x - 2 * dp, p.y + 7 * dp, fill); c.drawRect(p.x + 2 * dp, p.y - 7 * dp, p.x + 6 * dp, p.y + 7 * dp, fill)
            }
        }
    }

    private fun drawDistance() {
        val r = RectF(width - margin - 120 * dp, margin, width - margin, margin + 44 * dp)
        panel(r, 12 * dp)
        small.textAlign = Paint.Align.CENTER; small.typeface = bold
        c.drawText("DISTANZA", r.centerX(), r.top + 15 * dp, small)
        small.typeface = Typeface.DEFAULT
        val d = GameState.distanceM
        val s = if (d < 1000f) "${d.roundToInt()} m" else String.format("%.1f km", d / 1000f).replace('.', ',')
        text.textAlign = Paint.Align.CENTER; text.textSize = 19 * dp
        c.drawText(s, r.centerX(), r.top + 37 * dp, text)
    }

    private fun drawRightColumn() {
        // buttons: settings, engine power, trim + / -
        for (i in 0..3) {
            val p = sideBtn(i)
            fill.color = PANEL; c.drawCircle(p.x, p.y, 20 * dp, fill)
            stroke.color = PANEL_EDGE; stroke.strokeWidth = 1.2f * dp; c.drawCircle(p.x, p.y, 20 * dp, stroke)
            stroke.color = Color.WHITE; stroke.strokeWidth = 2f * dp
            when (i) {
                0 -> { // gear
                    c.drawCircle(p.x, p.y, 4 * dp, stroke)
                    for (k in 0 until 8) { val a = k * PI / 4; c.drawLine(p.x + cos(a).toFloat() * 6.5f * dp, p.y + sin(a).toFloat() * 6.5f * dp, p.x + cos(a).toFloat() * 9.5f * dp, p.y + sin(a).toFloat() * 9.5f * dp, stroke) }
                    c.drawCircle(p.x, p.y, 7 * dp, stroke)
                }
                1 -> { // power
                    stroke.color = if (Controls.engineOn) Color.WHITE else Color.rgb(255, 90, 80)
                    rect.set(p.x - 8 * dp, p.y - 8 * dp, p.x + 8 * dp, p.y + 8 * dp); c.drawArc(rect, -60f, 300f, false, stroke)
                    c.drawLine(p.x, p.y - 10 * dp, p.x, p.y - 1 * dp, stroke)
                }
                2 -> { c.drawLine(p.x - 7 * dp, p.y, p.x + 7 * dp, p.y, stroke); c.drawLine(p.x, p.y - 7 * dp, p.x, p.y + 7 * dp, stroke) }
                3 -> c.drawLine(p.x - 7 * dp, p.y, p.x + 7 * dp, p.y, stroke)
            }
        }
        val t2 = sideBtn(2); val t3 = sideBtn(3)
        small.textAlign = Paint.Align.CENTER; small.typeface = bold
        c.drawText("TRIM", t2.x, (t2.y + t3.y) / 2f - 1 * dp, small)
        c.drawText("${(Controls.trim * 100).roundToInt()}%", t2.x, (t2.y + t3.y) / 2f + 10 * dp, small)
        // throttle lever
        val r = leverRect()
        c.drawText("MANETTA", r.centerX(), r.top - 8 * dp, small)
        small.typeface = Typeface.DEFAULT
        panel(r, 14 * dp)
        val trackX = r.centerX()
        stroke.color = Color.argb(90, 255, 255, 255); stroke.strokeWidth = 5 * dp
        c.drawLine(trackX, r.top + 14 * dp, trackX, r.bottom - 14 * dp, stroke)
        val n = 0.2f
        val inner = RectF(r.left, r.top + 14 * dp, r.right, r.bottom - 14 * dp)
        val yNeutral = inner.bottom - inner.height() * n
        stroke.strokeWidth = 1.5f * dp; stroke.color = Color.argb(160, 255, 255, 255)
        for (k in 0..10) { val y = yNeutral - (yNeutral - inner.top) * k / 10f; c.drawLine(r.left + 9 * dp, y, r.left + (if (k % 5 == 0) 17 else 13) * dp, y, stroke) }
        small.textAlign = Paint.Align.LEFT; c.drawText("R", r.left + 8 * dp, inner.bottom - 4 * dp, small)
        val v = Controls.lever
        val hy = if (v >= 0) yNeutral - (yNeutral - inner.top) * v else yNeutral + (inner.bottom - yNeutral) * (-v / 0.6f)
        stroke.color = if (v >= 0) ACCENT else Color.rgb(255, 120, 90); stroke.strokeWidth = 5 * dp
        c.drawLine(trackX, yNeutral, trackX, hy, stroke)
        fill.color = Color.rgb(230, 235, 240)
        rect.set(r.left + 6 * dp, hy - 9 * dp, r.right - 6 * dp, hy + 9 * dp); c.drawRoundRect(rect, 5 * dp, 5 * dp, fill)
        fill.color = Color.rgb(200, 40, 40); c.drawCircle(trackX, hy, 3 * dp, fill)
        // value badge
        val badge = RectF(r.left - 2 * dp, r.bottom + 6 * dp, r.right + 2 * dp, r.bottom + 26 * dp)
        fill.color = Color.WHITE; c.drawRoundRect(badge, 10 * dp, 10 * dp, fill)
        text.textSize = 11 * dp; text.textAlign = Paint.Align.CENTER; text.color = Color.rgb(20, 30, 58)
        val lbl = if (v < 0) "R ${(-v / 0.6f * 100).roundToInt()}%" else "${(v * 100).roundToInt()}%"
        c.drawText(lbl, badge.centerX(), badge.centerY() + 4 * dp, text)
        text.color = Color.WHITE
    }

    private fun drawArrows() {
        for ((i, r) in listOf(leftArrow(), rightArrow()).withIndex()) {
            val held = if (i == 0) Controls.leftHeld else Controls.rightHeld
            fill.color = if (held) Color.argb(230, 40, 70, 120) else PANEL
            c.drawRoundRect(r, 18 * dp, 18 * dp, fill)
            stroke.color = PANEL_EDGE; stroke.strokeWidth = 1.2f * dp; c.drawRoundRect(r, 18 * dp, 18 * dp, stroke)
            fill.color = Color.WHITE
            val cx = r.centerX(); val cy = r.centerY(); val s = if (i == 0) -1f else 1f
            path.reset(); path.moveTo(cx + s * 14 * dp, cy); path.lineTo(cx - s * 9 * dp, cy - 15 * dp); path.lineTo(cx - s * 9 * dp, cy + 15 * dp); path.close()
            c.drawPath(path, fill)
        }
    }

    private fun drawPause() {
        fill.color = Color.argb(150, 0, 0, 0); c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        val items = pauseItems()
        val top = items[0].first.top
        panel(RectF(width / 2f - 166 * dp, top - 46 * dp, width / 2f + 166 * dp, top + 266 * dp), 18 * dp)
        text.textAlign = Paint.Align.CENTER; text.textSize = 20 * dp
        c.drawText("PAUSA", width / 2f, top - 16 * dp, text)
        small.textAlign = Paint.Align.CENTER
        c.drawText("GRAFICA", width / 2f, top + 74 * dp, small)
        c.drawText("FREQUENZA FOTOGRAMMI", width / 2f, top + 152 * dp, small)
        for ((r, k) in items) {
            val on = when {
                k == "resume" -> true
                k.startsWith("q") -> Settings.quality == k[1].digitToInt()
                k == "fps" -> Settings.showFps
                k.startsWith("f") -> Settings.fpsTarget == k.substring(1).toInt()
                else -> false
            }
            fill.color = if (on) ACCENT else Color.argb(70, 255, 255, 255)
            c.drawRoundRect(r, 10 * dp, 10 * dp, fill)
            val label = when {
                k == "resume" -> "RIPRENDI"
                k.startsWith("q") -> k.substringAfter(":")
                k == "fps" -> if (Settings.showFps) "Mostra FPS: sì" else "Mostra FPS: no"
                else -> "${k.substring(1)} FPS"
            }
            text.textSize = 14 * dp; c.drawText(label, r.centerX(), r.centerY() + 5 * dp, text)
        }
    }
}
