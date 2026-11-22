// Main GL renderer + game loop.
// Passes per frame: sun shadow map -> mirrored reflection (half res) -> HDR scene (sky, city, landmarks, boat,
// water, wake, spray) -> bloom -> filmic tone map + FXAA to screen.
package com.blazebaal.venice.render

import android.content.Context
import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import com.blazebaal.venice.core.*
import com.blazebaal.venice.ui.Controls
import com.blazebaal.venice.ui.GameState
import java.io.DataInputStream
import java.util.zip.InflaterInputStream
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

class Landmark(val mesh: Mesh, val model: FloatArray, val x: Float, val y: Float, val atlas: Int, val signRect: FloatArray = floatArrayOf(0f, 0f, 0f, 0f))

class GameRenderer(private val ctx: Context) : GLSurfaceView.Renderer {
    private lateinit var world: WorldMap
    private lateinit var streamer: TileStreamer
    private lateinit var generic: Program; private lateinit var facade: Program; private lateinit var ground: Program
    private lateinit var water: Program; private lateinit var sky: Program; private lateinit var depth: Program
    private lateinit var wakeP: Program; private lateinit var sprayP: Program
    private lateinit var brightP: Program; private lateinit var blurP: Program; private lateinit var compP: Program
    private var groundQuad: Mesh? = null; private var waterNear: Mesh? = null; private var waterMid: Mesh? = null; private var waterFar: Mesh? = null
    private var skyTri: Mesh? = null; private var boatMesh: Mesh? = null
    private val landmarks = ArrayList<Landmark>()
    private var texArr = IntArray(2)
    private lateinit var boat: Boat
    private val fx = Effects()
    private lateinit var wakeMesh: DynMesh; private lateinit var sprayMesh: DynMesh
    private val wakeData = FloatArray(Effects.MAX_WAKE * 3 * 6 * 6)
    private val sprayData = FloatArray(2400 * 60)
    private val wakeSim = WakeSim()
    private val wakeBuf = FloatArray(wakeSim.n * wakeSim.n * 4)
    private val wakeDirect = java.nio.ByteBuffer.allocateDirect(wakeBuf.size * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
    private var wakeTex = 0

    private var width = 1; private var height = 1
    private var scene: Target? = null; private var refl: Target? = null; private var bloomA: Target? = null; private var bloomB: Target? = null
    private var shadowTex = 0; private var shadowFbo = 0
    private val SHADOW = 2048
    private var lightVP = Mat4.identity()
    private val mirror = Mat4.identity().also { it[10] = -1f }
    private val ident = Mat4.identity()

    private var lastNs = 0L; private var time = 0f
    private var fpsAcc = 0f; private var fpsN = 0
    private var camX = 0f; private var camY = 0f; private var camZ = 3f
    private var camYawOff = 0f; private var camPitch = 0.2f

    // lighting (linear HDR): Venetian late-afternoon sun from the south-west
    private val sunDir = run { val az = Math.toRadians(228.0); val el = Math.toRadians(32.0); floatArrayOf((sin(az) * cos(el)).toFloat(), (cos(az) * cos(el)).toFloat(), sin(el).toFloat()) }
    private val sunCol = floatArrayOf(3.3f, 2.95f, 2.45f)
    private val skyAmb = floatArrayOf(0.34f, 0.42f, 0.56f)
    private val groundAmb = floatArrayOf(0.20f, 0.18f, 0.15f)
    private val fogCol = floatArrayOf(0.62f, 0.68f, 0.76f)
    private val zenith = floatArrayOf(0.16f, 0.30f, 0.62f)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val map = MapData(ctx.assets.open("venice.map"))
        world = WorldMap(map)
        GameState.minX = map.minX; GameState.minY = map.minY
        streamer = TileStreamer(world)
        generic = Program(Shaders.STD_VS, Shaders.GENERIC_FS, "generic")
        facade = Program(Shaders.STD_VS, Shaders.FACADE_FS, "facade")
        ground = Program(Shaders.GROUND_VS, Shaders.GROUND_FS, "ground")
        water = Program(Shaders.WATER_VS, Shaders.WATER_FS, "water")
        sky = Program(Shaders.SKY_VS, Shaders.SKY_FS, "sky")
        depth = Program(Shaders.DEPTH_VS, Shaders.DEPTH_FS, "depth")
        wakeP = Program(Shaders.WAKE_VS, Shaders.WAKE_FS, "wake")
        sprayP = Program(Shaders.SPRAY_VS, Shaders.SPRAY_FS, "spray")
        brightP = Program(Shaders.POST_VS, Shaders.BRIGHT_FS, "bright")
        blurP = Program(Shaders.POST_VS, Shaders.BLUR_FS, "blur")
        compP = Program(Shaders.POST_VS, Shaders.COMPOSITE_FS, "composite")
        texArr = TexArrays.load(ctx, L.COUNT)
        createShadowMap()
        groundQuad = Mesh(floatArrayOf(0f, 0f, 0f, 256f, 0f, 0f, 256f, 256f, 0f, 0f, 0f, 0f, 256f, 256f, 0f, 0f, 256f, 0f), 3, intArrayOf(3))
        skyTri = Mesh(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f), 2, intArrayOf(2))
        // fine grid around the camera so the wake's mound, hollow and V crests are real geometry, coarser rings outside
        waterNear = Mesh(waterGrid(64f, 0.5f, 0f), 3, intArrayOf(3))
        waterMid = Mesh(waterGrid(150f, 2f, 64f), 3, intArrayOf(3))
        waterFar = Mesh(waterGrid(1800f, 50f, 150f), 3, intArrayOf(3))
        wakeMesh = DynMesh(6, intArrayOf(3, 3), wakeData.size)
        sprayMesh = DynMesh(10, intArrayOf(3, 3, 2, 2), sprayData.size)
        wakeTex = IntArray(1).also { glGenTextures(1, it, 0) }[0]
        glBindTexture(GL_TEXTURE_2D, wakeTex)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, wakeSim.n, wakeSim.n, 0, GL_RGBA, GL_FLOAT, null)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        boatMesh = loadMesh("boat.mesh")
        loadLandmarks()
        loadStops()
        boat = Boat(-330f, 380f, Math.toRadians(41.0).toFloat())
        boat.wakeHeight = { x, y -> wakeSim.height(x, y) }
        findWater()
        camX = boat.x - 9f; camY = boat.y; camZ = 3.2f
        glEnable(GL_DEPTH_TEST); glDisable(GL_CULL_FACE)
        lastNs = System.nanoTime()
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
        t[0]
    } catch (e: Exception) { 0 }

    private var signTex = 0

    /** ACTV vaporetto stops: three pontoon models (12 / 16 / 22 m), each stop uses the one closest to its real length
     *  (never stretched, so the name plates keep their proportions); names and letters come from a shared sign sheet
     *  (4 columns x 32 rows, slot index per stop). */
    private fun loadStops() {
        val lens = intArrayOf(12, 16, 22)
        val variants = lens.map { l -> loadMesh("landmarks/actv$l.mesh")?.let { it to loadAtlas("landmarks/actv${l}_atlas.jpg") } }
        if (variants.all { it == null }) return
        signTex = loadAtlas("landmarks/actv_signs.png")
        val lines = try { ctx.assets.open("landmarks/actv.txt").bufferedReader().readLines() } catch (e: Exception) { emptyList() }
        for (line in lines) {
            val p = line.split('\t'); if (p.size < 7) continue
            val x = p[1].toFloat(); val y = p[2].toFloat(); val side = p[5].toInt(); val slot = p[6].toInt()
            val rot = Math.toRadians(p[3].toDouble() + (if (side < 0) 180.0 else 0.0)).toFloat()
            val len = p[4].toFloat()
            val vi = lens.indices.filter { variants[it] != null }.minByOrNull { kotlin.math.abs(lens[it] - len) } ?: continue
            val (mesh, atlas) = variants[vi]!!
            val model = Mat4.mul(Mat4.translation(x, y, 0f), Mat4.rotZ(rot))
            val rect = floatArrayOf((slot % 4) * 0.25f, (slot / 4) / 32f, 0.25f, 1f / 32f)
            landmarks.add(Landmark(mesh, model, x, y, atlas, rect))
        }
    }

    private fun loadLandmarks() {
        // each line: name, world x, world y, rotation (deg); local +X runs along the structure
        val lines = try { ctx.assets.open("landmarks/landmarks.txt").bufferedReader().readLines() } catch (e: Exception) { emptyList() }
        for (line in lines) {
            if (line.isBlank() || line.startsWith("#")) continue
            val p = line.trim().split(Regex("\\s+"))
            val mesh = loadMesh("landmarks/${p[0]}.mesh") ?: continue
            val x = p[1].toFloat(); val y = p[2].toFloat(); val rot = Math.toRadians(p[3].toDouble()).toFloat()
            landmarks.add(Landmark(mesh, Mat4.mul(Mat4.translation(x, y, 0f), Mat4.rotZ(rot)), x, y, loadAtlas("landmarks/${p[0]}_atlas.jpg")))
        }
    }

    private fun waterGrid(half: Float, step: Float, hole: Float): FloatArray {
        val n = (2 * half / step).toInt()
        val out = ArrayList<Float>(n * n * 18)
        for (j in 0 until n) for (i in 0 until n) {
            val x0 = -half + i * step; val y0 = -half + j * step; val x1 = x0 + step; val y1 = y0 + step
            if (hole > 0 && x0 >= -hole && x1 <= hole && y0 >= -hole && y1 <= hole) continue
            for (v in floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y0, x1, y1, x0, y1).toList().chunked(2)) { out.add(v[0]); out.add(v[1]); out.add(0f) }
        }
        return out.toFloatArray()
    }

    private fun findWater() {
        if (world.solid(boat.x, boat.y) < 0.2f) return
        for (r in 1..200) for (k in 0 until 16) {
            val a = k * PI.toFloat() / 8
            val x = boat.x + cos(a) * r; val y = boat.y + sin(a) * r
            if (world.solid(x, y) < 0.05f && world.solid(x + 4, y) < 0.05f && world.solid(x - 4, y) < 0.05f && world.solid(x, y + 4) < 0.05f && world.solid(x, y - 4) < 0.05f) { boat.x = x; boat.y = y; return }
        }
    }

    private fun createShadowMap() {
        val t = IntArray(1); glGenTextures(1, t, 0); shadowTex = t[0]
        glBindTexture(GL_TEXTURE_2D, shadowTex)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, SHADOW, SHADOW, 0, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, null)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL)
        val f = IntArray(1); glGenFramebuffers(1, f, 0); shadowFbo = f[0]
        glBindFramebuffer(GL_FRAMEBUFFER, shadowFbo)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, shadowTex, 0)
        glDrawBuffers(1, intArrayOf(GL_NONE), 0); glReadBuffer(GL_NONE)
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) { android.util.Log.e("Venice", "shadow FBO incomplete"); shadowTex = 0 }
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        scene?.release(); refl?.release(); bloomA?.release(); bloomB?.release()
        scene = Target(w, h, true, true)
        refl = Target(w / 2, h / 2, true, true)
        bloomA = Target(w / 4, h / 4, true, false); bloomB = Target(w / 4, h / 4, true, false)
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
        val dt = ((now - lastNs) / 1e9f).coerceIn(0.001f, 0.05f)
        lastNs = now; time += dt
        fpsAcc += dt; fpsN++
        if (fpsAcc > 0.5f) { GameState.fps = fpsN / fpsAcc; fpsAcc = 0f; fpsN = 0 }

        // ---- simulate
        val thr = Controls.throttle(); val st = Controls.steer()
        for (k in 0 until 3) boat.update(dt / 3, thr, st, world)
        fx.update(dt, boat, thr)
        wakeSim.update(dt, boat, thr, world)
        GameState.x = boat.x; GameState.y = boat.y; GameState.heading = boat.heading; GameState.speedKmh = boat.speedKmh()

        // ---- camera
        camYawOff += Controls.camYawDelta; Controls.camYawDelta = 0f
        camPitch = (camPitch + Controls.camPitchDelta).coerceIn(0.04f, 0.9f); Controls.camPitchDelta = 0f
        if (abs(thr) > 0.1f) camYawOff *= exp(-dt * 1.2f)
        val yaw = boat.heading + camYawOff
        val dist = 10.5f + boat.speedKmh() * 0.04f
        val tx = boat.x - cos(yaw) * dist * cos(camPitch); val ty = boat.y - sin(yaw) * dist * cos(camPitch)
        val tz = 1.6f + dist * sin(camPitch) + boat.z * 0.6f
        val k = 1f - exp(-dt * 5f)
        camX += (tx - camX) * k; camY += (ty - camY) * k; camZ += (tz - camZ) * (1f - exp(-dt * 3f))
        if (world.solid(camX, camY) > 0.5f && camZ < 14f) { camX += (boat.x - camX) * 0.25f; camY += (boat.y - camY) * 0.25f }
        camZ = max(camZ, Waves.height(camX, camY, boat.time, boat.waveAmp) + 0.6f)
        val shake = boat.bump * 0.05f
        val view = Mat4.lookAt(camX + sin(time * 40) * shake, camY, camZ + cos(time * 37) * shake, boat.x + cos(boat.heading) * 3f, boat.y + sin(boat.heading) * 3f, 1.2f + boat.z * 0.7f, 0f, 0f, 1f)
        val proj = Mat4.perspective(Math.toRadians(62.0).toFloat(), width.toFloat() / height, 0.3f, 2600f)
        val vp = Mat4.mul(proj, view)

        streamer.update(camX, camY, cos(yaw), sin(yaw))
        GameState.tilesLoaded = streamer.tiles.size
        GameState.loading = streamer.tiles.size < 4

        val bm = boatModel()
        renderShadows(camX + cos(yaw) * 150f, camY + sin(yaw) * 150f, bm)

        // ---- reflection pass (the world mirrored in the water plane)
        val r = refl!!
        r.bind()
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        drawSky(vp, true)
        drawOpaque(vp, mirror, -0.05f, 380f, bm)

        // ---- main HDR scene
        val sc = scene!!
        sc.bind()
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        drawSky(vp, false)
        drawOpaque(vp, ident, -1000f, 99999f, bm)
        drawWater(vp, r)
        drawSpray(vp)

        // ---- post
        glDisable(GL_DEPTH_TEST)
        val ba = bloomA!!; val bb = bloomB!!
        ba.bind(); brightP.use(); glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, sc.tex); brightP.i("uTex", 0)
        glUniform2f(brightP.loc("uTexel"), 1f / sc.w, 1f / sc.h); skyTri?.draw()
        blurP.use(); blurP.i("uTex", 0)
        for (pass in 0 until 2) {
            bb.bind(); glBindTexture(GL_TEXTURE_2D, ba.tex); glUniform2f(blurP.loc("uDir"), 1.5f / ba.w, 0f); skyTri?.draw()
            ba.bind(); glBindTexture(GL_TEXTURE_2D, bb.tex); glUniform2f(blurP.loc("uDir"), 0f, 1.5f / ba.h); skyTri?.draw()
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0); glViewport(0, 0, width, height)
        compP.use()
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, sc.tex); compP.i("uScene", 0)
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, ba.tex); compP.i("uBloom", 1)
        glUniform2f(compP.loc("uTexel"), 1f / width, 1f / height); compP.f("uExposure", if (sc.isHdr) 0.85f else 1.45f)
        skyTri?.draw()
        glEnable(GL_DEPTH_TEST)
    }

    private fun drawSky(vp: FloatArray, mirrored: Boolean) {
        glDepthMask(false)
        sky.use(); sky.mat4("uInvVP", Mat4.invert(vp)); sky.vec3("uSunDir", sunDir); sky.vec3("uFogCol", fogCol); sky.vec3("uSkyZenith", zenith); sky.vec3("uSunCol", sunCol)
        sky.f("uMirrorSky", if (mirrored) 1f else 0f)
        skyTri?.draw()
        glDepthMask(true)
    }

    private fun common(p: Program, vp: FloatArray, mir: FloatArray, clipZ: Float) {
        p.mat4("uVP", vp); p.mat4("uMirror", mir)
        p.vec3("uSunDir", sunDir); p.vec3("uSunCol", sunCol); p.vec3("uSkyAmb", skyAmb); p.vec3("uGroundAmb", groundAmb)
        p.vec3("uFogCol", fogCol); p.vec3("uSkyZenith", zenith); p.f("uFogDens", 1f / 1100f); p.vec3("uCam", camX, camY, camZ); p.f("uTime", boat.time)
        p.f("uClipZ", clipZ)
        glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, shadowTex); p.i("uShadow", 2)
        glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_2D_ARRAY, texArr[0]); p.i("uAlb", 3)
        glActiveTexture(GL_TEXTURE4); glBindTexture(GL_TEXTURE_2D_ARRAY, texArr[1]); p.i("uNrm", 4)
        p.mat4("uLightVP", lightVP); p.f("uShadowOn", if (shadowTex != 0) 1f else 0f)
    }

    private fun drawOpaque(vp: FloatArray, mir: FloatArray, clipZ: Float, maxDist: Float, bm: FloatArray) {
        val pv = Mat4.mul(vp, mir)
        val visible = streamer.tiles.values.filter { inFrustum(pv, it.ox, it.oy) && hypot(it.ox + 128 - camX, it.oy + 128 - camY) < maxDist + 200 }
        ground.use(); common(ground, vp, mir, clipZ); ground.i("uMask", 0); ground.i("uClass", 1)
        for (t in visible) {
            if (t.maskTex == 0) continue
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, t.maskTex)
            glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, t.classTex)
            ground.f("uMarshPass", 0f); ground.mat4("uModel", Mat4.translation(t.ox, t.oy, Z.GROUND)); groundQuad?.draw()
            if (t.hasMarsh) { ground.f("uMarshPass", 1f); ground.mat4("uModel", Mat4.translation(t.ox, t.oy, Z.MARSH)); groundQuad?.draw() }
        }
        facade.use(); common(facade, vp, mir, clipZ)
        for (t in visible) { val m = t.facade ?: continue; facade.mat4("uModel", t.model); m.draw() }
        generic.use(); common(generic, vp, mir, clipZ)
        for (t in visible) { val m = t.generic ?: continue; generic.mat4("uModel", t.model); m.draw() }
        generic.i("uAtlas", 6); generic.i("uSign", 8)
        glActiveTexture(GL_TEXTURE8); glBindTexture(GL_TEXTURE_2D, signTex)
        for (l in landmarks) if (hypot(l.x - camX, l.y - camY) < maxDist + 100) {
            glActiveTexture(GL_TEXTURE6); glBindTexture(GL_TEXTURE_2D, l.atlas)
            glUniform4f(generic.loc("uSignRect"), l.signRect[0], l.signRect[1], l.signRect[2], l.signRect[3])
            generic.mat4("uModel", l.model); l.mesh.draw()
        }
        boatMesh?.let { generic.mat4("uModel", bm); it.draw() }
    }

    private fun drawWater(vp: FloatArray, r: Target) {
        water.use(); common(water, vp, ident, -1000f)
        val cx = floor(camX / 50f) * 50f; val cy = floor(camY / 50f) * 50f
        water.vec3("uCenter", cx, cy, 0f); water.f("uWaveAmp", boat.waveAmp)
        glActiveTexture(GL_TEXTURE5); glBindTexture(GL_TEXTURE_2D, r.tex); water.i("uRefl", 5)
        glActiveTexture(GL_TEXTURE7); glBindTexture(GL_TEXTURE_2D, wakeTex)
        if (wakeSim.dirty) {
            wakeSim.pack(wakeBuf); wakeSim.dirty = false
            wakeDirect.position(0); wakeDirect.put(wakeBuf); wakeDirect.position(0)
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, wakeSim.n, wakeSim.n, GL_RGBA, GL_FLOAT, wakeDirect)
        }
        water.i("uWake", 7); water.vec3("uWakeO", wakeSim.ox, wakeSim.oy, wakeSim.size); water.vec2("uWakeDir", wakeSim.dirX, wakeSim.dirY)
        glUniform2f(water.loc("uScreen"), width.toFloat(), height.toFloat())
        water.f("uGridHalf", 1e6f); waterNear?.draw()
        water.f("uGridHalf", 150f); waterMid?.draw()
        water.f("uGridHalf", 1f); waterFar?.draw()
    }

    private fun drawWake(vp: FloatArray) {
        val o = fx.buildWake(boat.time, boat.waveAmp, wakeData)
        if (o == 0) return
        wakeMesh.upload(wakeData, o)
        glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); glDepthMask(false)
        wakeP.use(); common(wakeP, vp, ident, -1000f)
        wakeMesh.draw(GL_TRIANGLES)
        glDepthMask(true); glDisable(GL_BLEND)
    }

    private fun drawSpray(vp: FloatArray) {
        val o = fx.buildSpray(sprayData)
        if (o == 0) return
        sprayMesh.upload(sprayData, o)
        glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); glDepthMask(false)
        sprayP.use(); common(sprayP, vp, ident, -1000f)
        sprayMesh.draw(GL_TRIANGLES)
        glDepthMask(true); glDisable(GL_BLEND)
    }

    /** Sun shadow map covering ~420 m around a point just ahead of the camera. */
    private fun renderShadows(cx: Float, cy: Float, boatModel: FloatArray) {
        if (shadowTex == 0) return
        val half = 210f
        val texel = half * 2 / SHADOW
        val sx = floor(cx / texel) * texel; val sy = floor(cy / texel) * texel
        val dist = 400f
        val view = Mat4.lookAt(sx + sunDir[0] * dist, sy + sunDir[1] * dist, sunDir[2] * dist, sx, sy, 0f, 0f, 0f, 1f)
        lightVP = Mat4.mul(Mat4.ortho(-half, half, -half, half, 1f, dist * 2f), view)
        glBindFramebuffer(GL_FRAMEBUFFER, shadowFbo); glViewport(0, 0, SHADOW, SHADOW)
        glClear(GL_DEPTH_BUFFER_BIT)
        glEnable(GL_POLYGON_OFFSET_FILL); glPolygonOffset(1.6f, 2.5f)
        depth.use(); depth.mat4("uLightVP", lightVP)
        for (t in streamer.tiles.values) {
            if (abs(t.ox + 128 - sx) > half + 200 || abs(t.oy + 128 - sy) > half + 200) continue
            depth.mat4("uModel", t.model); t.facade?.draw(); t.generic?.draw()
        }
        for (l in landmarks) if (abs(l.x - sx) < half + 80 && abs(l.y - sy) < half + 80) { depth.mat4("uModel", l.model); l.mesh.draw() }
        depth.mat4("uModel", boatModel); boatMesh?.draw()
        glDisable(GL_POLYGON_OFFSET_FILL)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
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

    fun shutdown() { if (::streamer.isInitialized) streamer.shutdown() }
}
