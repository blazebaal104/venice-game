// Short coloured texts that tell you what is happening ("PLANATA", "COLONNA ALTA", "URTO", "STRISCIATA"...):
// posted from the GL thread, drawn by the HUD with a fade + slide animation. Each key has its own cooldown so the
// same message does not spam.
package com.blazebaal.venice.ui

object Events {
    class E(val text: String, val color: Int, val sub: String, val t0: Long)
    const val LIFE_MS = 2200L
    private val list = ArrayList<E>()
    private val last = HashMap<String, Long>()

    // colours by meaning
    val INFO = Theme.TEXT
    val GOOD = Theme.YELLOW
    val WATER = Theme.TEAL
    val WARN = android.graphics.Color.rgb(255, 150, 60)
    val BAD = Theme.RED

    fun post(text: String, color: Int, sub: String = "", key: String = text, cooldownMs: Long = 3000L) {
        val now = System.currentTimeMillis()
        synchronized(list) {
            if (now - (last[key] ?: 0L) < cooldownMs) return
            last[key] = now
            list.removeAll { it.text == text }
            list.add(E(text, color, sub, now))
            while (list.size > 3) list.removeAt(0)
        }
    }

    fun live(): List<E> {
        val now = System.currentTimeMillis()
        synchronized(list) { list.removeAll { now - it.t0 > LIFE_MS }; return list.toList() }
    }
}
