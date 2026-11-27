// The title screen's flyover: the whole of historic Venice as a light 3D model (assets/menu/city.bin, made by
// tools/menu/build_menu_city.py: every building extruded to its real height, ~0.5 M vertices at 16 bytes each) on a
// ground texture of canals, fondamenta and campi, orbited slowly by a camera looking down at ~50 degrees.
// No tile streaming, no shadows: cheap enough to run behind the menu.
package com.blazebaal.venice.render

import android.content.Context
import android.opengl.GLES30.*
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.InflaterInputStream
import kotlin.math.*

class MenuScene(ctx: Context) {
    // ground texture covers this rectangle (world metres), see build_menu_city.py
    private val gx0 = -2950f; private val gx1 = 2350f; private val gy0 = -1550f; private val gy1 = 1450f
    private val prog = Program(VS, FS, "menu")
    private var vao = 0; private var count = 0
    private var groundVao = 0; private var groundTex = 0
    val ok: Boolean

    init {
        var good = false
        try {
            val raw = DataInputStream(InflaterInputStream(ctx.assets.open("menu/city.bin")).buffered(1 shl 16)).use { it.readBytes() }
            val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            count = bb.int
            val data = ByteBuffer.allocateDirect(count * 16).order(ByteOrder.nativeOrder())
            // positions are little-endian floats: copy through (native order on every Android CPU is little-endian)
            data.put(raw, 4, count * 16); data.position(0)
            val ids = IntArray(2); glGenVertexArrays(1, ids, 0); glGenBuffers(1, ids, 1)
            vao = ids[0]
            glBindVertexArray(vao); glBindBuffer(GL_ARRAY_BUFFER, ids[1])
            glBufferData(GL_ARRAY_BUFFER, count * 16, data, GL_STATIC_DRAW)
            glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, false, 16, 0)
            glEnableVertexAttribArray(1); glVertexAttribPointer(1, 4, GL_UNSIGNED_BYTE, true, 16, 12)
            glBindVertexArray(0)
            // ground quad (mode 1) + open-water quad around it (mode 2): x, y, z, mode
            val q = floatArrayOf(
                gx0, gy0, 0.02f, 1f, gx1, gy0, 0.02f, 1f, gx1, gy1, 0.02f, 1f, gx0, gy0, 0.02f, 1f, gx1, gy1, 0.02f, 1f, gx0, gy1, 0.02f, 1f,
                -12000f, -12000f, -0.05f, 2f, 12000f, -12000f, -0.05f, 2f, 12000f, 12000f, -0.05f, 2f, -12000f, -12000f, -0.05f, 2f, 12000f, 12000f, -0.05f, 2f, -12000f, 12000f, -0.05f, 2f)
            val g = IntArray(2); glGenVertexArrays(1, g, 0); glGenBuffers(1, g, 1)
            groundVao = g[0]
            glBindVertexArray(groundVao); glBindBuffer(GL_ARRAY_BUFFER, g[1])
            glBufferData(GL_ARRAY_BUFFER, q.size * 4, floatBuffer(q), GL_STATIC_DRAW)
            glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, false, 16, 0)
            glEnableVertexAttribArray(2); glVertexAttribPointer(2, 1, GL_FLOAT, false, 16, 12)
            glBindVertexArray(0)
            val bmp = ctx.assets.open("menu/ground.jpg").use { android.graphics.BitmapFactory.decodeStream(it)!! }
            val t = IntArray(1); glGenTextures(1, t, 0); groundTex = t[0]
            glBindTexture(GL_TEXTURE_2D, groundTex)
            android.opengl.GLUtils.texImage2D(GL_TEXTURE_2D, 0, bmp, 0); bmp.recycle()
            glGenerateMipmap(GL_TEXTURE_2D)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            Aniso.apply(GL_TEXTURE_2D, 8f)
            good = true
        } catch (e: Exception) { android.util.Log.e("Venice", "menu scene", e) }
        ok = good
    }

    /** Orbiting camera: returns eye (x, y, z) and target for time t (seconds). One turn every 4 minutes. */
    fun camera(t: Float): FloatArray {
        val cx = -150f; val cy = 260f
        val ang = t * (2f * PI.toFloat() / 240f) + 3.6f
        val pitch = Math.toRadians(50.0).toFloat()
        val dist = 1450f + 120f * sin(t * 0.05f)
        val ex = cx - cos(ang) * cos(pitch) * dist; val ey = cy - sin(ang) * cos(pitch) * dist; val ez = sin(pitch) * dist
        return floatArrayOf(ex, ey, ez, cx, cy, 0f)
    }

    fun draw(vp: FloatArray, eye: FloatArray, time: Float, sunDir: FloatArray, sunCol: FloatArray, skyAmb: FloatArray, fogCol: FloatArray) {
        if (!ok) return
        prog.use()
        prog.mat4("uVP", vp); prog.vec3("uCam", eye[0], eye[1], eye[2]); prog.f("uTime", time)
        prog.vec3("uSunDir", sunDir); prog.vec3("uSunCol", sunCol); prog.vec3("uSkyAmb", skyAmb); prog.vec3("uFogCol", fogCol)
        prog.f("uFogStart", 1700f); prog.f("uFogEnd", 5200f)
        glActiveTexture(GL_TEXTURE10); glBindTexture(GL_TEXTURE_2D, groundTex); prog.i("uGround", 10)
        glUniform4f(prog.loc("uGroundRect"), gx0, gy1, gx1 - gx0, gy1 - gy0)
        prog.f("uBuildings", 0f)
        glBindVertexArray(groundVao); glDrawArrays(GL_TRIANGLES, 0, 12)
        prog.f("uBuildings", 1f)
        glBindVertexArray(vao); glDrawArrays(GL_TRIANGLES, 0, count)
        glBindVertexArray(0)
    }

    companion object {
        const val VS = """#version 300 es
layout(location = 0) in vec3 aPos; layout(location = 1) in vec4 aCol; layout(location = 2) in float aMode;
uniform mat4 uVP; uniform float uBuildings;
out vec3 vW; out vec3 vCol; flat out int vMode;
void main() { vW = aPos; vCol = aCol.rgb; vMode = uBuildings > 0.5 ? 0 : int(aMode + 0.5); gl_Position = uVP * vec4(aPos, 1.0); }"""
        const val FS = """#version 300 es
precision highp float;
in vec3 vW; in vec3 vCol; flat in int vMode;
uniform vec3 uSunDir; uniform vec3 uSunCol; uniform vec3 uSkyAmb; uniform vec3 uFogCol; uniform vec3 uCam;
uniform float uFogStart; uniform float uFogEnd; uniform float uTime;
uniform sampler2D uGround; uniform vec4 uGroundRect;
out vec4 o;
void main() {
    vec3 n = normalize(cross(dFdx(vW), dFdy(vW)));
    if (dot(n, uCam - vW) < 0.0) n = -n;
    vec3 alb; float water = 0.0;
    if (vMode == 0) {
        alb = pow(vCol, vec3(2.2));
    } else if (vMode == 1) {
        vec2 uv = vec2((vW.x - uGroundRect.x) / uGroundRect.z, (uGroundRect.y - vW.y) / uGroundRect.w);
        vec3 g = texture(uGround, uv).rgb;
        alb = pow(g, vec3(2.2)); water = smoothstep(0.0, 0.06, g.b - g.r); n = vec3(0.0, 0.0, 1.0);
    } else {
        alb = pow(vec3(0.13, 0.36, 0.38), vec3(2.2)); water = 1.0; n = vec3(0.0, 0.0, 1.0);
    }
    float diff = max(dot(n, uSunDir), 0.0);
    vec3 col = alb * (uSunCol * diff * 0.85 + uSkyAmb * (0.6 + 0.4 * n.z));
    if (water > 0.0) {
        vec3 nw = normalize(vec3(sin(vW.x * 0.09 + uTime * 0.9) * 0.05 + sin((vW.x + vW.y) * 0.05 - uTime * 0.7) * 0.04,
                                 sin(vW.y * 0.11 + uTime * 0.8) * 0.05 + sin((vW.x - vW.y) * 0.04 + uTime * 0.5) * 0.04, 1.0));
        vec3 v = normalize(uCam - vW);
        float spec = pow(max(dot(reflect(-uSunDir, nw), v), 0.0), 90.0);
        float fres = 0.04 + 0.96 * pow(1.0 - max(v.z, 0.0), 5.0);
        col = mix(col, uFogCol * 0.9, fres * 0.7 * water) + uSunCol * spec * 0.8 * water;
    }
    float d = length(vW - uCam);
    float f = smoothstep(uFogStart, uFogEnd, d) * 0.92 + (1.0 - exp(-d * 0.00012)) * 0.25;
    o = vec4(mix(col, uFogCol * 1.15, clamp(f, 0.0, 1.0)), 1.0);
}"""
    }
}
