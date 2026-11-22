// Tileable ripple slopes (RG, 0..255 around 127.5): a sum of directional waves with whole-number wave vectors
// (so it tiles) and a capillary-ish 1/k^1.6 spectrum. Pure Kotlin so the preview tool can export the same texture.
package com.blazebaal.venice.core

import kotlin.math.*
import kotlin.random.Random

object WaterNormalData {
    fun make(size: Int): ByteArray {
        val rnd = Random(11)
        val waves = ArrayList<FloatArray>()
        while (waves.size < 48) {
            val kx = rnd.nextInt(-22, 23); val ky = rnd.nextInt(-22, 23)
            val k = hypot(kx.toFloat(), ky.toFloat())
            if (k < 2f) continue
            val dirW = 0.6f + 0.4f * abs(cos(atan2(ky.toFloat(), kx.toFloat()) - 0.5f))
            waves.add(floatArrayOf(kx.toFloat(), ky.toFloat(), dirW / k.pow(1.6f), rnd.nextFloat() * 6.2832f))
        }
        val gx = FloatArray(size * size); val gy = FloatArray(size * size)
        var mx = 1e-6f
        for (j in 0 until size) for (i in 0 until size) {
            val u = i.toFloat() / size; val v = j.toFloat() / size
            var sx = 0f; var sy = 0f
            for (w in waves) {
                val c = cos(6.2832f * (w[0] * u + w[1] * v) + w[3]) * w[2] * 6.2832f
                sx += c * w[0]; sy += c * w[1]
            }
            gx[j * size + i] = sx; gy[j * size + i] = sy
            mx = max(mx, max(abs(sx), abs(sy)))
        }
        val norm = mx * 0.55f
        val data = ByteArray(size * size * 2)
        for (k in 0 until size * size) {
            data[k * 2] = ((gx[k] / norm).coerceIn(-1f, 1f) * 127.5f + 127.5f).roundToInt().toByte()
            data[k * 2 + 1] = ((gy[k] / norm).coerceIn(-1f, 1f) * 127.5f + 127.5f).roundToInt().toByte()
        }
        return data
    }
}
