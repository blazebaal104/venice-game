package converter

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.*

// Map extent: historic centre, Giudecca, San Giorgio, Murano, Lido (north/central)
const val LON_MIN = 12.285; const val LON_MAX = 12.430
const val LAT_MIN = 45.360; const val LAT_MAX = 45.475

object Cell { const val WATER = 0; const val LAND = 1; const val GREEN = 2; const val SAND = 3; const val MARSH = 4 }

fun log(s: String) = println("[${"%6.1f".format((System.nanoTime() - T0) / 1e9)}s] $s")
val T0 = System.nanoTime()

fun main(args: Array<String>) {
    val dataDir = File(args.getOrElse(0) { "data" })
    val outDir = File(args.getOrElse(1) { "out" }).apply { mkdirs() }
    val osm = OsmData()
    dataDir.listFiles { f -> f.name.startsWith("venice_osm_tile") }!!.sorted().forEach { log("load ${it.name}"); osm.load(it) }
    log("nodes ${osm.nodeLat.size} ways ${osm.ways.size} rels ${osm.relations.size} tagged nodes ${osm.taggedNodes.size}")

    val minX = Proj.x(LON_MIN); val maxX = Proj.x(LON_MAX); val minY = Proj.y(LAT_MIN); val maxY = Proj.y(LAT_MAX)
    val grid = Grid(minX, minY, ceil(maxX - minX).toInt(), ceil(maxY - minY).toInt(), 1.0)
    log("grid ${grid.w} x ${grid.h}")

    val world = World(osm, grid)
    world.zones = Zone.load(File(args.getOrElse(2) { "landmarks.txt" })).also { log("landmark zones: " + it.joinToString { z -> z.name }) }
    world.buildLand()
    val pontoons = world.findPontoons()
    File(outDir, "actv.txt").printWriter().use { w -> for (p in pontoons) w.println("%s\t%.2f\t%.2f\t%.2f\t%.2f\t%d".format(p.name, p.x, p.y, p.rotDeg, p.length, p.landSide)) }
    world.loadBuildings(File(dataDir, "veneto_edifici.geojson"), dataDir.listFiles { f -> f.name.endsWith(".tif") }?.firstOrNull()
        ?: File(dataDir, "bbh").walk().first { it.name.endsWith(".tif") })
    world.collectFeatures()
    world.report()
    world.preview(File(outDir, "preview.png"), 4.0)
    world.preview(File(outDir, "preview_rialto.png"), 0.5, -700.0, 150.0, 100.0, 750.0)
    MapWriter.write(world, File(outDir, "venice.map"))
    log("done")
}
