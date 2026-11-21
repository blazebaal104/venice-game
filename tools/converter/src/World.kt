package converter

import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.*

class Building(val poly: Poly, var height: Float, val kind: Int, val heightSource: Int)
class Bridge(val xs: DoubleArray, val ys: DoubleArray, val width: Float, val steps: Boolean, val name: String)
class LinePier(val xs: DoubleArray, val ys: DoubleArray, val width: Float)
class PointFeature(val type: Int, val x: Double, val y: Double, val dir: Float = 0f, val name: String = "")

object FeatureType {
    const val BENCH = 1; const val WELL = 2; const val BRICOLA = 3; const val TREE = 4; const val LAMP = 5
    const val STOP = 6; const val FLAGPOLE = 7; const val BIN = 8; const val FOUNTAIN = 9
}

class FillOp(val rings: List<Ring>, val fn: (Int) -> Int) {
    val bb: DoubleArray = run {
        var a = Double.MAX_VALUE; var b = Double.MAX_VALUE; var c = -Double.MAX_VALUE; var d = -Double.MAX_VALUE
        for (r in rings) for (i in 0 until r.n) { a = min(a, r.x[i]); b = min(b, r.y[i]); c = max(c, r.x[i]); d = max(d, r.y[i]) }
        doubleArrayOf(a, b, c, d)
    }
}

class World(val osm: OsmData, val grid: Grid) {
    val ops = ArrayList<FillOp>()
    private fun op(rings: List<Ring>, fn: (Int) -> Int) { val o = FillOp(rings, fn); ops.add(o); grid.fill(rings, fn) }
    val islands = ArrayList<Poly>()
    val waterPolys = ArrayList<Poly>()
    val buildings = ArrayList<Building>()
    val bridges = ArrayList<Bridge>()
    val bridgeAreas = ArrayList<Poly>()
    val piers = ArrayList<Poly>()
    val linePiers = ArrayList<LinePier>()
    val points = ArrayList<PointFeature>()
    val landPolys = ArrayList<Poly>() // final land polygons for rendering (from the raster)

    private fun closedWayPoly(w: OsmWay): Poly? {
        if (w.nodes.size < 4 || w.nodes.first() != w.nodes.last()) return null
        val r = Assemble.ringFromNodes(osm, w.nodes) ?: return null
        return Poly(r.ccw(), emptyList())
    }

    private fun areaPolys(match: (Map<String, String>) -> Boolean): List<Poly> {
        val out = ArrayList<Poly>()
        for (w in osm.ways.values) if (match(w.tags)) closedWayPoly(w)?.let { out.add(it) }
        for (r in osm.relations.values) if (r.tags["type"] == "multipolygon" && match(r.tags)) out.addAll(Assemble.multipolygon(osm, r))
        return out
    }

    fun buildLand() {
        islands.addAll(areaPolys { (it["place"] == "island" || it["place"] == "islet") && it["wetland"] != "tidalflat" })
        log("island polygons ${islands.size}; largest: " + islands.sortedByDescending { it.area() }.take(5).joinToString { "%.2f km²".format(it.area() / 1e6) })
        for (p in islands) op(p.rings()) { Cell.LAND }
        // closed coastline rings (land on the left -> counter-clockwise rings are land)
        val coast = osm.ways.values.filter { it.tags["natural"] == "coastline" }.map { it.nodes }
        val crings = Assemble.rings(osm, coast)
        var cl = 0
        for (r in crings) if (r.signedArea() > 0 && r.area() < 30e6) { op(listOf(r)) { Cell.LAND }; cl++ }
        log("coastline rings ${crings.size}, used as land $cl")
        // land cover on top of land
        val green = areaPolys {
            it["landuse"] in setOf("grass", "meadow", "forest", "village_green", "recreation_ground", "farmland", "vineyard", "orchard", "allotments") ||
                it["leisure"] in setOf("park", "garden", "pitch", "golf_course") || it["natural"] in setOf("wood", "scrub", "grassland", "heath")
        }
        for (p in green) op(p.rings()) { old -> if (old == Cell.LAND) Cell.GREEN else -1 }
        val sand = areaPolys { it["natural"] == "sand" || it["natural"] == "beach" }
        for (p in sand) op(p.rings()) { Cell.SAND }
        // salt marshes (barene) only; the big lagoon "wetland" relations are tidal flats under water
        val marsh = areaPolys { it["natural"] == "wetland" && it["wetland"] !in setOf("tidalflat", "mud", "reedbed") }
            .filter { it.area() < 0.6e6 }
        for (p in marsh) op(p.rings()) { Cell.MARSH }
        // water areas cut into land (canals, docks, ponds) - skip the lagoon itself
        val water = areaPolys { (it["natural"] == "water" || it["waterway"] == "riverbank" || it["waterway"] == "dock") && it["water"] != "lagoon" }
        for (p in water) {
            if (p.area() > 2.5e6) continue
            waterPolys.add(p)
            op(p.rings()) { Cell.WATER }
        }
        log("green ${green.size} sand ${sand.size} marsh ${marsh.size} water-areas ${waterPolys.size}")
        // canals mapped only as lines: carve with their width
        var carved = 0
        for (w in osm.ways.values) {
            val ww = w.tags["waterway"] ?: continue
            if (ww != "canal" && ww != "river") continue
            if (w.tags["tunnel"] != null) continue
            val ln = Assemble.line(osm, w.nodes) ?: continue
            val width = w.tags["width"]?.toDoubleOrNull() ?: 0.0
            if (width <= 0) continue
            carveLine(ln.first, ln.second, width / 2) { Cell.WATER }; carved++
        }
        log("carved line canals: $carved")
    }

