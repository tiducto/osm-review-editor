package eu.tiducto.osmreview.shared.mapcss

import eu.tiducto.osmreview.shared.Severity
import eu.tiducto.osmreview.shared.TagEdit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

@Serializable
data class Diagnostic(
    val file: String,
    val line: Int,
    val column: Int,
    val severity: Severity,
    val message: String,
) {
    override fun toString(): String = "$file:$line:$column: ${severity.name.lowercase()}: $message"
}

class ParseResult(val stylesheet: Stylesheet?, val diagnostics: List<Diagnostic>) {
    val hasErrors: Boolean get() = diagnostics.any { it.severity == Severity.ERROR }
}

object MapCss {
    fun parse(source: String, file: String = ""): ParseResult = MapCssParser(source, file, emptySet()).parse()

    fun parse(source: String, file: String, hostFunctions: Set<String>): ParseResult =
        MapCssParser(source, file, hostFunctions).parse()

    fun parseSelector(selector: String, hostFunctions: Set<String> = emptySet()): Pair<Selector?, List<Diagnostic>> =
        MapCssParser(selector, "", hostFunctions).parseSelectorOnly()

    fun isValidatorFile(name: String): Boolean = name.endsWith(".validator.mapcss")

    fun toGoqlPrefilter(selector: Selector, stylesheet: Stylesheet? = null): String? = Goql.forSelector(selector, stylesheet)

    fun toMapLibreFilter(
        selector: Selector,
        stylesheet: Stylesheet? = null,
        options: MapLibreOptions = MapLibreOptions(),
    ): JsonElement? = MapLibre.forSelector(selector, stylesheet, options)
}

@Serializable
data class StylesheetMeta(val properties: Map<String, JsonElement> = emptyMap()) {
    val title: String? get() = string("title")

    val description: String? get() = string("description")

    val dimOthers: Boolean get() = (properties[Vocabulary.DIM_OTHERS] as? JsonPrimitive)?.let { p ->
        p.booleanOrNull ?: p.contentOrNull?.let { it == "true" || it == "yes" }
    } ?: false

    val stats: String? get() = string(Vocabulary.STATS)

    private fun string(key: String): String? = (properties[key] as? JsonPrimitive)?.contentOrNull
}

@Serializable
data class StyleResult(
    val properties: Map<String, JsonElement> = emptyMap(),
    val classes: List<String> = emptyList(),
    val rules: List<Int> = emptyList(),
) {
    val matched: Boolean get() = rules.isNotEmpty()

    val color: String? get() = string("color")

    val width: Double? get() = (properties["width"] as? JsonPrimitive)?.doubleOrNull

    val text: String? get() = string("text")

    val legend: String? get() = string(Vocabulary.LEGEND)

    fun string(key: String): String? = (properties[key] as? JsonPrimitive)?.contentOrNull
}

@Serializable
data class CheckResult(
    val severity: Severity,
    val message: String,
    val group: String? = null,
    val fix: TagEdit? = null,
    val deleteObject: Boolean = false,
    val alternatives: List<String> = emptyList(),
    val file: String = "",
    val line: Int = 0,
    val rule: Int = 0,
)

@Serializable
data class LegendEntry(val label: String, val color: String? = null, val rule: Int = 0)

@Serializable
data class MapLibreOptions(
    val tagPrefix: String = "",
    val typeProperty: String? = null,
    val closedProperty: String? = null,
)
