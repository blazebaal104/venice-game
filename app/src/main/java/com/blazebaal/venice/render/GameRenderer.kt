// Main GL renderer + game loop.
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

class GameRenderer(private val ctx: Context) : GLSurfaceView.Renderer {
    private lateinit var world: WorldMap
    private lateinit var streamer: TileStreamer
    private lateinit var generic: Program
    private lateinit var facade: Program
    private lateinit var ground: Program
    private lateinit var water: Program
    private lateinit var sky: Program
    private lateinit var depth: Program
    private var shadowTex = 0; private var shadowFbo = 0
    private val SHADOW = 2048
    private var lightVP = Mat4.identity()
    private var groundQuad: Mesh? = null
    private var waterGrid: Mesh? = null
    private var skyTri: Mesh? = null
    private var boatMesh: Mesh? = null
    private lateinit var boat: Boat

    private var width = 1; private var height = 1
    private var lastNs = 0L
    private var time = 0f
    private var fpsAcc = 0f; private var fpsN = 0

    // camera state
    private var camX = 0f; private var camY = 0f; private var camZ = 3f
    private var camYawOff = 0f; private var camPitch = 0.22f

    // lighting: Venetian late-afternoon sun from the south-west
    private val sunDir = run { val az = Math.toRadians(225.0); val el = Math.toRadians(36.0); floatArrayOf((sin(az) * cos(el)).toFloat(), (cos(az) * cos(el)).toFloat(), sin(el).toFloat()) }
    private val sunCol = floatArrayOf(1.18f, 1.06f, 0.90f)
    private val skyAmb = floatArrayOf(0.40f, 0.46f, 0.56f)
    private val groundAmb = floatArrayOf(0.30f, 0.27f, 0.22f)
    private val fogCol = floatArrayOf(0.80f, 0.84f, 0.88f)
    private val zenith = floatArrayOf(0.33f, 0.52f, 0.80f)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val map = MapData(ctx.assets.open("venice.map"))
        world = WorldMap(map)
        GameState.minX = map.minX; GameState.minY = map.minY
        streamer = TileStreamer(world)
        generic = Program(Shaders.GENERIC_VS, Shaders.GENERIC_FS, "generic")
        facade = Program(Shaders.FACADE_VS, Shaders.FACADE_FS, "facade")
        ground = Program(Shaders.GROUND_VS, Shaders.GROUND_FS, "ground")
        water = Program(Shaders.WATER_VS, Shaders.WATER_FS, "water")
        sky = Program(Shaders.SKY_VS, Shaders.SKY_FS, "sky")
        depth = Program(Shaders.DEPTH_VS, Shaders.DEPTH_FS, "depth")
        createShadowMap()
        groundQuad = Mesh(floatArrayOf(0f, 0f, 0f, 256f, 0f, 0f, 256f, 256f, 0f, 0f, 0f, 0f, 256f, 256f, 0f, 0f, 256f, 0f), 3, intArrayOf(3))
        skyTri = Mesh(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f), 2, intArrayOf(2))
        waterGrid = Mesh(waterGridData(), 3, intArrayOf(3))
        boatMesh = loadBoat()
        // start on the Grand Canal below the Rialto, heading towards the bridge
        boat = Boat(-330f, 380f, Math.toRadians(41.0).toFloat())
        findWater()
        camX = boat.x - 8f; camY = boat.y; camZ = 3f
        glEnable(GL_DEPTH_TEST)
        glDisable(GL_CULL_FACE)
        glClearColor(fogCol[0], fogCol[1], fogCol[2], 1f)
        lastNs = System.nanoTime()
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

    /** Sun shadow map covering ~420 m around a point just ahead of the camera. */
    private fun renderShadows(cx: Float, cy: Float, boatModel: FloatArray?) {
        if (shadowTex == 0) return
        val half = 210f
        val texel = half * 2 / SHADOW
        val sx = floor(cx / texel) * texel; val sy = floor(cy / texel) * texel
        val dist = 400f
        val view = Mat4.lookAt(sx + sunDir[0] * dist, sy + sunDir[1] * dist, sunDir[2] * dist, sx, sy, 0f, 0f, 0f, 1f)
        lightVP = Mat4.mul(Mat4.ortho(-half, half, -half, half, 1f, dist * 2f), view)
        glBindFramebuffer(GL_FRAMEBUFFER, shadowFbo)
        glViewport(0, 0, SHADOW, SHADOW)
        glClear(GL_DEPTH_BUFFER_BIT)
        glEnable(GL_POLYGON_OFFSET_FILL); glPolygonOffset(1.6f, 2.5f)
        depth.use(); depth.mat4("uLightVP", lightVP)
        for (t in streamer.tiles.values) {
            if (abs(t.ox + 128 - sx) > half + 200 || abs(t.oy + 128 - sy) > half + 200) continue
            depth.mat4("uModel", t.model); t.facade?.draw(); t.generic?.draw()
        }
        if (boatModel != null) { depth.mat4("uModel", boatModel); boatMesh?.draw() }
        glDisable(GL_POLYGON_OFFSET_FILL)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, width, height)
    }

    private fun findWater() {
        if (world.solid(boat.x, boat.y) < 0.2f) return
        for (r in 1..200) for (k in 0 until 16) {
            val a = k * PI.toFloat() / 8
            val x = boat.x + cos(a) * r; val y = boat.y + sin(a) * r
            if (world.solid(x, y) < 0.05f && world.solid(x + 3, y) < 0.05f && world.solid(x - 3, y) < 0.05f && world.solid(x, y + 3) < 0.05f && world.solid(x, y - 3) < 0.05f) { boat.x = x; boat.y = y; return }
        }
    }

    private fun waterGridData(): FloatArray {
        val n = 90; val size = 3200f; val step = size / n
        val out = FloatArray(n * n * 6 * 3); var o = 0
        for (j in 0 until n) for (i in 0 until n) {
            val x0 = -size / 2 + i * step; val y0 = -size / 2 + j * step; val x1 = x0 + step; val y1 = y0 + step
            for (p in floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y0, x1, y1, x0, y1).toList().chunked(2)) { out[o++] = p[0]; out[o++] = p[1]; out[o++] = 0f }
        }
        return out
    }

    private fun loadBoat(): Mesh? = try {
        DataInputStream(InflaterInputStream(ctx.assets.open("boat.mesh")).buffered(1 shl 16)).use { d ->
            val n = d.readInt()
            val f = FloatArray(n * STRIDE) { d.readFloat() }
            Mesh(f)
        }
    } catch (e: Exception) { android.util.Log.e("Venice", "boat mesh", e); null }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) { width = w; height = h; glViewport(0, 0, w, h) }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = ((now - lastNs) / 1e9f).coerceIn(0.001f, 0.05f)
        lastNs = now; time += dt
        fpsAcc += dt; fpsN++
        if (fpsAcc > 0.5f) { GameState.fps = fpsN / fpsAcc; fpsAcc = 0f; fpsN = 0 }

        // ---- simulate (two substeps for stable collisions)
        val thr = Controls.throttle(); val st = Controls.steer()
        boat.update(dt / 2, thr, st, world); boat.update(dt / 2, thr, st, world)
        GameState.x = boat.x; GameState.y = boat.y; GameState.heading = boat.heading; GameState.speedKmh = boat.speedKmh()

        // ---- camera: chase cam with drag-to-orbit that eases back behind the boat
        camYawOff += Controls.camYawDelta; Controls.camYawDelta = 0f
        camPitch = (camPitch + Controls.camPitchDelta).coerceIn(0.05f, 0.9f); Controls.camPitchDelta = 0f
        if (abs(thr) > 0.1f) camYawOff *= exp(-dt * 1.2f)
        val yaw = boat.heading + camYawOff
        val dist = 8.5f + boat.speedKmh() * 0.035f
        val tx = boat.x - cos(yaw) * dist * cos(camPitch); val ty = boat.y - sin(yaw) * dist * cos(camPitch)
        val tz = 1.4f + dist * sin(camPitch)
        val k = 1f - exp(-dt * 6f)
        camX += (tx - camX) * k; camY += (ty - camY) * k; camZ += (tz - camZ) * k
        // keep the camera out of buildings: pull towards the boat if inside solid ground above roof-ish height
        if (world.solid(camX, camY) > 0.5f && camZ < 14f) { camX += (boat.x - camX) * 0.25f; camY += (boat.y - camY) * 0.25f }
        val shake = boat.bump * 0.04f
        val view = Mat4.lookAt(camX + sin(time * 40) * shake, camY, camZ + cos(time * 37) * shake, boat.x + cos(boat.heading) * 2.5f, boat.y + sin(boat.heading) * 2.5f, 1.1f, 0f, 0f, 1f)
        val proj = Mat4.perspective(Math.toRadians(62.0).toFloat(), width.toFloat() / height, 0.35f, 2600f)
        val vp = Mat4.mul(proj, view)

        // ---- stream tiles
        streamer.update(camX, camY, cos(yaw), sin(yaw))
        GameState.tilesLoaded = streamer.tiles.size
        GameState.loading = streamer.tiles.size < 4

        // ---- draw
        var bm = Mat4.translation(boat.x, boat.y, boat.heave)
        bm = Mat4.mul(bm, Mat4.rotZ(boat.heading)); bm = Mat4.mul(bm, Mat4.rotY(-boat.pitch)); bm = Mat4.mul(bm, Mat4.rotX(boat.roll))
        bm = Mat4.mul(bm, Mat4.translation(-2.22f, 0f, 0f))
        renderShadows(camX + cos(yaw) * 150f, camY + sin(yaw) * 150f, bm)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        // sky
        glDepthMask(false)
        sky.use(); sky.mat4("uInvVP", Mat4.invert(vp)); sky.vec3("uCam", camX, camY, camZ); sky.vec3("uSunDir", sunDir); sky.vec3("uFogCol", fogCol); sky.vec3("uSkyZenith", zenith); sky.vec3("uSunCol", sunCol)
        skyTri?.draw()
        glDepthMask(true)

        val visible = streamer.tiles.values.filter { inFrustum(vp, it.ox, it.oy) }

        // ground
        ground.use(); common(ground, vp)
        ground.i("uMask", 0); ground.i("uClass", 1)
        for (t in visible) {
            if (t.maskTex == 0) continue
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, t.maskTex)
            glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, t.classTex)
            ground.f("uMarshPass", 0f); ground.mat4("uModel", Mat4.translation(t.ox, t.oy, Z.GROUND)); groundQuad?.draw()
            if (t.hasMarsh) { ground.f("uMarshPass", 1f); ground.mat4("uModel", Mat4.translation(t.ox, t.oy, Z.MARSH)); groundQuad?.draw() }
        }
        // buildings & quays
        facade.use(); common(facade, vp)
        for (t in visible) { val m = t.facade ?: continue; facade.mat4("uModel", t.model); m.draw() }
        // bridges, props, piers
        generic.use(); common(generic, vp)
        for (t in visible) { val m = t.generic ?: continue; generic.mat4("uModel", t.model); m.draw() }
        // the boat
        boatMesh?.let { generic.mat4("uModel", bm); it.draw() }
        // water last
        water.use(); common(water, vp)
        water.vec3("uCenter", (floor(camX / 40f) * 40f), (floor(camY / 40f) * 40f), 0f)
        water.vec3("uSkyZenith", zenith)
        waterGrid?.draw()
    }

    private fun common(p: Program, vp: FloatArray) {
        p.mat4("uVP", vp)
        p.vec3("uSunDir", sunDir); p.vec3("uSunCol", sunCol); p.vec3("uSkyAmb", skyAmb); p.vec3("uGroundAmb", groundAmb)
        p.vec3("uFogCol", fogCol); p.f("uFogDens", 1f / 950f); p.vec3("uCam", camX, camY, camZ); p.f("uTime", time)
        glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, shadowTex)
        p.i("uShadow", 2); p.mat4("uLightVP", lightVP); p.f("uShadowOn", if (shadowTex != 0) 1f else 0f)
    }

    /** Conservative tile-vs-frustum test on the tile's bounding box (z from -2 to 100 m). */
    private fun inFrustum(vp: FloatArray, ox: Float, oy: Float): Boolean {
        var allLeft = true; var allRight = true; var allBelow = true; var allAbove = true; var allBehind = true
        for (c in 0 until 8) {
            val x = ox + if (c and 1 == 0) 0f else TILE_SIZE.toFloat()
            val y = oy + if (c and 2 == 0) 0f else TILE_SIZE.toFloat()
            val z = if (c and 4 == 0) -2f else 100f
            val cx = vp[0] * x + vp[4] * y + vp[8] * z + vp[12]
            val cy = vp[1] * x + vp[5] * y + vp[9] * z + vp[13]
            val cw = vp[3] * x + vp[7] * y + vp[11] * z + vp[15]
            if (cx > -cw) allLeft = false
            if (cx < cw) allRight = false
            if (cy > -cw) allBelow = false
            if (cy < cw) allAbove = false
            if (cw > 0) allBehind = false
        }
        return !(allLeft || allRight || allBelow || allAbove || allBehind)
    }

    fun onPause() { }
    fun shutdown() { if (::streamer.isInitialized) streamer.shutdown() }
}
