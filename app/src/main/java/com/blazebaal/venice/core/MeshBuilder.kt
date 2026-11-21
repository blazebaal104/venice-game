// Growable vertex buffer + primitive generators. Pure Kotlin.
// Vertex layout (12 floats): position xyz, normal xyz, uv (2), attr (4)
//  - facade shader: uv = (u along wall m, z m), attr = (segLen, seed01, kind, 0)
//  - generic shader: attr = (r, g, b, gloss)
package com.blazebaal.venice.core

import kotlin.math.*

const val STRIDE = 12

class MeshBuilder(initial: Int = 4096) {
    var data = FloatArray(initial * STRIDE)
    var count = 0 // vertices
        private set

    fun clear() { count = 0 }
    fun floats() = data.copyOf(count * STRIDE)

    fun v(px: Float, py: Float, pz: Float, nx: Float, ny: Float, nz: Float, u: Float, w: Float, a: Float, b: Float, c: Float, d: Float) {
        if ((count + 1) * STRIDE > data.size) data = data.copyOf(data.size * 2)
        var o = count * STRIDE
        data[o++] = px; data[o++] = py; data[o++] = pz; data[o++] = nx; data[o++] = ny; data[o++] = nz
        data[o++] = u; data[o++] = w; data[o++] = a; data[o++] = b; data[o++] = c; data[o] = d
        count++
    }

    // ---------- colored (generic shader) primitives ----------
    var r = 1f; var g = 1f; var b = 1f; var gloss = 0f
    fun color(r: Float, g: Float, b: Float, gloss: Float = 0f) { this.r = r; this.g = g; this.b = b; this.gloss = gloss }
    fun color(c: FloatArray) { r = c[0]; g = c[1]; b = c[2]; gloss = if (c.size > 3) c[3] else 0f }

    fun cv(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) = v(x, y, z, nx, ny, nz, 0f, 0f, r, g, b, gloss)

