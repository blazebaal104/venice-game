// Shared input + game state between the UI thread (touch/keys/HUD) and the GL thread.
package com.blazebaal.venice.ui

import android.content.Context
import kotlin.math.*

/** Which full-screen page is showing over the game (NONE = playing). */
enum class Screen { MENU, NONE, PAUSE, SETTINGS, MAP, CREDITS }

object Controls {
    // touch: throttle lever (stays where you leave it, like a real one: -1 full reverse .. 0 neutral .. 1 full ahead)
    @Volatile var lever = 0f
    @Volatile var leftHeld = false
    @Volatile var rightHeld = false
    /** analog steering pad: -1 (left) .. 1 (right), NaN when the pad is not touched */
    @Volatile var steerAnalog = Float.NaN
    @Volatile var engineOn = true
    /** outboard trim as the player sees it: 0 = fully in (bow down) .. 100 = fully out (bow up). Starts at 30. */
    @Volatile var trimPct = 30
    /** the same for the physics: -1 .. +1 */
    val trim get() = trimPct / 50f - 1f
    @Volatile var cameraMode = 0         // 0 chase, 1 high chase, 2 helm
    @Volatile var camYawDelta = 0f       // accumulated drag, consumed by the renderer
    @Volatile var camPitchDelta = 0f
    @Volatile var camHeld = false        // a finger is on the camera drag (keeps a looked-round view)
    // keyboard / gamepad
    @Volatile var kLeft = false; @Volatile var kRight = false; @Volatile var kUp = false; @Volatile var kDown = false
    /** one-shot requests from the UI to the GL thread */
    @Volatile var requestRespawn = false
    @Volatile var teleportX = Float.NaN; @Volatile var teleportY = Float.NaN

    @Volatile var screen = Screen.MENU
    /** title screen: where the boat preview is drawn (screen px: left, top, right, bottom) and how it is turned */
    @Volatile var menuBoatRect = IntArray(4)
    @Volatile var menuBoatYaw = 0f
    /** rad/s, set by the finger, integrated and damped by the renderer */
    @Volatile var menuBoatSpin = 0f
    /** true while the player is in the title-screen flow (menu, and settings/credits opened from it) */
    @Volatile var menuContext = true
    @Volatile var menuBoatTouchT = 0L
    @Volatile var menuBoatDragging = false
    val paused get() = screen != Screen.NONE

    fun steer(): Float {
        val a = steerAnalog
        if (!a.isNaN()) {
            // response curve: fine control around the centre, full lock at the edges
            val s = sign(a) * abs(a).pow(1.35f) * Settings.steerSensitivity
            return (-s).coerceIn(-1f, 1f)
        }
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
    /** the previous session's crash, shown once on the menu (empty: none) */
    @Volatile var lastCrash = ""
    // boot: the loading screen shows these until everything is ready
    @Volatile var bootProgress = 0f
    @Volatile var bootStage = ""
    @Volatile var bootError = ""
    @Volatile var booted = false
    /** a page is open over the game and the renderer has frozen on a blurred frame (no GPU work until it closes) */
    @Volatile var frozen = false
    @Volatile var x = 0f
    @Volatile var y = 0f
    @Volatile var heading = 0f
    @Volatile var speedKmh = 0f
    @Volatile var topSpeedKmh = 0f
    @Volatile var tripSeconds = 0f
    @Volatile var fps = 0f
    @Volatile var diag2 = ""
    @Volatile var renderScale = 1f
    @Volatile var tilesLoaded = 0
    @Volatile var tilesWanted = 1
    @Volatile var loading = true
    @Volatile var minX = 0f
    @Volatile var minY = 0f
    @Volatile var distanceM = 0f
    @Volatile var boatPitch = 0f
    @Volatile var porpoising = 0f
    /** map-metre projection origin (Campanile di San Marco), set once the map is read */
    @Volatile var lat0 = 45.4340; @Volatile var lon0 = 12.3389
    /** waypoint in world metres, NaN when none */
    @Volatile var wpX = Float.NaN; @Volatile var wpY = Float.NaN
    @Volatile var wpName = ""
    fun hasWaypoint() = !wpX.isNaN()
    fun clearWaypoint() { wpX = Float.NaN; wpY = Float.NaN; wpName = ""; route = null }
    /** the navigator's route along the canals (x0, y0, x1, y1...), where to steer now, and the distance left on it */
    @Volatile var route: FloatArray? = null
    @Volatile var aimX = Float.NaN; @Volatile var aimY = Float.NaN
    @Volatile var routeLeft = 0f
    fun waypointDistance() = if (!hasWaypoint()) 0f else if (route != null) routeLeft else hypot(wpX - x, wpY - y)
    /** width of the water across the boat (m), for the minimap zoom */
    @Volatile var canalWidth = 120f
    /** cache pre-build progress (0..1), -1 when idle */
    @Volatile var prebuild = -1f
}

/** Everything the player can change, saved between sessions. */
object Settings {
    const val PERFORMANCE = 0; const val BALANCED = 1; const val QUALITY = 2; const val ULTRA = 3

