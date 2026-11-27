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
    private var waterN = 0; private var foamT = 0

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

    // ================================================================ boot: one step per frame, with progress
    // Everything is loaded in small steps spread over frames (the loading screen animates meanwhile and memory never
    // peaks with several big things decoded at once). Effects that fail to build are switched off, never fatal.
    private var bootStep = 0
    private var mapBytes: ByteArray? = null
    private val boot: List<Pair<String, () -> Unit>> = listOf(
        "Leggo la mappa di Venezia" to {
            val bytes = ctx.assets.open("venice.map").use { it.readBytes() }
            world = WorldMap(MapData(java.io.ByteArrayInputStream(bytes)))
            val map = world.map
            GameState.minX = map.minX; GameState.minY = map.minY; GameState.lat0 = map.lat0; GameState.lon0 = map.lon0
            mapBytes = bytes
        },
        "Preparo la cache dei quartieri" to {
            // meshed tiles are cached on the phone; the key changes with the map data and with every app build
            val crc = java.util.zip.CRC32().apply { update(mapBytes!!) }.value; mapBytes = null
            // the key no longer has the app build in it: an update keeps the phone's cache unless the mesher changed
            // (and most tiles come pre-baked in the APK anyway)
            streamer = TileStreamer(world, Settings.renderDistance, TileCache(ctx.filesDir, "${java.lang.Long.toHexString(crc)}-m$MESHER_VERSION", ctx.assets))
        },
        "Compilo gli shader della città" to {
            generic = Program(Shaders.STD_VS, Shaders.GENERIC_FS, "generic")
            facade = Program(Shaders.FACADE_VS, Shaders.FACADE_FS, "facade")
            ground = Program(Shaders.GROUND_VS, Shaders.GROUND_FS, "ground")
        },
        "Compilo acqua e cielo" to {
            water = Program(Shaders.WATER_VS, Shaders.WATER_FS, "water")
            waterSimple = Program(Shaders.WATER_FLAT_VS, Shaders.WATER_FLAT_FS, "water-flat")
            // one big flat quad around the camera (the simple water needs no grid: nothing is displaced)
            flatWater = Mesh(floatArrayOf(-3000f, -3000f, 0f, 3000f, -3000f, 0f, 3000f, 3000f, 0f, -3000f, -3000f, 0f, 3000f, 3000f, 0f, -3000f, 3000f, 0f), 3, intArrayOf(3))
            sky = Program(Shaders.SKY_VS, Shaders.SKY_FS, "sky")
            depth = Program(Shaders.DEPTH_VS, Shaders.DEPTH_FS, "depth")
        },
        "Compilo luce ed effetti" to {
            sprayP = Program(Shaders.SPRAY_VS, Shaders.SPRAY_FS, "spray")
            brightP = Program(Shaders.POST_VS, Shaders.BRIGHT_FS, "bright")
            blurP = Program(Shaders.POST_VS, Shaders.BLUR_FS, "blur")
            compP = Program(Shaders.POST_VS, Shaders.COMPOSITE_FS, "composite")
            dimP = Program(Shaders.POST_VS, Shaders.DIM_FS, "dim")
            copyP = Program(Shaders.POST_VS, Shaders.COPY_FS, "copy")
            smokeP = try { Program(Shaders.SMOKE_VS, Shaders.SMOKE_FS, "smoke") } catch (e: Throwable) { android.util.Log.e("Venice", "smoke off", e); null }
            trailP = try { Program(Shaders.TRAIL_VS, Shaders.TRAIL_FS, "trail") } catch (e: Throwable) { android.util.Log.e("Venice", "trails off", e); null }
        },
        "Carico pietra, intonaco e legno" to { texArr = TexArrays.load(ctx, L.COUNT) },
        "Preparo ombre e acqua" to {
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
            foamT = loadData("water/foam.png")
        },
        "Varo la tua barca" to { boatMesh = loadMesh("boat.mesh") },
        "San Marco, Rialto e i monumenti" to { loadLandmarks() },
        "I ponti" to { loadBridges() },
        "Gli imbarcaderi ACTV" to { loadStops() },
        "Le barene" to { grassP = Program(Shaders.GRASS_VS, Shaders.GRASS_FS, "grass"); grass = MarshGrass(world) },
        "I gabbiani" to { try { gullP = Program(Shaders.GULL_VS, Shaders.GULL_FS, "gulls"); gulls = Gulls(world) } catch (e: Throwable) { android.util.Log.e("Venice", "gulls", e); gullP = null; gulls = null } },
        "La flotta ACTV" to {
            live = LiveBoats(world); live.setStops(stopList)
            for ((i, n) in listOf("motobattello", "motoscafo", "motonave", "lancione").withIndex()) { fleetMeshes[i] = loadMesh("live/$n.mesh"); fleetLod[i] = loadMesh("live/${n}_lod.mesh") }
            wake.fleet = live
            smokeMesh = InstMesh(6, intArrayOf(3, 3), LiveBoats.MAX_SMOKE); trailMesh = DynMesh(5, intArrayOf(2, 3), 9000 * 5)
        },
        "Metto in acqua" to {
            boat = Boat(-330f, 380f, Math.toRadians(41.0).toFloat())
            Obstacles.stops = stopBoxes
            findWater()
            camX = boat.x - 9f; camY = boat.y; camZ = 3.2f
        },
    )

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // a new GL context (first start, or the old one was lost): everything is rebuilt, step by step
        ready = false; bootStep = 0
        GameState.bootProgress = 0f; GameState.bootStage = boot[0].first; GameState.booted = false
        glEnable(GL_DEPTH_TEST); glDisable(GL_CULL_FACE)
    }

    /** runs one boot step; true when everything is loaded */
    private fun bootFrame(): Boolean {
        if (bootStep < boot.size) {
            val (label, step) = boot[bootStep]
            GameState.bootStage = label
            step()
            bootStep++
            GameState.bootProgress = bootStep.toFloat() / boot.size
            if (bootStep < boot.size) GameState.bootStage = boot[bootStep].first
            if (bootStep == boot.size) {
                lastNs = System.nanoTime(); scale = Settings.maxScale()
                ready = true
            }
        }
        return ready
    }

    companion object {
        /** bump whenever CityMesher output changes, so cached tiles on players' phones are rebuilt */
        const val MESHER_VERSION = 9
    }

    private fun loadMesh(asset: String): Mesh? = try {
        DataInputStream(InflaterInputStream(ctx.assets.open(asset)).buffered(1 shl 16)).use { d ->
            val n = d.readInt(); Mesh(FloatArray(n * STRIDE) { d.readFloat() })
        }
    } catch (e: Exception) { android.util.Log.e("Venice", "mesh $asset", e); null }

    /** Baked albedo atlas of a hand-built landmark (sRGB JPEG, mipmapped). 0 if the landmark has none. */
    /** a tileable data texture (linear, repeating, mipmapped) */
    private fun loadData(asset: String): Int = try {
        val opts = android.graphics.BitmapFactory.Options().apply { inPremultiplied = false; inScaled = false }
        val bmp = ctx.assets.open(asset).use { android.graphics.BitmapFactory.decodeStream(it, null, opts)!! }
        val t = IntArray(1); glGenTextures(1, t, 0)
        glBindTexture(GL_TEXTURE_2D, t[0])
        android.opengl.GLUtils.texImage2D(GL_TEXTURE_2D, 0, bmp, 0); bmp.recycle()
        glGenerateMipmap(GL_TEXTURE_2D)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT)
        t[0]
    } catch (e: Exception) { 0 }

    private fun loadAtlas(asset: String): Int {
        // pre-compressed copy first (8x less GPU memory, no Bitmap decode), the JPEG/PNG only as a fallback
        if (asset.endsWith(".jpg")) Etc.load2D(ctx, asset.removeSuffix(".jpg") + ".etc").let { if (it != 0) return it }
        return loadAtlasBitmap(asset)
    }

    private fun loadAtlasBitmap(asset: String): Int = try {
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
    private val fleetMeshes = arrayOfNulls<Mesh>(4); private val fleetLod = arrayOfNulls<Mesh>(4)
    private val boatModel = FloatArray(16)
    private var smokeP: Program? = null; private var trailP: Program? = null
    private lateinit var smokeMesh: InstMesh; private lateinit var trailMesh: DynMesh
    private val smokeData = FloatArray(LiveBoats.MAX_SMOKE * 6); private val trailData = FloatArray(9000 * 5)
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
        val meshes = HashMap<String, Pair<Mesh, BridgeShape>?>()
        val own = (ctx.assets.list("bridges") ?: emptyArray()).filter { it.endsWith("_atlas.jpg") && it != "bridges_atlas.jpg" }.associate { it.removeSuffix("_atlas.jpg") to loadAtlas("bridges/$it") }  // models baked on their own sheet
        val g = 1.1f
        for (line in lines) {
            if (line.isBlank() || line.startsWith("#")) continue
            val p = line.trim().split(Regex("\\s+")); if (p.size < 7) continue
            val ms = meshes.getOrPut(p[0]) { loadMeshData("bridges/${p[0]}.mesh")?.let { Pair(Mesh(it), bridgeShape(it)) } } ?: continue
            val mesh = ms.first
            val x = p[1].toFloat(); val y = p[2].toFloat(); val rot = Math.toRadians(p[3].toDouble()).toFloat()
            val sx = p[4].toFloat(); val sy = p[5].toFloat(); val sz = p[6].toFloat()
            val sc = Mat4.identity().also { it[0] = sx; it[5] = sy; it[10] = sz; it[14] = g * (1f - sz) }
            val model = Mat4.mul(Mat4.mul(Mat4.translation(x, y, 0f), Mat4.rotZ(rot)), sc)
            landmarks.add(Landmark(mesh, model, x, y, own[p[0]] ?: atlas))
            val sh = ms.second
            bridgeCol.add(BridgeInst(x, y, cos(rot), sin(rot), sx, sy, sz, g * (1f - sz), sh, hypot(max(abs(sh.minX), abs(sh.maxX)) * sx, sh.halfW * sy) + 3f))
        }
    }

    // ---------------------------------------------------------------- camera collision
    /** a bridge model's shape for the camera: along its span (local x), the underside and the top of the deck */
    private class BridgeShape(val minX: Float, val maxX: Float, val halfW: Float, val lo: FloatArray, val hi: FloatArray)
    private class BridgeInst(val x: Float, val y: Float, val c: Float, val s: Float, val sx: Float, val sy: Float, val sz: Float, val z0: Float, val shape: BridgeShape, val r: Float)
    private val bridgeCol = ArrayList<BridgeInst>()
    private val NB = 32
    private fun bridgeShape(d: FloatArray): BridgeShape {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var hw = 0f
        var i = 0
        while (i < d.size) { minX = min(minX, d[i]); maxX = max(maxX, d[i]); hw = max(hw, abs(d[i + 1])); i += STRIDE }
        val lo = FloatArray(NB) { Float.MAX_VALUE }; val hi = FloatArray(NB) { -Float.MAX_VALUE }
        val span = max(maxX - minX, 0.1f)
        i = 0
        while (i < d.size) {
            val k = ((d[i] - minX) / span * (NB - 1)).roundToInt().coerceIn(0, NB - 1)
            lo[k] = min(lo[k], d[i + 2]); hi[k] = max(hi[k], d[i + 2]); i += STRIDE
        }
        // bins no vertex fell in: take the neighbours'
        for (k in 0 until NB) if (lo[k] == Float.MAX_VALUE) {
            var a = k - 1; while (a >= 0 && lo[a] == Float.MAX_VALUE) a--
            var b = k + 1; while (b < NB && lo[b] == Float.MAX_VALUE) b++
            val src = if (a >= 0 && (b >= NB || k - a <= b - k)) a else b
            if (src in 0 until NB) { lo[k] = lo[src]; hi[k] = hi[src] } else { lo[k] = 0f; hi[k] = 0f }
        }
        return BridgeShape(minX, maxX, hw, lo, hi)
    }
    private fun loadMeshData(asset: String): FloatArray? = try {
        DataInputStream(InflaterInputStream(ctx.assets.open(asset)).buffered(1 shl 16)).use { d -> val n = d.readInt(); FloatArray(n * STRIDE) { d.readFloat() } }
    } catch (e: Exception) { android.util.Log.e("Venice", "mesh $asset", e); null }

    private var camBridge = -1; private var camBridgeUp = true; private var camBridgeT = 0f
    /** the height the camera must be at, at x, y, so it isn't inside a bridge: over the deck or under the arch,
     *  whichever it is nearer (decided once per bridge, so it doesn't flip-flop) */
    private fun bridgeClear(x: Float, y: Float, z: Float, floor: Float): Float {
        fun under(b: BridgeInst): Boolean {
            val dx = boat.x - b.x; val dy = boat.y - b.y
            val lx = (dx * b.c + dy * b.s) / b.sx; val ly = (-dx * b.s + dy * b.c) / b.sy
            return lx > b.shape.minX && lx < b.shape.maxX && abs(ly) < b.shape.halfW + 1f / b.sy
        }
        for ((i, b) in bridgeCol.withIndex()) {
            val dx = x - b.x; val dy = y - b.y
            if (abs(dx) > b.r || abs(dy) > b.r) continue
            val sh = b.shape
            val lx = (dx * b.c + dy * b.s) / b.sx; val ly = (-dx * b.s + dy * b.c) / b.sy
            if (lx < sh.minX - 0.8f / b.sx || lx > sh.maxX + 0.8f / b.sx || abs(ly) > sh.halfW + 0.9f / b.sy) continue
            val f = ((lx - sh.minX) / max(sh.maxX - sh.minX, 0.1f) * (NB - 1)).coerceIn(0f, NB - 1f)
            val k = f.toInt().coerceAtMost(NB - 2); val t = f - k
            val lo = b.z0 + b.sz * (sh.lo[k] + (sh.lo[k + 1] - sh.lo[k]) * t)
            val hi = b.z0 + b.sz * (sh.hi[k] + (sh.hi[k + 1] - sh.hi[k]) * t)
            val m = 0.6f
            if (z <= lo - m || z >= hi + m) continue
            if (camBridge != i) { camBridge = i; camBridgeUp = if (under(b) && lo - m > floor + 0.4f) false else z > (lo + hi) * 0.5f }
            camBridgeT = 1.2f
            return if (camBridgeUp || lo - m < floor + 0.2f) hi + m else lo - m
        }
        return z
    }

    private val bboxCache = HashMap<BuildingData, FloatArray>()
    /** what the camera has to stay above at x, y: a building's roofs, the quay, or nothing (water) */
    private fun obstacleTop(x: Float, y: Float): Float {
        if (world.solid(x, y) < 0.5f) return -10f
        val (tx, ty) = world.tileCoords(x, y)
        val t = world.tile(tx, ty) ?: return Z.GROUND + 1f
        val lx = x - t.ox; val ly = y - t.oy
        for (b in t.buildings) {
            val n = b.ringSizes.firstOrNull() ?: continue
            val bb = bboxCache.getOrPut(b) {
                var a = Float.MAX_VALUE; var c = Float.MAX_VALUE; var e = -Float.MAX_VALUE; var f = -Float.MAX_VALUE
                for (k in 0 until n) { a = min(a, b.xy[k * 2]); c = min(c, b.xy[k * 2 + 1]); e = max(e, b.xy[k * 2]); f = max(f, b.xy[k * 2 + 1]) }
                floatArrayOf(a, c, e, f)
            }
            if (lx < bb[0] || ly < bb[1] || lx > bb[2] || ly > bb[3]) continue
            var inside = false; var j = n - 1
            for (k in 0 until n) {
                val xi = b.xy[k * 2]; val yi = b.xy[k * 2 + 1]; val xj = b.xy[j * 2]; val yj = b.xy[j * 2 + 1]
                if ((yi > ly) != (yj > ly) && lx < (xj - xi) * (ly - yi) / (yj - yi) + xi) inside = !inside
                j = k
            }
            if (inside) return Z.GROUND + b.height + 4f            // plus the roof
        }
        return Z.GROUND + 1.1f                                       // fondamenta, campo: over it, not through it
    }
    /** how far (0..1) the camera can go from the pivot towards `to` before it would enter a building */
    private fun armFree(px: Float, py: Float, pz: Float, qx: Float, qy: Float, qz: Float): Float {
        val len = hypot(qx - px, qy - py)
        val n = max(2, (len / 0.3f).toInt())
        for (k in 1..n) {
            val f = k / n.toFloat()
            if (f * len < 1.8f) continue                  // the boat's own footprint: it may be touching a wall
            val x = px + (qx - px) * f; val y = py + (qy - py) * f; val z = pz + (qz - pz) * f
            if (z < obstacleTop(x, y)) return max(0f, (k - 1) / n.toFloat() - 0.35f / max(len, 0.1f))
        }
        return 1f
    }
    private var camSX = Float.NaN; private var camSY = 0f; private var camSZ = 0f
    private var camArm = 1f; private var camLift = 0f; private var camTouchT = -100f

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
    private var scrapeSnd = 0f; private var vzMin = 0f; private var slapCd = 0f
    private fun feedAudio(paused: Boolean) {
        Audio.paused = paused; Audio.menu = false
        Audio.master = Settings.volMaster; Audio.engineVol = Settings.volEngine; Audio.ambienceVol = Settings.volAmbience
        Audio.engineOn = Controls.engineOn; Audio.throttle = Controls.throttle(); Audio.speed = abs(boat.surge)
        Audio.trim = Controls.trim; Audio.airborne = boat.airborne
        // rising off a wave the prop comes up (load drops), slamming down it bites hard again
        Audio.propLoad = if (boat.airborne) 0f else (1f - ((boat.vz - 0.25f) * 0.7f).coerceIn(0f, 0.85f)) * (if (boat.vz < -0.8f) 1f else 1f)
        Audio.nearLand = ((0.11f - boat.waveAmp) / 0.075f).coerceIn(0f, 1f)
        Audio.waveAmp = boat.waveAmp
        Audio.tangle = boat.entangle.coerceIn(0f, 1f)
        // nearest pontoon (they creak only when you're close)
        val pts = LiveBoats.pontoons
        var pd = 1e9f; var pxN = 0f; var pyN = 0f
        var i = 0
        while (i + 1 < pts.size) { val d = hypot(pts[i] - boat.x, pts[i + 1] - boat.y); if (d < pd) { pd = d; pxN = pts[i]; pyN = pts[i + 1] }; i += 2 }
        Audio.pontoonX = pxN; Audio.pontoonY = pyN; Audio.pontoonD = pd
        val wx = (Settings.liveWeather && Weather.live) || Weather.override > 0
        Audio.rain = if (wx) Weather.rainIntensity() else 0f
        Audio.wind = if (wx) (hypot(Weather.windVec()[0], Weather.windVec()[1]) * 3.6f / 45f).coerceIn(0f, 1f) else 0.1f
        Audio.lx = camX; Audio.ly = camY; Audio.lyaw = boat.heading + camYawOff
        Audio.boats = LiveBoats.audio
        while (true) {
            val c = LiveBoats.cues.poll() ?: break
            when (c[0]) {
                LiveBoats.CUE_DOCK -> Audio.event(Audio.EV_DOCK, 1f, c[1], c[2])
                LiveBoats.CUE_DEPART -> Audio.event(Audio.EV_DEPART, 1f, c[1], c[2])
                LiveBoats.CUE_HORN -> Audio.event(Audio.EV_HORN, c[3], c[1], c[2], c[4])
                LiveBoats.CUE_WAKE -> Audio.event(Audio.EV_WAKE, c[3])
            }
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
                4 -> Events.post("URTO CONTRO UNA BRICOLA", Events.WARN, "${(boat.impact * 3.6f).roundToInt()} km/h", "urto")
                else -> Events.post("URTO", Events.WARN, "contro la fondamenta · ${(boat.impact * 3.6f).roundToInt()} km/h", "urto")
            }
            Audio.event(Audio.EV_IMPACT, boat.impact, kind = boat.impactKind.toFloat())
            boat.impact = 0f
        }
        if (boat.scrape > 0.25f) Events.post("STRISCIATA", Events.WARN, "sul marmo della fondamenta", cooldownMs = 2500)
        scrapeSnd -= dt
        if (boat.scrape > 0.1f && scrapeSnd <= 0f) { Audio.event(Audio.EV_SCRAPE, 1f); scrapeSnd = 0.45f }
        // the hull meeting a wave: at the bottom of each drop, a slap as loud as the drop was fast
        slapCd -= dt
        if (!boat.airborne) {
            if (boat.vz < vzMin) vzMin = boat.vz
            if (boat.vz > -0.02f && vzMin < -0.08f) {
                if (slapCd <= 0f && abs(boat.surge) < 12f) { Audio.event(Audio.EV_SLAP, -vzMin); slapCd = 0.2f }
                vzMin = 0f
            }
        } else vzMin = 0f
        if (boat.airborne) airT += dt else { if (airT > 0.45f) Events.post("SALTO", Events.GOOD, String.format("%.1f s in aria", airT).replace('.', ',')); airT = 0f }
        if (kmh > 138f) Events.post("VELOCITÀ MASSIMA", Events.GOOD, "140 km/h", cooldownMs = 20000)
        if (Controls.engineOn != lastEngine) { Events.post(if (Controls.engineOn) "MOTORE ACCESO" else "MOTORE SPENTO", Events.INFO, key = "engine", cooldownMs = 300); lastEngine = Controls.engineOn }
        val lv = Controls.throttle()
        if (lv < -0.05f && lastLever >= -0.05f) Events.post("RETROMARCIA", Events.GOOD, cooldownMs = 2000)
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
        refl = Target(max(32, (w * 0.42f).toInt()), max(32, (h * 0.42f).toInt()), true, true); reflValid = false
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

    private var menuScene: MenuScene? = null; private var menuT = 0f; 
    private lateinit var dimP: Program; private lateinit var copyP: Program; private lateinit var waterSimple: Program
    private lateinit var grassP: Program; private lateinit var grass: MarshGrass
    private var gullP: Program? = null; private var gulls: Gulls? = null
    private var towT = 0f; private var weedWarned = false
    private var flatWater: Mesh? = null
    // ---- frozen background while a page is open: the last frame, blurred, and no more rendering
    private var frozen = false; private var frzTex = 0; private var frzW = 0; private var frzH = 0
    private fun overlayOpen() = Controls.screen == Screen.PAUSE || Controls.screen == Screen.SETTINGS || Controls.screen == Screen.CREDITS || Controls.screen == Screen.MAP

    /** copies the frame just drawn, blurs it (quarter resolution, 3 widening passes) and shows it darkened */
    private fun freezeFrame() {
        if (frzTex == 0 || frzW != width || frzH != height) {
            if (frzTex != 0) glDeleteTextures(1, intArrayOf(frzTex), 0)
            val t = IntArray(1); glGenTextures(1, t, 0); frzTex = t[0]; frzW = width; frzH = height
            glBindTexture(GL_TEXTURE_2D, frzTex)
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, width, height)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glBindTexture(GL_TEXTURE_2D, frzTex); glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height)
        ensureTargets()
        val ba = bloomA!!; val bb = bloomB!!
        glDisable(GL_DEPTH_TEST); glDisable(GL_BLEND)
        ba.bind(); copyP.use(); glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, frzTex); copyP.i("uTex", 0); copyP.vec3("uTint", 1f, 1f, 1f); skyTri?.draw()
        blurP.use(); blurP.i("uTex", 0)
        for (r in floatArrayOf(1.2f, 2.4f, 4f, 6.5f, 9f)) {
            bb.bind(); glBindTexture(GL_TEXTURE_2D, ba.tex); glUniform2f(blurP.loc("uDir"), r / ba.w, 0f); skyTri?.draw()
            ba.bind(); glBindTexture(GL_TEXTURE_2D, bb.tex); glUniform2f(blurP.loc("uDir"), 0f, r / ba.h); skyTri?.draw()
        }
        frozen = true; GameState.frozen = true
        drawFrozen()
    }
    private fun drawFrozen() {
        glBindFramebuffer(GL_FRAMEBUFFER, 0); glViewport(0, 0, width, height); glDisable(GL_DEPTH_TEST)
        copyP.use(); glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, bloomA!!.tex); copyP.i("uTex", 0); copyP.vec3("uTint", 0.72f, 0.72f, 0.75f); skyTri?.draw()
        glEnable(GL_DEPTH_TEST)
    }
    private var reflCamX = 0f; private var reflCamY = 0f; private var reflCamZ = 0f; private var reflYaw = 0f; private var reflAge = 0; private var reflValid = false

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
            // no water, no box: the city behind is dimmed to the card's glass tone and the model floats over it
            glClear(GL_DEPTH_BUFFER_BIT)
            glDisable(GL_DEPTH_TEST); glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
            dimP.use(); glUniform4f(dimP.loc("uCol"), 0.004f, 0.007f, 0.012f, 0.84f); skyTri?.draw()
            glDisable(GL_BLEND); glEnable(GL_DEPTH_TEST)
            // spin: the finger sets the speed; released, it coasts and slows to a stop, then a slow idle turn resumes
            val sinceTouch = (System.nanoTime() - Controls.menuBoatTouchT) / 1e9f
            if (Controls.menuBoatDragging) { if (sinceTouch > 0.08f) Controls.menuBoatSpin *= exp(-dt * 10f) }
            else {
                Controls.menuBoatSpin *= exp(-dt * 1.1f)
                if (sinceTouch > 3f) Controls.menuBoatSpin += (0.22f - Controls.menuBoatSpin) * (1f - exp(-dt * 0.5f))
            }
            Controls.menuBoatYaw += Controls.menuBoatSpin * dt
            val yaw = Controls.menuBoatYaw
            val bob = sin(menuT * 1.3f) * 0.04f
            // as close as the window allows: ~7.8 m across, ~3.2 m tall in view (the model fills the card)
            val aspect = vw.toFloat() / max(1, vh); val t15 = tan(Math.toRadians(15.0)).toFloat()
            val camD = max(6.0f, 3.9f / (t15 * aspect)); val camA = 0.42f
            val bvp = Mat4.mul(Mat4.perspective(Math.toRadians(30.0).toFloat(), vw.toFloat() / max(1, vh), 0.5f, 100f),
                Mat4.lookAt(-cos(camA) * camD, 0f, sin(camA) * camD + 0.6f, 0f, 0f, 0.55f, 0f, 0f, 1f))
            fogStart = 1e5f; fogEnd = 0f; fogDens = 0f
            generic.use()                      // the dim pass above switched programs
            common(generic, bvp, ident, -1000f, false)
            generic.vec3("uCam", -cos(camA) * camD, 0f, sin(camA) * camD + 0.6f)
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
        if (!ready) {
            // loading: the loading screen (ScreenView) covers this; one step per frame
            glClearColor(0.05f, 0.03f, 0.08f, 1f); glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
            try { bootFrame() } catch (e: Throwable) { GameState.bootError = "${e.javaClass.simpleName}: ${e.message}"; throw e }
            return
        }
        // a page is open: one more frame is drawn, frozen and blurred, then nothing is rendered until it closes
        val overlay = overlayOpen()
        if (frozen && !overlay) {
            frozen = false; GameState.frozen = false
            lastNs = System.nanoTime()
            // the fleet catches up by itself (LiveBoats measures the real time that went by)
        }
        if (frozen) { drawFrozen(); return }
        frameBody()
        if (overlay) freezeFrame()
    }

    private fun frameBody() {
        val now = System.nanoTime()
        val rawDt = (now - lastNs) / 1e9f
        val dt = rawDt.coerceIn(0.001f, 0.05f)
        lastNs = now
        fpsAcc += rawDt; fpsN++
        if (fpsAcc > 0.5f) { GameState.fps = fpsN / fpsAcc; fpsAcc = 0f; fpsN = 0 }
        // bad weather: the fog closes in and the render distance drops with it (fewer chunks = steady FPS)
        drawDist = if (Settings.liveWeather || Weather.override > 0) min(Settings.renderDistance, Weather.renderCap()) else Settings.renderDistance
        streamer.radius = drawDist
        if (Controls.screen == Screen.MENU || (Controls.menuContext && Controls.screen != Screen.NONE)) {
            // title screen: an orbiting flyover of the whole city (light model, no streaming work for it) while the
            // chunks around the start position stream in, so "Gioca" drops you straight into a loaded Venice
            streamer.update(boat.x, boat.y, cos(boat.heading), sin(boat.heading), 2)
            GameState.tilesLoaded = streamer.tiles.size; GameState.tilesWanted = streamer.wanted
            GameState.loading = streamer.tiles.size < min(4, streamer.wanted)
            Audio.menu = true
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
            boat.marsh = world.marsh(boat.x, boat.y)
            for (k in 0 until 3) boat.update(sdt / 3, if (Controls.engineOn) thr else 0f, st, world)
            weed(sdt)
            advanceRipple(sdt)
            navigate(sdt)
            if (boat.splash > 1.5f) Audio.event(Audio.EV_SPLASH, boat.splash)
            distance += hypot(boat.x - ox, boat.y - oy)
            fx.update(sdt, boat, if (Controls.engineOn) thr else 0f)
            if (!Settings.simple) wake.update(sdt, boat, if (Controls.engineOn) thr else 0f, world)
        }
        LiveBoats.playerX = boat.x; LiveBoats.playerY = boat.y
        live.update(if (paused) 0f else dt, camX, camY)
        gulls?.let { gl ->
            val w = if ((Settings.liveWeather && Weather.live) || Weather.override > 0) Weather.windVec() else floatArrayOf(2.5f, 1.2f)
            gl.update(if (paused) 0f else dt, camX, camY, boat.x, boat.y, abs(boat.surge), ((0.11f - boat.waveAmp) / 0.075f).coerceIn(0f, 1f), w[0], w[1], LiveBoats.audio)
            for (c in gl.calls) Audio.event(Audio.EV_GULL, c[2], c[0], c[1])
            gl.calls.clear()
        }
        if (!paused) watchEvents(sdt)
        feedAudio(paused)
        GameState.x = boat.x; GameState.y = boat.y; GameState.heading = boat.heading; GameState.speedKmh = boat.speedKmh()
        GameState.distanceM = distance.toFloat()
        topKmh = max(topKmh, boat.speedKmh()); GameState.topSpeedKmh = topKmh; GameState.tripSeconds = tripTime
        GameState.boatPitch = boat.pitch; GameState.porpoising = boat.porpoise

        // ---- camera: zoomed in when slow, pulled back (and wider) as the speed builds up
        // look around: drag left/right to swing round the boat (profile, bow on); hold it there as long as you like,
        // let go and after ~5 s it eases back behind the boat
        if (Controls.camYawDelta != 0f || Controls.camPitchDelta != 0f || Controls.camHeld) camTouchT = time
        camYawOff += Controls.camYawDelta * Settings.cameraSensitivity; Controls.camYawDelta = 0f
        camYawOff = LiveBoats.wrap(camYawOff)
        camPitch = (camPitch + Controls.camPitchDelta * Settings.cameraSensitivity).coerceIn(0.04f, 0.9f); Controls.camPitchDelta = 0f
        if (time - camTouchT > 5f) camYawOff *= exp(-dt * 0.9f)
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
            // the free (unobstructed) camera follows smoothly; the obstacles are applied after, so they never feed
            // back into the smoothing (that was the shaking when near a wall)
            if (camSX.isNaN() || hypot(camSX - boat.x, camSY - boat.y) > 80f) { camSX = camX; camSY = camY; camSZ = camZ }
            camSX += (tx - camSX) * k; camSY += (ty - camSY) * k; camSZ += (tz - camSZ) * (1f - exp(-dt * 3f))
            val floor = Waves.height(camSX, camSY, boat.time, boat.waveAmp) + 0.6f
            // bridges: seen coming (where the camera will be in half a second), it eases over the deck or down under
            // the arch - whichever side of the bridge it is on - instead of cutting through it
            camBridgeT -= dt; if (camBridgeT <= 0f) camBridge = -1
            val vx = (tx - camSX) * k / max(dt, 1e-3f); val vy = (ty - camSY) * k / max(dt, 1e-3f)
            val zAhead = bridgeClear(camSX + vx * 0.5f, camSY + vy * 0.5f, camSZ, floor)
            val zHere = bridgeClear(camSX, camSY, camSZ + camLift, floor)
            val wantLift = (if (zHere != camSZ + camLift) zHere else zAhead) - camSZ
            camLift += (wantLift - camLift) * (1f - exp(-dt * 6f))
            var ez = max(camSZ + camLift, floor)
            val hardZ = bridgeClear(camSX, camSY, ez, floor); if (hardZ != ez) ez = hardZ     // never inside a deck
            // buildings: the camera can't go inside one - it stops at the wall (the boat keeps going) and comes back
            // out gently when there's room again
            val pz = 2.6f + boat.z * 0.6f                   // above the fondamenta: only buildings stop the camera
            val free = armFree(boat.x, boat.y, pz, camSX, camSY, ez)
            camArm = if (free < camArm) free else camArm + (free - camArm) * (1f - exp(-dt * 2.5f))
            val armLen = hypot(camSX - boat.x, camSY - boat.y) * camArm
            camX = boat.x + (camSX - boat.x) * camArm; camY = boat.y + (camSY - boat.y) * camArm
            var cz = pz + (ez - pz) * camArm
            // boxed in against a wall: rise and look down at the boat instead of ending up inside it
            if (armLen < 3f) cz += (1f - armLen / 3f) * 3.5f
            camZ = max(bridgeClear(camX, camY, cz, floor), floor)
            val shake = boat.bump * 0.05f + spS * spS * 0.028f + boat.porpoise * 0.06f
            // swung round to the bow: look at the boat itself; straight above it: a point ahead (never a degenerate view)
            val ahead = max(3f * max(0f, cos(camYawOff)), (1f - armLen / 3f).coerceIn(0f, 1f) * 2.5f)
            view = Mat4.lookAt(camX + sin(time * 40) * shake, camY, camZ + cos(time * 37) * shake, boat.x + cos(boat.heading) * ahead, boat.y + sin(boat.heading) * ahead, 1.2f + boat.z * 0.7f, 0f, 0f, 1f)
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

        // ---- reflection pass: low resolution, nearby city only, no shadows (sky only when reflections are off).
        // A still or slowly drifting camera reuses it for up to 2 frames: under the ripples nobody can tell, and it
        // is a whole second pass over the nearby city.
        val r = refl!!
        val camMove = hypot(camX - reflCamX, camY - reflCamY) + abs(camZ - reflCamZ)
        val turn = abs(LiveBoats.wrap(yaw - reflYaw))
        if (Settings.simple) { /* no reflection pass at all */ }
        else if (camMove > 0.35f || turn > 0.012f || reflAge >= 2 || !reflValid) {
            r.bind()
            glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
            if (Settings.reflections) drawOpaque(vp, mirror, -0.05f, min(260f, drawDist), bm, true)
            drawSky(vp, true)                // after the city: only the sky pixels that are actually visible get shaded
            reflCamX = camX; reflCamY = camY; reflCamZ = camZ; reflYaw = yaw; reflAge = 0; reflValid = true
        } else reflAge++

        // ---- main HDR scene: opaque first, then the sky only where nothing covers it (early depth rejection)
        sc.bind()
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        drawOpaque(vp, ident, -1000f, drawDist, bm, false)
        drawGrass(vp)
        drawGulls(vp)
        drawSky(vp, false)
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
        compP.f("uSharpen", Settings.sharpen * 0.5f * (1f + (1f - scale) * 1.5f))       // gentler: sharpening amplified shimmer
        compP.f("uBloomK", if (Settings.bloom) 1f else 0f)
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
                if (t.hasMarsh) { ground.f("uMarshPass", 1f); ground.f("uGrassR", if (clipZ > -999f) 0f else grassRadius()); ground.mat4("uModel", Mat4.translation(t.ox, t.oy, Z.MARSH)); groundQuad?.draw() }
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
            val vd = if (reflection) 160f else maxDist + 30f
            for (b in live.boats.values) {
                val d = hypot(b.px - camX, b.py - camY)
                if (d > vd || !sphereVisible(b.px, b.py, 2f, b.kind.len * 0.6f)) continue
                // full detail only up close; the reflection and anything past ~70 m get the 8x lighter model
                val vm = (if (reflection || d > 70f) fleetLod[b.kind.ordinal] else null) ?: fleetMeshes[b.kind.ordinal] ?: continue
                live.model(b, boat.time, boat.waveAmp, boatModel)
                generic.mat4("uModel", boatModel); vm.draw()
            }
        }
        boatMesh?.let { generic.mat4("uModel", bm); it.draw() }
    }

    private fun drawWater(vp: FloatArray, r: Target) {
        if (Settings.simple) {
            // low-end: flat water with baked ripples and the sky projected on it, nothing simulated, no reflection pass
            val w = waterSimple
            w.use(); common(w, vp, ident, -1000f, false)
            w.vec3("uCenter", floor(camX / 40f) * 40f, floor(camY / 40f) * 40f, 0f); w.f("uWaveAmp", boat.waveAmp)
            glActiveTexture(GL_TEXTURE10); glBindTexture(GL_TEXTURE_2D, waterN); w.i("uWaterN", 10)
            flatWater?.draw()
            return
        }
        water.use(); common(water, vp, ident, -1000f)
        val cx = floor(camX / 40f) * 40f; val cy = floor(camY / 40f) * 40f
        water.vec3("uCenter", cx, cy, 0f); water.f("uWaveAmp", boat.waveAmp)
        glActiveTexture(GL_TEXTURE5); glBindTexture(GL_TEXTURE_2D, r.tex); water.i("uRefl", 5)
        glActiveTexture(GL_TEXTURE7); glBindTexture(GL_TEXTURE_2D, wake.tex); water.i("uWake", 7)
        glActiveTexture(GL_TEXTURE10); glBindTexture(GL_TEXTURE_2D, waterN); water.i("uWaterN", 10)
        glActiveTexture(GL_TEXTURE11); glBindTexture(GL_TEXTURE_2D, foamT); water.i("uFoamT", 11)
        water.vec3("uWakeO", wake.ox, wake.oy, if (wake.ok) wake.size else 0.001f); water.vec2("uWakeDir", wake.dirX, wake.dirY)
        water.vec2("uRipOff", ripOffX, ripOffY)
        glUniform4f(water.loc("uBoat"), boat.x, boat.y, boat.heading, abs(boat.surge)); glUniform2f(water.loc("uHull"), Boat.HALF_LEN, Boat.HALF_BEAM)
        glUniform2f(water.loc("uScreen"), scene!!.w.toFloat(), scene!!.h.toFloat())
        water.f("uGridHalf", 1e6f); waterNear?.draw()
        water.f("uGridHalf", 160f); waterMid?.draw()
        water.f("uGridHalf", 1f); waterFar?.draw()
    }

    // ---------------------------------------------------------------- navigator + canal width
    private val planner by lazy { RoutePlanner(world) }
    private val navExec = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "navigator").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    @Volatile private var planning = false
    private var plannedX = Float.NaN; private var plannedY = Float.NaN; private var replanT = 0f; private var routeSeg = 0; private var widthT = 0f

    private fun navigate(dt: Float) {
        widthT -= dt
        if (widthT <= 0f) {
            widthT = 0.25f
            // water across the boat: both sides, perpendicular to the heading, up to 350 m
            val px = -sin(boat.heading); val py = cos(boat.heading)
            var w = 0f
            for (sg in intArrayOf(-1, 1)) { var d = 2f; while (d < 350f && world.solid(boat.x + px * d * sg, boat.y + py * d * sg) < 0.5f) d += if (d < 40f) 2f else 6f; w += d }
            GameState.canalWidth = w
        }
        if (!GameState.hasWaypoint()) { GameState.route = null; plannedX = Float.NaN; return }
        val wx = GameState.wpX; val wy = GameState.wpY
        replanT -= dt
        val route = GameState.route
        if (!planning && (wx != plannedX || wy != plannedY || (route != null && offRoute(route) > 45f && replanT <= 0f))) {
            planning = true; plannedX = wx; plannedY = wy; replanT = 3f
            val sx = boat.x; val sy = boat.y
            navExec.execute {
                val r = try { planner.plan(sx, sy, wx, wy) } catch (e: Throwable) { null }
                if (GameState.wpX == wx && GameState.wpY == wy) { GameState.route = r; routeSeg = 0 }
                planning = false
            }
        }
        val r = GameState.route ?: run { GameState.aimX = wx; GameState.aimY = wy; return }
        // progress: nearest segment (searching forward from the last one), aim 45 m further along, distance left
        val n = r.size / 2
        var best = routeSeg; var bd = Float.MAX_VALUE
        for (i in max(0, routeSeg - 2) until min(n - 1, routeSeg + 12)) { val d = segDist(r, i, boat.x, boat.y); if (d < bd) { bd = d; best = i } }
        routeSeg = best
        val ax = r[best * 2]; val ay = r[best * 2 + 1]; val bx = r[best * 2 + 2]; val by = r[best * 2 + 3]
        val l = max(hypot(bx - ax, by - ay), 1e-3f)
        val t = (((boat.x - ax) * (bx - ax) + (boat.y - ay) * (by - ay)) / (l * l)).coerceIn(0f, 1f)
        var left = l * (1 - t)
        for (i in best + 1 until n - 1) left += hypot(r[i * 2 + 2] - r[i * 2], r[i * 2 + 3] - r[i * 2 + 1])
        GameState.routeLeft = left + bd
        var need = 45f; var i = best; var px = ax + (bx - ax) * t; var py = ay + (by - ay) * t
        while (i < n - 1) {
            val ex = r[i * 2 + 2]; val ey = r[i * 2 + 3]; val sl = hypot(ex - px, ey - py)
            if (sl >= need) { px += (ex - px) * need / sl; py += (ey - py) * need / sl; break }
            need -= sl; px = ex; py = ey; i++
        }
        GameState.aimX = px; GameState.aimY = py
    }
    private fun segDist(r: FloatArray, i: Int, x: Float, y: Float): Float {
        val ax = r[i * 2]; val ay = r[i * 2 + 1]; val bx = r[i * 2 + 2]; val by = r[i * 2 + 3]
        val l2 = max((bx - ax) * (bx - ax) + (by - ay) * (by - ay), 1e-6f)
        val t = (((x - ax) * (bx - ax) + (y - ay) * (by - ay)) / l2).coerceIn(0f, 1f)
        return hypot(ax + (bx - ax) * t - x, ay + (by - ay) * t - y)
    }
    private fun offRoute(r: FloatArray): Float { var m = Float.MAX_VALUE; for (i in max(0, routeSeg - 2) until min(r.size / 2 - 1, routeSeg + 12)) m = min(m, segDist(r, i, boat.x, boat.y)); return m }

    // the boil behind the prop drifts along the boat's track: integrated per frame, never direction * total time
    private var ripOffX = 0f; private var ripOffY = 0f
    private fun advanceRipple(dt: Float) {
        ripOffX = (ripOffX + wake.dirX * dt * 0.25f) % 64f; ripOffY = (ripOffY + wake.dirY * dt * 0.25f) % 64f
    }

    private fun drawGulls(vp: FloatArray) {
        val p = gullP ?: return; val g = gulls ?: return
        p.use(); common(p, vp, ident, -1000f, false)
        g.draw()
    }

    private fun grassRadius() = if (Settings.simple) 32f else 60f
    private fun drawGrass(vp: FloatArray) {
        grass.radius = grassRadius(); grass.update(camX, camY)
        grassP.use(); common(grassP, vp, ident, -1000f, false)
        val w = if ((Settings.liveWeather && Weather.live) || Weather.override > 0) Weather.windVec() else floatArrayOf(2.5f, 1.2f)
        grassP.vec2("uWind", w[0], w[1]); grassP.f("uRadius", grass.radius)
        grass.draw()
    }

    /** barene: weed wraps round the prop; fully tangled, the boat is towed out to open water */
    private fun weed(dt: Float) {
        if (boat.entangle > 0.3f && !weedWarned) { weedWarned = true; Events.post("ALGHE NELL'ELICA", Events.WARN, key = "weed") }
        if (boat.entangle < 0.1f) weedWarned = false
        if (boat.entangle >= 1f) {
            if (towT == 0f) Events.post("ELICA IMPIGLIATA", Events.BAD, key = "tangled")
            towT += dt
            if (towT > 2.5f) {
                // nearest open water clear of the barene, searched in widening rings
                var done = false
                var r = 10f
                while (!done && r < 600f) {
                    for (k in 0 until 24) {
                        val a = k * 0.2618f; val x = boat.x + cos(a) * r; val y = boat.y + sin(a) * r
                        if (world.marsh(x, y) < 0.05f && world.solid(x, y) < 0.05f && world.marsh(x + 6f, y) < 0.1f && world.marsh(x - 6f, y) < 0.1f && world.solid(x + 4f, y) < 0.1f && world.solid(x, y + 4f) < 0.1f) {
                            boat.x = x; boat.y = y; boat.heading = a; done = true; break
                        }
                    }
                    r += 10f
                }
                boat.surge = 0f; boat.sway = 0f; boat.entangle = 0f; towT = 0f
                Events.post("RIMORCHIATO FUORI", Events.INFO, key = "towed")
            }
        } else towT = 0f
    }

    private fun drawTrails(vp: FloatArray) {
        val n = live.buildTrails(trailData, min(drawDist, 380f), camX, camY)
        if (n == 0) return
        trailMesh.upload(trailData, n * 5)
        glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); glDepthMask(false)
        val tp = trailP ?: return
        tp.use(); common(tp, vp, ident, -1000f, false); foam(tp)
        trailMesh.draw(GL_TRIANGLES)
        glDepthMask(true); glDisable(GL_BLEND)
    }

    private fun foam(p: Program) { glActiveTexture(GL_TEXTURE11); glBindTexture(GL_TEXTURE_2D, foamT); p.i("uFoamT", 11) }

    private fun drawSmoke(vp: FloatArray) {
        val src = live.smokeP; var n = 0
        for (i in 0 until live.smokeN) {
            val o = i * 9; val t = src[o + 5] / src[o + 6]
            val q = n * 6
            smokeData[q] = src[o]; smokeData[q + 1] = src[o + 1]; smokeData[q + 2] = src[o + 2]
            smokeData[q + 3] = src[o + 7] * (1f + t * 2.5f)                       // puffs grow as they rise
            smokeData[q + 4] = (0.10f + 0.30f * src[o + 8]) * min(1f, t * 8f) * (1f - t) // fade in, then out
            smokeData[q + 5] = src[o + 8] * (1f - t * 0.7f)
            n++
        }
        if (n == 0) return
        smokeMesh.upload(smokeData, n)
        glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); glDepthMask(false)
        if (Settings.simple) return
        val sp = smokeP ?: return
        sp.use(); common(sp, vp, ident, -1000f, false); foam(sp)
        smokeMesh.draw()
        glDepthMask(true); glDisable(GL_BLEND)
    }

    private fun drawSpray(vp: FloatArray) {
        val n = fx.buildInstances(sprayData)
        if (n == 0) return
        sprayMesh.upload(sprayData, n)
        glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); glDepthMask(false)
        sprayP.use(); common(sprayP, vp, ident, -1000f); foam(sprayP)
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
