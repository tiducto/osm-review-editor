package eu.tiducto.osmreview.rules

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object DeprecatedTags {
    @Serializable
    data class Entry(val old: Map<String, String>, val replace: Map<String, String>? = null) {
        val isOneToOne: Boolean
            get() = old.size == 1 && old.values.none { it == "*" } &&
                replace != null && replace.size == 1 && replace.values.none { it == "*" || it == "$1" }

        fun matches(tags: Map<String, String>): Boolean = old.all { (k, v) ->
            val have = tags[k] ?: return@all false
            v == "*" || v == have
        }

        fun applyTo(tags: Map<String, String>): Map<String, String> {
            val out = LinkedHashMap(tags)
            var transfer: String? = null
            for ((k, v) in old) {
                val have = out[k] ?: continue
                if (v == "*") transfer = have
                out.remove(k)
            }
            for ((k, v) in replace.orEmpty()) {
                when (v) {
                    "*" -> if (out[k] == null || out[k] == "no") out[k] = "yes"
                    "$1" -> transfer?.let { out[k] = it }
                    else -> out[k] = v
                }
            }
            return out
        }
    }

    val entries: List<Entry> by lazy {
        val text = DeprecatedTags::class.java.getResource("/id-tagging-schema/deprecated.json")!!.readText()
        Json { ignoreUnknownKeys = true }.decodeFromString<List<Entry>>(text)
    }

    val schemaVersion: String by lazy {
        DeprecatedTags::class.java.getResource("/id-tagging-schema/VERSION")?.readText()?.trim() ?: "unknown"
    }

    val goql: String by lazy {
        entries.joinToString(", ") { e ->
            "*" + e.old.entries.joinToString("") { (k, v) -> if (v == "*") "[${q(k)}]" else "[${q(k)}=${q(v)}]" }
        }
    }

    fun upgrade(tags: Map<String, String>): Pair<Map<String, String>, List<Entry>>? {
        var current = tags
        val applied = mutableListOf<Entry>()
        repeat(MAX_PASSES) {
            val e = entries.firstOrNull { it.matches(current) && it !in applied } ?: return@repeat
            current = e.applyTo(current)
            applied += e
        }
        return if (applied.isEmpty() || current == tags) null else current to applied
    }

    private const val MAX_PASSES = 5

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