    // ---- graphics
    @Volatile var quality = BALANCED
    /** "Semplice" graphics for low-end phones: simple water, no planar reflection, no wake simulation, no HDR/bloom/shadows */
    @Volatile var simple = false
    /** radius (m) of the loaded world: everything past it is unloaded and hidden in the fog. 256 m = one chunk (map tile). */
    @Volatile var renderDistance = 512f
    @Volatile var vegetationDistance = 180f
    @Volatile var detailDistance = 240f
    /** 0 = dynamic resolution, otherwise a fixed internal resolution in percent */
    @Volatile var resolution = 0
    @Volatile var fpsTarget = 60
    @Volatile var shadows = true
    @Volatile var reflections = true
    @Volatile var bloom = true
    @Volatile var sharpen = 0.5f
    /** 0 light .. 1 normal .. 2 thick: how far before the render-distance edge the fog builds up */
    @Volatile var fogDensity = 1
    @Volatile var showFps = false
    // ---- controls
    @Volatile var analogSteering = true
    /** 0 timone (slider), 1 frecce, 2 volante (wheel, stops at ±90°) */
    @Volatile var steerMode = 0
    @Volatile var steerSensitivity = 1f
    @Volatile var cameraSensitivity = 1f
    @Volatile var vibration = true
    @Volatile var speedUnit = 0          // 0 km/h, 1 knots, 2 mph
    @Volatile var hudScale = 1f
    @Volatile var minimapNorthUp = false
    // ---- audio (mixer levels for the audio system to come)
    @Volatile var volMaster = 0.8f
    @Volatile var volEngine = 0.8f
    @Volatile var volAmbience = 0.6f
    // ---- world
    @Volatile var liveWeather = true
    @Volatile var liveVaporetti = true
    @Volatile var language = 0           // 0 italiano, 1 english

    /** highest internal resolution (fraction of the screen) the dynamic scaler may use */
    fun maxScale() = if (resolution > 0) resolution / 100f else if (simple) 0.75f else when (quality) { PERFORMANCE -> 0.8f; else -> 1.0f }
    /** lowest it may drop to: never below 70 % any more, that is what made the city look like 144p */
    fun minScale() = if (resolution > 0) resolution / 100f else if (simple) 0.6f else when (quality) { PERFORMANCE -> 0.65f; BALANCED -> 0.8f; else -> 0.85f }
    /** the two looks: realistic (everything) or simple (playable on an A15) */
    fun applyMode(simpleMode: Boolean) {
        simple = simpleMode
        if (simpleMode) { applyPreset(PERFORMANCE); renderDistance = 384f; vegetationDistance = 80f; detailDistance = 120f; shadows = false; reflections = false; bloom = false; sharpen = 0.3f }
        else { applyPreset(BALANCED); sharpen = 0.5f }
    }

