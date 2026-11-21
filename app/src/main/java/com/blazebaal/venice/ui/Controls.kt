// Shared input + game state between the UI thread (touch/keys/HUD) and the GL thread.
package com.blazebaal.venice.ui

object Controls {
    // touch
    @Volatile var stickX = 0f        // -1 (left) .. 1 (right)
    @Volatile var stickY = 0f
    @Volatile var gas = false
    @Volatile var brake = false
    @Volatile var camYawDelta = 0f   // accumulated drag, consumed by the renderer
    @Volatile var camPitchDelta = 0f
    // keyboard / gamepad
    @Volatile var kLeft = false; @Volatile var kRight = false; @Volatile var kUp = false; @Volatile var kDown = false

    fun steer(): Float {
        var s = -stickX
        if (kLeft) s += 1f
        if (kRight) s -= 1f
        return s.coerceIn(-1f, 1f)
    }
    fun throttle(): Float {
        var t = 0f
        if (gas || kUp) t += 1f
        if (brake || kDown) t -= 1f
        if (!gas && !brake && !kUp && !kDown) t = (-stickY).coerceIn(-1f, 1f) * if (-stickY > 0.25f) 1f else if (-stickY < -0.25f) 1f else 0f
        return t.coerceIn(-1f, 1f)
    }
}

object GameState {
    @Volatile var x = 0f
    @Volatile var y = 0f
    @Volatile var heading = 0f
    @Volatile var speedKmh = 0f
    @Volatile var fps = 0f
    @Volatile var tilesLoaded = 0
    @Volatile var loading = true
    @Volatile var minX = 0f
    @Volatile var minY = 0f
}
