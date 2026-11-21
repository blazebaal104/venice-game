// Touch controls + HUD overlay drawn with Android Canvas.
package com.blazebaal.venice.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class HudView(ctx: Context) : View(ctx) {
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); setShadowLayer(4f, 0f, 1f, Color.argb(160, 0, 0, 0)) }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255); textSize = 9f * resources.displayMetrics.density; setShadowLayer(3f, 0f, 1f, Color.BLACK) }
    private var minimap: Bitmap? = null
    private val mmMetresPerPx = 6f // asset is 3 m/px, decoded at half size

    // touch tracking
    private var stickId = -1; private var stickCx = 0f; private var stickCy = 0f; private var stickX = 0f; private var stickY = 0f
    private var gasId = -1; private var brakeId = -1; private var camId = -1; private var camLx = 0f; private var camLy = 0f

    init {
        Thread {
            try {
                val opts = BitmapFactory.Options().apply { inSampleSize = 2; inPreferredConfig = Bitmap.Config.RGB_565 }
                minimap = ctx.assets.open("minimap.png").use { BitmapFactory.decodeStream(it, null, opts) }
                postInvalidate()
            } catch (_: Exception) { }
        }.start()
        val tick = object : Runnable { override fun run() { invalidate(); postDelayed(this, 33) } }
        post(tick)
    }

    private fun gasRect() = RectF(width - 150 * dp, height - 190 * dp, width - 24 * dp, height - 64 * dp)
    private fun brakeRect() = RectF(width - 270 * dp, height - 130 * dp, width - 166 * dp, height - 40 * dp)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val idx = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val id = e.getPointerId(idx); val x = e.getX(idx); val y = e.getY(idx)
                when {
                    gasRect().contains(x, y) -> { gasId = id; Controls.gas = true }
                    brakeRect().contains(x, y) -> { brakeId = id; Controls.brake = true }
                    x < width * 0.42f && stickId < 0 -> { stickId = id; stickCx = x; stickCy = y; stickX = x; stickY = y }
                    camId < 0 -> { camId = id; camLx = x; camLy = y }
                }
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                val id = e.getPointerId(i); val x = e.getX(i); val y = e.getY(i)
                if (id == stickId) {
                    stickX = x; stickY = y
                    val r = 60 * dp
                    Controls.stickX = ((x - stickCx) / r).coerceIn(-1f, 1f)
                    Controls.stickY = ((y - stickCy) / r).coerceIn(-1f, 1f)
                } else if (id == camId) {
                    Controls.camYawDelta += -(x - camLx) * 0.006f
                    Controls.camPitchDelta += (y - camLy) * 0.004f
                    camLx = x; camLy = y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val id = e.getPointerId(idx)
                if (e.actionMasked == MotionEvent.ACTION_CANCEL) { release(-2) } else release(id)
            }
        }
        return true
    }

    private fun release(id: Int) {
        if (id == stickId || id == -2) { stickId = -1; Controls.stickX = 0f; Controls.stickY = 0f }
        if (id == gasId || id == -2) { gasId = -1; Controls.gas = false }
        if (id == brakeId || id == -2) { brakeId = -1; Controls.brake = false }
        if (id == camId || id == -2) camId = -1
    }

    override fun onDraw(c: Canvas) {
        // joystick
        paint.style = Paint.Style.FILL
        if (stickId >= 0) {
            paint.color = Color.argb(60, 255, 255, 255); c.drawCircle(stickCx, stickCy, 62 * dp, paint)
            paint.color = Color.argb(150, 255, 255, 255)
            val dx = (stickX - stickCx).coerceIn(-60 * dp, 60 * dp); val dy = (stickY - stickCy).coerceIn(-60 * dp, 60 * dp)
            c.drawCircle(stickCx + dx, stickCy + dy, 26 * dp, paint)
        } else {
            paint.color = Color.argb(40, 255, 255, 255); c.drawCircle(110 * dp, height - 110 * dp, 62 * dp, paint)
            small.textAlign = Paint.Align.CENTER; c.drawText("STEER", 110 * dp, height - 106 * dp, small)
        }
        // gas / brake
        fun button(r: RectF, label: String, on: Boolean, col: Int) {
            paint.color = if (on) col else Color.argb(70, Color.red(col), Color.green(col), Color.blue(col))
            c.drawRoundRect(r, 22 * dp, 22 * dp, paint)
            text.textSize = 16 * dp; text.textAlign = Paint.Align.CENTER
            c.drawText(label, r.centerX(), r.centerY() + 6 * dp, text)
        }
        button(gasRect(), "GAS", Controls.gas, Color.argb(200, 40, 170, 90))
        button(brakeRect(), "BRAKE", Controls.brake, Color.argb(200, 200, 60, 50))
        // speedometer
        text.textAlign = Paint.Align.LEFT; text.textSize = 34 * dp
        c.drawText("${GameState.speedKmh.roundToInt()}", 24 * dp, 52 * dp, text)
        text.textSize = 13 * dp; c.drawText("km/h", 24 * dp + text.measureText("000") * 2.6f, 52 * dp, text)
        small.textAlign = Paint.Align.LEFT
        c.drawText("${GameState.fps.roundToInt()} fps · ${GameState.tilesLoaded} tiles", 24 * dp, 70 * dp, small)
        // minimap
        drawMinimap(c)
        // attribution (required by the data licences)
        small.textAlign = Paint.Align.CENTER
        c.drawText("Map data © OpenStreetMap contributors (ODbL) · Regione del Veneto – L.R. n. 28/76 · Copernicus Land Monitoring Service", width / 2f, height - 8 * dp, small)
        if (GameState.loading) { text.textSize = 20 * dp; text.textAlign = Paint.Align.CENTER; c.drawText("Loading Venice…", width / 2f, height / 2f, text) }
    }

    private fun drawMinimap(c: Canvas) {
        val bmp = minimap ?: return
        val r = 78 * dp
        val cx = width - r - 20 * dp; val cy = r + 20 * dp
        val save = c.save()
        val path = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
        c.clipPath(path)
        // world -> bitmap pixel: px = (x - minX)/6, py = H - (y - minY)/6
        val px = (GameState.x - GameState.minX) / mmMetresPerPx
        val py = bmp.height - (GameState.y - GameState.minY) / mmMetresPerPx
        val scale = 1.15f * dp // screen px per bitmap px (~400 m radius visible)
        c.translate(cx, cy)
        c.rotate(Math.toDegrees(GameState.heading.toDouble()).toFloat() - 90f) // heading-up
        c.scale(scale, scale)
        c.drawBitmap(bmp, -px, -py, null)
        c.restoreToCount(save)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f * dp; paint.color = Color.argb(200, 255, 255, 255)
        c.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.FILL; paint.color = Color.rgb(230, 40, 40)
        val arrow = Path().apply { moveTo(cx, cy - 9 * dp); lineTo(cx + 6 * dp, cy + 7 * dp); lineTo(cx, cy + 3 * dp); lineTo(cx - 6 * dp, cy + 7 * dp); close() }
        c.drawPath(arrow, paint)
        text.textSize = 12 * dp; text.textAlign = Paint.Align.CENTER
        // north marker
        val na = GameState.heading - PI.toFloat()
        c.drawText("N", cx + cos(na) * (r - 10 * dp), cy + sin(na) * (r - 10 * dp) + 4 * dp, text)
    }
}
