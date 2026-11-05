package eu.tiducto.osmreview.rules

import com.geodesk.feature.Feature
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.LineSegment
import org.locationtech.jts.operation.distance.DistanceOp
import kotlin.math.abs
import kotlin.math.atan2

enum class Side { LEFT, RIGHT }

/**
 * Side detection for separately mapped sidewalks. For a sidewalk near a road, take the road segment
 * closest to it and the sign of the cross product between that segment's direction and the vector to
 * the sidewalk's closest point: positive = left, negative = right (Mercator y grows northwards, so
 * the usual counter-clockwise convention holds). Sidewalks that are not roughly parallel to the road
 * (crossings, connectors) are ignored.
 */
object SidewalkSide {
    private const val MAX_ANGLE_DEG = 30.0

    fun sides(road: Feature, sidewalks: List<Feature>): Set<Side> {
        val roadGeom = road.toGeometry()
        return sidewalks.mapNotNull { side(roadGeom, it.toGeometry()) }.toSet()
    }

    fun side(road: Geometry, sidewalk: Geometry): Side? {
        val (onRoad, onSidewalk) = DistanceOp.nearestPoints(road, sidewalk).let { it[0] to it[1] }
        val roadSeg = segmentAt(road, onRoad) ?: return null
        val walkSeg = segmentAt(sidewalk, onSidewalk) ?: return null
        if (!roughlyParallel(roadSeg, walkSeg)) return null
        val cross = cross(roadSeg, onSidewalk)
        return when {
            cross > 0 -> Side.LEFT
            cross < 0 -> Side.RIGHT
            else -> null
        }
    }

    private fun cross(seg: LineSegment, p: Coordinate): Double {
        val dx = seg.p1.x - seg.p0.x
        val dy = seg.p1.y - seg.p0.y
        return dx * (p.y - seg.p0.y) - dy * (p.x - seg.p0.x)
    }

    private fun roughlyParallel(a: LineSegment, b: LineSegment): Boolean {
        var d = Math.toDegrees(abs(angle(a) - angle(b))) % 180.0
        if (d > 90) d = 180 - d
        return d <= MAX_ANGLE_DEG
    }

    private fun angle(s: LineSegment) = atan2(s.p1.y - s.p0.y, s.p1.x - s.p0.x)

    /** The segment of [g] closest to [p]. */
    private fun segmentAt(g: Geometry, p: Coordinate): LineSegment? {
        val cs = g.coordinates
        if (cs.size < 2) return null
        var best: LineSegment? = null
        var bestD = Double.MAX_VALUE
        for (i in 0 until cs.size - 1) {
            val s = LineSegment(cs[i], cs[i + 1])
            if (s.length == 0.0) continue
            val d = s.distance(p)
            if (d < bestD) {
                bestD = d
                best = s
            }
        }
        return best
    }
}
