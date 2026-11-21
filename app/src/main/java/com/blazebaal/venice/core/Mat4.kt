// Column-major 4x4 matrices (OpenGL convention). Pure Kotlin.
package com.blazebaal.venice.core

import kotlin.math.*

object Mat4 {
    fun identity() = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    fun mul(a: FloatArray, b: FloatArray, out: FloatArray = FloatArray(16)): FloatArray {
        val r = FloatArray(16)
        for (c in 0 until 4) for (rr in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += a[k * 4 + rr] * b[c * 4 + k]
            r[c * 4 + rr] = s
        }
        r.copyInto(out); return out
    }

    fun perspective(fovYRad: Float, aspect: Float, near: Float, far: Float): FloatArray {
        val f = 1f / tan(fovYRad / 2)
        val m = FloatArray(16)
        m[0] = f / aspect; m[5] = f; m[10] = (far + near) / (near - far); m[11] = -1f; m[14] = 2 * far * near / (near - far)
        return m
    }

    fun lookAt(ex: Float, ey: Float, ez: Float, cx: Float, cy: Float, cz: Float, ux: Float, uy: Float, uz: Float): FloatArray {
        var fx = cx - ex; var fy = cy - ey; var fz = cz - ez
        val fl = sqrt(fx * fx + fy * fy + fz * fz); fx /= fl; fy /= fl; fz /= fl
        var sx = fy * uz - fz * uy; var sy = fz * ux - fx * uz; var sz = fx * uy - fy * ux
        val sl = sqrt(sx * sx + sy * sy + sz * sz); sx /= sl; sy /= sl; sz /= sl
        val vx = sy * fz - sz * fy; val vy = sz * fx - sx * fz; val vz = sx * fy - sy * fx
        val m = identity()
        m[0] = sx; m[4] = sy; m[8] = sz
        m[1] = vx; m[5] = vy; m[9] = vz
        m[2] = -fx; m[6] = -fy; m[10] = -fz
        m[12] = -(sx * ex + sy * ey + sz * ez); m[13] = -(vx * ex + vy * ey + vz * ez); m[14] = fx * ex + fy * ey + fz * ez
        return m
    }

    fun ortho(l: Float, r: Float, b: Float, t: Float, n: Float, f: Float): FloatArray {
        val m = identity()
        m[0] = 2 / (r - l); m[5] = 2 / (t - b); m[10] = -2 / (f - n)
        m[12] = -(r + l) / (r - l); m[13] = -(t + b) / (t - b); m[14] = -(f + n) / (f - n)
        return m
    }

    fun translation(x: Float, y: Float, z: Float) = identity().also { it[12] = x; it[13] = y; it[14] = z }

    fun rotZ(a: Float) = identity().also { val c = cos(a); val s = sin(a); it[0] = c; it[1] = s; it[4] = -s; it[5] = c }
    fun rotY(a: Float) = identity().also { val c = cos(a); val s = sin(a); it[0] = c; it[2] = -s; it[8] = s; it[10] = c }
    fun rotX(a: Float) = identity().also { val c = cos(a); val s = sin(a); it[5] = c; it[6] = s; it[9] = -s; it[10] = c }

    /** General 4x4 inverse. */
    fun invert(m: FloatArray): FloatArray {
        val inv = FloatArray(16)
        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10]
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10]
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9]
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9]
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10]
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10]
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9]
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9]
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6]
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6]
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5]
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5]
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6]
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6]
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5]
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5]
        var det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12]
        if (abs(det) < 1e-12f) return identity()
        det = 1f / det
        for (i in 0 until 16) inv[i] *= det
        return inv
    }
}
