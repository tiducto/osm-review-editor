package eu.tiducto.osmreview.rules.mapcss

import com.geodesk.feature.Feature
import com.geodesk.feature.FeatureLibrary
import eu.tiducto.osmreview.shared.OsmType
import eu.tiducto.osmreview.shared.mapcss.MapCssElement
import eu.tiducto.osmreview.shared.mapcss.MapCssHost
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

class MapCssCaches(val generation: Long) {
    val islands = ConcurrentHashMap<Long, Int>()

    @Volatile private var regionIndex: RegionIndex? = null

    fun regions(gol: FeatureLibrary): RegionIndex = regionIndex ?: synchronized(this) {
        regionIndex ?: RegionIndex.build(gol).also { regionIndex = it }
    }
}

class GolMapCssHost(private val gol: FeatureLibrary, private val caches: MapCssCaches) : MapCssHost {
    private val highways by lazy { gol.select("w[highway]") }
    private val namedWays by lazy { gol.select("w[name]") }

    override fun call(name: String, element: MapCssElement, args: List<Any?>): Any? {
        val f = (element as? GolElement)?.feature
        return when (name) {
            "waylength" -> f?.takeIf { it.isWay || it.isRelation }?.length()
            "areasize" -> f?.takeIf { it.isArea }?.area()
            "odd_segment" -> f?.let { oddSegment(it, args.firstOrNull()?.toString().orEmpty()) }
            "network_island_size" -> f?.takeIf { it.isWay }?.let { islandSize(it).toDouble() }
            "half_width" -> HalfWidth.metres(element.tags)
            "connected_value" -> f?.let { connectedValue(it, args.getOrNull(0)?.toString().orEmpty(), args.getOrNull(1)?.toString()) }
            "inside" -> inside(element, args.firstOrNull()?.toString().orEmpty())
            "outside" -> !inside(element, args.firstOrNull()?.toString().orEmpty())
            "is_right_hand_traffic" -> isRightHand(element)
            else -> null
        }
    }

    override fun pseudoClass(name: String, element: MapCssElement): Boolean? = when (name) {
        "righthandtraffic" -> isRightHand(element)
        "in-downloaded-area" -> true
        "unconnected" -> element.type == OsmType.NODE && element.parents.none { it.element.type == OsmType.WAY }
        "connection" -> element.type == OsmType.NODE &&
            element.parents.filter { it.element.type == OsmType.WAY }.map { it.element.id }.distinct().size >= 2
        else -> null
    }

    override fun elementsInside(area: MapCssElement): List<MapCssElement> {
        val f = (area as? GolElement)?.feature ?: return emptyList()
        if (!area.isClosed && !f.isArea) return emptyList()
        return runCatching { gol.`in`(f.bounds()).coveredBy(f).asSequence().filter { it != f }.take(MAX_SPATIAL).map(::GolElement).toList() }
            .getOrDefault(emptyList())
    }

    override fun elementsCrossing(element: MapCssElement): List<MapCssElement> {
        val f = (element as? GolElement)?.feature ?: return emptyList()
        if (f.isNode) return emptyList()
        return runCatching { gol.select("w").crossing(f).asSequence().take(MAX_SPATIAL).map(::GolElement).toList() }
            .getOrDefault(emptyList())
    }

    fun inside(element: MapCssElement, codes: String): Boolean {
        val (x, y) = point(element) ?: return false
        val index = caches.regions(gol)
        return codes.split(',').map { it.trim() }.filter { it.isNotEmpty() }.any { index.contains(it, x, y) == true }
    }

    fun isRightHand(element: MapCssElement): Boolean {
        val (x, y) = point(element) ?: return true
        val index = caches.regions(gol)
        return LEFT_HAND.none { index.contains(it, x, y) == true }
    }

    private fun point(element: MapCssElement): Pair<Int, Int>? {
        val f = (element as? GolElement)?.feature ?: return null
        if (f.isNode) return f.x() to f.y()
        val b = f.bounds()
        return ((b.minX().toLong() + b.maxX()) / 2).toInt() to ((b.minY().toLong() + b.maxY()) / 2).toInt()
    }

