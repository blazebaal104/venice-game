// Tiny immediate-mode widget kit on Canvas for the menus: every frame the page draws its widgets and a widget
// reports whether it was tapped / changed. Handles taps vs. drags (scrolling lists) and slider dragging.
package com.blazebaal.venice.ui

import android.graphics.*
import android.view.MotionEvent
import kotlin.math.*

class Gui(val ink: Ink) {
    private val dp get() = ink.dp
    // pointer state
    var downX = -1f; var downY = -1f; var curX = -1f; var curY = -1f
    var pressed = false; private var dragging = false
    private var tapX = -1f; private var tapY = -1f
    var scrollDy = 0f
    private var activeSlider: String? = null
    var onFeedback: (() -> Unit)? = null

    fun touch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; curX = e.x; curY = e.y; pressed = true; dragging = false; activeSlider = null }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(e.x - downX, e.y - downY) > 10 * dp) dragging = true
                if (dragging && activeSlider == null) scrollDy += e.y - curY
                curX = e.x; curY = e.y
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) { tapX = e.x; tapY = e.y }
                pressed = false; dragging = false; activeSlider = null
            }
            MotionEvent.ACTION_CANCEL -> { pressed = false; dragging = false; activeSlider = null }
        }
    }

    /** Call after drawing a frame: a tap only counts for the frame that follows it. */
    fun endFrame() { tapX = -1f; tapY = -1f }
    fun consumeScroll(): Float { val d = scrollDy; scrollDy = 0f; return d }

    fun tapped(r: RectF): Boolean {
        if (tapX >= 0 && r.contains(tapX, tapY)) { tapX = -1f; tapY = -1f; onFeedback?.invoke(); return true }
        return false
    }
    fun tappedAnywhere(): PointF? = if (tapX >= 0) PointF(tapX, tapY).also { tapX = -1f; tapY = -1f } else null
    private fun held(r: RectF) = pressed && !dragging && r.contains(downX, downY) && r.contains(curX, curY)

    // ---------------------------------------------------------------- widgets
    /** Big menu button. style 0 = glass, 1 = primary (yellow), 2 = danger outline */
    fun button(r: RectF, label: String, style: Int = 0, icon: String? = null, sub: String? = null): Boolean {
        val c = ink.c; val h = held(r)
        val rad = 12 * dp
        when (style) {
            1 -> { ink.fill.style = Paint.Style.FILL; ink.fill.color = if (h) Color.rgb(230, 175, 30) else Theme.YELLOW; c.drawRoundRect(r, rad, rad, ink.fill) }
            else -> ink.glass(r, rad, if (h) Theme.GLASS_HI else Theme.GLASS)
        }
        val fg = when (style) { 1 -> Theme.INK; 2 -> Theme.RED; else -> Theme.TEXT }
        var x = r.left + 18 * dp
        if (icon != null) { ink.icon(icon, x + 10 * dp, r.centerY(), fg); x += 32 * dp }
        val big = min(20f, r.height() / dp * 0.36f)
        if (sub == null) ink.label(label, x, r.centerY() + big * 0.36f * dp, big, fg, spacing = 0.04f)
        else {
            ink.label(label, x, r.centerY() - 1 * dp, big * 0.9f, fg, spacing = 0.04f)
            ink.label(sub, x, r.centerY() + 15 * dp, 11f, if (style == 1) Color.argb(170, 12, 16, 24) else Theme.TEXT_DIM, face = Theme.condensed)
        }
        return tapped(r)
    }

    fun iconButton(cx: Float, cy: Float, radius: Float, icon: String, active: Boolean = false): Boolean {
        val r = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val h = held(r)
        ink.glassCircle(cx, cy, radius, if (h) Theme.GLASS_HI else Theme.GLASS)
        ink.icon(icon, cx, cy, if (active) Theme.YELLOW else Theme.TEXT)
        return tapped(r)
    }

    /** Row of choices; returns the selected index (changed if one was tapped). */
    fun segmented(r: RectF, options: List<String>, sel: Int): Int {
        val c = ink.c; var out = sel
        ink.fill.style = Paint.Style.FILL; ink.fill.color = Color.argb(60, 255, 255, 255)
        c.drawRoundRect(r, 9 * dp, 9 * dp, ink.fill)
        val w = r.width() / options.size
        for ((i, o) in options.withIndex()) {
            val cell = RectF(r.left + i * w + 2 * dp, r.top + 2 * dp, r.left + (i + 1) * w - 2 * dp, r.bottom - 2 * dp)
            if (i == sel) { ink.fill.color = Theme.YELLOW; c.drawRoundRect(cell, 7 * dp, 7 * dp, ink.fill) }
            var sz = 13f
            while (sz > 8.5f && ink.width(o, sz) > cell.width() - 8 * dp) sz -= 0.5f
            ink.label(o, cell.centerX(), cell.centerY() + sz * 0.35f * dp, sz, if (i == sel) Theme.INK else Theme.TEXT, Paint.Align.CENTER)
            if (i != sel && tapped(cell)) out = i
        }
        return out
    }

    fun toggle(r: RectF, on: Boolean): Boolean {
        val c = ink.c
        val tw = 46 * dp; val th = 26 * dp
        val tr = RectF(r.right - tw, r.centerY() - th / 2, r.right, r.centerY() + th / 2)
        ink.fill.style = Paint.Style.FILL; ink.fill.color = if (on) Theme.YELLOW else Color.argb(80, 255, 255, 255)
        c.drawRoundRect(tr, th / 2, th / 2, ink.fill)
        ink.fill.color = if (on) Theme.INK else Theme.TEXT
        c.drawCircle(if (on) tr.right - th / 2 else tr.left + th / 2, tr.centerY(), th / 2 - 4 * dp, ink.fill)
        return if (tapped(r)) !on else on
    }

    /** Horizontal slider; drag anywhere on the track. */
    fun slider(id: String, r: RectF, v: Float, min: Float, max: Float, step: Float = 0f): Float {
        val c = ink.c
        val track = RectF(r.left + 10 * dp, r.centerY() - 3 * dp, r.right - 10 * dp, r.centerY() + 3 * dp)
        var value = v
        if (pressed && activeSlider == null && RectF(r.left, r.top - 6 * dp, r.right, r.bottom + 6 * dp).contains(downX, downY)) activeSlider = id
        if (pressed && activeSlider == id) {
            var t = ((curX - track.left) / track.width()).coerceIn(0f, 1f)
            value = min + (max - min) * t
            if (step > 0f) value = (round((value - min) / step) * step + min).coerceIn(min, max)
            t = (value - min) / (max - min)
        }
        val t = ((value - min) / (max - min)).coerceIn(0f, 1f)
        ink.fill.style = Paint.Style.FILL; ink.fill.color = Color.argb(70, 255, 255, 255); c.drawRoundRect(track, 3 * dp, 3 * dp, ink.fill)
        ink.fill.color = Theme.YELLOW; c.drawRoundRect(RectF(track.left, track.top, track.left + track.width() * t, track.bottom), 3 * dp, 3 * dp, ink.fill)
        val kx = track.left + track.width() * t
        ink.fill.color = Theme.TEXT; c.drawCircle(kx, track.centerY(), 10 * dp, ink.fill)
        ink.fill.color = Theme.YELLOW; c.drawCircle(kx, track.centerY(), 4 * dp, ink.fill)
        return value
    }

    val isDraggingSlider get() = activeSlider != null

    /** "‹ value ›" picker for long option lists; returns the new index. */
    fun stepper(r: RectF, options: List<String>, sel: Int): Int {
        val c = ink.c
        ink.fill.style = Paint.Style.FILL; ink.fill.color = Color.argb(60, 255, 255, 255)
        c.drawRoundRect(r, 9 * dp, 9 * dp, ink.fill)
        val bw = r.height()
        val left = RectF(r.left, r.top, r.left + bw, r.bottom); val right = RectF(r.right - bw, r.top, r.right, r.bottom)
        ink.icon("back", left.centerX(), left.centerY(), Theme.YELLOW); ink.icon("next", right.centerX(), right.centerY(), Theme.YELLOW)
        var sz = 13.5f; val t = options[sel]
        while (sz > 9f && ink.width(t, sz) > r.width() - 2 * bw - 8 * dp) sz -= 0.5f
        ink.label(t, r.centerX(), r.centerY() + sz * 0.35f * dp, sz, Theme.TEXT, Paint.Align.CENTER)
        if (tapped(left)) return (sel + options.size - 1) % options.size
        if (tapped(right)) return (sel + 1) % options.size
        return sel
    }
}
