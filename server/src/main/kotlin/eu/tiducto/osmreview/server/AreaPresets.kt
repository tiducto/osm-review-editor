package eu.tiducto.osmreview.server

import eu.tiducto.osmreview.rules.MirrorQueries
import eu.tiducto.osmreview.shared.Area
import eu.tiducto.osmreview.shared.BBox
import eu.tiducto.osmreview.shared.WireJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.readText

data class AreaDefinition(val name: String, val area: Area?)

class AreaPresets(private val definitions: List<AreaDefinition>, private val queries: MirrorQueries) {
    constructor(config: Config, queries: MirrorQueries) : this(load(config.defaultAreasFile), queries)

    fun find(name: String): Area? = definitions.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }?.area

    fun presets(): List<Area> = definitions.mapNotNull { d ->
        d.area?.copy(geometry = null) ?: runCatching { queries.area(d.name)?.first?.copy(geometry = null) }.getOrNull()
    }

    companion object {
        private val log = LoggerFactory.getLogger(AreaPresets::class.java)
        val DEFAULT_NAMES = listOf("Opava", "Opava-Kateřinky", "Brno", "Brno-střed", "Ostrava", "Olomouc", "Praha")
        private val AREA_TYPES = setOf("Polygon", "MultiPolygon")

        fun load(file: Path?): List<AreaDefinition> {
            val fromFile = file?.let { f ->
                runCatching { parse(f.readText()) }
                    .onSuccess { log.info("Loaded {} area presets from {}", it.size, f) }
                    .onFailure { log.warn("Ignoring DEFAULT_AREAS {}: {}", f, it.message) }
                    .getOrNull()
            }.orEmpty()
            val names = fromFile.map { it.name.lowercase() }.toSet()
            return fromFile + DEFAULT_NAMES.filter { it.lowercase() !in names }.map { AreaDefinition(it, null) }
        }

        fun parse(text: String): List<AreaDefinition> {
            val root = WireJson.parseToJsonElement(text) as? JsonObject
                ?: throw IllegalArgumentException("not a GeoJSON object")
            require((root["type"] as? JsonPrimitive)?.contentOrNull == "FeatureCollection") { "not a GeoJSON FeatureCollection" }
            val features = root["features"] as? JsonArray ?: throw IllegalArgumentException("FeatureCollection has no features array")
            val seen = HashSet<String>()
            return features.mapNotNull { f ->
                val feature = f as? JsonObject ?: return@mapNotNull null
                val name = ((feature["properties"] as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull?.trim()
                if (name.isNullOrEmpty() || !seen.add(name.lowercase())) return@mapNotNull null
                AreaDefinition(name, areaOf(name, feature["geometry"]))
            }
        }

        private fun areaOf(name: String, geometry: JsonElement?): Area? {
            val g = geometry as? JsonObject ?: return null
            val type = (g["type"] as? JsonPrimitive)?.contentOrNull
            if (type !in AREA_TYPES) {
                if (geometry !is JsonNull) log.warn("Area preset '{}' has a {} geometry; using the mirror boundary", name, type)
                return null
            }
            val bbox = bboxOf(g["coordinates"]) ?: return null
            return Area(name, bbox, g)
        }

        private fun bboxOf(coordinates: JsonElement?): BBox? {
            var w = Double.POSITIVE_INFINITY
            var s = Double.POSITIVE_INFINITY
            var e = Double.NEGATIVE_INFINITY
            var n = Double.NEGATIVE_INFINITY
            fun walk(el: JsonElement) {
                if (el !is JsonArray) return
                val x = (el.getOrNull(0) as? JsonPrimitive)?.doubleOrNull
                val y = (el.getOrNull(1) as? JsonPrimitive)?.doubleOrNull
                if (x != null && y != null) {
                    w = minOf(w, x); e = maxOf(e, x); s = minOf(s, y); n = maxOf(n, y)
                } else {
                    el.forEach(::walk)
                }
            }
            coordinates?.let(::walk)
            if (w > e || s > n) return null
            require(w >= -180 && e <= 180 && s >= -90 && n <= 90) { "coordinates out of WGS84 range" }
            return BBox(w, s, e, n)
        }
    }
}
