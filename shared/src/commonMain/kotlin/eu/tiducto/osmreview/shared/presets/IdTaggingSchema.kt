package eu.tiducto.osmreview.shared.presets

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

data class PresetTexts(
    val name: String? = null,
    val terms: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
)

data class FieldTexts(
    val label: String? = null,
    val placeholder: String? = null,
    val options: Map<String, String> = emptyMap(),
)

data class PresetTranslations(
    val locale: String,
    val presets: Map<String, PresetTexts>,
    val fields: Map<String, FieldTexts>,
    val categories: Map<String, String>,
)

object IdTaggingSchema {
    const val PRESETS = "presets.json"
    const val FIELDS = "fields.json"
    const val CATEGORIES = "preset_categories.json"
    const val DEFAULTS = "preset_defaults.json"
    const val DEPRECATED = "deprecated.json"
    const val DISCARDED = "discarded.json"
    val LOCALES = listOf("en", "cs")

    fun translationFile(locale: String) = "translations/$locale.json"

    val files: List<String> get() = listOf(PRESETS, FIELDS, CATEGORIES, DEFAULTS, DEPRECATED, DISCARDED) + LOCALES.map(::translationFile)

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun presets(text: String): List<Preset> = json.parseToJsonElement(text).jsonObject.map { (id, value) ->
        val obj = value.jsonObject
        val geometry = (obj["geometry"] as? JsonArray)?.mapNotNull { g ->
            (g as? JsonPrimitive)?.contentOrNull?.let { runCatching { PresetGeometry.fromWireName(it) }.getOrNull() }
        }
        val cleaned = JsonObject(obj - "geometry" + ("id" to JsonPrimitive(id)))
        json.decodeFromJsonElement(Preset.serializer(), cleaned).copy(geometry = geometry)
    }

    fun fields(text: String): List<PresetField> = json.parseToJsonElement(text).jsonObject.map { (id, value) ->
        val obj = value.jsonObject
        PresetField(
            id = id,
            key = obj.string("key"),
            keys = obj.strings("keys"),
            type = obj.string("type") ?: "text",
            options = obj.strings("options"),
            universal = (obj["universal"] as? JsonPrimitive)?.contentOrNull == "true",
            default = obj.string("default"),
        )
    }

    fun translations(text: String): PresetTranslations {
        val root = json.parseToJsonElement(text).jsonObject
        val (locale, body) = root.entries.first()
        val presets = body.jsonObject["presets"]?.jsonObject ?: JsonObject(emptyMap())
        return PresetTranslations(
            locale = locale,
            presets = (presets["presets"] as? JsonObject).orEmpty().mapValues { (_, v) ->
                val o = v as? JsonObject ?: JsonObject(emptyMap())
                PresetTexts(o.string("name"), o.textList("terms", ','), o.textList("aliases", '\n'))
            },
            fields = (presets["fields"] as? JsonObject).orEmpty().mapValues { (_, v) ->
                val o = v as? JsonObject ?: JsonObject(emptyMap())
                FieldTexts(
                    label = o.string("label"),
                    placeholder = o.string("placeholder"),
                    options = (o["options"] as? JsonObject).orEmpty().mapNotNull { (k, ov) ->
                        val label = when (ov) {
                            is JsonPrimitive -> ov.contentOrNull
                            is JsonObject -> ov.string("title")
                            else -> null
                        }
                        label?.let { k to it }
                    }.toMap(),
                )
            },
            categories = (presets["categories"] as? JsonObject).orEmpty().mapNotNull { (k, v) ->
                (v as? JsonObject)?.string("name")?.let { k to it }
            }.toMap(),
        )
    }

    private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

    private fun JsonObject.textList(key: String, separator: Char): List<String> = when (val v = this[key]) {
        is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> v.contentOrNull?.split(separator)?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        else -> emptyList()
    }
}
