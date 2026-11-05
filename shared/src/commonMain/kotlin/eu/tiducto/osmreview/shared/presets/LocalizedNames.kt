package eu.tiducto.osmreview.shared.presets

import eu.tiducto.osmreview.shared.DiagnosticSink
import eu.tiducto.osmreview.shared.JsonLocator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class LocalizedName(val text: String?, val names: Map<String, String>) {
    fun resolve(locale: String?): String? = text ?: LocalizedNames.resolve(names, locale)
}

object LocalizedNames {
    const val FALLBACK = "en"
    private val LOCALE = Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$")

    fun isLocale(key: String): Boolean = LOCALE.matches(key)

    fun resolve(names: Map<String, String>, locale: String?): String? {
        if (names.isEmpty()) return null
        val wanted = locale?.trim()?.replace('_', '-').orEmpty()
        val lang = wanted.substringBefore('-')
        fun find(k: String) = names.entries.firstOrNull { it.key.equals(k, ignoreCase = true) }?.value
        return wanted.takeIf { it.isNotEmpty() }?.let(::find)
            ?: lang.takeIf { it.isNotEmpty() }?.let(::find)
            ?: lang.takeIf { it.isNotEmpty() }?.let { l -> names.entries.firstOrNull { it.key.substringBefore('-').equals(l, ignoreCase = true) }?.value }
            ?: find(FALLBACK)
            ?: names.values.first()
    }

    fun parse(e: JsonElement?, path: String, sink: DiagnosticSink): LocalizedName? = when (e) {
        null, JsonNull -> null
        is JsonPrimitive -> if (e.isString) {
            if (e.content.isBlank()) sink.error(path, "name must not be empty")
            LocalizedName(e.content, emptyMap())
        } else {
            null.also { sink.error(path, "Must be a string or an object of locale to name, e.g. {\"en\": \"Crossing\", \"cs\": \"Přechod\"}") }
        }
        is JsonObject -> {
            if (e.isEmpty()) sink.error(path, "Give at least one translation, e.g. {\"en\": \"Crossing\"}")
            val out = LinkedHashMap<String, String>()
            for ((k, v) in e) {
                val p = JsonLocator.child(path, k)
                if (!isLocale(k)) sink.error(p, "Invalid locale '$k'; use a language code like en, cs or cs-CZ", atKey = true)
                val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
                when {
                    s == null -> sink.error(p, "Must be a string")
                    s.isBlank() -> sink.error(p, "name must not be empty")
                    isLocale(k) -> out[k] = s
                }
            }
            if (out.isEmpty()) null else LocalizedName(null, out)
        }
        else -> null.also { sink.error(path, "Must be a string or an object of locale to name, e.g. {\"en\": \"Crossing\", \"cs\": \"Přechod\"}") }
    }
}
