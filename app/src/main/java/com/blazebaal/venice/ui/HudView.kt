// In-game HUD + touch controls, drawn with Android Canvas over the GL view.
// Layout: speedometer + minimap (top left, tap the map for the full-screen map), place / weather / clock pill (top
// centre), camera + pause (top right) with the waypoint / trip card under them, throttle lever ("manetta") with the
// engine and trim controls (right), analog steering pad or arrows (bottom left).
package com.blazebaal.venice.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.blazebaal.venice.core.Places
import com.blazebaal.venice.live.Weather
import java.util.Calendar
import kotlin.math.*

class HudView(ctx: Context) : View(ctx) {
    private val ink = Ink(ctx)
    private val base = ink.baseDp
    private val s get() = base * Settings.hudScale
    private lateinit var c: Canvas

    // touch tracking
    private var leverId = -1; private var leftId = -1; private var rightId = -1; private var camId = -1; private var padId = -1
    private var camLx = 0f; private var camLy = 0f
    private var lastLeverTap = 0L; private var lastNeutral = true
    private var placeName = ""; private var placeAt = 0L
    // trim buttons: tap = 1 step, hold = repeats, slowly at first and then faster
    private var trimId = -1; private var trimDir = 0; private var trimHoldStart = 0L; private var trimAcc = 0f

    init {
        MapLayer.load(ctx) { postInvalidate() }
        val tick = object : Runnable { override fun run() {
            if (trimDir != 0) holdTrim()
            if (Controls.screen == Screen.NONE || Controls.screen == Screen.PAUSE) invalidate()
            postDelayed(this, 33)
        } }
        post(tick)
    }

    private fun stepTrim(d: Int) {
        val v = (Controls.trimPct + d).coerceIn(0, 100)
        if (v != Controls.trimPct) { Controls.trimPct = v; if (v % 5 == 0 || v == 0 || v == 100) haptic() }
    }
    private fun holdTrim() {
        val held = (System.currentTimeMillis() - trimHoldStart) / 1000f
        if (held < 0.35f) return
        val rate = min(60f, 6f + (held - 0.35f) * 40f)      // steps per second
        trimAcc += rate * 0.033f
        while (trimAcc >= 1f) { trimAcc -= 1f; stepTrim(trimDir) }
    }

    private fun haptic() { if (Settings.vibration) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }

    // ---------------------------------------------------------------- layout
    private val margin get() = 14 * s
    private fun speedoC() = PointF(margin + 48 * s, margin + 48 * s)
    private fun mapRect() = RectF(margin + 104 * s, margin, margin + 104 * s + 112 * s, margin + 112 * s)
    private fun leverRect() = RectF(width - margin - 48 * s, height * 0.40f, width - margin, height - margin - 36 * s)
    private fun sideX() = width - margin - 48 * s - 36 * s
    private fun engineBtn() = PointF(sideX(), height * 0.40f + 22 * s)
    private fun trimUp() = PointF(sideX(), height * 0.40f + 82 * s)
    private fun trimDown() = PointF(sideX(), height - margin - 58 * s)
    private fun pauseBtn() = PointF(width - margin - 23 * s, margin + 23 * s)
    private fun camBtn() = PointF(width - margin - 23 * s - 54 * s, margin + 23 * s)
    private fun padRect() = RectF(margin, height - margin - 86 * s, margin + 250 * s, height - margin)
    private fun leftArrow() = RectF(margin + 4 * s, height - margin - 72 * s, margin + 76 * s, height - margin)
    private fun rightArrow() = RectF(margin + 88 * s, height - margin - 72 * s, margin + 160 * s, height - margin)
    private fun hit(p: PointF, x: Float, y: Float) = hypot(x - p.x, y - p.y) < 26 * s

