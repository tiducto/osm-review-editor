package eu.tiducto.osmreview.rules.mapcss

import com.geodesk.feature.FeatureLibrary
import com.geodesk.geom.Box
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.prep.PreparedGeometry
import org.locationtech.jts.geom.prep.PreparedGeometryFactory

class RegionIndex(private val regions: Map<String, List<Region>>) {
    class Region(val code: String, val bounds: Box, val shape: PreparedGeometry)

    val codes: Set<String> get() = regions.keys

    fun knows(code: String): Boolean = code.uppercase() in regions

    fun contains(code: String, x: Int, y: Int): Boolean? {
        val list = regions[code.uppercase()] ?: return null
        return list.any { r -> r.bounds.contains(x, y) && r.shape.covers(points.createPoint(Coordinate(x.toDouble(), y.toDouble()))) }
    }

    companion object {
        private val points = GeometryFactory()
        val CODE_KEYS = listOf("ISO3166-1", "ISO3166-1:alpha2", "ISO3166-2")
        val QUERY = CODE_KEYS.joinToString(", ") { "a[boundary=administrative][\"$it\"]" }

        fun build(gol: FeatureLibrary): RegionIndex {
            val out = HashMap<String, MutableList<Region>>()
            for (f in gol.select(QUERY)) {
                val codes = CODE_KEYS.map { f.tag(it).trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
                if (codes.isEmpty()) continue
                val shape = runCatching { PreparedGeometryFactory.prepare(f.toGeometry()) }.getOrNull() ?: continue
                val bounds = Box(f.bounds())
                codes.forEach { c -> out.getOrPut(c) { mutableListOf() } += Region(c, bounds, shape) }
            }
            return RegionIndex(out)
        }
    }
}
