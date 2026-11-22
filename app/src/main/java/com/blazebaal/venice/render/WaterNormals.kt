// Tileable ripple-slope texture for the water, generated once at start-up (replaces ~15 noise evaluations per pixel).
// A sum of directional waves with whole-number wave vectors (so it tiles) and a capillary-ish 1/k^1.6 spectrum.
package com.blazebaal.venice.render

import android.opengl.GLES30.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WaterNormals {
    fun create(size: Int = 256): Int {
        val data = com.blazebaal.venice.core.WaterNormalData.make(size)
        val t = IntArray(1); glGenTextures(1, t, 0)
        glBindTexture(GL_TEXTURE_2D, t[0]); glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        val buf = ByteBuffer.allocateDirect(data.size).order(ByteOrder.nativeOrder()).put(data); buf.position(0)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RG8, size, size, 0, GL_RG, GL_UNSIGNED_BYTE, buf)
        glGenerateMipmap(GL_TEXTURE_2D)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT)
        return t[0]
    }
}
