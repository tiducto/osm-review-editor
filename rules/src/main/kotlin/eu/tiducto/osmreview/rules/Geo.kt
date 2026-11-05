package eu.tiducto.osmreview.rules

import com.geodesk.feature.Feature
import com.geodesk.geom.Box
import com.geodesk.geom.Mercator
import eu.tiducto.osmreview.shared.BBox
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryCollection
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.LinearRing
import org.locationtech.jts.geom.MultiLineString
import org.locationtech.jts.geom.MultiPoint
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.Polygon
import kotlin.math.round

/** Conversions between GeoDesk's Mercator integer space, WGS84 and GeoJSON. */
object Geo {
    private val factory = GeometryFactory()

    fun box(b: BBox): Box = Box.ofWSEN(b.west, b.south, b.east, b.north)

    fun bbox(b: Box): BBox = BBox(
        Mercator.lonFromX(b.minX().toDouble()), Mercator.latFromY(b.minY().toDouble()),
        Mercator.lonFromX(b.maxX().toDouble()), Mercator.latFromY(b.maxY().toDouble()),
    )

    /** WGS84 GeoJSON geometry for a feature (relations other than areas become a collection). */
    fun featureGeoJson(f: Feature): JsonElement = toGeoJson(f.toGeometry())

    /** Mercator JTS geometry → WGS84 GeoJSON, coordinates rounded to 7 decimals (OSM precision). */
    fun toGeoJson(g: Geometry): JsonElement = when (g) {
        is Point -> obj("Point", pos(g.coordinate))
        is LineString -> obj("LineString", line(g.coordinates))
        is Polygon -> obj("Polygon", poly(g))
        is MultiPoint -> obj("MultiPoint", JsonArray(g.coordinates.map(::pos)))
        is MultiLineString -> obj("MultiLineString", JsonArray(parts(g).map { line((it as LineString).coordinates) }))
        is MultiPolygon -> obj("MultiPolygon", JsonArray(parts(g).map { poly(it as Polygon) }))
        is GeometryCollection -> buildJsonObject {
            put("type", "GeometryCollection")
            put("geometries", JsonArray(parts(g).map(::toGeoJson)))
        }
        else -> error("Unsupported geometry ${g.geometryType}")
    }

    /** WGS84 GeoJSON Polygon/MultiPolygon → Mercator JTS geometry, for scope filters. */
    fun fromGeoJson(json: JsonElement): Geometry {
        val o = json.jsonObject
        val type = o["type"]!!.jsonPrimitive.content
        if (type == "Feature") return fromGeoJson(o["geometry"]!!)
        if (type == "FeatureCollection") {
            return factory.buildGeometry(o["features"]!!.jsonArray.map(::fromGeoJson)).union()
        }
        val c = o["coordinates"]!!.jsonArray
        return when (type) {
            "Polygon" -> polygon(c)
            "MultiPolygon" -> factory.createMultiPolygon(c.map { polygon(it.jsonArray) }.toTypedArray())
            else -> throw IllegalArgumentException("Scope must be a Polygon or MultiPolygon, got $type")
        }
    }

    fun boundsOf(g: Geometry): Box = Box.of(g)

    private fun polygon(rings: JsonArray): Polygon {
        val linear = rings.map { ring -> factory.createLinearRing(ring.jsonArray.map(::mercator).toTypedArray()) }
        return factory.createPolygon(linear.first(), linear.drop(1).toTypedArray<LinearRing>())
    }

    private fun mercator(p: JsonElement): Coordinate {
        val a = p.jsonArray
        return Coordinate(
            Mercator.xFromLon(a[0].jsonPrimitive.double).toDouble(),
            Mercator.yFromLat(a[1].jsonPrimitive.double).toDouble(),
        )
    }

    private fun parts(g: Geometry) = (0 until g.numGeometries).map(g::getGeometryN)

    private fun obj(type: String, coordinates: JsonArray) = buildJsonObject {
        put("type", type)
        put("coordinates", coordinates)
    }

    private fun poly(p: Polygon) = buildJsonArray {
        add(line(p.exteriorRing.coordinates))
        for (i in 0 until p.numInteriorRing) add(line(p.getInteriorRingN(i).coordinates))
    }

    private fun line(cs: Array<Coordinate>) = JsonArray(cs.map(::pos))

    private fun pos(c: Coordinate) = JsonArray(
        listOf(JsonPrimitive(r7(Mercator.lonFromX(c.x))), JsonPrimitive(r7(Mercator.latFromY(c.y)))),
    )

    private fun r7(v: Double) = round(v * 1e7) / 1e7
}
