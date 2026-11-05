package eu.tiducto.osmreview.shared

object HalfWidth {
    const val DEFAULT_LANE_WIDTH = 3.0

    val DEFAULT_WIDTH: Map<String, Double> = mapOf(
        "motorway" to 11.0, "motorway_link" to 6.0, "trunk" to 9.0, "trunk_link" to 6.0,
        "primary" to 8.0, "primary_link" to 5.5, "secondary" to 7.0, "secondary_link" to 5.0,
        "tertiary" to 6.5, "tertiary_link" to 5.0, "unclassified" to 5.5, "residential" to 5.5,
        "living_street" to 5.0, "service" to 4.0, "pedestrian" to 5.0, "track" to 3.0, "busway" to 6.5,
        "road" to 5.5, "footway" to 2.0, "cycleway" to 2.0, "path" to 1.5, "steps" to 2.0, "bridleway" to 2.0,
        "corridor" to 2.0, "platform" to 3.0,
    )

    val LANE_WIDTH: Map<String, Double> = mapOf("motorway" to 3.5, "trunk" to 3.5, "primary" to 3.25, "secondary" to 3.25)

    private val LENGTH = Regex("""^\s*(\d+(?:[.,]\d+)?)\s*(m|km|cm|ft|')?\s*$""")

    fun width(tags: Map<String, String>): Double? {
        tags["width"]?.let(::parseLength)?.let { return it }
        val highway = tags["highway"]
        tags["lanes"]?.trim()?.toIntOrNull()?.takeIf { it in 1..20 }?.let { lanes ->
            val base = highway?.removeSuffix("_link")
            return lanes * (LANE_WIDTH[base] ?: DEFAULT_LANE_WIDTH)
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