    /** Triangle with computed flat normal (counter-clockwise = front). */
    fun tri(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float) {
        val ux = bx - ax; val uy = by - ay; val uz = bz - az
        val wx = cx - ax; val wy = cy - ay; val wz = cz - az
        var nx = uy * wz - uz * wy; var ny = uz * wx - ux * wz; var nz = ux * wy - uy * wx
        val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-9f); nx /= l; ny /= l; nz /= l
        cv(ax, ay, az, nx, ny, nz); cv(bx, by, bz, nx, ny, nz); cv(cx, cy, cz, nx, ny, nz)
    }

    /** Quad a-b-c-d counter-clockwise. */
    fun quad(a: Vec3, b: Vec3, c: Vec3, d: Vec3) { tri(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z); tri(a.x, a.y, a.z, c.x, c.y, c.z, d.x, d.y, d.z) }

    /** Oriented box: centre, half sizes, rotation about Z (radians). */
    fun box(cx: Float, cy: Float, cz: Float, hx: Float, hy: Float, hz: Float, rot: Float = 0f) {
        val cs = cos(rot); val sn = sin(rot)
        fun p(sx: Float, sy: Float, sz: Float) = Vec3(cx + sx * hx * cs - sy * hy * sn, cy + sx * hx * sn + sy * hy * cs, cz + sz * hz)
        val p000 = p(-1f, -1f, -1f); val p100 = p(1f, -1f, -1f); val p110 = p(1f, 1f, -1f); val p010 = p(-1f, 1f, -1f)
        val p001 = p(-1f, -1f, 1f); val p101 = p(1f, -1f, 1f); val p111 = p(1f, 1f, 1f); val p011 = p(-1f, 1f, 1f)
        quad(p001, p101, p111, p011) // top
        quad(p000, p010, p110, p100) // bottom
        quad(p000, p100, p101, p001)
        quad(p100, p110, p111, p101)
        quad(p110, p010, p011, p111)
        quad(p010, p000, p001, p011)
    }

    /** Cylinder/cone frustum from base point along an axis (tilt dx,dy per metre of height). */
    fun frustum(x: Float, y: Float, z: Float, r0: Float, r1: Float, h: Float, segs: Int = 10, tiltX: Float = 0f, tiltY: Float = 0f, capTop: Boolean = true) {
        val tx = x + tiltX * h; val ty = y + tiltY * h
        for (i in 0 until segs) {
            val a0 = 2 * PI.toFloat() * i / segs; val a1 = 2 * PI.toFloat() * (i + 1) / segs
            val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
            val am = (a0 + a1) / 2; val nx = cos(am); val ny = sin(am)
            val slope = (r0 - r1) / h
            val nl = sqrt(1 + slope * slope)
            val b0 = Vec3(x + c0 * r0, y + s0 * r0, z); val b1 = Vec3(x + c1 * r0, y + s1 * r0, z)
            val t1 = Vec3(tx + c1 * r1, ty + s1 * r1, z + h); val t0 = Vec3(tx + c0 * r1, ty + s0 * r1, z + h)
            val n = Vec3(nx / nl, ny / nl, slope / nl)
            cv(b0.x, b0.y, b0.z, n.x, n.y, n.z); cv(b1.x, b1.y, b1.z, n.x, n.y, n.z); cv(t1.x, t1.y, t1.z, n.x, n.y, n.z)
            cv(b0.x, b0.y, b0.z, n.x, n.y, n.z); cv(t1.x, t1.y, t1.z, n.x, n.y, n.z); cv(t0.x, t0.y, t0.z, n.x, n.y, n.z)
            if (capTop && r1 > 0.001f) { cv(tx, ty, z + h, 0f, 0f, 1f); cv(t0.x, t0.y, t0.z, 0f, 0f, 1f); cv(t1.x, t1.y, t1.z, 0f, 0f, 1f) }
        }
    }

    /** Low-poly blob (octahedron subdivided once), squashed by sz. */
    fun blob(cx: Float, cy: Float, cz: Float, rad: Float, sz: Float = 1f, jitter: Int = 0) {
        val v = arrayOf(Vec3(1f, 0f, 0f), Vec3(-1f, 0f, 0f), Vec3(0f, 1f, 0f), Vec3(0f, -1f, 0f), Vec3(0f, 0f, 1f), Vec3(0f, 0f, -1f))
        val faces = arrayOf(intArrayOf(0, 2, 4), intArrayOf(2, 1, 4), intArrayOf(1, 3, 4), intArrayOf(3, 0, 4), intArrayOf(2, 0, 5), intArrayOf(1, 2, 5), intArrayOf(3, 1, 5), intArrayOf(0, 3, 5))
        fun mid(a: Vec3, b: Vec3) = Vec3(a.x + b.x, a.y + b.y, a.z + b.z).norm()
        fun wob(p: Vec3): Vec3 {
            val h = ((p.x * 12.9898f + p.y * 78.233f + p.z * 37.719f + jitter * 3.1f).let { sin(it) * 43758.547f }).let { it - floor(it) }
            val k = rad * (0.85f + 0.3f * h)
            return Vec3(cx + p.x * k, cy + p.y * k, cz + p.z * k * sz)
        }
        for (f in faces) {
            val a = v[f[0]]; val b = v[f[1]]; val c = v[f[2]]
            val ab = mid(a, b); val bc = mid(b, c); val ca = mid(c, a)
            for (t in arrayOf(arrayOf(a, ab, ca), arrayOf(ab, b, bc), arrayOf(ca, bc, c), arrayOf(ab, bc, ca))) {
                val p0 = wob(t[0]); val p1 = wob(t[1]); val p2 = wob(t[2])
                tri(p0.x, p0.y, p0.z, p1.x, p1.y, p1.z, p2.x, p2.y, p2.z)
            }
        }
    }
}

data class Vec3(val x: Float, val y: Float, val z: Float) {
    fun norm(): Vec3 { val l = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-9f); return Vec3(x / l, y / l, z / l) }
}
