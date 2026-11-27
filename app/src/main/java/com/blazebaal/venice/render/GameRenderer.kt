// Main GL renderer + game loop.
// Per frame: GPU wake step(s) -> boat shadow (small, every frame; the city shadow map is cached and only redrawn when
// the shadowed area moves) -> mirrored reflection (low res, near city only) -> HDR scene at an internal resolution
// that adapts to hold the target frame rate -> bloom -> filmic tone map + FXAA, upscaled to the screen.
package com.blazebaal.venice.render

import android.content.Context
import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import com.blazebaal.venice.audio.Audio
import com.blazebaal.venice.core.*
import com.blazebaal.venice.live.Weather
import com.blazebaal.venice.ui.Screen
import com.blazebaal.venice.ui.Events
import com.blazebaal.venice.ui.Controls
import com.blazebaal.venice.ui.GameState
import com.blazebaal.venice.ui.Settings
import java.io.DataInputStream
import java.util.zip.InflaterInputStream
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

class Landmark(val mesh: Mesh, val model: FloatArray, val x: Float, val y: Float, val atlas: Int, val signRect: FloatArray = floatArrayOf(0f, 0f, 0f, 0f))

class GameRenderer(private val ctx: Context) : GLSurfaceView.Renderer {
    private lateinit var world: WorldMap
    lateinit var streamer: TileStreamer; private set
    @Volatile var ready = false; private set
    private lateinit var generic: Program; private lateinit var facade: Program; private lateinit var ground: Program
    private lateinit var water: Program; private lateinit var sky: Program; private lateinit var depth: Program
    private lateinit var sprayP: Program
    private lateinit var brightP: Program; private lateinit var blurP: Program; private lateinit var compP: Program
    private var groundQuad: Mesh? = null; private var waterNear: Mesh? = null; private var waterMid: Mesh? = null; private var waterFar: Mesh? = null
    private var skyTri: Mesh? = null; private var boatMesh: Mesh? = null
    private val landmarks = ArrayList<Landmark>()
    private var texArr = IntArray(2)
    private lateinit var boat: Boat
    private val fx = Effects()
    private lateinit var sprayMesh: InstMesh
    private val sprayData = FloatArray(Effects.MAX_P * 8)
    private lateinit var wake: WakeGpu
    private var waterN = 0

    private var width = 1; private var height = 1
    private var scale = 0.85f; private var targetsScale = -1f
    private var scene: Target? = null; private var refl: Target? = null; private var bloomA: Target? = null; private var bloomB: Target? = null
    private lateinit var cityShadow: ShadowMap; private lateinit var boatShadow: ShadowMap
    private var lightVP = Mat4.identity(); private var lightVPB = Mat4.identity()
    private var shadowCx = Float.NaN; private var shadowCy = Float.NaN; private var shadowVersion = -1
    private val mirror = Mat4.identity().also { it[10] = -1f }
    private val ident = Mat4.identity()
    private val visible = ArrayList<GpuTile>(128)

    private var lastNs = 0L; private var time = 0f
    private var fpsAcc = 0f; private var fpsN = 0
    private var frameEma = 1f / 60f; private var badT = 0f; private var goodT = 0f; private var lastDrop = -100f
    private var camX = 0f; private var camY = 0f; private var camZ = 3f
    private var camYawOff = 0f; private var camPitch = 0.2f; private var camDist = 8f; private var camFov = 56f
    private var distance = 0.0

