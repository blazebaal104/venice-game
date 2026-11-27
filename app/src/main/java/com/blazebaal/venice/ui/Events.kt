// Short coloured texts that tell you what is happening ("PLANATA", "SCIA BASSA", "URTO", "RETROMARCIA"...):
// posted from the GL thread, drawn by the HUD. Only ONE text is ever on screen: a new one replaces the one showing
// (the old one lifts away while the new one pops in from small to big). Each key has its own cooldown so the same
// message does not spam. The `sub` description is kept for the log only, it is not drawn.
package com.blazebaal.venice.ui

object Events {
    class E(val text: String, val color: Int, val sub: String, val t0: Long)
    const val LIFE_MS = 1900L
    const val OUT_MS = 170L
    private var current: E? = null
    /** the text being replaced, and when it started leaving */
    private var outgoing: E? = null; private var outT0 = 0L
    private val last = HashMap<String, Long>()

    // colours by meaning
    val INFO = Theme.TEXT
    val GOOD = Theme.YELLOW
    val WATER = android.graphics.Color.rgb(140, 212, 255)      // light blue: what the water/hull is doing
    val WARN = android.graphics.Color.rgb(255, 150, 60)
    val BAD = Theme.RED

    fun post(text: String, color: Int, sub: String = "", key: String = text, cooldownMs: Long = 3000L) {
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - (last[key] ?: 0L) < cooldownMs) return
            last[key] = now
            val cur = current
            if (cur != null && now - cur.t0 < LIFE_MS) {
                if (cur.text == text) { current = E(text, color, sub, cur.t0.coerceAtLeast(now - 250)); return }
                outgoing = cur; outT0 = now
            }
            current = E(text, color, sub, now)
        }
    }

    /** (current, outgoing, outT0) as they should be drawn now */
    fun snapshot(now: Long): Triple<E?, E?, Long> = synchronized(this) {
        if (current != null && now - current!!.t0 > LIFE_MS) current = null
        if (outgoing != null && now - outT0 > OUT_MS) outgoing = null
        Triple(current, outgoing, outT0)
    }
}
