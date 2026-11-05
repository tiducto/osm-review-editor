package eu.tiducto.osmreview.rules

import com.geodesk.geom.Mercator
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.io.WKBWriter
import org.locationtech.jts.operation.valid.IsValidOp
import org.locationtech.jts.simplify.TopologyPreservingSimplifier
import java.security.MessageDigest
import java.util.Locale

class InvalidScopeException(message: String) : IllegalArgumentException(message)

data class PolygonScope(val scope: Scope, val key: String, val geoJson: JsonElement)

object PolygonScopes {
    const val MAX_VERTICES = 20_000
    const val MAX_SPAN_DEGREES = 10.0
    const val MAX_LAT = 85.0

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): PolygonScope = parse(parseJson(text))

    fun parseJson(text: String): JsonElement = try {
        json.parseToJsonElement(text)
    } catch (e: SerializationException) {
        throw InvalidScopeException("polygon is not valid JSON: ${e.message?.lineSequence()?.firstOrNull()}")
    }

    fun parse(element: JsonElement): PolygonScope {
        val geometryJson = unwrap(element)
        checkCoordinates(geometryJson)
        val geom = try {
            Geo.fromGeoJson(geometryJson)
        } catch (e: IllegalArgumentException) {
            throw InvalidScopeException("polygon is not a valid GeoJSON Polygon: ${e.message}")
        }
        val error = IsValidOp(geom).validationError
        if (error != null) {
            val at = error.coordinate?.let { " near ${lonLat(it.x, it.y)}" }.orEmpty()
            throw InvalidScopeException("polygon is invalid: ${error.message}$at")
        }
        if (geom.isEmpty || geom.area <= 0.0) throw InvalidScopeException("polygon has no area")
        val bounds = Geo.boundsOf(geom)
        return PolygonScope(Scope(bounds, geom), "polygon:${hash(geom)}", Geo.toGeoJson(geom))
    }

    fun fromAreaGeometry(geometry: JsonElement?): Geometry? {
        val o = geometry as? JsonObject ?: return null
        val type = (o["type"] as? JsonPrimitive)?.contentOrNull
        if (type != "Polygon" && type != "MultiPolygon") return null
        return runCatching { Geo.fromGeoJson(o) }.getOrNull()?.takeIf { it is Polygon || it is MultiPolygon }
    }

    fun hash(g: Geometry): String {
        val normalized = g.copy().also { it.normalize() }
        val digest = MessageDigest.getInstance("SHA-256").digest(WKBWriter().write(normalized))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    fun simplifiedGeoJson(g: Geometry, maxPoints: Int = 2000): JsonElement {
        var out = g
        var tolerance = 500.0
        while (out.numPoints > maxPoints && tolerance < 1e8) {
            out = TopologyPreservingSimplifier.simplify(g, tolerance)
            tolerance *= 4
        }
        return Geo.toGeoJson(out)
    }

    private fun unwrap(element: JsonElement): JsonObject {
        val o = element as? JsonObject ?: throw InvalidScopeException("polygon must be a GeoJSON object")
        return when (val type = (o["type"] as? JsonPrimitive)?.contentOrNull) {
            "Feature" -> unwrap(o["geometry"] ?: throw InvalidScopeException("polygon Feature has no geometry"))
            "FeatureCollection" -> {
                val features = (o["features"] as? JsonArray).orEmpty()
                if (features.size != 1) throw InvalidScopeException("polygon FeatureCollection must hold exactly one feature, got ${features.size}")
                unwrap(features.single())
            }
            "Polygon", "MultiPolygon" -> o
            null -> throw InvalidScopeException("polygon has no GeoJSON type")
            else -> throw InvalidScopeException("polygon must be a GeoJSON Polygon or MultiPolygon, got $type")
        }
    }

    private fun checkCoordinates(o: JsonObject) {
        val coordinates = o["coordinates"] as? JsonArray ?: throw InvalidScopeException("polygon has no coordinates array")
        val polygons = if (o["type"]!!.let { (it as JsonPrimitive).content } == "Polygon") listOf(coordinates) else coordinates.map { it.asArray("polygon") }
        if (polygons.isEmpty()) throw InvalidScopeException("polygon has no rings")
        var vertices = 0
        var west = Double.MAX_VALUE
        var east = -Double.MAX_VALUE
        var south = Double.MAX_VALUE
        var north = -Double.MAX_VALUE
        for (rings in polygons) {
            val ringList = rings.asArray("polygon")
            if (ringList.isEmpty()) throw InvalidScopeException("polygon has no rings")
            ringList.forEachIndexed { r, ringJson ->
                val ring = ringJson.asArray("ring").map { pos ->
                    val p = pos.asArray("position")
                    val lon = (p.getOrNull(0) as? JsonPrimitive)?.doubleOrNull
                    val lat = (p.getOrNull(1) as? JsonPrimitive)?.doubleOrNull
                    if (lon == null || lat == null) throw InvalidScopeException("polygon positions must be [lon, lat] numbers, got $p")
                    if (lon !in -180.0..180.0 || lat !in -MAX_LAT..MAX_LAT) {
                        throw InvalidScopeException("polygon position [$lon, $lat] is outside WGS84 lon/lat range; give [lon, lat] in degrees")
                    }
                    west = minOf(west, lon)
                    east = maxOf(east, lon)
                    south = minOf(south, lat)
                    north = maxOf(north, lat)
                    lon to lat
                }
                if (ring.size < 4) throw InvalidScopeException("polygon ring ${r + 1} needs at least 4 positions, got ${ring.size}")
                if (ring.first() != ring.last()) throw InvalidScopeException("polygon ring ${r + 1} is not closed (first and last position differ)")
                vertices += ring.size
                if (vertices > MAX_VERTICES) throw InvalidScopeException("polygon has more than $MAX_VERTICES vertices; simplify it")
            }
        }
        if (east - west > MAX_SPAN_DEGREES || north - south > MAX_SPAN_DEGREES) {
            throw InvalidScopeException(
                String.format(
                    Locale.ROOT,
                    "polygon spans %.2f° x %.2f°; the limit is %.0f° in each direction (use a named area for a whole country)",
                    east - west, north - south, MAX_SPAN_DEGREES,
                ),
            )
        }
    }

    private fun JsonElement.asArray(what: String): JsonArray = this as? JsonArray ?: throw InvalidScopeException("polygon $what must be an array")

    private fun lonLat(x: Double, y: Double) = String.format(Locale.ROOT, "[%.6f, %.6f]", Mercator.lonFromX(x), Mercator.latFromY(y))
}

fun Scope.containsLonLat(lon: Double, lat: Double): Boolean {
    val x = Mercator.xFromLon(lon)
    val y = Mercator.yFromLat(lat)
    if (x !in bounds.minX()..bounds.maxX() || y !in bounds.minY()..bounds.maxY()) return false
    val p = prepared ?: return true
    return p.covers(pointFactory.createPoint(Coordinate(x.toDouble(), y.toDouble())))
}

private val pointFactory = GeometryFactory()
