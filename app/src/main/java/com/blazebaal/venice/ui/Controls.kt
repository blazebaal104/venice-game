// Shared input + game state between the UI thread (touch/keys/HUD) and the GL thread.
package com.blazebaal.venice.ui

import android.content.Context

object Controls {
    // touch: throttle lever (stays where you leave it, like a real one: -1 full reverse .. 0 neutral .. 1 full ahead)
    @Volatile var lever = 0f
    @Volatile var leftHeld = false
    @Volatile var rightHeld = false
    @Volatile var engineOn = true
    @Volatile var trim = 0f              // 0 .. 1 (bow up)
    @Volatile var cameraMode = 0         // 0 chase, 1 high chase, 2 helm
    @Volatile var paused = false
    @Volatile var camYawDelta = 0f       // accumulated drag, consumed by the renderer
    @Volatile var camPitchDelta = 0f
    // keyboard / gamepad
    @Volatile var kLeft = false; @Volatile var kRight = false; @Volatile var kUp = false; @Volatile var kDown = false

    fun steer(): Float {
        var s = 0f
        if (leftHeld || kLeft) s += 1f
        if (rightHeld || kRight) s -= 1f
        return s.coerceIn(-1f, 1f)
    }
    fun throttle(): Float {
        if (kUp) return 1f
        if (kDown) return -1f
        return lever.coerceIn(-1f, 1f)
    }
}

object GameState {
    @Volatile var x = 0f
    @Volatile var y = 0f
    @Volatile var heading = 0f
    @Volatile var speedKmh = 0f
    @Volatile var fps = 0f
    @Volatile var renderScale = 1f
    @Volatile var tilesLoaded = 0
    @Volatile var loading = true
    @Volatile var minX = 0f
    @Volatile var minY = 0f
    @Volatile var distanceM = 0f
}

/** Graphics settings (saved between sessions). */
object Settings {
    const val PERFORMANCE = 0; const val BALANCED = 1; const val QUALITY = 2
    @Volatile var quality = BALANCED
    @Volatile var fpsTarget = 120
    @Volatile var showFps = true

    /** highest internal resolution (fraction of the screen) the dynamic scaler may use */
    fun maxScale() = when (quality) { PERFORMANCE -> 0.62f; BALANCED -> 0.8f; else -> 1.0f }

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("venice", Context.MODE_PRIVATE)
        quality = p.getInt("quality", BALANCED); fpsTarget = p.getInt("fps", 120); showFps = p.getBoolean("showFps", true)
    }
    fun save(ctx: Context) {
        ctx.getSharedPreferences("venice", Context.MODE_PRIVATE).edit()
            .putInt("quality", quality).putInt("fps", fpsTarget).putBoolean("showFps", showFps).apply()
    }
}
