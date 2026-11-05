package eu.tiducto.osmreview.shared.presets

import eu.tiducto.osmreview.shared.DiagnosticSink
import eu.tiducto.osmreview.shared.JsonLocator
import eu.tiducto.osmreview.shared.RuleDiagnostic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

data class ParsedPreset(val preset: Preset, val path: String)

data class ParsedPresetFile(
    val file: String,
    val text: String,
    val presets: List<ParsedPreset>,
    val diagnostics: List<RuleDiagnostic>,
)

object PresetFiles {
    val KNOWN_KEYS = setOf(
        "id", "name", "geometry", "tags", "addTags", "removeTags", "fields", "moreFields", "icon",
        "terms", "aliases", "matchScore", "searchable", "extends", "apply",
    )
    private val ID = Regex("^[A-Za-z0-9_@][A-Za-z0-9_@/.:-]*$")

    private val pretty = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun parse(file: String, text: String): ParsedPresetFile {
        val (root, parseErrors) = JsonLocator.parse(file, text)
        if (root == null) return ParsedPresetFile(file, text, emptyList(), parseErrors)
        val sink = DiagnosticSink(file, text)
        val items: List<Pair<String, JsonElement>> = when (root) {
            is JsonObject -> listOf("" to root)
            is JsonArray -> root.mapIndexed { i, e -> "[$i]" to e }
            else -> {
                sink.error("", "A preset file holds one preset object or an array of preset objects")
                emptyList()
            }
        }
        val presets = items.mapNotNull { (path, e) -> parseOne(e, path, sink)?.let { ParsedPreset(it, path) } }
        return ParsedPresetFile(file, text, presets, sink.diagnostics)
    }