    fun carveLine(xs: DoubleArray, ys: DoubleArray, r: Double, v: (Int) -> Int) {
        for (i in 0 until xs.size - 1) {
            val dx = xs[i + 1] - xs[i]; val dy = ys[i + 1] - ys[i]; val l = hypot(dx, dy)
            if (l < 1e-6) continue
            val nx = -dy / l * r; val ny = dx / l * r
            op(listOf(Ring(doubleArrayOf(xs[i] + nx, xs[i + 1] + nx, xs[i + 1] - nx, xs[i] - nx), doubleArrayOf(ys[i] + ny, ys[i + 1] + ny, ys[i + 1] - ny, ys[i] - ny))), v)
        }
    }

    fun loadBuildings(geojson: File, tif: File) {
        log("reading heights ${tif.name}")
        val hr = HeightRaster(tif)
        // OSM buildings carrying explicit heights / levels
        class OsmH(val poly: Poly, val h: Double, val bb: DoubleArray)
        val osmH = ArrayList<OsmH>()
        for (w in osm.ways.values) {
            val t = w.tags
            if (t["building"] == null && t["building:part"] == null) continue
            val h = t["height"]?.replace("m", "")?.trim()?.toDoubleOrNull() ?: t["building:levels"]?.toDoubleOrNull()?.let { it * 3.3 + 1.0 } ?: continue
            closedWayPoly(w)?.let { osmH.add(OsmH(it, h, it.outer.bbox())) }
        }
        // bucket index
        val cell = 100.0
        val idx = HashMap<Long, ArrayList<OsmH>>()
        fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)
        for (o in osmH) {
            for (cx in floor(o.bb[0] / cell).toInt()..floor(o.bb[2] / cell).toInt()) for (cy in floor(o.bb[1] / cell).toInt()..floor(o.bb[3] / cell).toInt())
                idx.getOrPut(key(cx, cy)) { ArrayList() }.add(o)
        }
        log("osm buildings with height: ${osmH.size}")
        @Suppress("UNCHECKED_CAST")
        val fc = Json(geojson.readText()).parse() as Map<String, Any?>
        val feats = fc["features"] as List<Map<String, Any?>>
        var srcOsm = 0; var srcCop = 0; var srcDef = 0
        for (f in feats) {
            val g = f["geometry"] as Map<String, Any?>? ?: continue
            val props = f["properties"] as Map<String, Any?>
            val kind = when ((props["edi_uso"] as String?) ?: "") { "12" -> 2; "0303" -> 3; else -> if ((props["edi_ty"] as String?) == "13") 4 else 1 }
            val polys = when (g["type"]) {
                "MultiPolygon" -> g["coordinates"] as List<List<List<List<Double>>>>
                "Polygon" -> listOf(g["coordinates"] as List<List<List<Double>>>)
                else -> emptyList()
            }
            for (pc in polys) {
                val rings = pc.map { ring ->
                    val pts = if (ring.size > 1 && ring.first()[0] == ring.last()[0] && ring.first()[1] == ring.last()[1]) ring.dropLast(1) else ring
                    Ring(DoubleArray(pts.size) { Proj.x(pts[it][0]) }, DoubleArray(pts.size) { Proj.y(pts[it][1]) })
                }
                if (rings.isEmpty() || rings[0].n < 3) continue
                val outer = rings[0].ccw().simplify(0.15)
                if (outer.area() < 4.0) continue
                val (cx, cy) = outer.centroid()
                if (cx < grid.minX || cy < grid.minY || cx > grid.minX + grid.w || cy > grid.minY + grid.h) continue
                val poly = Poly(outer, rings.drop(1).map { it.cw().simplify(0.15) }.filter { it.area() > 2.0 })
                // height: OSM match first
                var h = -1.0; var src = 0
                val bb = outer.bbox()
                val cands = HashSet<OsmH>()
                for (gx in floor(bb[0] / cell).toInt()..floor(bb[2] / cell).toInt()) for (gy in floor(bb[1] / cell).toInt()..floor(bb[3] / cell).toInt()) idx[key(gx, gy)]?.let { cands.addAll(it) }
                for (o in cands) {
                    val (ox, oy) = o.poly.outer.centroid()
                    if (outer.contains(ox, oy) || o.poly.outer.contains(cx, cy)) h = max(h, o.h)
                }
                if (h > 0) src = 1 else {
                    // sample Copernicus block height inside the footprint
                    val samples = ArrayList<Int>()
                    val sx = max(1.0, (bb[2] - bb[0]) / 6); val sy = max(1.0, (bb[3] - bb[1]) / 6)
                    var yy = bb[1] + sy / 2
                    while (yy < bb[3]) {
                        var xx = bb[0] + sx / 2
                        while (xx < bb[2]) {
                            if (poly.contains(xx, yy)) { val v = hr.heightAt(Proj.lon(xx), Proj.lat(yy)); if (v > 0) samples.add(v) }
                            xx += sx
                        }
                        yy += sy
                    }
                    val v0 = hr.heightAt(Proj.lon(cx), Proj.lat(cy)); if (v0 > 0) samples.add(v0)
                    if (samples.isNotEmpty()) { samples.sort(); h = samples[(samples.size * 0.7).toInt().coerceAtMost(samples.size - 1)].toDouble(); src = 2 }
                    else { h = if (kind == 4) 4.0 else 10.0; src = 0 }
                }
                // Copernicus underestimates thin buildings at 10 m; small footprints are rarely below 2 floors in Venice
                if (src == 2 && kind != 4 && outer.area() > 30) h = max(h, 7.5)
                // small structures standing in open water: vaporetto pontoon shelters / boat sheds
                val onWater = grid.atWorld(cx, cy) == Cell.WATER
                var bkind = kind
                if (onWater && outer.area() < 400 && src != 1) { h = 3.0; bkind = 5 }
                h = h.coerceIn(2.5, 99.0)
                when (src) { 1 -> srcOsm++; 2 -> srcCop++; else -> srcDef++ }
                buildings.add(Building(poly, h.toFloat(), bkind, src))
            }
        }
        for (b in buildings) op(listOf(b.poly.outer)) { old -> if (old == Cell.WATER || old == Cell.MARSH) Cell.LAND else -1 }
        log("buildings ${buildings.size}: heights from OSM $srcOsm, Copernicus $srcCop, default $srcDef")
    }

    fun collectFeatures() {
        fun inExtent(x: Double, y: Double) = x >= grid.minX && y >= grid.minY && x < grid.minX + grid.w && y < grid.minY + grid.h
        for (n in osm.taggedNodes.values) {
            val t = n.tags
            val type = when {
                t["amenity"] == "bench" || t["leisure"] == "picnic_table" -> FeatureType.BENCH
                t["man_made"] == "water_well" -> FeatureType.WELL
                t["man_made"] == "dolphin" -> FeatureType.BRICOLA
                t["natural"] == "tree" -> FeatureType.TREE
                t["highway"] == "street_lamp" -> FeatureType.LAMP
                t["amenity"] == "ferry_terminal" || (t["public_transport"] == "stop_position" && t["ferry"] == "yes") -> FeatureType.STOP
                t["man_made"] == "flagpole" -> FeatureType.FLAGPOLE
                t["amenity"] == "waste_basket" -> FeatureType.BIN
                t["amenity"] == "drinking_water" -> FeatureType.FOUNTAIN
                else -> 0
            }
            if (type == 0) continue
            val x = Proj.x(n.lon); val y = Proj.y(n.lat)
            if (inExtent(x, y)) points.add(PointFeature(type, x, y, (t["direction"]?.toFloatOrNull() ?: -1f), t["name"] ?: ""))
        }
        for (w in osm.ways.values) {
            val t = w.tags
            // bridges (walkable ways)
            if (t["bridge"] != null && t["bridge"] != "no" && t["highway"] != null) {
                val ln = Assemble.line(osm, w.nodes) ?: continue
                if (!inExtent(ln.first[0], ln.second[0])) continue
                val width = t["width"]?.toFloatOrNull() ?: if (t["highway"] in setOf("primary", "trunk", "secondary", "tertiary", "residential", "service")) 7f else 3.2f
                bridges.add(Bridge(ln.first, ln.second, width, t["highway"] == "steps" || t["step_count"] != null || t["highway"] == "footway" || t["highway"] == "pedestrian", t["name"] ?: ""))
                continue
            }
            if (t["man_made"] == "bridge") { closedWayPoly(w)?.let { bridgeAreas.add(it) }; continue }
            if (t["man_made"] == "pier") {
                if (w.nodes.first() == w.nodes.last()) closedWayPoly(w)?.let { piers.add(it) }
                else Assemble.line(osm, w.nodes)?.let { linePiers.add(LinePier(it.first, it.second, t["width"]?.toFloatOrNull() ?: 2.0f)) }
                continue
            }
            // area features represented by a single point
            val type = when {
                t["man_made"] == "water_well" -> FeatureType.WELL
                t["man_made"] == "dolphin" -> FeatureType.BRICOLA
                else -> 0
            }
            if (type != 0) Assemble.ringFromNodes(osm, w.nodes)?.centroid()?.let { (x, y) -> if (inExtent(x, y)) points.add(PointFeature(type, x, y)) }
        }
        log("points ${points.size} (" + points.groupBy { it.type }.map { "${it.key}:${it.value.size}" }.joinToString() + "), bridges ${bridges.size}, bridge areas ${bridgeAreas.size}, piers ${piers.size} + ${linePiers.size} lines")
    }

    fun report() {
        val counts = IntArray(8)
        for (b in grid.a) counts[b.toInt()]++
        log("cells: water ${counts[0]} land ${counts[1]} green ${counts[2]} sand ${counts[3]} marsh ${counts[4]}")
        var onWater = 0
        for (b in buildings) { val (cx, cy) = b.poly.outer.centroid(); if (grid.atWorld(cx, cy) == Cell.WATER) onWater++ }
        log("buildings with centroid on water: $onWater / ${buildings.size}")
    }

    fun preview(f: File, mPerPx: Double, x0: Double = grid.minX, y0: Double = grid.minY, x1: Double = grid.minX + grid.w, y1: Double = grid.minY + grid.h) {
        val iw = ((x1 - x0) / mPerPx).toInt(); val ih = ((y1 - y0) / mPerPx).toInt()
        val img = BufferedImage(iw, ih, BufferedImage.TYPE_INT_RGB)
        val col = intArrayOf(0x2f6a6a, 0xc9bfae, 0x6f8f4f, 0xe2d3a0, 0x8a9a6a)
        for (py in 0 until ih) for (px in 0 until iw) {
            val v = grid.atWorld(x0 + (px + 0.5) * mPerPx, y1 - (py + 0.5) * mPerPx)
            img.setRGB(px, py, col[v])
        }
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        fun tx(x: Double) = (x - x0) / mPerPx
        fun ty(y: Double) = (y1 - y) / mPerPx
        for (b in buildings) {
            val p = Path2D.Double()
            val r = b.poly.outer
            p.moveTo(tx(r.x[0]), ty(r.y[0])); for (i in 1 until r.n) p.lineTo(tx(r.x[i]), ty(r.y[i])); p.closePath()
            val shade = (110 + min(b.height * 3.5f, 140f)).toInt()
            g.color = if (b.heightSource == 1) Color(shade, shade - 30, shade - 40) else Color(shade, shade - 20, shade - 25)
            g.fill(p)
        }
        g.stroke = BasicStroke(1.2f)
        g.color = Color(200, 40, 40)
        for (br in bridges) for (i in 0 until br.xs.size - 1) g.drawLine(tx(br.xs[i]).toInt(), ty(br.ys[i]).toInt(), tx(br.xs[i + 1]).toInt(), ty(br.ys[i + 1]).toInt())
        g.color = Color(120, 70, 30)
        for (lp in linePiers) for (i in 0 until lp.xs.size - 1) g.drawLine(tx(lp.xs[i]).toInt(), ty(lp.ys[i]).toInt(), tx(lp.xs[i + 1]).toInt(), ty(lp.ys[i + 1]).toInt())
        for (p in points) if (p.type == FeatureType.BRICOLA) { g.color = Color(70, 40, 20); g.fillRect(tx(p.x).toInt(), ty(p.y).toInt(), 1, 1) }
        g.dispose()
        ImageIO.write(img, "png", f)
        log("preview ${f.name} ${iw}x$ih")
    }
}
