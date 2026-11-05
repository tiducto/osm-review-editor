package eu.tiducto.osmreview.rules

import com.geodesk.feature.FeatureLibrary
import com.geodesk.geom.Mercator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.prep.PreparedGeometry
import org.locationtech.jts.geom.prep.PreparedGeometryFactory
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

class ClipArea(val name: String, lonLatShape: Geometry, val bufferMeters: Double) {
    private val shape: PreparedGeometry
    private val envelope: Envelope

    init {
        val env = lonLatShape.envelopeInternal
        val maxAbsLat = max(abs(env.minY), abs(env.maxY)).coerceAtMost(85.0)
        val deg = bufferMeters / (METERS_PER_DEGREE * cos(maxAbsLat * PI / 180.0))
        val simplified = if (deg > 0) DouglasPeuckerSimplifier.simplify(lonLatShape, deg / 4) else lonLatShape
        val buffered = if (deg > 0) simplified.buffer(deg, 4) else simplified
        shape = PreparedGeometryFactory.prepare(buffered)
        envelope = buffered.envelopeInternal
    }

    fun contains(lon: Double, lat: Double): Boolean =
        envelope.contains(lon, lat) && shape.covers(factory.createPoint(Coordinate(lon, lat)))

    companion object {
        private const val METERS_PER_DEGREE = 111_320.0
        private val factory = GeometryFactory()

        fun parsePoly(text: String): Geometry {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val outers = ArrayList<Polygon>()
            val holes = ArrayList<Polygon>()
            var i = 1
            while (i < lines.size && lines[i] != "END") {
                val hole = lines[i].startsWith("!")
                i++
                val coords = ArrayList<Coordinate>()
                while (i < lines.size && lines[i] != "END") {
                    val p = lines[i].split(Regex("\\s+"))
                    coords += Coordinate(p[0].toDouble(), p[1].toDouble())
                    i++
                }
                i++
                if (coords.size < 3) continue
                if (coords.first() != coords.last()) coords += Coordinate(coords.first())
                val poly = factory.createPolygon(coords.toTypedArray())
                if (hole) holes += poly else outers += poly
            }
            require(outers.isNotEmpty()) { "No polygon in .poly file" }
            var g: Geometry = factory.createMultiPolygon(outers.toTypedArray()).buffer(0.0)
            holes.forEach { g = g.difference(it) }
            return g
        }

        fun parseGeoJson(text: String): Geometry = lonLat(Json.parseToJsonElement(text))

        private fun lonLat(json: JsonElement): Geometry {
            val o = json.jsonObject
            return when (val type = o["type"]!!.jsonPrimitive.content) {
                "Feature" -> lonLat(o["geometry"]!!)
                "FeatureCollection" -> factory.buildGeometry(o["features"]!!.jsonArray.map(::lonLat)).union()
                "Polygon" -> polygon(o["coordinates"]!!.jsonArray)
                "MultiPolygon" -> factory.createMultiPolygon(o["coordinates"]!!.jsonArray.map { polygon(it.jsonArray) }.toTypedArray())
                else -> throw IllegalArgumentException("Clip area must be a Polygon or MultiPolygon, got $type")
            }
        }

        private fun polygon(rings: JsonArray): Polygon {
            val linear = rings.map { ring ->
                factory.createLinearRing(ring.jsonArray.map { p -> Coordinate(p.jsonArray[0].jsonPrimitive.double, p.jsonArray[1].jsonPrimitive.double) }.toTypedArray())
            }
            return factory.createPolygon(linear.first(), linear.drop(1).toTypedArray())
        }

        fun fromFile(file: Path, bufferMeters: Double): ClipArea {
            val text = file.readText()
            val shape = if (file.name.endsWith(".poly")) parsePoly(text) else parseGeoJson(text)
            return ClipArea(file.name, shape, bufferMeters)
        }

        fun fromMirror(gol: FeatureLibrary, code: String, bufferMeters: Double): ClipArea? {
            val iso = code.uppercase()
            val f = gol.select("a[boundary=administrative][admin_level=2]")
                .firstOrNull { b -> listOf("ISO3166-1", "ISO3166-1:alpha2").any { b.tag(it).trim().uppercase() == iso } }
                ?: return null
            val geom = f.toGeometry().copy()
            Mercator.unproject(geom)
            return ClipArea("mirror:$iso (${f.ref})", geom, bufferMeters)
        }
    }
}