    private fun parseOne(e: JsonElement, path: String, sink: DiagnosticSink): Preset? {
        if (e !is JsonObject) {
            sink.error(path, "A preset must be a JSON object")
            return null
        }
        for (k in e.keys) if (k !in KNOWN_KEYS) {
            sink.warning(JsonLocator.child(path, k), "Unknown key '$k' is ignored; known keys: ${KNOWN_KEYS.joinToString()}", atKey = true)
        }
        fun p(k: String) = JsonLocator.child(path, k)
        fun string(k: String): String? = when (val v = e[k]) {
            null, JsonNull -> null
            is JsonPrimitive -> if (v.isString) v.content else null.also { sink.error(p(k), "Must be a string") }
            else -> null.also { sink.error(p(k), "Must be a string") }
        }
        fun strings(k: String): List<String>? = when (val v = e[k]) {
            null, JsonNull -> null
            is JsonArray -> v.mapIndexedNotNull { i, x ->
                if (x is JsonPrimitive && x.isString) x.content else null.also { sink.error("${p(k)}[$i]", "Must be a string") }
            }
            else -> null.also { sink.error(p(k), "Must be an array of strings") }
        }
        fun tagMap(k: String): Map<String, String>? = when (val v = e[k]) {
            null, JsonNull -> null
            is JsonObject -> v.entries.mapNotNull { (tk, tv) ->
                when {
                    tk.isBlank() -> null.also { sink.error(JsonLocator.child(p(k), tk), "Tag keys must not be empty", atKey = true) }
                    tv is JsonPrimitive && tv.isString -> tk to tv.content
                    else -> null.also { sink.error(JsonLocator.child(p(k), tk), "Tag values must be strings") }
                }
            }.toMap()
            else -> null.also { sink.error(p(k), "Must be an object of tag key to value") }
        }

        val id = string("id")
        if (id == null) {
            if (e["id"] == null) sink.error(path, "Missing \"id\"")
        } else if (!ID.matches(id)) {
            sink.error(p("id"), "Invalid id '$id': use letters, digits and _ @ / . : -")
        }
        val geometry = strings("geometry")?.let { list ->
            if (list.isEmpty()) sink.error(p("geometry"), "Give at least one geometry")
            list.mapIndexedNotNull { i, g ->
                runCatching { PresetGeometry.fromWireName(g) }.getOrElse { sink.error("${p("geometry")}[$i]", it.message.orEmpty()); null }
            }
        }
        val apply = string("apply")?.let { a ->
            PresetApplyMode.entries.firstOrNull { it.name.equals(a, ignoreCase = true) }
                ?: null.also { sink.error(p("apply"), "apply must be merge, replace or create") }
        }
        val matchScore = e["matchScore"]?.takeIf { it != JsonNull }?.let { v ->
            val d = (v as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
            if (d == null || d < 0) sink.error(p("matchScore"), "matchScore must be a non-negative number")
            d
        }
        val searchable = e["searchable"]?.takeIf { it != JsonNull }?.let { v ->
            (v as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: null.also { sink.error(p("searchable"), "searchable must be true or false") }
        }
        val name = string("name")
        if (name != null && name.isBlank()) sink.error(p("name"), "name must not be empty")
        val extends = string("extends")
        val tags = tagMap("tags")
        val addTags = tagMap("addTags")
        if (extends == null) {
            if (name == null) sink.error(path, "A preset without \"extends\" needs a \"name\"")
            if (geometry == null) sink.error(path, "A preset without \"extends\" needs \"geometry\"")
            if (tags == null) sink.error(path, "A preset without \"extends\" needs \"tags\"")
        }
        if (apply == PresetApplyMode.CREATE && addTags == null && tags?.values?.any { it == "*" } == true) {
            sink.warning(p("apply"), "apply=create with wildcard tags creates value 'yes'; give addTags with concrete values")
        }
        return Preset(
            id = id ?: return null,
            name = name,
            geometry = geometry,
            tags = tags,
            addTags = addTags,
            removeTags = tagMap("removeTags"),
            fields = strings("fields"),
            moreFields = strings("moreFields"),
            icon = string("icon"),
            terms = strings("terms"),
            aliases = strings("aliases"),
            matchScore = matchScore,
            searchable = searchable,
            extends = extends,
            apply = apply,
        )
    }

    fun crossCheck(files: List<ParsedPresetFile>, base: PresetIndex): Map<String, List<RuleDiagnostic>> {
        val out = LinkedHashMap<String, MutableList<RuleDiagnostic>>()
        val firstFile = HashMap<String, String>()
        val all = files.flatMap { f -> f.presets.map { f to it } }
        val userIds = all.map { it.second.preset.id }.toSet()
        val index = base.withUser(all.map { UserPreset(it.second.preset, it.first.file) })
        for ((file, parsed) in all) {
            val sink = DiagnosticSink(file.file, file.text)
            val p = parsed.preset
            fun path(k: String) = JsonLocator.child(parsed.path, k)
            fun exists(id: String, self: Boolean) = base.isBase(id) || (id in userIds && !self)
            val previous = firstFile[p.id]
            if (previous != null) {
                sink.error(path("id"), "Duplicate preset id '${p.id}', also defined in $previous; the first one wins")
            } else {
                firstFile[p.id] = file.file
            }
            if (base.isBase(p.id) && p.extends != p.id && previous == null) {
                sink.info(path("id"), "Replaces the iD preset '${p.id}'; use \"extends\": \"${p.id}\" to keep its other keys")
            }
            p.extends?.let { e ->
                if (!exists(e, self = e == p.id)) sink.error(path("extends"), "extends refers to unknown preset '$e'")
            }
            p.name?.let { n -> PresetIndex.referenced(n)?.let { r -> if (!exists(r, r == p.id)) sink.error(path("name"), "Unknown preset reference {$r}") } }
            for (k in listOf("fields", "moreFields")) {
                val list = if (k == "fields") p.fields else p.moreFields
                list?.forEachIndexed { i, f ->
                    val r = PresetIndex.referenced(f)
                    when {
                        r != null && !exists(r, r == p.id) -> sink.error("${path(k)}[$i]", "Unknown preset reference {$r}")
                        r == null && f !in base.fieldsById -> sink.warning("${path(k)}[$i]", "Unknown field '$f' (not in iD's fields.json)")
                    }
                }
            }
            for (k in listOf("terms", "aliases")) {
                val list = if (k == "terms") p.terms else p.aliases
                list?.forEachIndexed { i, t ->
                    PresetIndex.referenced(t)?.let { r -> if (!exists(r, r == p.id)) sink.error("${path(k)}[$i]", "Unknown preset reference {$r}") }
                }
            }
            index.resolveError(p.id)?.let { sink.error(path("extends"), it) }
            if (p.extends != null && index.resolveError(p.id) == null) {
                val resolved = runCatching { index.resolve(p.id) }.getOrNull()
                if (resolved != null && resolved.geometry.isEmpty()) sink.error(parsed.path, "Resolved preset has no geometry")
            }
            if (sink.diagnostics.isNotEmpty()) out.getOrPut(file.file) { mutableListOf() } += sink.diagnostics
        }
        return out
    }

    fun encode(p: Preset): String = pretty.encodeToString(JsonElement.serializer(), toJson(p)) + "\n"

    fun toJson(p: Preset): JsonObject = buildJsonObject {
        put("id", p.id)
        p.extends?.let { put("extends", it) }
        p.name?.let { put("name", it) }
        p.apply?.let { put("apply", it.name.lowercase()) }
        p.geometry?.let { g -> put("geometry", buildJsonArray { g.forEach { add(JsonPrimitive(it.wireName)) } }) }
        p.tags?.let { put("tags", tagsJson(it)) }
        p.addTags?.let { put("addTags", tagsJson(it)) }
        p.removeTags?.let { put("removeTags", tagsJson(it)) }
        p.fields?.let { put("fields", stringsJson(it)) }
        p.moreFields?.let { put("moreFields", stringsJson(it)) }
        p.icon?.let { put("icon", it) }
        p.terms?.let { put("terms", stringsJson(it)) }
        p.aliases?.let { put("aliases", stringsJson(it)) }
        p.matchScore?.let { put("matchScore", it) }
        p.searchable?.let { put("searchable", it) }
    }

    private fun tagsJson(m: Map<String, String>) = JsonObject(m.mapValues { JsonPrimitive(it.value) })

    private fun stringsJson(l: List<String>) = JsonArray(l.map(::JsonPrimitive))

    fun fileNameFor(id: String): String {
        val slug = id.replace(Regex("[^A-Za-z0-9_.-]+"), "-").trim('-', '.').ifEmpty { "preset" }
        return "presets/${slug.take(100)}.json"
    }
}
