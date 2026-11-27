// Rain: a few thousand streaks in a 36 x 36 x 22 m box that wraps around the camera (no CPU work per frame: the
// vertex shader moves and wraps them), slanted by the real wind, longer and denser the heavier the rain.
package com.blazebaal.venice.render

import android.opengl.GLES30.*
import kotlin.math.*

class Rain {
    private val count = 3000
    private val mesh: Mesh
    private val prog = Program(VS, FS, "rain")

    init {
        val r = java.util.Random(11)
        val d = FloatArray(count * 6 * 5)
        val corners = floatArrayOf(-1f, 0f, 1f, 0f, 1f, 1f, -1f, 0f, 1f, 1f, -1f, 1f)
        var o = 0
        for (i in 0 until count) {
            val sx = r.nextFloat(); val sy = r.nextFloat(); val sz = r.nextFloat()
            for (k in 0 until 6) { d[o++] = sx; d[o++] = sy; d[o++] = sz; d[o++] = corners[k * 2]; d[o++] = corners[k * 2 + 1] }
        }
        mesh = Mesh(d, 5, intArrayOf(3, 2))
    }

    /** intensity 0..1, wind in m/s (x, y) */
    fun draw(vp: FloatArray, camX: Float, camY: Float, camZ: Float, time: Float, intensity: Float, windX: Float, windY: Float, fog: FloatArray) {
        if (intensity <= 0.01f) return
        glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); glDepthMask(false)
        prog.use()
        prog.mat4("uVP", vp); prog.vec3("uCam", camX, camY, camZ); prog.f("uTime", time)
        prog.vec2("uWind", windX, windY); prog.f("uFall", 8.5f + 2f * intensity); prog.f("uLen", 0.35f + 0.5f * intensity)
        prog.f("uDensity", (0.25f + 0.75f * intensity).coerceIn(0f, 1f)); prog.vec3("uTint", fog)
        mesh.draw()
        glDepthMask(true); glDisable(GL_BLEND)
    }

    companion object {
        const val VS = """#version 300 es
layout(location = 0) in vec3 aSeed; layout(location = 1) in vec2 aCorner;
uniform mat4 uVP; uniform vec3 uCam; uniform float uTime; uniform vec2 uWind; uniform float uFall; uniform float uLen; uniform float uDensity;
out float vA;
void main() {
    vec3 box = vec3(36.0, 36.0, 22.0);
    vec3 vel = vec3(uWind, -uFall);
    vec3 base = uCam - box * 0.5;
    vec3 p = mod(aSeed * box + vel * uTime - base, box) + base;
    float keep = step(fract(aSeed.x * 91.7 + aSeed.y * 37.3 + aSeed.z * 13.1), uDensity);
    vec3 dir = normalize(vel);
    vec3 tail = p - dir * uLen;
    vec3 pos = mix(p, tail, aCorner.y);
    vec3 side = normalize(cross(dir, p - uCam + vec3(0.0001)));
    pos += side * aCorner.x * 0.008 * (1.0 + length(p - uCam) * 0.03);
    float d = length(p - uCam);
    vA = keep * step(0.0, p.z) * (1.0 - smoothstep(10.0, 18.0, d)) * smoothstep(0.6, 2.0, d) * mix(0.35, 0.05, aCorner.y);
    gl_Position = keep > 0.5 ? uVP * vec4(pos, 1.0) : vec4(2.0, 2.0, 2.0, 1.0);
}"""
        const val FS = """#version 300 es
precision mediump float;
in float vA; uniform vec3 uTint; out vec4 o;
void main() { o = vec4(mix(vec3(0.82, 0.85, 0.9), uTint, 0.3), vA); }"""
    }
}
