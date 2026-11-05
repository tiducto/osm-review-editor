package eu.tiducto.osmreview.rules

import com.geodesk.feature.Feature
import com.geodesk.feature.FeatureType
import com.geodesk.geom.Mercator
import eu.tiducto.osmreview.shared.LngLat
import eu.tiducto.osmreview.shared.OsmRef
import eu.tiducto.osmreview.shared.OsmType

val Feature.ref: OsmRef
    get() = OsmRef(
        when (type()) {
            FeatureType.NODE -> OsmType.NODE
            FeatureType.WAY -> OsmType.WAY
            FeatureType.RELATION -> OsmType.RELATION
            else -> error("Unknown feature type ${type()}")
        },
        id(),
    )

/** All tags as strings (GeoDesk returns numbers for numeric values). */
fun Feature.tagMap(): Map<String, String> {
    val out = sortedMapOf<String, String>()
    val t = tags()
    while (t.next()) out[t.key()] = t.stringValue()
    return out
}

/** A way whose first node is its last node. */
fun Feature.isClosedRing(): Boolean {
    if (!isWay) return false
    val xy = toXY()
    return xy.size >= 8 && xy[0] == xy[xy.size - 2] && xy[1] == xy[xy.size - 1]
}

/** Representative point in WGS84: the node itself, or the bbox centre for ways and relations. */
fun Feature.centroid(): LngLat = if (isNode) {
    LngLat(lon(), lat())
} else {
    val b = bounds()
    val x = (b.minX().toLong() + b.maxX()) / 2.0
    val y = (b.minY().toLong() + b.maxY()) / 2.0
    LngLat(Mercator.lonFromX(x), Mercator.latFromY(y))
}
