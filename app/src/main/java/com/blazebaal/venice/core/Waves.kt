// One wave field shared by the water shader and the boat physics, so what you see is what you ride.
package com.blazebaal.venice.core

import kotlin.math.*

object Waves {
    // direction (x, y), wavelength (m), relative amplitude, phase speed factor
    val W = arrayOf(
        floatArrayOf(0.96f, 0.28f, 9.5f, 0.80f),
        floatArrayOf(-0.38f, 0.92f, 6.3f, 0.62f),
        floatArrayOf(0.71f, -0.70f, 3.9f, 0.38f),
        floatArrayOf(-0.88f, -0.47f, 2.3f, 0.22f),
        // shorter, smaller components from scattered directions so the surface never reads as regular stripes
        floatArrayOf(0.31f, 0.95f, 4.7f, 0.30f),
        floatArrayOf(-0.97f, 0.24f, 3.3f, 0.24f),
        floatArrayOf(0.62f, 0.78f, 1.9f, 0.14f),
        floatArrayOf(-0.15f, -0.99f, 1.5f, 0.11f),
    )
    private const val G = 9.81f

    fun k(i: Int) = 2f * PI.toFloat() / W[i][2]
    fun omega(i: Int) = sqrt(G * k(i))   // deep-water dispersion

    /** Surface height at (x, y) for global amplitude [amp] (metres). */
    fun height(x: Float, y: Float, t: Float, amp: Float): Float {
        var h = 0f
        for (i in W.indices) { val w = W[i]; h += amp * w[3] * sin((w[0] * x + w[1] * y) * k(i) - omega(i) * t) }
        return h
    }

    /** GLSL with the same constants, injected into the water shader. */
    fun glsl(): String {
        val sb = StringBuilder()
        sb.append("float waveH(vec2 p, float t, float a) { float h = 0.0;\n")
        for (i in W.indices) {
            val w = W[i]
            sb.append("  h += a * ${f(w[3])} * sin(dot(p, vec2(${f(w[0])}, ${f(w[1])})) * ${f(k(i))} - ${f(omega(i))} * t);\n")
        }
        sb.append("  return h; }\n")
        sb.append("vec2 waveG(vec2 p, float t, float a) { vec2 g = vec2(0.0);\n")
        for (i in W.indices) {
            val w = W[i]
            sb.append("  g += a * ${f(w[3])} * ${f(k(i))} * cos(dot(p, vec2(${f(w[0])}, ${f(w[1])})) * ${f(k(i))} - ${f(omega(i))} * t) * vec2(${f(w[0])}, ${f(w[1])});\n")
        }
        sb.append("  return g; }\n")
        return sb.toString()
    }
    private fun f(v: Float) = String.format(java.util.Locale.US, "%.5f", v)
}
