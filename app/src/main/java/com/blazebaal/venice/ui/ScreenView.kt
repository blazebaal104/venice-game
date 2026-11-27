// Full-screen pages over the game: title screen, pause menu, settings, credits and the full-screen map.
// Transparent and touch-through while playing (Screen.NONE).
package com.blazebaal.venice.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.blazebaal.venice.core.Places
import com.blazebaal.venice.live.Weather
import java.util.Calendar
import kotlin.math.*

/** What the pages need from the game (implemented by MainActivity). */
interface GameActions {
    fun prebuildCity()
    fun clearCache()
    fun cachedTiles(): Int
    fun totalTiles(): Int
    fun quit()
}

class ScreenView(ctx: Context, private val actions: GameActions) : View(ctx) {
    private val ink = Ink(ctx)
    private val gui = Gui(ink)
    private val dp = ink.baseDp
    private lateinit var c: Canvas
    private var t0 = System.nanoTime()
    private var settingsReturn = Screen.MENU
    private var creditsReturn = Screen.MENU
    private var mapReturn = Screen.NONE
    private var tab = 0
    private var scrollY = 0f; private var contentH = 0f
    private var teleportOpen = false
    private var lastScreen = Screen.MENU

    // map state
    private var mapCx = 0f; private var mapCy = 0f; private var mapScale = 0.4f    // screen px per metre
    private var selX = Float.NaN; private var selY = Float.NaN; private var selName = ""
    private var lastPx = 0f; private var lastPy = 0f; private var scaling = false
    private val scaleDet = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { scaling = true; return true }
        override fun onScale(d: ScaleGestureDetector): Boolean { zoomAt(d.scaleFactor, d.focusX, d.focusY); return true }
    })

    init {
        gui.onFeedback = { if (Settings.vibration) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
        val tick = object : Runnable { override fun run() { if (Controls.screen != Screen.NONE) invalidate(); postDelayed(this, 33) } }
        post(tick)
    }

    // ---------------------------------------------------------------- navigation
    fun open(s: Screen) {
        val from = Controls.screen
        when (s) {
            Screen.SETTINGS -> { settingsReturn = if (from == Screen.SETTINGS) settingsReturn else from; scrollY = 0f }
            Screen.CREDITS -> creditsReturn = if (from == Screen.CREDITS) creditsReturn else from
            Screen.MAP -> { mapReturn = if (from == Screen.MAP) mapReturn else from; centreMapOnBoat(); selX = Float.NaN }
            else -> {}
        }
        if (s != Screen.MAP) MapLayer.dropRegion()
        teleportOpen = false
        gui.consumeScroll()
        Controls.screen = s
        invalidate()
    }

    /** Android back button. Returns false when the app should close. */
    fun back(): Boolean {
        when (Controls.screen) {
            Screen.MENU -> return false
            Screen.NONE -> open(Screen.PAUSE)
            Screen.PAUSE -> if (teleportOpen) teleportOpen = false else open(Screen.NONE)
            Screen.SETTINGS -> { Settings.save(context); open(settingsReturn) }
            Screen.CREDITS -> open(creditsReturn)
            Screen.MAP -> open(mapReturn)
        }
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (Controls.screen == Screen.NONE) return false
        if (Controls.screen == Screen.MAP) {
            scaleDet.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastPx = e.x; lastPy = e.y; scaling = false }
                MotionEvent.ACTION_MOVE -> if (!scaling && e.pointerCount == 1) {
                    mapCx -= (e.x - lastPx) / mapScale; mapCy += (e.y - lastPy) / mapScale
                    lastPx = e.x; lastPy = e.y
                }
                MotionEvent.ACTION_POINTER_UP -> { scaling = true }
            }
        }
        if (Controls.screen == Screen.MENU && menuTouch(e)) { invalidate(); return true }
        gui.touch(e)
        invalidate()
        return true
    }

    // ---------------------------------------------------------------- drawing
    override fun onDraw(canvas: Canvas) {
        val sc = Controls.screen
        if (sc != lastScreen) {
            // pages opened straight from the HUD (minimap tap, pause button) return to the game
            if (sc == Screen.MAP) { if (lastScreen == Screen.NONE) mapReturn = Screen.NONE; centreMapOnBoat(); selX = Float.NaN }
            if (sc == Screen.PAUSE) teleportOpen = false
            gui.consumeScroll(); lastScreen = sc
        }
        if (sc == Screen.NONE) return
        c = canvas; ink.c = canvas
        when (sc) {
            Screen.MENU -> drawMenu()
            Screen.PAUSE -> drawPause()
            Screen.SETTINGS -> drawSettings()
            Screen.CREDITS -> drawCredits()
            Screen.MAP -> drawMap()
            else -> {}
        }
        gui.endFrame()
    }

    private val W get() = width.toFloat()
    private val H get() = height.toFloat()
    private val m get() = 20 * dp

    private fun dim(a: Int) { ink.fill.style = Paint.Style.FILL; ink.fill.color = Color.argb(a, 6, 9, 14); c.drawRect(0f, 0f, W, H, ink.fill) }

    private fun title(text: String, x: Float, y: Float, size: Float = 26f) {
        ink.stripes(RectF(x, y - size * dp * 0.78f, x + 8 * dp, y), 6f)
        ink.label(text, x + 16 * dp, y, size, Theme.TEXT, face = Theme.black, spacing = 0.02f)
    }

    // ================================================================ TITLE SCREEN
    // The 3D flyover of Venice and the turning boat are drawn by the GL renderer underneath; this page only draws
    // the panels, text and buttons, and leaves the boat's window transparent.
    private var boatDrag = false; private var boatLastX = 0f
    private val boatWin = RectF()

    private fun drawMenu() {
        // gentle darkening behind the title column and the bottom line, never over the boat window
        ink.fill.shader = LinearGradient(W * 0.5f, 0f, W, 0f, intArrayOf(Color.argb(0, 6, 9, 14), Color.argb(170, 6, 9, 14)), null, Shader.TileMode.CLAMP)
        c.drawRect(W * 0.5f, 0f, W, H, ink.fill); ink.fill.shader = null

        // ---- boat card (left): header, transparent 3D window, specs
        val cardW = min(320 * dp, W * 0.34f)
        val x0 = m; val x1 = m + cardW
        val header = RectF(x0, m, x1, m + 64 * dp)
        ink.glass(header, 16 * dp)
        ink.label("LA TUA BARCA", header.left + 18 * dp, header.top + 24 * dp, 10.5f, Theme.YELLOW, spacing = 0.16f)
        ink.label("Lampo 5.4", header.left + 18 * dp, header.top + 50 * dp, 22f, Theme.TEXT, face = Theme.black)
        ink.label("1 / 1", header.right - 18 * dp, header.top + 50 * dp, 12f, Theme.TEXT_FAINT, Paint.Align.RIGHT)
        val specsH = 132 * dp
        val specs = RectF(x0, H - m - specsH, x1, H - m)
        boatWin.set(x0, header.bottom + 8 * dp, x1, specs.top - 8 * dp)
        if (boatWin.height() > 60 * dp) {
            Controls.menuBoatRect = intArrayOf(boatWin.left.toInt(), boatWin.top.toInt(), boatWin.right.toInt(), boatWin.bottom.toInt())
            ink.stroke.color = Theme.EDGE; ink.stroke.strokeWidth = 1.5f * dp
            c.drawRoundRect(boatWin, 16 * dp, 16 * dp, ink.stroke)
            ink.label(if (boatDrag) "" else "TRASCINA PER RUOTARE", boatWin.centerX(), boatWin.bottom - 12 * dp, 9f, Theme.TEXT_FAINT, Paint.Align.CENTER, spacing = 0.14f)
        } else Controls.menuBoatRect = IntArray(4)
        ink.glass(specs, 16 * dp)
        ink.label("Barchino in vetroresina · fuoribordo 115 cv", specs.left + 18 * dp, specs.top + 24 * dp, 11f, Theme.TEXT_DIM, face = Theme.condensed)
        val stats = listOf(Triple("VELOCITÀ", 0.92f, "140 km/h"), Triple("ACCELERAZIONE", 0.72f, "0-100 in 9 s"), Triple("MANOVRABILITÀ", 0.8f, ""))
        for ((i, st) in stats.withIndex()) {
            val y = specs.top + 50 * dp + i * 26 * dp
            ink.label(st.first, specs.left + 18 * dp, y + 4 * dp, 9.5f, Theme.TEXT_DIM, spacing = 0.1f)
            val valueW = if (st.third.isEmpty()) 0f else ink.width(st.third, 10.5f) + 10 * dp
            val bar = RectF(specs.left + 120 * dp, y - 2 * dp, specs.right - 18 * dp - valueW, y + 4 * dp)
            ink.fill.style = Paint.Style.FILL; ink.fill.color = Color.argb(50, 255, 255, 255); c.drawRoundRect(bar, 3 * dp, 3 * dp, ink.fill)
            ink.fill.color = Theme.YELLOW; c.drawRoundRect(RectF(bar.left, bar.top, bar.left + bar.width() * st.second, bar.bottom), 3 * dp, 3 * dp, ink.fill)
            if (st.third.isNotEmpty()) ink.label(st.third, specs.right - 18 * dp, y + 4 * dp, 10.5f, Theme.TEXT, Paint.Align.RIGHT)
        }
        ink.label("Altre barche in arrivo", specs.left + 18 * dp, specs.bottom - 12 * dp, 9.5f, Theme.TEXT_FAINT, face = Theme.condensed)

        // ---- title + buttons (right), sized to fit the height
        val colW = min(330 * dp, W * 0.34f)
        val cx0 = W - m - colW
        val titleSize = min(60f, colW / dp / 4.3f)
        val bh = min(54 * dp, (H - 2 * m - 150 * dp) / 3.4f)
        val blockH = 40 * dp + titleSize * dp + 22 * dp + bh * 2.7f + 24 * dp
        var y = max(m, (H - blockH) / 2f - 10 * dp)
        ink.stripes(RectF(cx0, y, cx0 + colW, y + 8 * dp), 7f)
        y += 14 * dp + titleSize * dp
        ink.text.setShadowLayer(8 * dp, 0f, 2 * dp, Color.argb(160, 0, 0, 0))
        ink.label("GTACTV", cx0 - 2 * dp, y, titleSize, Theme.TEXT, face = Theme.black, spacing = -0.01f)
        ink.label("VENEZIA · LAGUNA · SCALA 1:1", cx0, y + 20 * dp, 10.5f, Theme.YELLOW, spacing = 0.2f)
        ink.text.clearShadowLayer()
        y += 40 * dp
        val loaded = !GameState.loading
        val sub = if (loaded) "Venezia è pronta" else "Caricamento ${(100f * GameState.tilesLoaded / max(1, min(4, GameState.tilesWanted))).roundToInt().coerceAtMost(99)}%"
        if (gui.button(RectF(cx0, y, cx0 + colW, y + bh), "GIOCA", 1, "play", sub)) open(Screen.NONE)
        y += bh + 10 * dp
        val half = (colW - 10 * dp) / 2
        if (gui.button(RectF(cx0, y, cx0 + half, y + bh * 0.85f), "IMPOSTAZIONI", 0, "gear")) open(Screen.SETTINGS)
        if (gui.button(RectF(cx0 + half + 10 * dp, y, cx0 + colW, y + bh * 0.85f), "CREDITI", 0, "info")) open(Screen.CREDITS)

        // ---- bottom right: live Venice weather + vaporetti status
        val wtxt = if (Weather.live) "VENEZIA ORA · ${Weather.temperature.roundToInt()}° · ${Weather.label()} · VENTO ${Weather.windKmh.roundToInt()} KM/H ${compass(Weather.windDirDeg)}"
                   else "VENEZIA · ${Weather.temperature.roundToInt()}° · METEO REALE IN ATTESA DI CONNESSIONE"
        var ws = 10f; while (ws > 7.5f && ink.width(wtxt, ws, spacing = 0.06f) > colW - 24 * dp) ws -= 0.5f
        ink.text.setShadowLayer(4 * dp, 0f, 1 * dp, Color.BLACK)
        ink.weatherIcon(Weather.icon(), cx0 + 8 * dp, H - m - 5 * dp, Theme.TEXT, 0.8f)
        ink.label(wtxt, cx0 + 24 * dp, H - m - 1 * dp, ws, Theme.TEXT_DIM, spacing = 0.06f)
        ink.text.clearShadowLayer()
    }

    /** drag on the boat window turns the boat (handled before the widget kit) */
    private fun menuTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (boatWin.contains(e.x, e.y)) { boatDrag = true; boatLastX = e.x; Controls.menuBoatDragging = true; return true }
            MotionEvent.ACTION_MOVE -> if (boatDrag) { Controls.menuBoatYaw += (e.x - boatLastX) * 0.012f; boatLastX = e.x; return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (boatDrag) { boatDrag = false; Controls.menuBoatDragging = false; return true }
        }
        return boatDrag
    }

    private fun compass(deg: Float): String = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")[(((deg % 360f) + 360f + 22.5f) / 45f).toInt() % 8]

    // ================================================================ PAUSE
    private fun drawPause() {
        dim(150)
        val colW = min(300 * dp, W * 0.36f)
        val x0 = m + 10 * dp
        title("PAUSA", x0, m + 34 * dp, 28f)
        val items = listOf("RIPRENDI" to "play", "MAPPA" to "map", "IMPOSTAZIONI" to "gear", "RIPORTA IN ACQUA" to "water", "TELETRASPORTO" to "teleport", "MENU PRINCIPALE" to "home")
        val top = m + 54 * dp; val avail = H - top - m
        val bh = min(48 * dp, avail / items.size - 8 * dp)
        for ((i, it) in items.withIndex()) {
            val r = RectF(x0, top + i * (bh + 8 * dp), x0 + colW, top + i * (bh + 8 * dp) + bh)
            if (gui.button(r, it.first, if (i == 0) 1 else 0, it.second)) when (i) {
                0 -> open(Screen.NONE)
                1 -> open(Screen.MAP)
                2 -> open(Screen.SETTINGS)
                3 -> { Controls.requestRespawn = true; open(Screen.NONE) }
                4 -> teleportOpen = !teleportOpen
                5 -> open(Screen.MENU)
            }
        }
        val rx = x0 + colW + 20 * dp; val rw = W - rx - m
        if (teleportOpen) drawTeleport(RectF(rx, top, rx + rw, H - m)) else drawTripCard(RectF(rx, top, rx + rw, H - m))
    }

    private fun drawTripCard(r: RectF) {
        val card = RectF(r.left, r.top, r.right, min(r.bottom, r.top + 220 * dp))
        ink.glass(card, 16 * dp)
        ink.label("IL TUO GIRO", card.left + 18 * dp, card.top + 26 * dp, 11f, Theme.YELLOW, spacing = 0.15f)
        ink.label(Places.nameAt(GameState.x, GameState.y, GameState.lat0, GameState.lon0), card.left + 18 * dp, card.top + 52 * dp, 20f, Theme.TEXT, face = Theme.black)
        val d = GameState.distanceM
        val st = listOf(
            "DISTANZA" to (if (d < 1000f) "${d.roundToInt()} m" else String.format("%.1f km", d / 1000f)),
            "VELOCITÀ MAX" to "${Settings.speed(GameState.topSpeedKmh).roundToInt()} ${Settings.speedLabel().lowercase()}",
            "TEMPO" to String.format("%d:%02d", (GameState.tripSeconds / 60).toInt(), (GameState.tripSeconds % 60).toInt()),
        )
        val cw = (card.width() - 36 * dp) / 3
        for ((i, s) in st.withIndex()) {
            val x = card.left + 18 * dp + i * cw
            ink.label(s.first, x, card.top + 86 * dp, 9.5f, Theme.TEXT_DIM, spacing = 0.12f)
            ink.label(s.second, x, card.top + 110 * dp, 20f, Theme.TEXT, face = Theme.black)
        }
        ink.stroke.color = Theme.EDGE; ink.stroke.strokeWidth = 1 * dp; c.drawLine(card.left + 18 * dp, card.top + 130 * dp, card.right - 18 * dp, card.top + 130 * dp, ink.stroke)
        ink.weatherIcon(Weather.icon(), card.left + 30 * dp, card.top + 160 * dp, Theme.TEXT, 1.1f)
        ink.label("${Weather.temperature.roundToInt()}° · ${Weather.label()}", card.left + 52 * dp, card.top + 158 * dp, 14f, Theme.TEXT)
        ink.label(if (Weather.live) "Meteo reale di Venezia · vento ${Weather.windKmh.roundToInt()} km/h da ${compass(Weather.windDirDeg)}" else "Meteo reale: in attesa di connessione",
            card.left + 52 * dp, card.top + 176 * dp, 10.5f, Theme.TEXT_DIM, face = Theme.condensed)
        // trim tip
        if (r.bottom - card.bottom > 70 * dp) {
            val tip = RectF(r.left, card.bottom + 12 * dp, r.right, card.bottom + 76 * dp)
            ink.glass(tip, 14 * dp)
            ink.label("CONSIGLIO · TRIM", tip.left + 16 * dp, tip.top + 22 * dp, 10f, Theme.TEAL, spacing = 0.12f)
            ink.label("Trim giù per partire e virare stretto, poi alzalo fino alla banda verde per la massima velocità.", tip.left + 16 * dp, tip.top + 42 * dp, 11f, Theme.TEXT_DIM, face = Theme.condensed)
            ink.label("Troppo alto e la prua inizia a saltellare (porpoising).", tip.left + 16 * dp, tip.top + 57 * dp, 11f, Theme.TEXT_DIM, face = Theme.condensed)
        }
    }

    private fun drawTeleport(r: RectF) {
        ink.glass(r, 16 * dp)
        ink.label("TELETRASPORTO", r.left + 18 * dp, r.top + 26 * dp, 11f, Theme.YELLOW, spacing = 0.15f)
        val list = MapLayer.named().filter { it.kind == 1 }
        val cols = 2; val gap = 8 * dp
        val bw = (r.width() - 36 * dp - gap) / cols; val bh = 34 * dp
        val rows = (list.size + 1) / 2
        val maxRows = ((r.height() - 46 * dp) / (bh + gap)).toInt().coerceAtLeast(1)
        for ((i, p) in list.withIndex()) {
            val row = i / cols; if (row >= maxRows) break
            val x = r.left + 18 * dp + (i % cols) * (bw + gap); val y = r.top + 40 * dp + row * (bh + gap)
            if (gui.button(RectF(x, y, x + bw, y + bh), p.name.uppercase())) {
                Controls.teleportX = p.x; Controls.teleportY = p.y; open(Screen.NONE)
            }
        }
        if (rows > maxRows) ink.label("…", r.right - 18 * dp, r.bottom - 10 * dp, 14f, Theme.TEXT_DIM, Paint.Align.RIGHT)
    }

    // ================================================================ SETTINGS
    private val tabs = listOf("GRAFICA", "CONTROLLI", "AUDIO", "HUD E MAPPA", "METEO E MONDO", "ARCHIVIO", "INFO")

    private fun drawSettings() {
        if (settingsReturn == Screen.MENU) { drawMenuBackdrop() } else dim(185)
        if (gui.iconButton(m + 22 * dp, m + 22 * dp, 22 * dp, "back")) { Settings.save(context); open(settingsReturn); return }
        title("IMPOSTAZIONI", m + 58 * dp, m + 32 * dp, 24f)
        // tabs
        val tabW = min(190 * dp, W * 0.24f)
        val tabTop = m + 60 * dp
        val th = min(44 * dp, (H - tabTop - m) / tabs.size - 6 * dp)
        for ((i, t) in tabs.withIndex()) {
            val r = RectF(m, tabTop + i * (th + 6 * dp), m + tabW, tabTop + i * (th + 6 * dp) + th)
            val on = i == tab
            if (on) { ink.fill.style = Paint.Style.FILL; ink.fill.color = Theme.YELLOW; c.drawRoundRect(r, 10 * dp, 10 * dp, ink.fill) }
            else ink.glass(r, 10 * dp)
            ink.label(t, r.left + 14 * dp, r.centerY() + 5 * dp, 13f, if (on) Theme.INK else Theme.TEXT, spacing = 0.06f)
            if (!on && gui.tapped(r)) { tab = i; scrollY = 0f }
        }
        // content panel (scrollable)
        val panel = RectF(m + tabW + 16 * dp, tabTop, W - m, H - m)
        ink.glass(panel, 16 * dp)
        if (!gui.isDraggingSlider) scrollY = (scrollY + gui.consumeScroll()).coerceIn(min(0f, panel.height() - contentH - 20 * dp), 0f) else gui.consumeScroll()
        val save = c.save(); c.clipRect(panel)
        var y = panel.top + 18 * dp + scrollY
        val x = panel.left + 20 * dp; val w = panel.width() - 40 * dp
        val start = y
        y = when (tab) {
            0 -> graphics(x, y, w)
            1 -> controls(x, y, w)
            2 -> audio(x, y, w)
            3 -> hud(x, y, w)
            4 -> world(x, y, w)
            5 -> storage(x, y, w)
            else -> info(x, y, w)
        }
        contentH = y - start
        c.restoreToCount(save)
    }

    private fun drawMenuBackdrop() { dim(175) }

    private val ROW get() = 70 * dp
    /** One settings row: label + wrapped description on the left, the control's rect on the right (never overlapping). */
    private fun row(x: Float, y: Float, w: Float, label: String, desc: String? = null, h: Float = 70f): RectF {
        val cw = min(w * 0.46f, 340 * dp)
        val textW = w - cw - 20 * dp
        var ls = 15f; while (ls > 11f && ink.width(label, ls) > textW) ls -= 0.5f
        ink.label(label, x, y + (if (desc == null) 39f else 27f) * dp, ls, Theme.TEXT)
        if (desc != null) {
            // wrap to at most two lines in the label column
            val words = desc.split(' '); var line = ""; var ly = y + 45 * dp; var lines = 0
            for (wd in words) {
                val t = if (line.isEmpty()) wd else "$line $wd"
                if (ink.width(t, 11f, Theme.condensed) > textW && line.isNotEmpty()) {
                    ink.label(if (lines == 1) "$line…" else line, x, ly, 11f, Theme.TEXT_DIM, face = Theme.condensed)
                    lines++; ly += 14 * dp; line = wd
                    if (lines >= 2) { line = ""; break }
                } else line = t
            }
            if (line.isNotEmpty() && lines < 2) ink.label(line, x, ly, 11f, Theme.TEXT_DIM, face = Theme.condensed)
        }
        ink.stroke.color = Theme.EDGE; ink.stroke.strokeWidth = 1 * dp; c.drawLine(x, y + h * dp, x + w, y + h * dp, ink.stroke)
        return RectF(x + w - cw, y + (h * dp - 40 * dp) / 2, x + w, y + (h * dp + 40 * dp) / 2)
    }
    private fun section(x: Float, y: Float, t: String): Float { ink.label(t, x, y + 30 * dp, 10.5f, Theme.YELLOW, spacing = 0.18f); return y + 40 * dp }

    private fun graphics(x: Float, y0: Float, w: Float): Float {
        var y = section(x, y0, "QUALITÀ")
        val q = gui.segmented(row(x, y, w, "Preimpostazione", "Imposta tutto il resto in un colpo"), listOf("Prestazioni", "Bilanciata", "Qualità", "Ultra"), Settings.quality)
        if (q != Settings.quality) Settings.applyPreset(q)
        y += ROW
        y = section(x, y, "DISTANZE (1 CHUNK = 256 m)")
        Settings.renderDistance = gui.slider("rd", row(x, y, w, "Distanza visiva", String.format("%.1f chunk · %d m · oltre: nebbia, niente caricato", Settings.chunks(), Settings.renderDistance.roundToInt()).replace('.', ',')), Settings.renderDistance, 256f, 1280f, 32f)
        y += ROW
        Settings.vegetationDistance = gui.slider("vd", row(x, y, w, "Distanza vegetazione", "${Settings.vegetationDistance.roundToInt()} m · alberi e giardini"), Settings.vegetationDistance, 60f, 400f, 10f)
        y += ROW
        Settings.detailDistance = gui.slider("dd", row(x, y, w, "Distanza dettagli", "${Settings.detailDistance.roundToInt()} m · balconi, persiane, comignoli"), Settings.detailDistance, 100f, 450f, 10f)
        y += ROW
        Settings.fogDensity = gui.segmented(row(x, y, w, "Nebbia", "Quanto presto la nebbia copre l'orizzonte"), listOf("Leggera", "Normale", "Fitta"), Settings.fogDensity)
        y += ROW
        y = section(x, y, "IMMAGINE")
        val resOpts = listOf(0, 70, 85, 100)
        val ri = gui.segmented(row(x, y, w, "Risoluzione", if (Settings.resolution == 0) "Dinamica: scende (mai sotto il ${(Settings.minScale() * 100).roundToInt()}%) solo se servono FPS" else "Fissa al ${Settings.resolution}%"),
            listOf("Dinamica", "70%", "85%", "100%"), resOpts.indexOf(Settings.resolution).coerceAtLeast(0))
        Settings.resolution = resOpts[ri]
        y += ROW
        val fpsOpts = listOf(30, 60, 90, 120)
        Settings.fpsTarget = fpsOpts[gui.segmented(row(x, y, w, "Fotogrammi al secondo", "Più alto = meno risoluzione dinamica"), fpsOpts.map { "$it" }, fpsOpts.indexOf(Settings.fpsTarget).coerceAtLeast(1))]
        y += ROW
        Settings.sharpen = gui.slider("sh", row(x, y, w, "Nitidezza", "${(Settings.sharpen * 100).roundToInt()}%"), Settings.sharpen, 0f, 1f, 0.05f)
        y += ROW
        Settings.shadows = gui.toggle(row(x, y, w, "Ombre della città", "Ombre del sole su case e canali"), Settings.shadows); y += ROW
        Settings.reflections = gui.toggle(row(x, y, w, "Riflessi sull'acqua", "Palazzi riflessi nei canali"), Settings.reflections); y += ROW
        Settings.bloom = gui.toggle(row(x, y, w, "Bagliore (bloom)", "Riflessi del sole che brillano"), Settings.bloom); y += ROW
        Settings.showFps = gui.toggle(row(x, y, w, "Mostra FPS", "Contatore in alto a sinistra"), Settings.showFps); y += ROW
        return y
    }

    private fun controls(x: Float, y0: Float, w: Float): Float {
        var y = section(x, y0, "GUIDA")
        Settings.analogSteering = gui.segmented(row(x, y, w, "Sterzo", if (Settings.analogSteering) "Trascina il timone: sterzata progressiva" else "Due frecce: tutto a destra o a sinistra"), listOf("Timone", "Frecce"), if (Settings.analogSteering) 0 else 1) == 0
        y += ROW
        Settings.steerSensitivity = gui.slider("ss", row(x, y, w, "Sensibilità sterzo", "${(Settings.steerSensitivity * 100).roundToInt()}%"), Settings.steerSensitivity, 0.5f, 1.5f, 0.05f); y += ROW
        Settings.cameraSensitivity = gui.slider("cs", row(x, y, w, "Sensibilità telecamera", "${(Settings.cameraSensitivity * 100).roundToInt()}%"), Settings.cameraSensitivity, 0.4f, 2f, 0.1f); y += ROW
        Settings.vibration = gui.toggle(row(x, y, w, "Vibrazione", "Scatti della manetta e dei pulsanti"), Settings.vibration); y += ROW
        y = section(x, y, "COMANDI")
        for (l in listOf("Manetta: trascina su e giù · doppio tocco = folle", "Trim + / −: assetto del fuoribordo (banda verde = massima velocità)", "Trascina sullo schermo: ruota la telecamera", "Tastiera: W/S gas e retro, A/D sterzo")) {
            ink.label("·  $l", x, y + 18 * dp, 12f, Theme.TEXT_DIM, face = Theme.condensed); y += 24 * dp
        }
        return y
    }

    private fun audio(x: Float, y0: Float, w: Float): Float {
        var y = section(x, y0, "VOLUME")
        Settings.volMaster = gui.slider("vm", row(x, y, w, "Generale", "${(Settings.volMaster * 100).roundToInt()}%"), Settings.volMaster, 0f, 1f, 0.05f); y += ROW
        Settings.volEngine = gui.slider("ve", row(x, y, w, "Motore", "${(Settings.volEngine * 100).roundToInt()}%"), Settings.volEngine, 0f, 1f, 0.05f); y += ROW
        Settings.volAmbience = gui.slider("va", row(x, y, w, "Ambiente", "Acqua, gabbiani, campane · ${(Settings.volAmbience * 100).roundToInt()}%"), Settings.volAmbience, 0f, 1f, 0.05f); y += ROW
        ink.label("Tutti i suoni sono generati in tempo reale: motore, acqua, vaporetti, gabbiani, campane, pioggia.", x, y + 22 * dp, 11.5f, Theme.TEXT_DIM, face = Theme.condensed)
        return y + 34 * dp
    }

    private fun hud(x: Float, y0: Float, w: Float): Float {
        var y = section(x, y0, "HUD")
        Settings.speedUnit = gui.segmented(row(x, y, w, "Unità di velocità"), listOf("km/h", "nodi", "mph"), Settings.speedUnit); y += ROW
        Settings.hudScale = gui.slider("hs", row(x, y, w, "Dimensione HUD", "${(Settings.hudScale * 100).roundToInt()}%"), Settings.hudScale, 0.8f, 1.25f, 0.05f); y += ROW
        y = section(x, y, "MINIMAPPA")
        Settings.minimapNorthUp = gui.segmented(row(x, y, w, "Orientamento"), listOf("Ruota con la barca", "Nord in alto"), if (Settings.minimapNorthUp) 1 else 0) == 1; y += ROW
        return y
    }

    private fun world(x: Float, y0: Float, w: Float): Float {
        var y = section(x, y0, "METEO REALE")
        Settings.liveWeather = gui.toggle(row(x, y, w, "Meteo reale di Venezia", "Nuvole, pioggia, nebbia, vento e onde come fuori dalla finestra"), Settings.liveWeather); y += ROW
        Weather.override = gui.stepper(row(x, y, w, "Anteprima meteo", "Prova un tempo diverso da quello reale (solo per vederlo)"), listOf("Reale", "Sereno", "Nuvoloso", "Pioggia", "Temporale", "Nebbia"), Weather.override); y += ROW
        val st = if (!Weather.live) "In attesa di connessione · uso il clima medio del mese (${Weather.temperature.roundToInt()}°)"
        else { val cal = Calendar.getInstance().apply { timeInMillis = Weather.updatedAt }
            String.format("Aggiornato alle %02d:%02d · %d° · %s · vento %d km/h da %s · visibilità %s", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), Weather.temperature.roundToInt(), Weather.label().lowercase(), Weather.windKmh.roundToInt(), compass(Weather.windDirDeg), if (Weather.visibilityM >= 10000f) "oltre 10 km" else "${(Weather.visibilityM / 1000f).roundToInt()} km") }
        ink.label(st, x, y + 18 * dp, 11.5f, Theme.TEXT_DIM, face = Theme.condensed); y += 30 * dp
        ink.label("Dati: Open-Meteo.com (CC BY 4.0), aggiornati ogni 10 minuti. Pioggia e vento sulle onde: in arrivo.", x, y + 18 * dp, 11.5f, Theme.TEXT_FAINT, face = Theme.condensed); y += 36 * dp
        y = section(x, y, "VAPORETTI DAL VIVO")
        Settings.liveVaporetti = gui.toggle(row(x, y, w, "Vaporetti ACTV in tempo reale", "I battelli veri, sulla loro linea, dove sono adesso"), Settings.liveVaporetti); y += ROW
        com.blazebaal.venice.live.Vaporetti.enabled = Settings.liveVaporetti
        ink.label(com.blazebaal.venice.live.Vaporetti.status, x, y + 18 * dp, 11.5f, Theme.TEXT_DIM, face = Theme.condensed); y += 26 * dp
        ink.label("Dati: ACTV S.p.A., open data GTFS / GTFS-Realtime (Comune di Venezia), aggiornati ogni 15 secondi.", x, y + 18 * dp, 11.5f, Theme.TEXT_FAINT, face = Theme.condensed)
        return y + 34 * dp
    }

    private fun storage(x: Float, y0: Float, w: Float): Float {
        var y = section(x, y0, "VENEZIA OFFLINE")
        val cached = actions.cachedTiles(); val total = actions.totalTiles()
        val pb = GameState.prebuild
        val desc = if (pb >= 0f) "Preparazione… ${(pb * 100).roundToInt()}%" else "$cached di $total chunk già pronti in memoria"
        val r = row(x, y, w, "Prepara tutta Venezia", desc)
        if (pb < 0f && gui.button(r, if (cached >= total) "PRONTA" else "PREPARA", 1)) actions.prebuildCity()
        if (pb >= 0f) { ink.fill.style = Paint.Style.FILL; ink.fill.color = Color.argb(60, 255, 255, 255); c.drawRoundRect(r, 10 * dp, 10 * dp, ink.fill); ink.stripes(RectF(r.left, r.top, r.left + r.width() * pb, r.bottom), 8f) }
        y += ROW
        ink.label("Costruisce una volta sola tutti i palazzi e li salva sul telefono: poi la città si carica", x, y + 16 * dp, 11.5f, Theme.TEXT_DIM, face = Theme.condensed)
        ink.label("dalla memoria invece di essere ricostruita mentre guidi (meno scatti a tutta velocità).", x, y + 32 * dp, 11.5f, Theme.TEXT_DIM, face = Theme.condensed)
        y += 48 * dp
        val r2 = row(x, y, w, "Svuota la cache", "Libera spazio; i chunk verranno ricostruiti")
        if (gui.button(r2, "SVUOTA", 2)) actions.clearCache()
        return y + 60 * dp
    }

    private val creditLines = listOf(
        "GTACTV" to 0,
        "Un gioco di barche in una Venezia 1:1, scritto da zero in Kotlin su OpenGL ES." to 2,
        "DATI DELLA MAPPA" to 1,
        "© OpenStreetMap contributors · Open Database License (ODbL). Il database derivato venice.map è disponibile sotto ODbL." to 2,
        "Edifici: Regione del Veneto – L.R. n. 28/76 – Formazione della Carta Tecnica Regionale · IODL 2.0" to 2,
        "Altezze: Copernicus Land Monitoring Service, Urban Atlas Building Block Height 2021 (Unione Europea)" to 2,
        "METEO" to 1,
        "Meteo in tempo reale: Open-Meteo.com · CC BY 4.0" to 2,
        "VAPORETTI" to 1,
        "Posizioni e orari dei vaporetti: ACTV S.p.A., open data (GTFS e GTFS-Realtime) secondo le regole open data del Comune di Venezia." to 2,
        "MODELLI" to 1,
        "Barca, ponti, pontili ACTV e monumenti modellati in Blender via script." to 2,
    )
    private fun info(x: Float, y0: Float, w: Float): Float {
        var y = y0
        for ((t, k) in creditLines) {
            when (k) {
                0 -> { ink.label(t, x, y + 30 * dp, 28f, Theme.TEXT, face = Theme.black); y += 40 * dp }
                1 -> { y += 8 * dp; ink.label(t, x, y + 18 * dp, 10f, Theme.YELLOW, spacing = 0.18f); y += 26 * dp }
                else -> { y = wrap(t, x, y, w, 12f); y += 4 * dp }
            }
        }
        return y + 10 * dp
    }
    private fun wrap(t: String, x: Float, y0: Float, w: Float, size: Float): Float {
        var y = y0; var line = ""
        for (word in t.split(' ')) {
            val tryL = if (line.isEmpty()) word else "$line $word"
            if (ink.width(tryL, size, Theme.condensed) > w && line.isNotEmpty()) { ink.label(line, x, y + 16 * dp, size, Theme.TEXT_DIM, face = Theme.condensed); y += 18 * dp; line = word } else line = tryL
        }
        if (line.isNotEmpty()) { ink.label(line, x, y + 16 * dp, size, Theme.TEXT_DIM, face = Theme.condensed); y += 18 * dp }
        return y
    }

    // ================================================================ CREDITS (full page)
    private fun drawCredits() {
        drawMenuBackdrop()
        if (gui.iconButton(m + 22 * dp, m + 22 * dp, 22 * dp, "back")) { open(creditsReturn); return }
        title("CREDITI", m + 58 * dp, m + 32 * dp, 24f)
        val panel = RectF(m, m + 60 * dp, W - m, H - m)
        ink.glass(panel, 16 * dp)
        scrollY = (scrollY + gui.consumeScroll()).coerceIn(min(0f, panel.height() - contentH - 20 * dp), 0f)
        val save = c.save(); c.clipRect(panel)
        val start = panel.top + 10 * dp + scrollY
        contentH = info(panel.left + 24 * dp, start, panel.width() - 48 * dp) - start
        c.restoreToCount(save)
    }

    // ================================================================ FULL-SCREEN MAP
    private fun centreMapOnBoat() { mapCx = GameState.x; mapCy = GameState.y; mapScale = 0.45f * dp }

    private fun zoomAt(f: Float, fx: Float, fy: Float) {
        val old = mapScale
        mapScale = (mapScale * f).coerceIn(0.03f * dp, 3f * dp)
        // keep the point under the fingers fixed
        val wx = mapCx + (fx - W / 2) / old; val wy = mapCy - (fy - H / 2) / old
        mapCx = wx - (fx - W / 2) / mapScale; mapCy = wy + (fy - H / 2) / mapScale
    }

    private fun drawMap() {
        ink.fill.style = Paint.Style.FILL; ink.fill.color = Color.rgb(29, 78, 86); c.drawRect(0f, 0f, W, H, ink.fill)
        // sharp tiles when zoomed in past the half-resolution copy (6 m/px)
        val sharp = mapScale * 6f > 1.05f
        if (sharp) {
            val halfW = W / 2 / mapScale; val halfH = H / 2 / mapScale
            val want = Rect(MapLayer.px(mapCx - halfW).toInt(), MapLayer.py(mapCy + halfH).toInt(), MapLayer.px(mapCx + halfW).toInt(), MapLayer.py(mapCy - halfH).toInt())
            MapLayer.requestRegion(want) { postInvalidate() }
        }
        val save = c.save(); c.translate(W / 2, H / 2)
        MapLayer.draw(ink, mapCx, mapCy, mapScale, 0f, sharp)
        fun sp(x: Float, y: Float) = MapLayer.toScreen(x, y, mapCx, mapCy, mapScale, 0f)
        // labels
        val z = mapScale / dp
        for (p in MapLayer.named()) {
            val q = sp(p.x, p.y)
            if (abs(q.x) > W / 2 + 100 * dp || abs(q.y) > H / 2 + 40 * dp) continue
            when {
                p.kind == 0 && z < 0.9f -> { ink.text.setShadowLayer(4 * dp, 0f, 1 * dp, Color.BLACK); ink.label(p.name, q.x, q.y, if (z < 0.25f) 12f else 15f, Color.argb(220, 255, 255, 255), Paint.Align.CENTER, spacing = 0.2f); ink.text.clearShadowLayer() }
                p.kind == 1 && z > 0.18f -> { ink.fill.style = Paint.Style.FILL; ink.fill.color = Theme.TEXT; c.drawCircle(q.x, q.y, 3 * dp, ink.fill); ink.text.setShadowLayer(3 * dp, 0f, 1 * dp, Color.BLACK); ink.label(p.name, q.x + 6 * dp, q.y + 4 * dp, 11f, Theme.TEXT); ink.text.clearShadowLayer() }
                p.kind == 2 && z > 0.3f && z < 1.6f -> { ink.text.setShadowLayer(3 * dp, 0f, 1 * dp, Color.BLACK); ink.label(p.name, q.x, q.y, 11f, Color.argb(200, 160, 230, 240), Paint.Align.CENTER, Theme.condensed, 0.15f); ink.text.clearShadowLayer() }
            }
        }
        if (z > 0.55f) for (st in MapLayer.stops) {
            val q = sp(st.x, st.y)
            if (abs(q.x) > W / 2 + 60 * dp || abs(q.y) > H / 2 + 20 * dp) continue
            ink.fill.style = Paint.Style.FILL; ink.fill.color = Theme.YELLOW; c.drawRoundRect(q.x - 3.5f * dp, q.y - 3.5f * dp, q.x + 3.5f * dp, q.y + 3.5f * dp, 1 * dp, 1 * dp, ink.fill)
            if (z > 1.1f) { ink.text.setShadowLayer(3 * dp, 0f, 1 * dp, Color.BLACK); ink.label(st.name, q.x + 6 * dp, q.y - 4 * dp, 10f, Theme.YELLOW, face = Theme.condensed); ink.text.clearShadowLayer() }
        }
        // live vaporetti: arrow + line number
        if (Settings.liveVaporetti) for (v in com.blazebaal.venice.render.LiveBoats.markers) {
            val q = sp(v.x, v.y)
            if (abs(q.x) > W / 2 + 30 * dp || abs(q.y) > H / 2 + 30 * dp) continue
            vaporettoMark(q.x, q.y, v.h, v.line, z > 0.25f)
        }
        // waypoint + route line
        if (GameState.hasWaypoint()) {
            val b = sp(GameState.x, GameState.y); val w = sp(GameState.wpX, GameState.wpY)
            ink.stroke.color = Theme.YELLOW; ink.stroke.strokeWidth = 3 * dp; ink.stroke.pathEffect = DashPathEffect(floatArrayOf(8 * dp, 6 * dp), 0f)
            c.drawLine(b.x, b.y, w.x, w.y, ink.stroke); ink.stroke.pathEffect = null
            ink.icon("pin", w.x, w.y - 10 * dp, Theme.YELLOW, 1.4f)
        }
        // selection
        if (!selX.isNaN()) { val q = sp(selX, selY); ink.icon("pin", q.x, q.y - 10 * dp, Theme.TEXT, 1.4f) }
        // boat
        val b = sp(GameState.x, GameState.y)
        val sv = c.save(); c.translate(b.x, b.y); c.rotate(-Math.toDegrees(GameState.heading.toDouble()).toFloat() + 90f)
        ink.fill.style = Paint.Style.FILL; ink.fill.color = Theme.YELLOW
        ink.path.reset(); ink.path.moveTo(0f, -12 * dp); ink.path.lineTo(8 * dp, 9 * dp); ink.path.lineTo(0f, 4 * dp); ink.path.lineTo(-8 * dp, 9 * dp); ink.path.close()
        c.drawPath(ink.path, ink.fill); ink.stroke.color = Theme.INK; ink.stroke.strokeWidth = 1.5f * dp; c.drawPath(ink.path, ink.stroke)
        c.restoreToCount(sv)
        c.restoreToCount(save)

        // ---- chrome
        if (gui.iconButton(m + 22 * dp, m + 22 * dp, 22 * dp, "back")) { open(mapReturn); return }
        ink.glass(RectF(m + 54 * dp, m, m + 260 * dp, m + 44 * dp), 14 * dp)
        ink.label("MAPPA DI VENEZIA", m + 70 * dp, m + 20 * dp, 12f, Theme.YELLOW, spacing = 0.12f)
        ink.label("Tocca per scegliere una destinazione", m + 70 * dp, m + 36 * dp, 10.5f, Theme.TEXT_DIM, face = Theme.condensed)
        val bx = W - m - 22 * dp
        if (gui.iconButton(bx, H / 2 - 56 * dp, 22 * dp, "plus")) zoomAt(1.6f, W / 2, H / 2)
        if (gui.iconButton(bx, H / 2, 22 * dp, "minus")) zoomAt(1 / 1.6f, W / 2, H / 2)
        if (gui.iconButton(bx, H / 2 + 56 * dp, 22 * dp, "target")) { mapCx = GameState.x; mapCy = GameState.y }
        // scale bar
        val metres = listOf(50f, 100f, 200f, 500f, 1000f, 2000f, 5000f).lastOrNull { it * mapScale < 120 * dp } ?: 50f
        val sx = m; val sy = H - m - 6 * dp
        ink.stroke.color = Theme.TEXT; ink.stroke.strokeWidth = 2 * dp
        c.drawLine(sx, sy, sx + metres * mapScale, sy, ink.stroke); c.drawLine(sx, sy - 5 * dp, sx, sy, ink.stroke); c.drawLine(sx + metres * mapScale, sy - 5 * dp, sx + metres * mapScale, sy, ink.stroke)
        ink.text.setShadowLayer(3 * dp, 0f, 1 * dp, Color.BLACK)
        ink.label(if (metres >= 1000f) "${(metres / 1000).roundToInt()} km" else "${metres.roundToInt()} m", sx, sy - 9 * dp, 11f, Theme.TEXT)
        ink.text.clearShadowLayer()

        // ---- selection / waypoint card
        val cardW = min(360 * dp, W - 2 * m - 300 * dp).coerceAtLeast(260 * dp)
        val card = RectF(W / 2 - cardW / 2, H - m - 64 * dp, W / 2 + cardW / 2, H - m)
        if (!selX.isNaN()) {
            ink.glass(card, 16 * dp)
            val d = hypot(selX - GameState.x, selY - GameState.y)
            ink.label(selName.uppercase(), card.left + 16 * dp, card.top + 26 * dp, 12f, Theme.YELLOW, spacing = 0.08f)
            ink.label(if (d < 1000f) "${d.roundToInt()} m da te" else String.format("%.1f km da te", d / 1000f).replace('.', ','), card.left + 16 * dp, card.top + 46 * dp, 12f, Theme.TEXT_DIM, face = Theme.condensed)
            val b1 = RectF(card.right - 236 * dp, card.top + 12 * dp, card.right - 126 * dp, card.bottom - 12 * dp)
            val b2 = RectF(card.right - 118 * dp, card.top + 12 * dp, card.right - 12 * dp, card.bottom - 12 * dp)
            if (gui.button(b1, "VAI QUI", 0)) { Controls.teleportX = selX; Controls.teleportY = selY; GameState.clearWaypoint(); selX = Float.NaN; open(Screen.NONE); return }
            if (gui.button(b2, "NAVIGA", 1)) { GameState.wpX = selX; GameState.wpY = selY; GameState.wpName = selName; selX = Float.NaN }
        } else if (GameState.hasWaypoint()) {
            ink.glass(card, 16 * dp)
            ink.label("DESTINAZIONE · ${GameState.wpName.uppercase()}", card.left + 16 * dp, card.top + 26 * dp, 12f, Theme.YELLOW, spacing = 0.08f)
            val d = GameState.waypointDistance()
            ink.label(if (d < 1000f) "${d.roundToInt()} m" else String.format("%.1f km", d / 1000f).replace('.', ','), card.left + 16 * dp, card.top + 46 * dp, 12f, Theme.TEXT_DIM, face = Theme.condensed)
            if (gui.button(RectF(card.right - 130 * dp, card.top + 12 * dp, card.right - 12 * dp, card.bottom - 12 * dp), "RIMUOVI", 2)) GameState.clearWaypoint()
        }
        // a tap that no button took selects a point on the map
        gui.tappedAnywhere()?.let { p ->
            selX = mapCx + (p.x - W / 2) / mapScale; selY = mapCy - (p.y - H / 2) / mapScale
            selName = nearestName(selX, selY)
        }
    }

    /** Yellow ACTV chip with the line number and a small heading arrow. */
    private fun vaporettoMark(x: Float, y: Float, h: Float, line: String, label: Boolean) {
        val sv = c.save(); c.translate(x, y); c.rotate(-Math.toDegrees(h.toDouble()).toFloat() + 90f)
        ink.fill.style = Paint.Style.FILL; ink.fill.color = Theme.INK
        ink.path.reset(); ink.path.moveTo(0f, -11 * dp); ink.path.lineTo(5 * dp, -4 * dp); ink.path.lineTo(-5 * dp, -4 * dp); ink.path.close(); c.drawPath(ink.path, ink.fill)
        c.restoreToCount(sv)
        val r = if (label) 9 * dp else 5 * dp
        ink.fill.color = Theme.YELLOW; c.drawCircle(x, y, r, ink.fill)
        ink.stroke.color = Theme.INK; ink.stroke.strokeWidth = 1.5f * dp; c.drawCircle(x, y, r, ink.stroke)
        if (label) ink.label(line, x, y + 3.5f * dp, if (line.length > 2) 8f else 10f, Theme.INK, Paint.Align.CENTER)
    }

    private fun nearestName(x: Float, y: Float): String {
        val st = MapLayer.stops.minByOrNull { hypot(it.x - x, it.y - y) }
        if (st != null && hypot(st.x - x, st.y - y) < 60f) return "Fermata ${st.name}"
        return Places.nameAt(x, y, GameState.lat0, GameState.lon0)
    }
}