    // ---------------------------------------------------------------- input
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (Controls.screen != Screen.NONE) { release(-2); return false }
        val idx = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val id = e.getPointerId(idx); val x = e.getX(idx); val y = e.getY(idx)
                val lr = leverRect()
                when {
                    RectF(lr.left - 10 * s, lr.top - 20 * s, lr.right + 8 * s, lr.bottom + 30 * s).contains(x, y) -> {
                        val now = System.currentTimeMillis()
                        if (now - lastLeverTap < 300) { Controls.lever = 0f; haptic() } else { leverId = id; setLever(y) }   // double tap: neutral
                        lastLeverTap = now
                    }
                    Settings.analogSteering && padRect().contains(x, y) -> { padId = id; setPad(x) }
                    !Settings.analogSteering && leftArrow().contains(x, y) -> { leftId = id; Controls.leftHeld = true }
                    !Settings.analogSteering && rightArrow().contains(x, y) -> { rightId = id; Controls.rightHeld = true }
                    mapRect().contains(x, y) -> { Controls.screen = Screen.MAP; haptic() }
                    hit(pauseBtn(), x, y) -> { Controls.screen = Screen.PAUSE; haptic() }
                    hit(camBtn(), x, y) -> { Controls.cameraMode = (Controls.cameraMode + 1) % 3; haptic() }
                    hit(engineBtn(), x, y) -> { Controls.engineOn = !Controls.engineOn; if (!Controls.engineOn) Controls.lever = 0f; haptic() }
                    hit(trimUp(), x, y) -> { trimId = id; trimDir = 1; trimHoldStart = System.currentTimeMillis(); trimAcc = 0f; stepTrim(1); haptic() }
                    hit(trimDown(), x, y) -> { trimId = id; trimDir = -1; trimHoldStart = System.currentTimeMillis(); trimAcc = 0f; stepTrim(-1); haptic() }
                    camId < 0 -> { camId = id; camLx = x; camLy = y }
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                val id = e.getPointerId(i); val x = e.getX(i); val y = e.getY(i)
                when (id) {
                    leverId -> setLever(y)
                    padId -> setPad(x)
                    camId -> {
                        Controls.camYawDelta += -(x - camLx) * 0.006f
                        Controls.camPitchDelta += (y - camLy) * 0.004f
                        camLx = x; camLy = y
                    }
                    leftId -> if (!leftArrow().contains(x, y)) { leftId = -1; Controls.leftHeld = false }
                    rightId -> if (!rightArrow().contains(x, y)) { rightId = -1; Controls.rightHeld = false }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val id = e.getPointerId(idx)
                if (e.actionMasked == MotionEvent.ACTION_CANCEL) release(-2) else release(id)
            }
        }
        return true
    }

    /** lever travel: top = full ahead, a neutral notch, bottom fifth = reverse */
    private fun setLever(y: Float) {
        if (!Controls.engineOn) return
        val r = leverRect()
        val t = ((r.bottom - y) / r.height()).coerceIn(0f, 1f)      // 0 bottom .. 1 top
        val n = 0.2f                                                   // neutral position
        var v = if (t >= n) (t - n) / (1f - n) else -(n - t) / n * 0.6f
        if (abs(t - n) < 0.03f) v = 0f
        val neutral = v == 0f
        if (neutral && !lastNeutral) haptic()                          // a click in the neutral notch
        lastNeutral = neutral
        Controls.lever = v.coerceIn(-0.6f, 1f)
    }

    private fun setPad(x: Float) {
        val r = padRect()
        Controls.steerAnalog = ((x - r.centerX()) / (r.width() / 2 - 20 * s)).coerceIn(-1f, 1f)
    }

    private fun release(id: Int) {
        if (id == leftId || id == -2) { leftId = -1; Controls.leftHeld = false }
        if (id == rightId || id == -2) { rightId = -1; Controls.rightHeld = false }
        if (id == leverId || id == -2) leverId = -1
        if (id == camId || id == -2) camId = -1
        if (id == padId || id == -2) { padId = -1; Controls.steerAnalog = Float.NaN }
        if (id == trimId || id == -2) { trimId = -1; trimDir = 0 }
    }

    // ---------------------------------------------------------------- drawing
    override fun onDraw(canvas: Canvas) {
        if (Controls.screen == Screen.MENU) return
        c = canvas; ink.c = canvas; ink.dp = s
        drawSpeedStreaks()
        drawSpeedo()
        drawMinimap()
        drawTopCentre()
        drawTopRight()
        drawRightColumn()
        if (Settings.analogSteering) drawPad() else drawArrows()
        if (GameState.hasWaypoint()) drawWaypointArrow()
        drawEvents()
        if (Settings.showFps) ink.label("${GameState.fps.roundToInt()} FPS · ${(GameState.renderScale * 100).roundToInt()}% · ${GameState.tilesLoaded} chunk", margin + 2 * s, margin + 118 * s, 10f, Theme.TEXT_DIM, face = Theme.condensed)
        if (GameState.loading) drawLoading()
        ink.dp = base
    }

    private fun chip(t: String, cx: Float, cy: Float, col: Int) {
        val w = ink.width(t, 11f, spacing = 0.06f) + 24 * s
        val r = RectF(cx - w / 2, cy - 12 * s, cx + w / 2, cy + 12 * s)
        ink.fill.style = Paint.Style.FILL; ink.fill.color = col; c.drawRoundRect(r, 12 * s, 12 * s, ink.fill)
        ink.label(t, cx, cy + 4 * s, 11f, Theme.INK, Paint.Align.CENTER, spacing = 0.06f)
    }

    /** Faint white streaks rushing past the screen edges above ~70 km/h. */
    private val streakSeed = FloatArray(24) { (it * 0.6180339f) % 1f }
    private fun drawSpeedStreaks() {
        val k = ((GameState.speedKmh - 70f) / 70f).coerceIn(0f, 1f)
        if (k <= 0f) return
        val t = (System.nanoTime() / 1e9).toFloat()
        val cx = width / 2f; val cy = height * 0.55f
        ink.stroke.strokeWidth = 1.5f * s
        for (i in streakSeed.indices) {
            val a = streakSeed[i] * 6.283f
            val ph = ((t * (1.6f + streakSeed[(i + 7) % 24] * 1.2f) + streakSeed[(i + 3) % 24]) % 1f)
            val r0 = 0.55f + ph * 0.6f; val r1 = r0 + 0.08f + 0.12f * k
            val ex = cos(a) * width * 0.55f; val ey = sin(a) * height * 0.62f
            ink.stroke.color = Color.argb((90 * k * (1f - ph)).toInt(), 255, 255, 255)
            c.drawLine(cx + ex * r0, cy + ey * r0, cx + ex * r1, cy + ey * r1, ink.stroke)
        }
    }

    private fun drawSpeedo() {
        val ctr = speedoC(); val r = 48 * s
        ink.glassCircle(ctr.x, ctr.y, r)
        // dial: 240 degree sweep, ticks every 10 km/h, progress arc
        val a0 = 150f; val sweep = 240f; val maxKmh = 150f
        ink.rect.set(ctr.x - r + 7 * s, ctr.y - r + 7 * s, ctr.x + r - 7 * s, ctr.y + r - 7 * s)
        ink.stroke.strokeWidth = 4 * s; ink.stroke.color = Color.argb(50, 255, 255, 255); c.drawArc(ink.rect, a0, sweep, false, ink.stroke)
        val f = (GameState.speedKmh / maxKmh).coerceIn(0f, 1f)
        ink.stroke.color = if (GameState.speedKmh > 120f) Theme.RED else Theme.YELLOW; c.drawArc(ink.rect, a0, sweep * f, false, ink.stroke)
        ink.stroke.strokeWidth = 1.2f * s
        for (k in 0..15) {
            val a = Math.toRadians((a0 + sweep * k / 15f).toDouble())
            val r1 = r - 15 * s; val r2 = r - (if (k % 5 == 0) 21 else 18) * s
            ink.stroke.color = if (k % 5 == 0) Theme.TEXT else Theme.TEXT_FAINT
            c.drawLine(ctr.x + cos(a).toFloat() * r1, ctr.y + sin(a).toFloat() * r1, ctr.x + cos(a).toFloat() * r2, ctr.y + sin(a).toFloat() * r2, ink.stroke)
        }
        ink.label("${Settings.speed(GameState.speedKmh).roundToInt()}", ctr.x, ctr.y + 10 * s, 30f, Theme.TEXT, Paint.Align.CENTER, Theme.black)
        ink.label(Settings.speedLabel(), ctr.x, ctr.y + 25 * s, 9f, Theme.TEXT_DIM, Paint.Align.CENTER, spacing = 0.15f)
    }

    private fun drawMinimap() {
        val r = mapRect(); val rad = 16 * s
        ink.glass(r, rad)
        val save = c.save()
        ink.path.reset(); ink.path.addRoundRect(RectF(r.left + 3 * s, r.top + 3 * s, r.right - 3 * s, r.bottom - 3 * s), rad - 3 * s, rad - 3 * s, Path.Direction.CW)
        c.clipPath(ink.path)
        val cx = r.centerX(); val cy = r.centerY()
        // zoom out as the speed builds up: ~250 m across standing still, ~600 m flat out
        val across = 250f + 350f * (GameState.speedKmh / 140f).coerceIn(0f, 1f)
        val pxPerM = r.width() / across
        val rot = if (Settings.minimapNorthUp) 0f else Math.toDegrees(GameState.heading.toDouble()).toFloat() - 90f
        c.translate(cx, cy)
        // sharp: decode the full-resolution map around the boat (the half-resolution copy looked blurry)
        val halfM = across * 0.75f
        MapLayer.requestRegion(Rect(MapLayer.px(GameState.x - halfM).toInt(), MapLayer.py(GameState.y + halfM).toInt(), MapLayer.px(GameState.x + halfM).toInt(), MapLayer.py(GameState.y - halfM).toInt())) { postInvalidate() }
        MapLayer.draw(ink, GameState.x, GameState.y, pxPerM, rot, true)
        // live vaporetti on the minimap
        if (Settings.liveVaporetti) for (v in com.blazebaal.venice.render.LiveBoats.markers) {
            val q = MapLayer.toScreen(v.x, v.y, GameState.x, GameState.y, pxPerM, rot)
            if (abs(q.x) > r.width() / 2 || abs(q.y) > r.height() / 2) continue
            ink.fill.style = Paint.Style.FILL; ink.fill.color = Theme.YELLOW; c.drawCircle(q.x, q.y, 6.5f * s, ink.fill)
            ink.stroke.color = Theme.INK; ink.stroke.strokeWidth = 1.2f * s; c.drawCircle(q.x, q.y, 6.5f * s, ink.stroke)
            ink.label(v.line, q.x, q.y + 2.8f * s, if (v.line.length > 2) 6f else 7.5f, Theme.INK, Paint.Align.CENTER)
        }
        // waypoint: line from the boat + pin (clamped to the rim when off-map)
        if (GameState.hasWaypoint()) {
            val p = MapLayer.toScreen(GameState.wpX, GameState.wpY, GameState.x, GameState.y, pxPerM, rot)
            val lim = r.width() / 2 - 12 * s
            val d = hypot(p.x, p.y); val k = if (d > lim) lim / d else 1f
            ink.stroke.color = Theme.YELLOW; ink.stroke.strokeWidth = 2 * s
            ink.stroke.pathEffect = DashPathEffect(floatArrayOf(5 * s, 4 * s), 0f)
            c.drawLine(0f, 0f, p.x * k, p.y * k, ink.stroke); ink.stroke.pathEffect = null
            ink.icon("pin", p.x * k, p.y * k - 6 * s, Theme.YELLOW, 0.9f)
        }
        c.restoreToCount(save)
        // boat arrow (heading-up: always pointing up)
        val ha = if (Settings.minimapNorthUp) -Math.toDegrees(GameState.heading.toDouble()).toFloat() + 90f else 0f
        val sv = c.save(); c.translate(cx, cy); c.rotate(ha)
        ink.fill.style = Paint.Style.FILL; ink.fill.color = Theme.YELLOW
        ink.path.reset(); ink.path.moveTo(0f, -9 * s); ink.path.lineTo(6.5f * s, 7 * s); ink.path.lineTo(0f, 3 * s); ink.path.lineTo(-6.5f * s, 7 * s); ink.path.close()
        c.drawPath(ink.path, ink.fill)
        ink.stroke.color = Theme.INK; ink.stroke.strokeWidth = 1.2f * s; c.drawPath(ink.path, ink.stroke)
        c.restoreToCount(sv)
        // north marker on the rim
        val na = if (Settings.minimapNorthUp) -PI.toFloat() / 2 else GameState.heading - PI.toFloat()
        val nx = (cx + cos(na) * 48 * s).coerceIn(r.left + 10 * s, r.right - 10 * s); val ny = (cy + sin(na) * 48 * s).coerceIn(r.top + 10 * s, r.bottom - 10 * s)
        ink.glassCircle(nx, ny, 8 * s, Theme.INK)
        ink.label("N", nx, ny + 3.5f * s, 10f, Theme.YELLOW, Paint.Align.CENTER)
        // expand hint
        ink.label("MAPPA", r.right - 8 * s, r.bottom - 7 * s, 8f, Theme.TEXT_DIM, Paint.Align.RIGHT, spacing = 0.1f)
    }

    private fun drawTopCentre() {
        val now = System.currentTimeMillis()
        if (now - placeAt > 1000) { placeName = Places.nameAt(GameState.x, GameState.y, GameState.lat0, GameState.lon0).uppercase(); placeAt = now }
        val cx = width / 2f
        val w = max(190 * s, ink.width(placeName, 10f, spacing = 0.12f) + 40 * s)
        val r = RectF(cx - w / 2, margin, cx + w / 2, margin + 48 * s)
        ink.glass(r, 14 * s)
        ink.label(placeName, cx, r.top + 16 * s, 10f, Theme.YELLOW, Paint.Align.CENTER, spacing = 0.12f)
        val cal = Calendar.getInstance()
        val t = String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        val temp = "${Weather.temperature.roundToInt()}°"
        ink.weatherIcon(Weather.icon(), cx - 52 * s, r.top + 32 * s, Theme.TEXT, 0.85f)
        ink.label(temp, cx - 38 * s, r.top + 38 * s, 17f, Theme.TEXT)
        ink.stroke.color = Theme.EDGE; ink.stroke.strokeWidth = 1 * s; c.drawLine(cx + 2 * s, r.top + 25 * s, cx + 2 * s, r.top + 41 * s, ink.stroke)
        ink.label(t, cx + 12 * s, r.top + 39 * s, 19f, Theme.TEXT, face = Theme.black)
    }

    private fun drawTopRight() {
        for ((p, ic) in listOf(pauseBtn() to "pause", camBtn() to "camera")) { ink.glassCircle(p.x, p.y, 22 * s); ink.icon(ic, p.x, p.y) }
        val r = RectF(width - margin - 150 * s, margin + 54 * s, width - margin, margin + 100 * s)
        ink.glass(r, 14 * s)
        ink.label("DISTANZA", r.left + 14 * s, r.top + 17 * s, 9.5f, Theme.TEXT_DIM, spacing = 0.12f)
        ink.label(dist(GameState.distanceM), r.left + 14 * s, r.top + 38 * s, 18f, Theme.TEXT, face = Theme.black)
        ink.icon("water", r.right - 22 * s, r.centerY(), Theme.TEXT_FAINT, 0.9f)
    }

    /** Floating arrow under the top-centre pill pointing where to steer for the waypoint (relative to the boat). */
    private var arrowRot = Float.NaN
    private fun drawWaypointArrow() {
        val bearing = atan2(GameState.wpY - GameState.y, GameState.wpX - GameState.x)
        var rel = Math.toDegrees((GameState.heading - bearing).toDouble()).toFloat()
        while (rel > 180f) rel -= 360f; while (rel < -180f) rel += 360f
        // smooth the rotation so it doesn't twitch with every wave
        if (arrowRot.isNaN()) arrowRot = rel
        var d = rel - arrowRot; while (d > 180f) d -= 360f; while (d < -180f) d += 360f
        arrowRot += d * 0.25f
        val cx = width / 2f; val cy = margin + 92 * s
        val bob = sin(System.nanoTime() / 4e8).toFloat() * 2 * s
        val sv = c.save(); c.translate(cx, cy + bob); c.rotate(arrowRot)
        ink.fill.style = Paint.Style.FILL
        // soft shadow, then the arrow
        ink.fill.color = Color.argb(90, 0, 0, 0)
        ink.path.reset(); ink.path.moveTo(1.5f * s, -20 * s); ink.path.lineTo(17.5f * s, 15 * s); ink.path.lineTo(1.5f * s, 7 * s); ink.path.lineTo(-14.5f * s, 15 * s); ink.path.close(); c.drawPath(ink.path, ink.fill)
        ink.fill.color = Theme.YELLOW
        ink.path.reset(); ink.path.moveTo(0f, -22 * s); ink.path.lineTo(16 * s, 13 * s); ink.path.lineTo(0f, 5 * s); ink.path.lineTo(-16 * s, 13 * s); ink.path.close(); c.drawPath(ink.path, ink.fill)
        ink.stroke.color = Theme.INK; ink.stroke.strokeWidth = 1.5f * s; c.drawPath(ink.path, ink.stroke)
        c.restoreToCount(sv)
        ink.text.setShadowLayer(3 * s, 0f, 1 * s, Color.BLACK)
        ink.label(dist(GameState.waypointDistance()), cx, cy + 34 * s, 11f, Theme.TEXT, Paint.Align.CENTER)
        ink.text.clearShadowLayer()
    }

    /** Event texts: big coloured words that fade + slide in, hold, and fade out. No boxes. */
    private fun drawEvents() {
        val ev = Events.live(); if (ev.isEmpty()) return
        val now = System.currentTimeMillis()
        var y = height * 0.30f
        for (e in ev.reversed()) {
            val t = (now - e.t0).toFloat()
            val a = when { t < 180f -> t / 180f; t > Events.LIFE_MS - 450f -> ((Events.LIFE_MS - t) / 450f).coerceAtLeast(0f); else -> 1f }
            val slide = (1f - min(1f, t / 220f)) * 14 * s
            val alpha = (a * 255).toInt()
            ink.text.setShadowLayer(6 * s, 0f, 2 * s, Color.argb((a * 200).toInt(), 0, 0, 0))
            ink.label(e.text, width / 2f, y + slide, 16f, Color.argb(alpha, Color.red(e.color), Color.green(e.color), Color.blue(e.color)), Paint.Align.CENTER, Theme.black, 0.05f)
            if (e.sub.isNotEmpty()) ink.label(e.sub, width / 2f, y + slide + 13 * s, 9f, Color.argb((alpha * 0.7f).toInt(), 235, 238, 242), Paint.Align.CENTER, Theme.condensed, 0.04f)
            ink.text.clearShadowLayer()
            y -= (if (e.sub.isNotEmpty()) 34 else 24) * s
        }
    }

    private fun dist(d: Float) = if (d < 1000f) "${d.roundToInt()} m" else String.format("%.1f km", d / 1000f).replace('.', ',')

    private fun drawRightColumn() {
        // engine
        val e = engineBtn(); ink.glassCircle(e.x, e.y, 22 * s); ink.icon("power", e.x, e.y, if (Controls.engineOn) Theme.TEAL else Theme.RED)
        // trim: + / - and a gauge showing the motor angle and the boat's attitude between them
        val up = trimUp(); val dn = trimDown()
        ink.glassCircle(up.x, up.y, 20 * s); ink.icon("plus", up.x, up.y)
        ink.glassCircle(dn.x, dn.y, 20 * s); ink.icon("minus", dn.x, dn.y)
        // value between the buttons + a small bar: green band = best top speed, red = porpoising
        val mid = (up.y + dn.y) / 2
        val tp = Controls.trimPct
        ink.label("TRIM", e.x, mid - 12 * s, 8.5f, Theme.TEXT_DIM, Paint.Align.CENTER, spacing = 0.12f)
        ink.label("$tp", e.x, mid + 8 * s, 17f, if (tp >= 78) Theme.RED else Theme.TEXT, Paint.Align.CENTER, Theme.black)
        val bar = RectF(e.x - 17 * s, mid + 15 * s, e.x + 17 * s, mid + 19 * s)
        ink.fill.style = Paint.Style.FILL; ink.fill.color = Color.argb(60, 255, 255, 255); c.drawRoundRect(bar, 2 * s, 2 * s, ink.fill)
        ink.fill.color = Color.argb(150, 46, 196, 182); c.drawRect(bar.left + bar.width() * 0.58f, bar.top, bar.left + bar.width() * 0.74f, bar.bottom, ink.fill)
        ink.fill.color = Color.argb(150, 255, 90, 78); c.drawRect(bar.left + bar.width() * 0.78f, bar.top, bar.right, bar.bottom, ink.fill)
        ink.fill.color = Theme.TEXT; val kx = bar.left + bar.width() * tp / 100f; c.drawRect(kx - 1.5f * s, bar.top - 3 * s, kx + 1.5f * s, bar.bottom + 3 * s, ink.fill)

        // throttle lever
        val r = leverRect()
        ink.label("MANETTA", r.centerX(), r.top - 9 * s, 9f, Theme.TEXT_DIM, Paint.Align.CENTER, spacing = 0.12f)
        ink.glass(r, 16 * s)
        val trackX = r.centerX()
        ink.stroke.color = Color.argb(70, 255, 255, 255); ink.stroke.strokeWidth = 5 * s
        c.drawLine(trackX, r.top + 14 * s, trackX, r.bottom - 14 * s, ink.stroke)
        val n = 0.2f
        val inner = RectF(r.left, r.top + 14 * s, r.right, r.bottom - 14 * s)
        val yNeutral = inner.bottom - inner.height() * n
        ink.stroke.strokeWidth = 1.5f * s
        for (k in 0..10) { val y = yNeutral - (yNeutral - inner.top) * k / 10f; ink.stroke.color = if (k % 5 == 0) Theme.TEXT_DIM else Theme.TEXT_FAINT; c.drawLine(r.left + 9 * s, y, r.left + (if (k % 5 == 0) 18 else 13) * s, y, ink.stroke) }
        ink.label("N", r.right - 9 * s, yNeutral + 4 * s, 9f, Theme.TEXT_DIM, Paint.Align.RIGHT)
        ink.label("R", r.left + 9 * s, inner.bottom - 2 * s, 9f, Theme.RED)
        val v = Controls.lever
        val hy = if (v >= 0) yNeutral - (yNeutral - inner.top) * v else yNeutral + (inner.bottom - yNeutral) * (-v / 0.6f)
        ink.stroke.color = if (v >= 0) Theme.YELLOW else Theme.RED; ink.stroke.strokeWidth = 5 * s
        c.drawLine(trackX, yNeutral, trackX, hy, ink.stroke)
        ink.fill.style = Paint.Style.FILL; ink.fill.color = Theme.TEXT
        ink.rect.set(r.left + 6 * s, hy - 10 * s, r.right - 6 * s, hy + 10 * s); c.drawRoundRect(ink.rect, 6 * s, 6 * s, ink.fill)
        ink.fill.color = Theme.INK; for (k in -1..1) c.drawRect(r.left + 14 * s, hy + k * 4 * s - 0.6f * s, r.right - 14 * s, hy + k * 4 * s + 0.6f * s, ink.fill)
        // value badge
        val badge = RectF(r.left - 4 * s, r.bottom + 8 * s, r.right + 4 * s, r.bottom + 30 * s)
        ink.fill.color = if (v < 0) Theme.RED else Theme.YELLOW; c.drawRoundRect(badge, 11 * s, 11 * s, ink.fill)
        val lbl = if (!Controls.engineOn) "OFF" else if (v < 0) "R ${(-v / 0.6f * 100).roundToInt()}%" else "${(v * 100).roundToInt()}%"
        ink.label(lbl, badge.centerX(), badge.centerY() + 4.5f * s, 12f, Theme.INK, Paint.Align.CENTER)
    }

    private fun drawPad() {
        val r = padRect()
        ink.glass(r, r.height() / 2)
        val a = Controls.steerAnalog
        ink.stroke.color = Theme.TEXT_FAINT; ink.stroke.strokeWidth = 1.5f * s
        c.drawLine(r.centerX(), r.top + 18 * s, r.centerX(), r.bottom - 18 * s, ink.stroke)
        ink.icon("back", r.left + 26 * s, r.centerY(), Theme.TEXT_DIM); ink.icon("next", r.right - 26 * s, r.centerY(), Theme.TEXT_DIM)
        val travel = r.width() / 2 - 20 * s - 10 * s
        val kx = r.centerX() + (if (a.isNaN()) 0f else a) * travel
        if (!a.isNaN()) { ink.fill.style = Paint.Style.FILL; ink.fill.color = Theme.YELLOW_DIM; c.drawRoundRect(min(kx, r.centerX()), r.centerY() - 4 * s, max(kx, r.centerX()), r.centerY() + 4 * s, 4 * s, 4 * s, ink.fill) }
        ink.fill.style = Paint.Style.FILL; ink.fill.color = if (a.isNaN()) Theme.TEXT else Theme.YELLOW
        c.drawCircle(kx, r.centerY(), 24 * s, ink.fill)
        ink.label("TIMONE", kx, r.centerY() + 3.5f * s, 9f, Theme.INK, Paint.Align.CENTER, spacing = 0.1f)
    }

    private fun drawArrows() {
        for ((i, r) in listOf(leftArrow(), rightArrow()).withIndex()) {
            val held = if (i == 0) Controls.leftHeld else Controls.rightHeld
            ink.glass(r, 20 * s, if (held) Theme.GLASS_HI else Theme.GLASS)
            ink.fill.style = Paint.Style.FILL; ink.fill.color = if (held) Theme.YELLOW else Theme.TEXT
            val cx = r.centerX(); val cy = r.centerY(); val sg = if (i == 0) -1f else 1f
            ink.path.reset(); ink.path.moveTo(cx + sg * 14 * s, cy); ink.path.lineTo(cx - sg * 9 * s, cy - 15 * s); ink.path.lineTo(cx - sg * 9 * s, cy + 15 * s); ink.path.close()
            c.drawPath(ink.path, ink.fill)
        }
    }

    private fun drawLoading() {
        val w = 260 * s; val r = RectF(width / 2f - w / 2, height * 0.42f, width / 2f + w / 2, height * 0.42f + 56 * s)
        ink.glass(r, 14 * s)
        ink.label("CARICAMENTO DI VENEZIA…", r.centerX(), r.top + 22 * s, 12f, Theme.TEXT, Paint.Align.CENTER, spacing = 0.08f)
        val f = (GameState.tilesLoaded.toFloat() / max(1, min(4, GameState.tilesWanted))).coerceIn(0f, 1f)
        val bar = RectF(r.left + 18 * s, r.bottom - 18 * s, r.right - 18 * s, r.bottom - 12 * s)
        ink.fill.style = Paint.Style.FILL; ink.fill.color = Color.argb(60, 255, 255, 255); c.drawRoundRect(bar, 3 * s, 3 * s, ink.fill)
        ink.stripes(RectF(bar.left, bar.top, bar.left + bar.width() * f, bar.bottom), 4f)
    }
}
