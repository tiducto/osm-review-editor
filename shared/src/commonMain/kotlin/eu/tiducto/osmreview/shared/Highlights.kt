package eu.tiducto.osmreview.shared

import eu.tiducto.osmreview.shared.mapcss.LegendEntry
import eu.tiducto.osmreview.shared.mapcss.MapLibreOptions
import eu.tiducto.osmreview.shared.mapcss.StyleResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class TileProperties(
    val tagPrefix: String = "",
    val typeProperty: String = "\$type",
    val closedProperty: String = "\$closed",
    val idProperty: String = "\$id",
) {
    fun mapLibreOptions(): MapLibreOptions = MapLibreOptions(tagPrefix, typeProperty, closedProperty)

    companion object {
        val DEFAULT = TileProperties()
    }
}

@Serializable
data class HighlightRule(
    val index: Int,
    val line: Int,
    val selectors: List<String>,
    val legend: String? = null,
    val color: String? = null,
    val compiled: Boolean,
    val filter: JsonElement? = null,
)

@Serializable
data class HighlightFile(
    val path: String,
    val hash: String,
    val title: String,
    val description: String? = null,
    val dimOthers: Boolean = false,
    val stats: String? = null,
    val legend: List<LegendEntry> = emptyList(),
    val rules: List<HighlightRule> = emptyList(),
    val allCompiled: Boolean = false,
    val goql: String? = null,
    val valid: Boolean = true,
    val readOnly: Boolean = false,
    val diagnostics: List<RuleDiagnostic> = emptyList(),
)

@Serializable
data class HighlightList(val tileProperties: TileProperties, val files: List<HighlightFile>)

@Serializable
data class HighlightFeature(val ref: OsmRef, val style: StyleResult)

@Serializable
data class HighlightEvaluation(
    val path: String,
    val hash: String,
    val bbox: BBox,
    val tile: String? = null,
    val features: List<HighlightFeature>,
    val truncated: Boolean = false,
    val candidates: Int = 0,
    val tookMs: Long = 0,
    val cached: Boolean = false,
)

@Serializable
data class CoverageEntry(
    val label: String,
    val color: String? = null,
    val rule: Int = 0,
    val count: Int,
    val lengthKm: Double = 0.0,
    val areaKm2: Double = 0.0,
    val percent: Double = 0.0,
)

@Serializable
data class CoverageStats(
    val path: String,
    val title: String,
    val stats: String,
    val scope: String,
    val areaName: String? = null,
    val scopeAreaKm2: Double,
    val entries: List<CoverageEntry>,
    val total: CoverageEntry,
    val candidates: Int,
    val tookMs: Long,
)

@Serializable
data class HighlightStatsRequest(
    val path: String,
    val area: String? = null,
    val bbox: BBox? = null,
    val polygon: JsonElement? = null,
)