    // lighting (linear HDR): Venetian late-afternoon sun from the south-west
    private val sunDir = run { val az = Math.toRadians(228.0); val el = Math.toRadians(32.0); floatArrayOf((sin(az) * cos(el)).toFloat(), (cos(az) * cos(el)).toFloat(), sin(el).toFloat()) }
    private val sunClear = floatArrayOf(3.3f, 2.95f, 2.45f)
    private val sunCol = sunClear.copyOf()
    private val skyAmb = floatArrayOf(0.34f, 0.42f, 0.56f)
    private val rain by lazy { Rain() }
    private var wet = 0f; private var flash = 0f; private var nextBolt = 6f; private var boltT = -1f
    private val groundAmb = floatArrayOf(0.20f, 0.18f, 0.15f)
    // clear-day values; blended towards overcast / fog by the live weather every frame
    private val fogClear = floatArrayOf(0.62f, 0.68f, 0.76f); private val fogGrey = floatArrayOf(0.66f, 0.68f, 0.70f)
    private val zenithClear = floatArrayOf(0.16f, 0.30f, 0.62f); private val zenithGrey = floatArrayOf(0.42f, 0.46f, 0.52f)
    private val fogCol = fogClear.copyOf()
    private val zenith = zenithClear.copyOf()
    private var fogDens = 1f / 640f
    private var fogStart = 400f; private var fogEnd = 640f
    /** = Settings.renderDistance, read once per frame */
    private var drawDist = 640f
    private var tripTime = 0f; private var topKmh = 0f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val bytes = ctx.assets.open("venice.map").use { it.readBytes() }
        val map = MapData(java.io.ByteArrayInputStream(bytes))
        world = WorldMap(map)
        GameState.minX = map.minX; GameState.minY = map.minY; GameState.lat0 = map.lat0; GameState.lon0 = map.lon0
        // meshed tiles are cached on the phone; the key changes with the map data and with every app build
        val crc = java.util.zip.CRC32().apply { update(bytes) }.value
        val build = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).let { if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() } } catch (_: Exception) { 0L }
        streamer = TileStreamer(world, Settings.renderDistance, TileCache(ctx.filesDir, "${java.lang.Long.toHexString(crc)}-$build-m$MESHER_VERSION"))
        generic = Program(Shaders.STD_VS, Shaders.GENERIC_FS, "generic")
        facade = Program(Shaders.FACADE_VS, Shaders.FACADE_FS, "facade")
        ground = Program(Shaders.GROUND_VS, Shaders.GROUND_FS, "ground")
        water = Program(Shaders.WATER_VS, Shaders.WATER_FS, "water")
        sky = Program(Shaders.SKY_VS, Shaders.SKY_FS, "sky")
        depth = Program(Shaders.DEPTH_VS, Shaders.DEPTH_FS, "depth")
        sprayP = Program(Shaders.SPRAY_VS, Shaders.SPRAY_FS, "spray")
        brightP = Program(Shaders.POST_VS, Shaders.BRIGHT_FS, "bright")
        blurP = Program(Shaders.POST_VS, Shaders.BLUR_FS, "blur")
        compP = Program(Shaders.POST_VS, Shaders.COMPOSITE_FS, "composite")
        texArr = TexArrays.load(ctx, L.COUNT)
        cityShadow = ShadowMap(2048); boatShadow = ShadowMap(1024)
        groundQuad = Mesh(floatArrayOf(0f, 0f, 0f, 256f, 0f, 0f, 256f, 256f, 0f, 0f, 0f, 0f, 256f, 256f, 0f, 0f, 256f, 0f), 3, intArrayOf(3))
        skyTri = Mesh(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f), 2, intArrayOf(2))
        // fine grid around the camera so the wake's mound, hollow and V crests are real geometry, coarser rings outside
        waterNear = Mesh(waterGrid(56f, 0.5f, 0f), 3, intArrayOf(3))
        waterMid = Mesh(waterGrid(160f, 2f, 56f), 3, intArrayOf(3))
        waterFar = Mesh(waterGrid(1000f, 40f, 160f), 3, intArrayOf(3))
        sprayMesh = InstMesh(8, intArrayOf(3, 3, 2), Effects.MAX_P)
        wake = WakeGpu(skyTri!!)
        waterN = WaterNormals.create()
        boatMesh = loadMesh("boat.mesh")
        loadLandmarks()
        loadBridges()
        loadStops()
        live = LiveBoats(world); live.setStops(stopList)
        for ((i, n) in listOf("motobattello", "motoscafo", "motonave", "lancione").withIndex()) fleetMeshes[i] = loadMesh("live/$n.mesh")
        wake.fleet = live
        smokeP = Program(Shaders.SMOKE_VS, Shaders.SMOKE_FS, "smoke"); trailP = Program(Shaders.TRAIL_VS, Shaders.TRAIL_FS, "trail")
        smokeMesh = InstMesh(6, intArrayOf(3, 3), LiveBoats.MAX_SMOKE); trailMesh = DynMesh(5, intArrayOf(2, 3), 24000)
        boat = Boat(-330f, 380f, Math.toRadians(41.0).toFloat())
        Obstacles.stops = stopBoxes
        findWater()
        camX = boat.x - 9f; camY = boat.y; camZ = 3.2f
        glEnable(GL_DEPTH_TEST); glDisable(GL_CULL_FACE)
        lastNs = System.nanoTime()
        scale = Settings.maxScale()
        ready = true
    }

    companion object {
        /** bump whenever CityMesher output changes, so cached tiles on players' phones are rebuilt */
        const val MESHER_VERSION = 7
    }

    private fun loadMesh(asset: String): Mesh? = try {
        DataInputStream(InflaterInputStream(ctx.assets.open(asset)).buffered(1 shl 16)).use { d ->
            val n = d.readInt(); Mesh(FloatArray(n * STRIDE) { d.readFloat() })
        }
    } catch (e: Exception) { android.util.Log.e("Venice", "mesh $asset", e); null }

    /** Baked albedo atlas of a hand-built landmark (sRGB JPEG, mipmapped). 0 if the landmark has none. */
    private fun loadAtlas(asset: String): Int = try {
        val bmp = ctx.assets.open(asset).use { android.graphics.BitmapFactory.decodeStream(it)!! }
        val t = IntArray(1); glGenTextures(1, t, 0)
        glBindTexture(GL_TEXTURE_2D, t[0])
        android.opengl.GLUtils.texImage2D(GL_TEXTURE_2D, 0, bmp, 0); bmp.recycle()
        glGenerateMipmap(GL_TEXTURE_2D)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        Aniso.apply(GL_TEXTURE_2D, 4f)
        t[0]
    } catch (e: Exception) { 0 }

    private var signTex = 0
    private lateinit var live: LiveBoats
    private val fleetMeshes = arrayOfNulls<Mesh>(4)
    private lateinit var smokeP: Program; private lateinit var trailP: Program
    private lateinit var smokeMesh: InstMesh; private lateinit var trailMesh: DynMesh
    private val smokeData = FloatArray(LiveBoats.MAX_SMOKE * 6); private val trailData = FloatArray(24000 * 5)
    private val stopBoxes = ArrayList<FloatArray>()
    private var stopList: List<StopLayout.Stop> = emptyList()
    private var stopExtras: Mesh? = null

    /** ACTV vaporetto stops (see landmarks/actv.txt): pontoon models by length, names from a shared sign sheet.
     *  Each stop is also a solid obstacle for the boat. */
    private fun loadStops() {
        val lens = intArrayOf(12, 16, 22)
        val variants = lens.map { l -> loadMesh("landmarks/actv$l.mesh")?.let { it to loadAtlas("landmarks/actv${l}_atlas.jpg") } }
        if (variants.all { it == null }) return
        signTex = loadAtlas("landmarks/actv_signs.png")
        val lines = try { ctx.assets.open("landmarks/actv.txt").bufferedReader().readLines() } catch (e: Exception) { emptyList() }
        val lay = StopLayout.build(lines, world)
        for (st in lay.stops) {
            val (mesh, atlas) = variants[lens.indexOf(st.len)] ?: continue
            val model = Mat4.mul(Mat4.translation(st.x, st.y, 0f), Mat4.rotZ(st.rot))
            val rect = floatArrayOf((st.slot % 4) * 0.25f, (st.slot / 4) / 32f, 0.25f, 1f / 32f)
            landmarks.add(Landmark(mesh, model, st.x, st.y, atlas, rect))
        }
        stopBoxes.addAll(lay.boxes)
        stopList = lay.stops
        val extra = lay.extras
        if (extra.count > 0) stopExtras = Mesh(extra.floats())
    }

    private fun loadLandmarks() {
        // each line: name, world x, world y, rotation (deg); local +X runs along the structure
        val lines = try { ctx.assets.open("landmarks/landmarks.txt").bufferedReader().readLines() } catch (e: Exception) { emptyList() }
        val zones = ArrayList<FloatArray>()
        for (line in lines) {
            if (line.isBlank() || line.startsWith("#")) continue
            val p = line.trim().split(Regex("\\s+"))
            if (p.size >= 6) zones.add(floatArrayOf(p[1].toFloat(), p[2].toFloat(), Math.toRadians(p[3].toDouble()).toFloat(), p[4].toFloat(), p[5].toFloat()))
            val mesh = loadMesh("landmarks/${p[0]}.mesh") ?: continue
            val x = p[1].toFloat(); val y = p[2].toFloat(); val rot = Math.toRadians(p[3].toDouble()).toFloat()
            landmarks.add(Landmark(mesh, Mat4.mul(Mat4.translation(x, y, 0f), Mat4.rotZ(rot)), x, y, loadAtlas("landmarks/${p[0]}_atlas.jpg")))
        }
        Exclusions.zones = zones
    }

    /** Hand-built bridge types (assets/bridges): one line per instance `mesh x y rotDeg sx sy sz`, the mesh is
     *  stretched along the walkway (sx), across (sy) and in height about the quay level (sz). One shared atlas. */
    private fun loadBridges() {
        val lines = try { ctx.assets.open("bridges/bridges.txt").bufferedReader().readLines() } catch (e: Exception) { return }
        val atlas = loadAtlas("bridges/bridges_atlas.jpg"); if (atlas == 0) return
        val meshes = HashMap<String, Mesh?>()
        val own = (ctx.assets.list("bridges") ?: emptyArray()).filter { it.endsWith("_atlas.jpg") && it != "bridges_atlas.jpg" }.associate { it.removeSuffix("_atlas.jpg") to loadAtlas("bridges/$it") }  // models baked on their own sheet
        val g = 1.1f
        for (line in lines) {
            if (line.isBlank() || line.startsWith("#")) continue
            val p = line.trim().split(Regex("\\s+")); if (p.size < 7) continue
            val mesh = meshes.getOrPut(p[0]) { loadMesh("bridges/${p[0]}.mesh") } ?: continue
            val x = p[1].toFloat(); val y = p[2].toFloat(); val rot = Math.toRadians(p[3].toDouble()).toFloat()
            val sx = p[4].toFloat(); val sy = p[5].toFloat(); val sz = p[6].toFloat()
            val sc = Mat4.identity().also { it[0] = sx; it[5] = sy; it[10] = sz; it[14] = g * (1f - sz) }
            val model = Mat4.mul(Mat4.mul(Mat4.translation(x, y, 0f), Mat4.rotZ(rot)), sc)
            landmarks.add(Landmark(mesh, model, x, y, own[p[0]] ?: atlas))
        }
    }

    private fun waterGrid(half: Float, step: Float, hole: Float): FloatArray {
        val n = (2 * half / step).toInt()
        val out = FloatArray(n * n * 18); var o = 0
        for (j in 0 until n) for (i in 0 until n) {
            val x0 = -half + i * step; val y0 = -half + j * step; val x1 = x0 + step; val y1 = y0 + step
            if (hole > 0 && x0 >= -hole && x1 <= hole && y0 >= -hole && y1 <= hole) continue
            for (v in floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y0, x1, y1, x0, y1).toList().chunked(2)) { out[o++] = v[0]; out[o++] = v[1]; out[o++] = 0f }
        }
        return out.copyOf(o)
    }

    /** Puts the boat on open water: only if it is on land, or (strict) anywhere that is not clear on all sides. */
    private fun findWater(strict: Boolean = false) {
        fun clear(x: Float, y: Float, r: Float) = world.solid(x, y) < 0.05f && world.solid(x + r, y) < 0.05f && world.solid(x - r, y) < 0.05f && world.solid(x, y + r) < 0.05f && world.solid(x, y - r) < 0.05f
        val r0 = if (strict) 6f else 4f
        if (!strict && world.solid(boat.x, boat.y) < 0.2f) return
        if (strict && clear(boat.x, boat.y, r0)) return
        for (r in 1..300) for (k in 0 until 16) {
            val a = k * PI.toFloat() / 8
            val x = boat.x + cos(a) * r; val y = boat.y + sin(a) * r
            if (clear(x, y, r0)) { boat.x = x; boat.y = y; return }
        }
    }

    // ---------------------------------------------------------------- what's happening, as HUD texts
    private var scrapeSnd = 0f
    private fun feedAudio(paused: Boolean) {
        Audio.paused = paused
        Audio.master = Settings.volMaster; Audio.engineVol = Settings.volEngine; Audio.ambienceVol = Settings.volAmbience
        Audio.engineOn = Controls.engineOn; Audio.throttle = Controls.throttle(); Audio.speed = abs(boat.surge)
        Audio.trim = Controls.trim; Audio.airborne = boat.airborne
        Audio.nearLand = ((0.11f - boat.waveAmp) / 0.075f).coerceIn(0f, 1f)
        val wx = (Settings.liveWeather && Weather.live) || Weather.override > 0
        Audio.rain = if (wx) Weather.rainIntensity() else 0f
        Audio.wind = if (wx) (hypot(Weather.windVec()[0], Weather.windVec()[1]) * 3.6f / 45f).coerceIn(0f, 1f) else 0.1f
        Audio.lx = camX; Audio.ly = camY; Audio.lyaw = boat.heading + camYawOff
        Audio.boats = LiveBoats.audio
        while (true) {
            val c = LiveBoats.cues.poll() ?: break
            Audio.event(if (c[0] == LiveBoats.CUE_DOCK) Audio.EV_DOCK else Audio.EV_DEPART, 1f, c[1], c[2])
        }
    }
    private var planArmed = true; private var trimCat = -1; private var airT = 0f; private var lastEngine = true; private var lastLever = 0f
    private fun watchEvents(dt: Float) {
        val kmh = boat.speedKmh()
        if (planArmed && kmh > 32f && boat.surge > 0f) { Events.post("PLANATA", Events.GOOD, "lo scafo è sul plano"); planArmed = false }
        if (kmh < 22f) planArmed = true
        // the water column / wake type follows the trim
        val tp = Controls.trimPct
        val cat = when { tp < 35 -> 0; tp < 58 -> 1; tp < 78 -> 2; else -> 3 }
        if (kmh > 45f && cat != trimCat) {
            when (cat) {
                0 -> Events.post("SCIA BASSA", Events.WATER, "trim giù · prua nell'acqua, più presa in curva", "trimcat", 1200)
                1 -> Events.post("SCIA LARGA", Events.WATER, "assetto neutro", "trimcat", 1200)
                2 -> Events.post("COLONNA D'ACQUA", Events.GOOD, "assetto ideale · massima velocità", "trimcat", 1200)
                else -> Events.post("COLONNA ALTA", Events.WARN, "troppo trim · la prua comincia a saltare", "trimcat", 1200)
            }
            trimCat = cat
        }
        if (kmh < 35f) trimCat = -1
        if (boat.porpoise > 0.35f) Events.post("ASSETTO TROPPO ALTO", Events.BAD, "abbassa il trim", cooldownMs = 5000)
        if (boat.impact > 0f) {
            when (boat.impactKind) {
                3 -> Events.post("URTO CON IL VAPORETTO", Events.BAD, "${(boat.impact * 3.6f).roundToInt()} km/h", "urto")
                2 -> Events.post("URTO CONTRO IL PONTILE", Events.WARN, "${(boat.impact * 3.6f).roundToInt()} km/h", "urto")
                else -> Events.post("URTO", Events.WARN, "contro la fondamenta · ${(boat.impact * 3.6f).roundToInt()} km/h", "urto")
            }
            Audio.event(Audio.EV_IMPACT, boat.impact)
            boat.impact = 0f
        }
        if (boat.scrape > 0.25f) Events.post("STRISCIATA", Events.WARN, "sul marmo della fondamenta", cooldownMs = 2500)
        scrapeSnd -= dt
        if (boat.scrape > 0.1f && scrapeSnd <= 0f) { Audio.event(Audio.EV_SCRAPE, 1f); scrapeSnd = 0.45f }
        if (boat.airborne) airT += dt else { if (airT > 0.45f) Events.post("SALTO", Events.GOOD, String.format("%.1f s in aria", airT).replace('.', ',')); airT = 0f }
        if (kmh > 138f) Events.post("VELOCITÀ MASSIMA", Events.GOOD, "140 km/h", cooldownMs = 20000)
        if (Controls.engineOn != lastEngine) { Events.post(if (Controls.engineOn) "MOTORE ACCESO" else "MOTORE SPENTO", Events.INFO, key = "engine", cooldownMs = 300); lastEngine = Controls.engineOn }
        val lv = Controls.throttle()
        if (lv < -0.05f && lastLever >= -0.05f) Events.post("RETROMARCIA", Events.INFO, cooldownMs = 2000)
        lastLever = lv
        // waypoint reached: it disappears
        if (GameState.hasWaypoint() && hypot(GameState.wpX - boat.x, GameState.wpY - boat.y) < 28f) {
            Events.post("ARRIVATO", Events.WATER, GameState.wpName.uppercase(), cooldownMs = 500)
            GameState.clearWaypoint()
        }
    }

    /** Fog distances from the render distance + fog setting, and fog / sky colours from the live weather. */
    private fun updateAtmosphere(dt: Float) {
        val live = (Settings.liveWeather && Weather.live) || Weather.override > 0
        val grey = if (live) Weather.gloom() else 0f
        // overcast dims the sun; rain soaks the city slowly and it dries slowly
        for (i in 0..2) sunCol[i] += (sunClear[i] * (1f - 0.65f * grey) - sunCol[i]) * (1f - exp(-dt * 0.5f))
        val rainI = if (live) Weather.rainIntensity() else 0f
        wet += ((if (rainI > 0f) 1f else 0f) - wet) * (1f - exp(-dt * (if (rainI > 0f) 0.08f else 0.02f)))
        // storms: lightning every 8-25 s, a double flicker, thunder a moment later
        if (live && Weather.isStorm()) {
            nextBolt -= dt
            if (nextBolt <= 0f) { nextBolt = 8f + Math.random().toFloat() * 17f; boltT = 0f }
        }
        if (boltT >= 0f) {
            boltT += dt
            flash = if (boltT < 0.08f) 1f else if (boltT < 0.14f) 0.2f else if (boltT < 0.22f) 0.8f else max(0f, 0.8f - (boltT - 0.22f) * 3f)
            if (boltT > 1.2f && boltT - dt <= 1.2f) com.blazebaal.venice.audio.Audio.event(com.blazebaal.venice.audio.Audio.EV_THUNDER, 1f)
            if (boltT > 2f) boltT = -1f
        } else flash = 0f
        val k = 1f - exp(-dt * 0.5f)   // ease weather changes in over a few seconds
        for (i in 0..2) {
            fogCol[i] += (fogClear[i] + (fogGrey[i] - fogClear[i]) * grey - fogCol[i]) * k
            zenith[i] += (zenithClear[i] + (zenithGrey[i] - zenithClear[i]) * grey * 0.85f - zenith[i]) * k
        }
        val wf = if (Settings.liveWeather || Weather.override > 0) Weather.fogFactor() else 1f
        fogEnd = drawDist * 0.97f
        val startK = when (Settings.fogDensity) { 0 -> 0.72f; 2 -> 0.3f; else -> 0.5f } / sqrt(wf)
        fogStart = fogEnd * startK.coerceIn(0.08f, 0.9f)
        fogDens = wf / (drawDist * 1.25f) * when (Settings.fogDensity) { 0 -> 0.8f; 2 -> 1.3f; else -> 1f }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        targetsScale = -1f
    }

    private fun ensureTargets() {
        if (abs(targetsScale - scale) < 0.001f) return
        targetsScale = scale
        val w = max(64, (width * scale).toInt()); val h = max(64, (height * scale).toInt())
        scene?.release(); refl?.release(); bloomA?.release(); bloomB?.release()
        scene = Target(w, h, true, true)
        refl = Target(max(32, (w * 0.42f).toInt()), max(32, (h * 0.42f).toInt()), true, true)
        bloomA = Target(w / 4, h / 4, true, false); bloomB = Target(w / 4, h / 4, true, false)
        GameState.renderScale = scale
    }

    /** Lower the internal resolution when frames are late, raise it again when there is headroom. */
    private fun adaptResolution(dt: Float) {
        val target = 1f / Settings.fpsTarget
        frameEma += (dt - frameEma) * 0.08f
        if (frameEma > target * 1.12f) { badT += dt; goodT = 0f } else { badT = max(0f, badT - dt * 0.5f); if (frameEma < target * 1.05f) goodT += dt else goodT = 0f }
        val maxS = Settings.maxScale(); val minS = Settings.minScale()
        if (scale < minS - 0.01f) scale = minS
        if (scale > maxS + 0.01f) scale = maxS
        if (badT > 0.7f && scale > minS + 0.01f) { scale = max(minS, scale - 0.08f); badT = 0f; goodT = 0f; lastDrop = time }
        else if (goodT > 5f && scale < maxS - 0.01f && time - lastDrop > 12f) { scale = min(maxS, scale + 0.05f); goodT = 0f }
    }

    private var menuScene: MenuScene? = null; private var menuT = 0f; private var previewDisc: Mesh? = null
    private var autoYaw = 0f

    private fun drawMenu(dt: Float) {
        adaptResolution(dt.coerceAtMost(0.1f))
        ensureTargets()
        val ms = menuScene ?: MenuScene(ctx).also { menuScene = it }
        menuT += dt
        updateAtmosphere(dt)
        val sc = scene!!
        val e = ms.camera(menuT)
        val view = Mat4.lookAt(e[0], e[1], e[2], e[3], e[4], e[5], 0f, 0f, 1f)
        val proj = Mat4.perspective(Math.toRadians(50.0).toFloat(), sc.w.toFloat() / sc.h, 20f, 9000f)
        val vp = Mat4.mul(proj, view)
        val savedDist = drawDist; drawDist = 4000f
        val cx = camX; val cy = camY; val cz = camZ
        camX = e[0]; camY = e[1]; camZ = e[2]
        sc.bind()
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        drawSky(vp, false)
        ms.draw(vp, e, menuT, sunDir, sunCol, skyAmb, fogCol)
        // the hand-built landmarks, bridges and pontoons on top (the light model has no Basilica, Campanile...)
        val saveStart = fogStart; val saveEnd = fogEnd; val saveDens = fogDens
        fogStart = 1700f; fogEnd = 5200f; fogDens = 1f / 6000f
        generic.use(); common(generic, vp, ident, -1000f, false); generic.f("uFadeDist", 0f)
        generic.i("uAtlas", 6); generic.i("uSign", 8)
        glActiveTexture(GL_TEXTURE8); glBindTexture(GL_TEXTURE_2D, signTex)
        for (l in landmarks) if (hypot(l.x - e[3], l.y - e[4]) < 2600f) {
            glActiveTexture(GL_TEXTURE6); glBindTexture(GL_TEXTURE_2D, l.atlas)
            glUniform4f(generic.loc("uSignRect"), l.signRect[0], l.signRect[1], l.signRect[2], l.signRect[3])
            generic.mat4("uModel", l.model); l.mesh.draw()
        }
        // the player's boat, floating in its own little viewport and turning slowly (drag to turn it)
        val r = Controls.menuBoatRect
        if (r[2] > r[0] && r[3] > r[1] && width > 0) {
            val kx = sc.w.toFloat() / width; val ky = sc.h.toFloat() / height
            val vx = (r[0] * kx).toInt(); val vw = ((r[2] - r[0]) * kx).toInt(); val vh = ((r[3] - r[1]) * ky).toInt(); val vy = sc.h - (r[3] * ky).toInt()
            glEnable(GL_SCISSOR_TEST); glScissor(vx, vy, vw, vh); glViewport(vx, vy, vw, vh)
            glClearColor(0.012f, 0.02f, 0.035f, 1f); glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
            if (Controls.menuBoatDragging) autoYaw = 0f else autoYaw += dt * 0.35f
            val yaw = Controls.menuBoatYaw + autoYaw
            val bob = sin(menuT * 1.3f) * 0.05f
            val bvp = Mat4.mul(Mat4.perspective(Math.toRadians(32.0).toFloat(), vw.toFloat() / max(1, vh), 0.5f, 100f),
                Mat4.lookAt(-cos(0.65f) * 13.5f, 0f, sin(0.65f) * 13.5f + 0.8f, 0f, 0f, 0.6f, 0f, 0f, 1f))
            fogStart = 1e5f; fogEnd = 0f; fogDens = 0f
            common(generic, bvp, ident, -1000f, false)
            generic.vec3("uCam", -cos(0.65f) * 13.5f, 0f, sin(0.65f) * 13.5f + 0.8f)
            val disc = previewDisc ?: MeshBuilder(4096).run {
                color(0.05f, 0.20f, 0.22f, 0.92f)
                val n = 64
                for (i in 0 until n) { val a0 = i * 2f * PI.toFloat() / n; val a1 = (i + 1) * 2f * PI.toFloat() / n
                    quad(Vec3(cos(a0) * 0.05f, sin(a0) * 0.05f, 0f), Vec3(cos(a0) * 7.5f, sin(a0) * 7.5f, 0f), Vec3(cos(a1) * 7.5f, sin(a1) * 7.5f, 0f), Vec3(cos(a1) * 0.05f, sin(a1) * 0.05f, 0f)) }
                Mesh(floats())
            }.also { previewDisc = it }
            generic.mat4("uModel", ident); disc.draw()
            var m = Mat4.mul(Mat4.translation(0f, 0f, bob), Mat4.rotZ(yaw))
            m = Mat4.mul(m, Mat4.rotY(-0.03f + sin(menuT * 0.9f) * 0.012f))
            val s = Mat4.identity(); s[0] = Boat.SCALE; s[5] = Boat.SCALE; s[10] = Boat.SCALE
            m = Mat4.mul(Mat4.mul(m, s), Mat4.translation(-2.22f, 0f, 0f))
            boatMesh?.let { generic.mat4("uModel", m); it.draw() }
            glDisable(GL_SCISSOR_TEST); glViewport(0, 0, sc.w, sc.h)
        }
        fogStart = saveStart; fogEnd = saveEnd; fogDens = saveDens
        camX = cx; camY = cy; camZ = cz; drawDist = savedDist
        // post: tone map + sharpen straight to the screen (no bloom on the menu)
        glDisable(GL_DEPTH_TEST)
        glBindFramebuffer(GL_FRAMEBUFFER, 0); glViewport(0, 0, width, height)
        compP.use()
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, sc.tex); compP.i("uScene", 0)
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, bloomA!!.tex); compP.i("uBloom", 1)
        glUniform2f(compP.loc("uTexel"), 1f / sc.w, 1f / sc.h); compP.f("uExposure", if (sc.isHdr) 0.85f else 1.45f)
        compP.f("uSharpen", Settings.sharpen); compP.f("uBloomK", 0f)
        skyTri?.draw()
        glEnable(GL_DEPTH_TEST)
    }

    private fun boatModel(): FloatArray {
        var m = Mat4.translation(boat.x, boat.y, boat.z)
        m = Mat4.mul(m, Mat4.rotZ(boat.heading)); m = Mat4.mul(m, Mat4.rotY(-boat.pitch)); m = Mat4.mul(m, Mat4.rotX(boat.roll))
        val s = Mat4.identity(); s[0] = Boat.SCALE; s[5] = Boat.SCALE; s[10] = Boat.SCALE
        m = Mat4.mul(m, s)
        return Mat4.mul(m, Mat4.translation(-2.22f, 0f, 0f))
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val rawDt = (now - lastNs) / 1e9f
        val dt = rawDt.coerceIn(0.001f, 0.05f)
        lastNs = now
        fpsAcc += rawDt; fpsN++
        if (fpsAcc > 0.5f) { GameState.fps = fpsN / fpsAcc; fpsAcc = 0f; fpsN = 0 }
        // bad weather: the fog closes in and the render distance drops with it (fewer chunks = steady FPS)
        drawDist = if (Settings.liveWeather || Weather.override > 0) min(Settings.renderDistance, Weather.renderCap()) else Settings.renderDistance
        streamer.radius = drawDist
        if (Controls.screen == Screen.MENU) {
            // title screen: an orbiting flyover of the whole city (light model, no streaming work for it) while the
            // chunks around the start position stream in, so "Gioca" drops you straight into a loaded Venice
            streamer.update(boat.x, boat.y, cos(boat.heading), sin(boat.heading), 2)
            GameState.tilesLoaded = streamer.tiles.size; GameState.tilesWanted = streamer.wanted
            GameState.loading = streamer.tiles.size < min(4, streamer.wanted)
            drawMenu(dt)
            return
        }
        adaptResolution(rawDt.coerceAtMost(0.1f))
        ensureTargets()
        val paused = Controls.paused
        val sdt = if (paused) 0f else dt
        time += sdt
        // one-shot requests from the menus
        if (Controls.requestRespawn) { Controls.requestRespawn = false; boat.surge = 0f; boat.sway = 0f; boat.yawRate = 0f; findWater(true) }
        if (!Controls.teleportX.isNaN()) {
            boat.x = Controls.teleportX; boat.y = Controls.teleportY; boat.surge = 0f; boat.sway = 0f
            Controls.teleportX = Float.NaN; Controls.teleportY = Float.NaN
            findWater(true); camX = boat.x - 9f; camY = boat.y
        }
        updateAtmosphere(dt)

        // ---- simulate
        val thr = Controls.throttle(); val st = Controls.steer()
        if (!paused) {
            boat.trim = Controls.trim
            boat.weatherWaves = if (Settings.liveWeather || Weather.override > 0) Weather.waveFactor() else 1f
            tripTime += sdt
            val ox = boat.x; val oy = boat.y
            for (k in 0 until 3) boat.update(sdt / 3, if (Controls.engineOn) thr else 0f, st, world)
            if (boat.splash > 1.5f) Audio.event(Audio.EV_SPLASH, boat.splash)
            distance += hypot(boat.x - ox, boat.y - oy)
            fx.update(sdt, boat, if (Controls.engineOn) thr else 0f)
            wake.update(sdt, boat, if (Controls.engineOn) thr else 0f, world)
        }
        live.update(if (paused) 0f else dt, camX, camY)
        if (!paused) watchEvents(sdt)
        feedAudio(paused)
        GameState.x = boat.x; GameState.y = boat.y; GameState.heading = boat.heading; GameState.speedKmh = boat.speedKmh()
        GameState.distanceM = distance.toFloat()
        topKmh = max(topKmh, boat.speedKmh()); GameState.topSpeedKmh = topKmh; GameState.tripSeconds = tripTime
        GameState.boatPitch = boat.pitch; GameState.porpoising = boat.porpoise

        // ---- camera: zoomed in when slow, pulled back (and wider) as the speed builds up
        camYawOff += Controls.camYawDelta * Settings.cameraSensitivity; Controls.camYawDelta = 0f
        camPitch = (camPitch + Controls.camPitchDelta * Settings.cameraSensitivity).coerceIn(0.04f, 0.9f); Controls.camPitchDelta = 0f
        if (abs(thr) > 0.1f) camYawOff *= exp(-dt * 1.2f)
        val yaw = boat.heading + camYawOff
        // speed feel: the camera used to pull far back as you sped up, which made 130 km/h feel like 70. Now it stays
        // low and close behind the boat, the field of view widens and the water rushes past at the screen edges.
        val sp = (boat.speedKmh() / 140f).coerceIn(0f, 1f)
        val spS = sp * sp * (3f - 2f * sp)
        val mode = Controls.cameraMode
        val wantDist = when (mode) { 1 -> 15f + 3f * spS; 2 -> 0f; else -> 7.2f + 1.8f * spS }
        val wantFov = when (mode) { 2 -> 70f + 12f * spS; else -> 56f + 26f * spS }
        camDist += (wantDist - camDist) * (1f - exp(-dt * 1.6f))
        camFov += (wantFov - camFov) * (1f - exp(-dt * 1.6f))
        val view: FloatArray
        if (mode == 2) {
            // helm view: standing at the console, looking over the bow
            val c = cos(boat.heading); val s = sin(boat.heading)
            camX = boat.x - c * 0.9f * Boat.SCALE; camY = boat.y - s * 0.9f * Boat.SCALE; camZ = boat.z + 1.75f * Boat.SCALE
            view = Mat4.lookAt(camX, camY, camZ, camX + cos(yaw) * 10f, camY + sin(yaw) * 10f, camZ - 0.6f - camPitch * 4f + boat.pitch * 6f, 0f, 0f, 1f)
        } else {
            val pitch = (camPitch + (if (mode == 1) 0.25f else 0f) - spS * 0.11f).coerceIn(0.03f, 1.0f)
            val tx = boat.x - cos(yaw) * camDist * cos(pitch); val ty = boat.y - sin(yaw) * camDist * cos(pitch)
            val tz = 1.6f + camDist * sin(pitch) + boat.z * 0.6f
            val k = 1f - exp(-dt * (5f + 4f * spS))
            camX += (tx - camX) * k; camY += (ty - camY) * k; camZ += (tz - camZ) * (1f - exp(-dt * 3f))
            if (world.solid(camX, camY) > 0.5f && camZ < 14f) { camX += (boat.x - camX) * 0.25f; camY += (boat.y - camY) * 0.25f }
            camZ = max(camZ, Waves.height(camX, camY, boat.time, boat.waveAmp) + 0.6f)
            val shake = boat.bump * 0.05f + spS * spS * 0.028f + boat.porpoise * 0.06f
            view = Mat4.lookAt(camX + sin(time * 40) * shake, camY, camZ + cos(time * 37) * shake, boat.x + cos(boat.heading) * 3f, boat.y + sin(boat.heading) * 3f, 1.2f + boat.z * 0.7f, 0f, 0f, 1f)
        }
        val sc = scene!!
        val proj = Mat4.perspective(Math.toRadians(camFov.toDouble()).toFloat(), sc.w.toFloat() / sc.h, 0.3f, max(700f, drawDist * 1.6f))
        val vp = Mat4.mul(proj, view)

        streamer.update(camX, camY, cos(yaw), sin(yaw), if (boat.speedKmh() > 60f) 2 else 1)
        GameState.tilesLoaded = streamer.tiles.size; GameState.tilesWanted = streamer.wanted
        GameState.loading = streamer.tiles.size < min(4, streamer.wanted)

        val bm = boatModel()
        if (Settings.shadows) renderCityShadow(camX + cos(yaw) * 120f, camY + sin(yaw) * 120f)
        renderBoatShadow(bm)

        // ---- reflection pass: low resolution, nearby city only, no shadows (sky only when reflections are off)
        val r = refl!!
        r.bind()
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        drawSky(vp, true)
        if (Settings.reflections) drawOpaque(vp, mirror, -0.05f, min(260f, drawDist), bm, true)

        // ---- main HDR scene
        sc.bind()
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        drawSky(vp, false)
        drawOpaque(vp, ident, -1000f, drawDist, bm, false)
        drawWater(vp, r)
        drawTrails(vp)
        drawSpray(vp)
        drawSmoke(vp)
        if (Settings.liveWeather || Weather.override > 0) { val w = Weather.windVec(); rain.draw(vp, camX, camY, camZ, boat.time, Weather.rainIntensity(), w[0] * 0.6f, w[1] * 0.6f, fogCol) }

        // ---- post
        glDisable(GL_DEPTH_TEST)
        val ba = bloomA!!; val bb = bloomB!!
        if (Settings.bloom) {
        ba.bind(); brightP.use(); glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, sc.tex); brightP.i("uTex", 0)
        glUniform2f(brightP.loc("uTexel"), 1f / sc.w, 1f / sc.h); skyTri?.draw()
        blurP.use(); blurP.i("uTex", 0)
        bb.bind(); glBindTexture(GL_TEXTURE_2D, ba.tex); glUniform2f(blurP.loc("uDir"), 1.5f / ba.w, 0f); skyTri?.draw()
        ba.bind(); glBindTexture(GL_TEXTURE_2D, bb.tex); glUniform2f(blurP.loc("uDir"), 0f, 1.5f / ba.h); skyTri?.draw()
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0); glViewport(0, 0, width, height)
        compP.use()
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, sc.tex); compP.i("uScene", 0)
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, ba.tex); compP.i("uBloom", 1)
        glUniform2f(compP.loc("uTexel"), 1f / sc.w, 1f / sc.h); compP.f("uExposure", (if (sc.isHdr) 0.85f else 1.45f) * (1f + flash * 1.6f))
        // sharpen more when the internal resolution is lower
        compP.f("uSharpen", Settings.sharpen * (1f + (1f - scale) * 1.5f)); compP.f("uBloomK", if (Settings.bloom) 1f else 0f)
        skyTri?.draw()
        glEnable(GL_DEPTH_TEST)
    }

    private fun drawSky(vp: FloatArray, mirrored: Boolean) {
        glDepthMask(false)
        sky.use(); sky.mat4("uInvVP", Mat4.invert(vp)); sky.vec3("uSunDir", sunDir); sky.vec3("uFogCol", fogCol); sky.vec3("uSkyZenith", zenith); sky.vec3("uSunCol", sunCol)
        sky.f("uMirrorSky", if (mirrored) 1f else 0f); sky.f("uTime", time)
        sky.f("uCloudCover", if (Settings.liveWeather || Weather.override > 0) Weather.clouds() else 0.55f)
        sky.f("uHazeH", (34f / drawDist).coerceIn(0.02f, 0.25f))
        if ((Settings.liveWeather && Weather.live) || Weather.override > 0) { val w = Weather.windVec(); sky.vec2("uWind", w[0] * 0.4f, w[1] * 0.4f) } else sky.vec2("uWind", 1f, 0.375f)
        skyTri?.draw()
        glDepthMask(true)
    }

    private fun common(p: Program, vp: FloatArray, mir: FloatArray, clipZ: Float, shadows: Boolean = true) {
        p.mat4("uVP", vp); p.mat4("uMirror", mir)
        p.vec3("uSunDir", sunDir); p.vec3("uSunCol", sunCol); p.vec3("uSkyAmb", skyAmb); p.vec3("uGroundAmb", groundAmb)
        p.vec3("uFogCol", fogCol); p.vec3("uSkyZenith", zenith); p.f("uFogDens", fogDens); p.f("uFogStart", fogStart); p.f("uFogEnd", fogEnd); p.f("uWet", wet); p.vec3("uCam", camX, camY, camZ); p.f("uTime", boat.time)
        p.f("uClipZ", clipZ)
        glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, cityShadow.tex); p.i("uShadow", 2)
        glActiveTexture(GL_TEXTURE9); glBindTexture(GL_TEXTURE_2D, boatShadow.tex); p.i("uShadowB", 9)
        glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_2D_ARRAY, texArr[0]); p.i("uAlb", 3)
        glActiveTexture(GL_TEXTURE4); glBindTexture(GL_TEXTURE_2D_ARRAY, texArr[1]); p.i("uNrm", 4)
        p.mat4("uLightVP", lightVP); p.mat4("uLightVPB", lightVPB); p.f("uShadowOn", if (shadows && Settings.shadows && cityShadow.tex != 0) 1f else 0f)
    }

    private fun drawOpaque(vp: FloatArray, mir: FloatArray, clipZ: Float, maxDist: Float, bm: FloatArray, reflection: Boolean) {
        val pv = Mat4.mul(vp, mir)
        setPlanes(pv)
        visible.clear()
        for (t in streamer.tiles.values) if (hypot(t.cx - camX, t.cy - camY) < maxDist + 182 && inFrustum(pv, t.ox, t.oy)) visible.add(t)
        if (!reflection) {
            ground.use(); common(ground, vp, mir, clipZ); ground.i("uMask", 0); ground.i("uClass", 1)
            for (t in visible) {
                if (t.maskTex == 0) continue
                glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, t.maskTex)
                glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, t.classTex)
                ground.f("uMarshPass", 0f); ground.mat4("uModel", Mat4.translation(t.ox, t.oy, Z.GROUND)); groundQuad?.draw()
                if (t.hasMarsh) { ground.f("uMarshPass", 1f); ground.mat4("uModel", Mat4.translation(t.ox, t.oy, Z.MARSH)); groundQuad?.draw() }
            }
        }
        facade.use(); common(facade, vp, mir, clipZ, !reflection)
        for (t in visible) { val m = t.facade ?: continue; facade.mat4("uModel", t.model); m.draw() }
        generic.use(); common(generic, vp, mir, clipZ, !reflection)
        generic.f("uFadeDist", 0f)
        val genDist = if (reflection) 140f else maxDist
        for (t in visible) {
            if (hypot(t.cx - camX, t.cy - camY) > genDist + 182) continue
            val m = t.generic ?: continue; generic.mat4("uModel", t.model); m.draw()
        }
        // close-up detail (balconies, shutters, sills, chimney pots, antennas) and vegetation: their own, shorter
        // distances, dissolving out with a dither instead of popping
        if (!reflection) {
            val dd = min(Settings.detailDistance, maxDist)
            generic.f("uFadeDist", dd)
            for (t in visible) if (hypot(t.cx - camX, t.cy - camY) < dd + 182) t.detail?.let { d -> generic.mat4("uModel", t.model); d.draw() }
        }
        val vd = if (reflection) min(90f, Settings.vegetationDistance) else min(Settings.vegetationDistance, maxDist)
        generic.f("uFadeDist", vd)
        for (t in visible) if (hypot(t.cx - camX, t.cy - camY) < vd + 182) t.vegetation?.let { v -> generic.mat4("uModel", t.model); v.draw() }
        generic.f("uFadeDist", 0f)
        stopExtras?.let { generic.mat4("uModel", ident); it.draw() }
        generic.i("uAtlas", 6); generic.i("uSign", 8)
        glActiveTexture(GL_TEXTURE8); glBindTexture(GL_TEXTURE_2D, signTex)
        val lmDist = if (reflection) 200f else maxDist + 60f
        for (l in landmarks) if (hypot(l.x - camX, l.y - camY) < lmDist && sphereVisible(l.x, l.y, 20f, 140f)) {
            glActiveTexture(GL_TEXTURE6); glBindTexture(GL_TEXTURE_2D, l.atlas)
            glUniform4f(generic.loc("uSignRect"), l.signRect[0], l.signRect[1], l.signRect[2], l.signRect[3])
            generic.mat4("uModel", l.model); l.mesh.draw()
        }
        // live ACTV vaporetti (plain-colour materials, no atlas)
        run {
            val vd = if (reflection) 220f else maxDist + 30f
            for (b in live.boats.values) if (hypot(b.px - camX, b.py - camY) < vd && sphereVisible(b.px, b.py, 2f, b.kind.len * 0.6f)) {
                val vm = fleetMeshes[b.kind.ordinal] ?: continue
                generic.mat4("uModel", live.model(b, boat.time, boat.waveAmp)); vm.draw()
            }
        }
        boatMesh?.let { generic.mat4("uModel", bm); it.draw() }
    }

    private fun drawWater(vp: FloatArray, r: Target) {
        water.use(); common(water, vp, ident, -1000f)
        val cx = floor(camX / 40f) * 40f; val cy = floor(camY / 40f) * 40f
        water.vec3("uCenter", cx, cy, 0f); water.f("uWaveAmp", boat.waveAmp)
        glActiveTexture(GL_TEXTURE5); glBindTexture(GL_TEXTURE_2D, r.tex); water.i("uRefl", 5)
        glActiveTexture(GL_TEXTURE7); glBindTexture(GL_TEXTURE_2D, wake.tex); water.i("uWake", 7)
        glActiveTexture(GL_TEXTURE10); glBindTexture(GL_TEXTURE_2D, waterN); water.i("uWaterN", 10)
        water.vec3("uWakeO", wake.ox, wake.oy, if (wake.ok) wake.size else 0.001f); water.vec2("uWakeDir", wake.dirX, wake.dirY)
        glUniform4f(water.loc("uBoat"), boat.x, boat.y, boat.heading, abs(boat.surge)); glUniform2f(water.loc("uHull"), Boat.HALF_LEN, Boat.HALF_BEAM)
        glUniform2f(water.loc("uScreen"), scene!!.w.toFloat(), scene!!.h.toFloat())
        water.f("uGridHalf", 1e6f); waterNear?.draw()
        water.f("uGridHalf", 160f); waterMid?.draw()
        water.f("uGridHalf", 1f); waterFar?.draw()
    }

    private fun drawTrails(vp: FloatArray) {
        val n = live.buildTrails(trailData, min(drawDist, 700f), camX, camY)
        if (n == 0) return
        trailMesh.upload(trailData, n)
        glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); glDepthMask(false)
        trailP.use(); common(trailP, vp, ident, -1000f)
        trailMesh.draw()
        glDepthMask(true); glDisable(GL_BLEND)
    }

    private fun drawSmoke(vp: FloatArray) {
        val src = live.smokeP; var n = 0
        for (i in 0 until live.smokeN) {
            val o = i * 9; val t = src[o + 5] / src[o + 6]
            val q = n * 6
            smokeData[q] = src[o]; smokeData[q + 1] = src[o + 1]; smokeData[q + 2] = src[o + 2]
            smokeData[q + 3] = src[o + 7] * (1f + t * 5f)                         // puffs grow as they rise
            smokeData[q + 4] = (0.10f + 0.30f * src[o + 8]) * min(1f, t * 8f) * (1f - t) // fade in, then out
            smokeData[q + 5] = src[o + 8] * (1f - t * 0.7f)
            n++
        }
        if (n == 0) return
        smokeMesh.upload(smokeData, n)
        glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); glDepthMask(false)
        smokeP.use(); common(smokeP, vp, ident, -1000f)
        smokeMesh.draw()
        glDepthMask(true); glDisable(GL_BLEND)
    }

    private fun drawSpray(vp: FloatArray) {
        val n = fx.buildInstances(sprayData)
        if (n == 0) return
        sprayMesh.upload(sprayData, n)
        glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); glDepthMask(false)
        sprayP.use(); common(sprayP, vp, ident, -1000f)
        sprayMesh.draw()
        glDepthMask(true); glDisable(GL_BLEND)
    }

    /** City sun shadow: ~420 m square ahead of the camera, cached; redrawn only when that square moves (64 m grid)
     *  or new tiles arrive inside it. */
    private fun renderCityShadow(cx: Float, cy: Float) {
        if (cityShadow.tex == 0) return
        val sx = floor(cx / 64f) * 64f + 32f; val sy = floor(cy / 64f) * 64f + 32f
        if (sx == shadowCx && sy == shadowCy && streamer.version == shadowVersion) return
        shadowCx = sx; shadowCy = sy; shadowVersion = streamer.version
        val half = 230f
        val dist = 400f
        val view = Mat4.lookAt(sx + sunDir[0] * dist, sy + sunDir[1] * dist, sunDir[2] * dist, sx, sy, 0f, 0f, 0f, 1f)
        lightVP = Mat4.mul(Mat4.ortho(-half, half, -half, half, 1f, dist * 2f), view)
        cityShadow.bind()
        glClear(GL_DEPTH_BUFFER_BIT)
        glEnable(GL_POLYGON_OFFSET_FILL); glPolygonOffset(1.6f, 2.5f)
        depth.use(); depth.mat4("uLightVP", lightVP)
        for (t in streamer.tiles.values) {
            if (abs(t.ox + 128 - sx) > half + 200 || abs(t.oy + 128 - sy) > half + 200) continue
            depth.mat4("uModel", t.model); t.facade?.draw(); t.generic?.draw(); t.detail?.draw()
            if (hypot(t.cx - camX, t.cy - camY) < Settings.vegetationDistance + 182) t.vegetation?.draw()
        }
        for (l in landmarks) if (abs(l.x - sx) < half + 80 && abs(l.y - sy) < half + 80) { depth.mat4("uModel", l.model); l.mesh.draw() }
        glDisable(GL_POLYGON_OFFSET_FILL)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    /** The boat's own shadow (on its deck and the water around it), every frame into a small map. */
    private fun renderBoatShadow(bm: FloatArray) {
        if (boatShadow.tex == 0) return
        val half = 9f; val dist = 40f
        val view = Mat4.lookAt(boat.x + sunDir[0] * dist, boat.y + sunDir[1] * dist, sunDir[2] * dist, boat.x, boat.y, 0f, 0f, 0f, 1f)
        lightVPB = Mat4.mul(Mat4.ortho(-half, half, -half, half, 1f, dist * 2f), view)
        boatShadow.bind()
        glClear(GL_DEPTH_BUFFER_BIT)
        glEnable(GL_POLYGON_OFFSET_FILL); glPolygonOffset(1.2f, 2f)
        depth.use(); depth.mat4("uLightVP", lightVPB); depth.mat4("uModel", bm); boatMesh?.draw()
        glDisable(GL_POLYGON_OFFSET_FILL)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    private val planes = FloatArray(20)
    /** the 5 frustum planes (left, right, bottom, top, near) of a view-projection, normalised */
    private fun setPlanes(vp: FloatArray) {
        val sg = floatArrayOf(1f, -1f, 1f, -1f, 1f); val row = intArrayOf(0, 0, 1, 1, 2)
        for (k in 0 until 5) {
            val r = row[k]; val s = sg[k]
            val a = vp[3] + s * vp[r]; val b = vp[7] + s * vp[4 + r]; val c = vp[11] + s * vp[8 + r]; val d = vp[15] + s * vp[12 + r]
            val l = max(sqrt(a * a + b * b + c * c), 1e-6f)
            planes[k * 4] = a / l; planes[k * 4 + 1] = b / l; planes[k * 4 + 2] = c / l; planes[k * 4 + 3] = d / l
        }
    }
    private fun sphereVisible(x: Float, y: Float, z: Float, r: Float): Boolean {
        for (k in 0 until 5) if (planes[k * 4] * x + planes[k * 4 + 1] * y + planes[k * 4 + 2] * z + planes[k * 4 + 3] < -r) return false
        return true
    }

    private fun inFrustum(vp: FloatArray, ox: Float, oy: Float): Boolean {
        var allLeft = true; var allRight = true; var allBelow = true; var allAbove = true; var allBehind = true
        for (c in 0 until 8) {
            val x = ox + if (c and 1 == 0) 0f else TILE_SIZE.toFloat()
            val y = oy + if (c and 2 == 0) 0f else TILE_SIZE.toFloat()
            val z = if (c and 4 == 0) -2f else 100f
            val cx = vp[0] * x + vp[4] * y + vp[8] * z + vp[12]
            val cy = vp[1] * x + vp[5] * y + vp[9] * z + vp[13]
            val cw = vp[3] * x + vp[7] * y + vp[11] * z + vp[15]
            if (cx > -cw) allLeft = false; if (cx < cw) allRight = false
            if (cy > -cw) allBelow = false; if (cy < cw) allAbove = false
            if (cw > 0) allBehind = false
        }
        return !(allLeft || allRight || allBelow || allAbove || allBehind)
    }

    fun shutdown() { if (::streamer.isInitialized) streamer.shutdown(); if (::live.isInitialized) live.shutdown() }
}