    fun islandSize(way: Feature): Int {
        caches.islands[way.id()]?.let { return it }
        val seen = HashSet<Long>()
        val queue = ArrayDeque<Feature>()
        seen += way.id()
        queue.addLast(way)
        while (queue.isNotEmpty() && seen.size < ISLAND_CAP) {
            val w = queue.removeFirst()
            for (n in highways.connectedTo(w)) {
                if (seen.add(n.id())) queue.addLast(n)
                if (seen.size >= ISLAND_CAP) break
            }
        }
        val size = if (queue.isEmpty() && seen.size < ISLAND_CAP) seen.size else ISLAND_CAP
        seen.forEach { caches.islands.merge(it, size, ::max) }
        return size
    }

    fun oddSegment(way: Feature, keys: String): String {
        if (!way.isWay) return ""
        val name = way.tag("name")
        if (name.isEmpty()) return ""
        val nodes = way.nodes().toList()
        if (nodes.size < 2) return ""
        val first = nodes.first()
        val last = nodes.last()
        if (first.x() == last.x() && first.y() == last.y()) return ""
        fun neighbours(n: Feature) = namedWays.connectedTo(n).filter { it.id() != way.id() && it.tag("name") == name }
        val a = neighbours(first)
        if (a.isEmpty()) return ""
        val b = neighbours(last)
        if (b.isEmpty()) return ""
        val all = (a + b).distinctBy { it.id() }
        return keys.split(';').map { it.trim() }.filter { it.isNotEmpty() }.filter { k ->
            val values = all.map { it.tag(k) }.toSet()
            values.size == 1 && values.single() != way.tag(k)
        }.joinToString(";")
    }

    fun connectedValue(f: Feature, key: String, highwayList: String?): String {
        if (key.isEmpty() || f.isRelation) return ""
        val classes = highwayList?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        val values = highways.connectedTo(f).asSequence()
            .filter { classes == null || it.tag("highway") in classes }
            .map { it.tag(key) }
            .filter { it.isNotEmpty() }
            .toSet()
        return values.singleOrNull().orEmpty()
    }

    companion object {
        const val ISLAND_CAP = 100
        const val MAX_SPATIAL = 500
        val LEFT_HAND = listOf("GB", "IE", "MT", "CY", "IM", "JE", "GG")
    }
}

object HalfWidth {
    private val DEFAULT_WIDTH = mapOf(
        "motorway" to 11.0, "motorway_link" to 6.0, "trunk" to 9.0, "trunk_link" to 6.0,
        "primary" to 8.0, "primary_link" to 5.5, "secondary" to 7.0, "secondary_link" to 5.0,
        "tertiary" to 6.5, "tertiary_link" to 5.0, "unclassified" to 5.5, "residential" to 5.5,
        "living_street" to 5.0, "service" to 4.0, "pedestrian" to 5.0, "track" to 3.0, "busway" to 6.5,
        "road" to 5.5, "footway" to 2.0, "cycleway" to 2.0, "path" to 1.5, "steps" to 2.0, "bridleway" to 2.0,
        "corridor" to 2.0, "platform" to 3.0,
    )
    private val LANE_WIDTH = mapOf("motorway" to 3.5, "trunk" to 3.5, "primary" to 3.25, "secondary" to 3.25)
    private val LENGTH = Regex("""^\s*(\d+(?:[.,]\d+)?)\s*(m|km|cm|ft|')?\s*$""")

    fun width(tags: Map<String, String>): Double? {
        tags["width"]?.let(::parseLength)?.let { return it }
        val highway = tags["highway"]
        tags["lanes"]?.trim()?.toIntOrNull()?.takeIf { it in 1..20 }?.let { lanes ->
            val base = highway?.removeSuffix("_link")
            return lanes * (LANE_WIDTH[base] ?: 3.0)
        }
        return highway?.let { DEFAULT_WIDTH[it] }
    }

    fun metres(tags: Map<String, String>): Double? = width(tags)?.let { it / 2 }

    fun parseLength(s: String): Double? {
        val m = LENGTH.matchEntire(s) ?: return null
        val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val metres = when (m.groupValues[2]) {
            "km" -> v * 1000
            "cm" -> v / 100
            "ft", "'" -> v * 0.3048
            else -> v
        }
        return metres.takeIf { it > 0 && it < 200 }
    }
}
