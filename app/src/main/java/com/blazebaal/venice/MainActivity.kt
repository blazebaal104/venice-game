package com.blazebaal.venice

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import com.blazebaal.venice.audio.Audio
import com.blazebaal.venice.live.Vaporetti
import com.blazebaal.venice.live.Weather
import com.blazebaal.venice.render.GameRenderer
import com.blazebaal.venice.ui.Controls
import com.blazebaal.venice.ui.GameActions
import com.blazebaal.venice.ui.GameState
import com.blazebaal.venice.ui.HudView
import com.blazebaal.venice.ui.Screen
import com.blazebaal.venice.ui.ScreenView
import com.blazebaal.venice.ui.Settings
import android.view.Surface
import android.view.SurfaceHolder

class MainActivity : Activity(), GameActions {
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: GameRenderer
    private lateinit var screens: ScreenView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // crash reporter: the last crash is saved and shown once on the next launch (top of the main menu)
        val crashFile = java.io.File(filesDir, "last_crash.txt")
        if (crashFile.exists()) { GameState.lastCrash = try { crashFile.readText().take(400) } catch (_: Exception) { "" }; crashFile.delete() }
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val rt = Runtime.getRuntime()
                val mem = "heap ${(rt.totalMemory() - rt.freeMemory()) / 1048576}/${rt.maxMemory() / 1048576} MB"
                val frames = e.stackTrace.take(4).joinToString(" < ") { "${it.fileName}:${it.lineNumber}" }
                crashFile.writeText("${e.javaClass.simpleName}: ${e.message ?: ""} · thread ${t.name} · $mem · $frames")
            } catch (_: Throwable) { }
            prev?.uncaughtException(t, e)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Settings.load(this)
        Controls.screen = Screen.MENU
        if (Settings.liveWeather) Weather.start() else Weather.climateFallback()
        Vaporetti.enabled = Settings.liveVaporetti; Vaporetti.start(this)
        requestHighRefreshRate()
        renderer = GameRenderer(this)
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 24, 0)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        // ask the compositor for 120 Hz on this surface (Android 11+), otherwise many phones keep games at 60 Hz
        glView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                if (android.os.Build.VERSION.SDK_INT >= 30) try { h.surface.setFrameRate(Settings.fpsTarget.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE) } catch (_: Exception) { }
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {}
        })
        val root = FrameLayout(this)
        root.addView(glView)
        root.addView(HudView(this))
        screens = ScreenView(this, this)
        root.addView(screens)
        setContentView(root)
        hideSystemUi()
    }

    /** Pick the display mode with the highest refresh rate at the current resolution. */
    private fun requestHighRefreshRate() {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        try {
            val d = display ?: return
            val cur = d.mode
            val best = d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxByOrNull { it.refreshRate } ?: return
            window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId; preferredRefreshRate = best.refreshRate }
        } catch (_: Exception) { }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!screens.back()) { Settings.save(this); @Suppress("DEPRECATION") super.onBackPressed() }
    }

    // ---- GameActions (settings page)
    override fun prebuildCity() {
        if (!renderer.ready) return
        GameState.prebuild = 0f
        renderer.streamer.prebuildAll { p -> GameState.prebuild = p }
    }
    override fun clearCache() { if (renderer.ready) glView.queueEvent { renderer.streamer.clearCache() } }
    override fun cachedTiles() = if (renderer.ready) renderer.streamer.cachedCount() else 0
    override fun totalTiles() = if (renderer.ready) renderer.streamer.totalTiles() else 0
    override fun quit() { finish() }

    private fun hideSystemUi() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideSystemUi() }
    override fun onResume() { super.onResume(); glView.onResume(); if (Settings.liveWeather) Weather.start(); Vaporetti.start(this); Audio.start() }
    override fun onPause() {
        super.onPause(); glView.onPause(); Settings.save(this); Weather.stop(); Vaporetti.stop(); Audio.stop()
        // leaving the app mid-run opens the pause menu, so you don't come back to a boat already moving
        if (Controls.screen == Screen.NONE) Controls.screen = Screen.PAUSE
    }
    override fun onDestroy() { renderer.shutdown(); super.onDestroy() }

    // keyboard (W/A/S/D or arrows) for playing with a keyboard connected to the phone
    private fun key(code: Int, down: Boolean): Boolean {
        when (code) {
            KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_BUTTON_R2 -> Controls.kUp = down
            KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_BUTTON_L2 -> Controls.kDown = down
            KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_DPAD_LEFT -> Controls.kLeft = down
            KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_DPAD_RIGHT -> Controls.kRight = down
            else -> return false
        }
        return true
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent?) = key(keyCode, true) || super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent?) = key(keyCode, false) || super.onKeyUp(keyCode, event)
}
