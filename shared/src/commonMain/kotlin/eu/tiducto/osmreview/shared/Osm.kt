package eu.tiducto.osmreview.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class OsmType(val letter: Char) {
    @SerialName("node")
    NODE('n'),

    @SerialName("way")
    WAY('w'),

    @SerialName("relation")
    RELATION('r'),
    ;

    /** The lowercase name OSM API v0.6 uses in paths and XML (`node`, `way`, `relation`). */
    val apiName: String get() = name.lowercase()

    companion object {
        fun fromLetter(c: Char): OsmType = entries.firstOrNull { it.letter == c.lowercaseChar() }
            ?: throw IllegalArgumentException("Unknown OSM type letter '$c'")

        fun fromApiName(name: String): OsmType = entries.firstOrNull { it.apiName == name.lowercase() }
            ?: throw IllegalArgumentException("Unknown OSM type '$name'")
    }
}

/** A typed OSM object reference. Its string form is the JOSM one: `n123`, `w45`, `r6`. */
@Serializable
data class OsmRef(val type: OsmType, val id: Long) {
    override fun toString(): String = "${type.letter}$id"

    companion object {
        private val PATTERN = Regex("""^([nwrNWR])(-?\d+)$""")

        fun parse(s: String): OsmRef {
            val m = PATTERN.matchEntire(s.trim()) ?: throw IllegalArgumentException("Not an OSM ref: '$s'")
            return OsmRef(OsmType.fromLetter(m.groupValues[1][0]), m.groupValues[2].toLong())
        }
    }
}

/** WGS84 point, GeoJSON order. */
@Serializable
data class LngLat(val lng: Double, val lat: Double)

/** WGS84 bounding box. */
@Serializable
data class BBox(val west: Double, val south: Double, val east: Double, val north: Double) {
    init {
        require(west <= east && south <= north) { "Invalid bbox: $west,$south,$east,$north" }
    }
}
