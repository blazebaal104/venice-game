// OpenStreetMap XML reader (JDK StAX only, no external libraries).
package converter

import java.io.File
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

class OsmWay(val id: Long, val nodes: LongArray, val tags: Map<String, String>)
class OsmMember(val type: String, val ref: Long, val role: String)
class OsmRelation(val id: Long, val members: List<OsmMember>, val tags: Map<String, String>)
class OsmNode(val id: Long, val lat: Double, val lon: Double, val tags: Map<String, String>)

class OsmData {
    // node coordinates for every node (needed for way geometry)
    val nodeLat = HashMap<Long, Double>(4_000_000)
    val nodeLon = HashMap<Long, Double>(4_000_000)
    val taggedNodes = HashMap<Long, OsmNode>()
    val ways = HashMap<Long, OsmWay>()
    val relations = HashMap<Long, OsmRelation>()

    fun load(f: File) {
        val fac = XMLInputFactory.newInstance()
        val r = f.inputStream().buffered(1 shl 20).use { input ->
            val x = fac.createXMLStreamReader(input)
            var curTags = HashMap<String, String>()
            var curNodes = ArrayList<Long>()
            var curMembers = ArrayList<OsmMember>()
            var kind = ""
            var id = 0L
            var lat = 0.0
            var lon = 0.0
            while (x.hasNext()) {
                when (x.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (x.localName) {
                        "node" -> {
                            kind = "node"; id = x.getAttributeValue(null, "id").toLong()
                            lat = x.getAttributeValue(null, "lat").toDouble(); lon = x.getAttributeValue(null, "lon").toDouble()
                            nodeLat[id] = lat; nodeLon[id] = lon
                            curTags = HashMap()
                        }
                        "way" -> { kind = "way"; id = x.getAttributeValue(null, "id").toLong(); curTags = HashMap(); curNodes = ArrayList() }
                        "relation" -> { kind = "relation"; id = x.getAttributeValue(null, "id").toLong(); curTags = HashMap(); curMembers = ArrayList() }
                        "nd" -> curNodes.add(x.getAttributeValue(null, "ref").toLong())
                        "member" -> curMembers.add(OsmMember(x.getAttributeValue(null, "type"), x.getAttributeValue(null, "ref").toLong(), x.getAttributeValue(null, "role") ?: ""))
                        "tag" -> curTags[x.getAttributeValue(null, "k")] = x.getAttributeValue(null, "v")
                    }
                    XMLStreamConstants.END_ELEMENT -> when (x.localName) {
                        "node" -> if (curTags.isNotEmpty()) taggedNodes[id] = OsmNode(id, lat, lon, curTags)
                        "way" -> if (!ways.containsKey(id)) ways[id] = OsmWay(id, curNodes.toLongArray(), curTags)
                        "relation" -> if (!relations.containsKey(id)) relations[id] = OsmRelation(id, curMembers, curTags)
                    }
                }
            }
            x.close()
        }
    }

    fun has(id: Long) = nodeLat.containsKey(id)
}
