// GTACTV look: dark smoked-glass panels, ACTV yellow as the accent (the colour of every vaporetto stop), lagoon teal
// as the second colour, condensed bold type. Shared by the HUD and every menu so the whole game reads as one thing.
package com.blazebaal.venice.ui

import android.content.Context
import android.graphics.*
import kotlin.math.*

object Theme {
    val YELLOW = Color.rgb(255, 199, 44)
    val YELLOW_DIM = Color.argb(120, 255, 199, 44)
    val TEAL = Color.rgb(46, 196, 182)
    val RED = Color.rgb(255, 90, 78)
    val INK = Color.rgb(12, 16, 24)
    val GLASS = Color.argb(196, 12, 17, 27)
    val GLASS_HI = Color.argb(224, 20, 28, 42)
    val EDGE = Color.argb(38, 255, 255, 255)
    val TEXT = Color.rgb(242, 244, 247)
    val TEXT_DIM = Color.argb(170, 242, 244, 247)
    val TEXT_FAINT = Color.argb(95, 242, 244, 247)

    val condensedBold: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    val condensed: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    val black: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
}

/** Paints + drawing helpers bound to a canvas and a dp scale. */
class Ink(ctx: Context) {
    var dp = ctx.resources.displayMetrics.density
    val baseDp = dp
    lateinit var c: Canvas
    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.TEXT; typeface = Theme.condensedBold }
    val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val path = Path(); val rect = RectF()

    fun glass(r: RectF, radius: Float = 14 * dp, color: Int = Theme.GLASS) {
        fill.style = Paint.Style.FILL; fill.color = color; c.drawRoundRect(r, radius, radius, fill)
        stroke.color = Theme.EDGE; stroke.strokeWidth = 1f * dp; c.drawRoundRect(r, radius, radius, stroke)
    }
    fun glassCircle(x: Float, y: Float, r: Float, color: Int = Theme.GLASS) {
        fill.style = Paint.Style.FILL; fill.color = color; c.drawCircle(x, y, r, fill)
        stroke.color = Theme.EDGE; stroke.strokeWidth = 1f * dp; c.drawCircle(x, y, r, stroke)
    }
    fun label(s: String, x: Float, y: Float, size: Float, color: Int = Theme.TEXT, align: Paint.Align = Paint.Align.LEFT, face: Typeface = Theme.condensedBold, spacing: Float = 0f) {
        text.textSize = size * dp; text.color = color; text.textAlign = align; text.typeface = face; text.letterSpacing = spacing
        c.drawText(s, x, y, text)
        text.letterSpacing = 0f
    }
    fun width(s: String, size: Float, face: Typeface = Theme.condensedBold, spacing: Float = 0f): Float {
        text.textSize = size * dp; text.typeface = face; text.letterSpacing = spacing
        val w = text.measureText(s); text.letterSpacing = 0f; return w
    }
    /** ACTV pontoon hazard stripes (yellow / black, 45 degrees) clipped to a rect: the game's signature motif. */
    fun stripes(r: RectF, step: Float = 9f) {
        val save = c.save(); c.clipRect(r)
        fill.style = Paint.Style.FILL; fill.color = Theme.YELLOW; c.drawRect(r, fill)
        fill.color = Theme.INK
        var x = r.left - r.height()
        val s = step * dp
        while (x < r.right) {
            path.reset(); path.moveTo(x, r.bottom); path.lineTo(x + s, r.bottom); path.lineTo(x + s + r.height(), r.top); path.lineTo(x + r.height(), r.top); path.close()
            c.drawPath(path, fill); x += s * 2
        }
        c.restoreToCount(save)
    }

    // ---------------------------------------------------------------- icons (stroked, 24 dp grid centred on x, y)
    fun icon(kind: String, x: Float, y: Float, color: Int = Theme.TEXT, s: Float = 1f) {
        val u = dp * s
        stroke.color = color; stroke.strokeWidth = 2f * u; fill.color = color; fill.style = Paint.Style.FILL
        when (kind) {
            "pause" -> { c.drawRoundRect(x - 6 * u, y - 7 * u, x - 2 * u, y + 7 * u, 1 * u, 1 * u, fill); c.drawRoundRect(x + 2 * u, y - 7 * u, x + 6 * u, y + 7 * u, 1 * u, 1 * u, fill) }
            "camera" -> {
                rect.set(x - 9 * u, y - 5 * u, x + 9 * u, y + 7 * u); c.drawRoundRect(rect, 2.5f * u, 2.5f * u, stroke)
                path.reset(); path.moveTo(x - 4 * u, y - 5 * u); path.lineTo(x - 2 * u, y - 8 * u); path.lineTo(x + 3 * u, y - 8 * u); path.lineTo(x + 5 * u, y - 5 * u); c.drawPath(path, stroke)
                c.drawCircle(x, y + 1 * u, 3.2f * u, stroke)
            }
            "power" -> { rect.set(x - 8 * u, y - 8 * u, x + 8 * u, y + 8 * u); c.drawArc(rect, -60f, 300f, false, stroke); c.drawLine(x, y - 10 * u, x, y - 1 * u, stroke) }
            "plus" -> { c.drawLine(x - 6 * u, y, x + 6 * u, y, stroke); c.drawLine(x, y - 6 * u, x, y + 6 * u, stroke) }
            "minus" -> c.drawLine(x - 6 * u, y, x + 6 * u, y, stroke)
            "close" -> { c.drawLine(x - 6 * u, y - 6 * u, x + 6 * u, y + 6 * u, stroke); c.drawLine(x - 6 * u, y + 6 * u, x + 6 * u, y - 6 * u, stroke) }
            "back" -> { path.reset(); path.moveTo(x + 3 * u, y - 7 * u); path.lineTo(x - 4 * u, y); path.lineTo(x + 3 * u, y + 7 * u); c.drawPath(path, stroke) }
            "next" -> { path.reset(); path.moveTo(x - 3 * u, y - 7 * u); path.lineTo(x + 4 * u, y); path.lineTo(x - 3 * u, y + 7 * u); c.drawPath(path, stroke) }
            "target" -> { c.drawCircle(x, y, 7 * u, stroke); c.drawCircle(x, y, 2 * u, fill); for (k in 0 until 4) { val a = k * PI / 2; c.drawLine(x + cos(a).toFloat() * 8 * u, y + sin(a).toFloat() * 8 * u, x + cos(a).toFloat() * 11 * u, y + sin(a).toFloat() * 11 * u, stroke) } }
            "pin" -> {
                path.reset(); path.moveTo(x, y + 9 * u); path.cubicTo(x - 9 * u, y - 1 * u, x - 7 * u, y - 10 * u, x, y - 10 * u); path.cubicTo(x + 7 * u, y - 10 * u, x + 9 * u, y - 1 * u, x, y + 9 * u); path.close()
                c.drawPath(path, fill); fill.color = Theme.INK; c.drawCircle(x, y - 3 * u, 2.6f * u, fill)
            }
            "map" -> {
                path.reset(); path.moveTo(x - 9 * u, y - 6 * u); path.lineTo(x - 3 * u, y - 8 * u); path.lineTo(x + 3 * u, y - 6 * u); path.lineTo(x + 9 * u, y - 8 * u)
                path.lineTo(x + 9 * u, y + 6 * u); path.lineTo(x + 3 * u, y + 8 * u); path.lineTo(x - 3 * u, y + 6 * u); path.lineTo(x - 9 * u, y + 8 * u); path.close(); c.drawPath(path, stroke)
                c.drawLine(x - 3 * u, y - 8 * u, x - 3 * u, y + 6 * u, stroke); c.drawLine(x + 3 * u, y - 6 * u, x + 3 * u, y + 8 * u, stroke)
            }
            "gear" -> { c.drawCircle(x, y, 3.5f * u, stroke); for (k in 0 until 8) { val a = k * PI / 4; c.drawLine(x + cos(a).toFloat() * 6.5f * u, y + sin(a).toFloat() * 6.5f * u, x + cos(a).toFloat() * 9.5f * u, y + sin(a).toFloat() * 9.5f * u, stroke) }; c.drawCircle(x, y, 7 * u, stroke) }
            "boat" -> {
                path.reset(); path.moveTo(x - 10 * u, y + 1 * u); path.lineTo(x + 6 * u, y + 1 * u); path.lineTo(x + 10 * u, y - 3 * u); path.lineTo(x - 9 * u, y - 3 * u); path.close(); c.drawPath(path, stroke)
                c.drawLine(x - 2 * u, y - 3 * u, x - 2 * u, y - 8 * u, stroke); c.drawLine(x - 2 * u, y - 8 * u, x + 3 * u, y - 6 * u, stroke)
                path.reset(); path.moveTo(x - 10 * u, y + 6 * u); path.quadTo(x - 6 * u, y + 3 * u, x - 2 * u, y + 6 * u); path.quadTo(x + 2 * u, y + 9 * u, x + 6 * u, y + 6 * u); c.drawPath(path, stroke)
            }
            "info" -> { c.drawCircle(x, y, 9 * u, stroke); c.drawLine(x, y - 1 * u, x, y + 5 * u, stroke); c.drawCircle(x, y - 5 * u, 1.3f * u, fill) }
            "home" -> { path.reset(); path.moveTo(x - 8 * u, y - 1 * u); path.lineTo(x, y - 8 * u); path.lineTo(x + 8 * u, y - 1 * u); c.drawPath(path, stroke); rect.set(x - 6 * u, y - 2 * u, x + 6 * u, y + 8 * u); c.drawRect(rect, stroke) }
            "water" -> for (k in 0..2) { val yy = y - 5 * u + k * 5 * u; path.reset(); path.moveTo(x - 9 * u, yy); path.quadTo(x - 4.5f * u, yy - 3 * u, x, yy); path.quadTo(x + 4.5f * u, yy + 3 * u, x + 9 * u, yy); c.drawPath(path, stroke) }
            "teleport" -> { c.drawCircle(x, y - 2 * u, 5 * u, stroke); path.reset(); path.moveTo(x - 9 * u, y + 7 * u); path.quadTo(x, y + 2 * u, x + 9 * u, y + 7 * u); c.drawPath(path, stroke) }
            "play" -> { path.reset(); path.moveTo(x - 5 * u, y - 8 * u); path.lineTo(x + 8 * u, y); path.lineTo(x - 5 * u, y + 8 * u); path.close(); c.drawPath(path, fill) }
        }
    }

    /** Weather glyphs, see Weather.icon(). */
    fun weatherIcon(kind: Int, x: Float, y: Float, color: Int = Theme.TEXT, s: Float = 1f) {
        val u = dp * s
        stroke.color = color; stroke.strokeWidth = 1.6f * u; fill.color = color; fill.style = Paint.Style.FILL
        fun sun(cx: Float, cy: Float, r: Float) { c.drawCircle(cx, cy, r, fill); for (k in 0 until 8) { val a = k * PI / 4; c.drawLine(cx + cos(a).toFloat() * (r + 2 * u), cy + sin(a).toFloat() * (r + 2 * u), cx + cos(a).toFloat() * (r + 4 * u), cy + sin(a).toFloat() * (r + 4 * u), stroke) } }
        fun cloud(cx: Float, cy: Float, k: Float) {
            path.reset()
            path.addCircle(cx - 4 * u * k, cy + 1 * u * k, 3.6f * u * k, Path.Direction.CW); path.addCircle(cx + 0.5f * u * k, cy - 1.5f * u * k, 4.6f * u * k, Path.Direction.CW)
            path.addCircle(cx + 5 * u * k, cy + 1.2f * u * k, 3.4f * u * k, Path.Direction.CW); path.addRect(cx - 4 * u * k, cy + 1 * u * k, cx + 5 * u * k, cy + 4.6f * u * k, Path.Direction.CW)
            c.drawPath(path, fill)
        }
        when (kind) {
            0 -> sun(x, y, 3.6f * u)
            7 -> { c.drawCircle(x, y, 5 * u, fill); fill.color = Theme.INK; c.drawCircle(x + 2.6f * u, y - 1.8f * u, 4.4f * u, fill) }
            1 -> { sun(x - 3 * u, y - 3 * u, 2.8f * u); cloud(x + 1.5f * u, y + 1.5f * u, 0.85f) }
            2 -> cloud(x, y, 1f)
            3 -> { cloud(x, y - 2 * u, 1f); for (k in -1..1) c.drawLine(x + k * 4 * u, y + 5 * u, x + k * 4 * u - 1.5f * u, y + 9 * u, stroke) }
            4 -> for (k in 0..3) c.drawLine(x - (8 - k % 2 * 2) * u, y - 5 * u + k * 3.4f * u, x + (8 - (k + 1) % 2 * 2) * u, y - 5 * u + k * 3.4f * u, stroke)
            5 -> { cloud(x, y - 2 * u, 1f); fill.color = Theme.YELLOW; path.reset(); path.moveTo(x, y + 2 * u); path.lineTo(x - 3 * u, y + 7 * u); path.lineTo(x, y + 7 * u); path.lineTo(x - 2 * u, y + 11 * u); path.lineTo(x + 3 * u, y + 5 * u); path.lineTo(x, y + 5 * u); path.close(); c.drawPath(path, fill) }
            6 -> { cloud(x, y - 2 * u, 1f); for (k in -1..1) c.drawCircle(x + k * 4 * u, y + 7 * u, 1.2f * u, fill) }
        }
    }

    /** Top-down drawing of the red runabout (title screen boat card, map marker style). Bow points to +x. */
    fun boatTopDown(cx: Float, cy: Float, len: Float, angleDeg: Float = 0f) {
        val save = c.save(); c.translate(cx, cy); c.rotate(angleDeg)
        val h = len / 2; val b = len * 0.2f
        // hull
        path.reset(); path.moveTo(h, 0f)
        path.cubicTo(h * 0.75f, -b * 0.95f, h * 0.2f, -b, -h * 0.55f, -b); path.lineTo(-h, -b * 0.9f); path.lineTo(-h, b * 0.9f); path.lineTo(-h * 0.55f, b)
        path.cubicTo(h * 0.2f, b, h * 0.75f, b * 0.95f, h, 0f); path.close()
        fill.style = Paint.Style.FILL; fill.color = Color.rgb(214, 38, 38); c.drawPath(path, fill)
        // white deck inset
        c.save(); c.scale(0.86f, 0.8f); fill.color = Color.rgb(240, 241, 238); c.drawPath(path, fill); c.restore()
        fill.color = Color.rgb(205, 218, 214); c.drawRoundRect(-h * 0.5f, -b * 0.55f, h * 0.15f, b * 0.55f, b * 0.2f, b * 0.2f, fill)
        // console + windscreen
        fill.color = Color.rgb(40, 44, 50); c.drawRoundRect(-h * 0.02f, -b * 0.38f, h * 0.18f, b * 0.38f, b * 0.1f, b * 0.1f, fill)
        stroke.color = Color.argb(200, 160, 200, 220); stroke.strokeWidth = len * 0.012f; c.drawArc(RectF(h * 0.08f, -b * 0.5f, h * 0.34f, b * 0.5f), -70f, 140f, false, stroke)
        // seats
        fill.color = Color.rgb(226, 228, 226); c.drawRoundRect(-h * 0.3f, -b * 0.32f, -h * 0.12f, b * 0.32f, b * 0.12f, b * 0.12f, fill)
        c.drawRoundRect(-h * 0.85f, -b * 0.7f, -h * 0.62f, b * 0.7f, b * 0.12f, b * 0.12f, fill)
        // outboard
        fill.color = Color.rgb(30, 32, 36); c.drawRoundRect(-h * 1.14f, -b * 0.3f, -h * 0.94f, b * 0.3f, b * 0.15f, b * 0.15f, fill)
        c.restoreToCount(save)
    }
}