    /** Quality presets set the distances too; the player can still fine-tune them afterwards. */
    fun applyPreset(q: Int) {
        quality = q
        when (q) {
            PERFORMANCE -> { renderDistance = 384f; vegetationDistance = 110f; detailDistance = 160f; shadows = false; reflections = true; bloom = false }
            BALANCED -> { renderDistance = 512f; vegetationDistance = 180f; detailDistance = 240f; shadows = true; reflections = true; bloom = true }
            QUALITY -> { renderDistance = 768f; vegetationDistance = 260f; detailDistance = 330f; shadows = true; reflections = true; bloom = true }
            else -> { renderDistance = 1024f; vegetationDistance = 380f; detailDistance = 420f; shadows = true; reflections = true; bloom = true }
        }
    }

    fun chunks() = renderDistance / 256f

    fun speed(kmh: Float) = when (speedUnit) { 1 -> kmh / 1.852f; 2 -> kmh / 1.609f; else -> kmh }
    fun speedLabel() = when (speedUnit) { 1 -> "NODI"; 2 -> "MPH"; else -> "KM/H" }

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("venice", Context.MODE_PRIVATE)
        quality = p.getInt("quality", BALANCED)
        renderDistance = p.getFloat("renderDistance", 512f); vegetationDistance = p.getFloat("vegDistance", 180f); detailDistance = p.getFloat("detailDistance", 240f)
        resolution = p.getInt("resolution", 0)
        // the old default of 120 FPS pushed the dynamic resolution down to 50 % on most phones
        fpsTarget = if (p.getInt("settingsVersion", 1) < 2) 60 else p.getInt("fps", 60)
        shadows = p.getBoolean("shadows", true); reflections = p.getBoolean("reflections", true); bloom = p.getBoolean("bloom", true)
        sharpen = if (p.getInt("settingsVersion", 1) < 3) 0.5f else p.getFloat("sharpen", 0.5f); fogDensity = p.getInt("fog", 1)
        showFps = if (p.getInt("settingsVersion", 1) < 4) false else p.getBoolean("showFps", false)
        analogSteering = p.getBoolean("analog", true); steerMode = p.getInt("steerMode", if (analogSteering) 0 else 1); steerSensitivity = p.getFloat("steerSens", 1f); cameraSensitivity = p.getFloat("camSens", 1f)
        vibration = p.getBoolean("vibration", true); speedUnit = p.getInt("speedUnit", 0); hudScale = p.getFloat("hudScale", 1f)
        minimapNorthUp = p.getBoolean("northUp", false)
        volMaster = p.getFloat("volMaster", 0.8f); volEngine = p.getFloat("volEngine", 0.8f); volAmbience = p.getFloat("volAmbience", 0.6f)
        liveWeather = p.getBoolean("liveWeather", true); language = p.getInt("language", 0)
        liveVaporetti = p.getBoolean("liveVaporetti", true)
        simple = p.getBoolean("simple", false)
    }
    fun save(ctx: Context) {
        ctx.getSharedPreferences("venice", Context.MODE_PRIVATE).edit()
            .putInt("settingsVersion", 4)
            .putInt("quality", quality).putFloat("renderDistance", renderDistance).putFloat("vegDistance", vegetationDistance).putFloat("detailDistance", detailDistance)
            .putInt("resolution", resolution).putInt("fps", fpsTarget)
            .putBoolean("shadows", shadows).putBoolean("reflections", reflections).putBoolean("bloom", bloom)
            .putFloat("sharpen", sharpen).putInt("fog", fogDensity).putBoolean("showFps", showFps)
            .putBoolean("analog", analogSteering).putInt("steerMode", steerMode).putFloat("steerSens", steerSensitivity).putFloat("camSens", cameraSensitivity)
            .putBoolean("vibration", vibration).putInt("speedUnit", speedUnit).putFloat("hudScale", hudScale).putBoolean("northUp", minimapNorthUp)
            .putFloat("volMaster", volMaster).putFloat("volEngine", volEngine).putFloat("volAmbience", volAmbience)
            .putBoolean("liveWeather", liveWeather).putBoolean("liveVaporetti", liveVaporetti).putInt("language", language)
            .putBoolean("simple", simple).apply()
    }
}
