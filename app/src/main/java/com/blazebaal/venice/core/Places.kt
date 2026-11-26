// A few named places (from their real coordinates), projected into map metres like the converter does.
// Used where the generic map data needs a hand (park planting, the wooden Accademia bridge), and by the UI for
// map labels, the "where am I" name on the HUD and teleporting from the pause menu.
package com.blazebaal.venice.core

import kotlin.math.*

object Places {
    class P(val x: Float, val y: Float, val r: Float)
    var santElena = P(1e9f, 1e9f, 0f); var giardini = P(1e9f, 1e9f, 0f); var napoleonici = P(1e9f, 1e9f, 0f); var reali = P(1e9f, 1e9f, 0f)
    var accademia = P(1e9f, 1e9f, 0f); var scalzi = P(1e9f, 1e9f, 0f); var costituzione = P(1e9f, 1e9f, 0f)
    private var ready = false

    /** Same equirectangular projection as tools/converter: metres east / north of (lat0, lon0). */
    fun project(lat: Double, lon: Double, lat0: Double, lon0: Double): FloatArray {
        val phi = Math.toRadians(lat0)
        val mLat = 111132.954 - 559.822 * cos(2 * phi) + 1.175 * cos(4 * phi)
        val mLon = 111412.84 * cos(phi) - 93.5 * cos(3 * phi) + 0.118 * cos(5 * phi)
        return floatArrayOf(((lon - lon0) * mLon).toFloat(), ((lat - lat0) * mLat).toFloat())
    }

    fun init(lat0: Double, lon0: Double) {
        if (ready) return
        fun p(lat: Double, lon: Double, r: Float) = project(lat, lon, lat0, lon0).let { P(it[0], it[1], r) }
        santElena = p(45.42935, 12.36170, 300f)      // Parco delle Rimembranze: stone (umbrella) pines
        giardini = p(45.42860, 12.35640, 280f)       // Giardini della Biennale: dense broad-leaved trees
        napoleonici = p(45.43070, 12.35290, 170f)    // Giardini Napoleonici / Viale Garibaldi
        reali = p(45.43270, 12.33760, 70f)           // Giardini Reali by San Marco
        accademia = p(45.43195, 12.32893, 0f)
        scalzi = p(45.44133, 12.32213, 0f)
        costituzione = p(45.43846, 12.32064, 0f)
        ready = true
    }
    fun inside(p: P, x: Float, y: Float) = hypot(x - p.x, y - p.y) < p.r

    // ------------------------------------------------------------------ names for the map, HUD and teleports
    /** kind: 0 = sestiere / island (big label), 1 = landmark (teleport target), 2 = neighbourhood */
    class Named(val name: String, val lat: Double, val lon: Double, val kind: Int, val radius: Float = 400f)

    val NAMED = listOf(
        Named("CANNAREGIO", 45.4448, 12.3305, 0, 900f), Named("CASTELLO", 45.4355, 12.3530, 0, 1100f),
        Named("DORSODURO", 45.4305, 12.3245, 0, 800f), Named("SAN MARCO", 45.4350, 12.3350, 0, 500f),
        Named("SAN POLO", 45.4382, 12.3295, 0, 450f), Named("SANTA CROCE", 45.4400, 12.3225, 0, 500f),
        Named("GIUDECCA", 45.4262, 12.3270, 0, 900f), Named("MURANO", 45.4582, 12.3530, 0, 900f),
        Named("LIDO", 45.4100, 12.3660, 0, 2500f), Named("SANT'ELENA", 45.4290, 12.3620, 0, 400f),
        Named("SAN GIORGIO", 45.4292, 12.3430, 0, 300f), Named("SAN MICHELE", 45.4478, 12.3468, 0, 350f),
        Named("TRONCHETTO", 45.4410, 12.3060, 0, 500f), Named("BURANO", 45.4853, 12.4170, 0, 600f),
        Named("Piazza San Marco", 45.43410, 12.33860, 1, 150f), Named("Ponte di Rialto", 45.43800, 12.33590, 1, 150f),
        Named("Ponte dell'Accademia", 45.43195, 12.32893, 1, 120f), Named("Santa Maria della Salute", 45.43080, 12.33450, 1, 150f),
        Named("Punta della Dogana", 45.43060, 12.33620, 1, 120f), Named("Ferrovia · Santa Lucia", 45.44120, 12.32100, 1, 200f),
        Named("Piazzale Roma", 45.43810, 12.31800, 1, 200f), Named("Ponte della Costituzione", 45.43846, 12.32064, 1, 100f),
        Named("Ponte degli Scalzi", 45.44133, 12.32213, 1, 100f), Named("Ca' d'Oro", 45.44060, 12.33400, 1, 100f),
        Named("Arsenale", 45.43480, 12.34970, 1, 250f), Named("Giardini della Biennale", 45.42860, 12.35640, 1, 250f),
        Named("San Giorgio Maggiore", 45.42940, 12.34320, 1, 150f), Named("Il Redentore", 45.42650, 12.33250, 1, 120f),
        Named("Fondamente Nove", 45.44290, 12.34150, 1, 200f), Named("Frari", 45.43660, 12.32620, 1, 120f),
        Named("Bacino di San Marco", 45.43150, 12.34000, 2, 400f), Named("Canal Grande", 45.43600, 12.33000, 2, 300f),
        Named("Canale della Giudecca", 45.42800, 12.32500, 2, 500f), Named("Cimitero di San Michele", 45.44780, 12.34680, 1, 200f),
    )

    class World(val name: String, val x: Float, val y: Float, val kind: Int, val radius: Float)
    private var worldCache: List<World>? = null; private var cacheKey = 0.0

    fun named(lat0: Double, lon0: Double): List<World> {
        val k = lat0 * 1000 + lon0
        worldCache?.let { if (k == cacheKey) return it }
        val l = NAMED.map { n -> project(n.lat, n.lon, lat0, lon0).let { World(n.name, it[0], it[1], n.kind, n.radius) } }
        worldCache = l; cacheKey = k
        return l
    }

    /** Name of where (x, y) is: the landmark if very close, otherwise the sestiere / island. */
    fun nameAt(x: Float, y: Float, lat0: Double, lon0: Double): String {
        val l = named(lat0, lon0)
        l.filter { it.kind == 1 }.minByOrNull { hypot(it.x - x, it.y - y) / it.radius }?.let { if (hypot(it.x - x, it.y - y) < it.radius) return it.name }
        val best = l.filter { it.kind != 1 }.minByOrNull { hypot(it.x - x, it.y - y) / it.radius }
        return if (best != null && hypot(best.x - x, best.y - y) < best.radius * 1.8f) best.name.lowercase().split(' ', '\'').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }.replace("Sant Elena", "Sant'Elena")
        else "Laguna di Venezia"
    }
}
