package eu.tiducto.osmreview.server.rulesfolder

import eu.tiducto.osmreview.shared.DiagnosticSeverity
import eu.tiducto.osmreview.shared.DiagnosticSink
import eu.tiducto.osmreview.shared.JsonLocator
import eu.tiducto.osmreview.shared.RuleDiagnostic
import eu.tiducto.osmreview.shared.RuleKind
import eu.tiducto.osmreview.shared.RuleSource
import eu.tiducto.osmreview.shared.presets.ParsedPresetFile
import eu.tiducto.osmreview.shared.presets.PresetFiles
import eu.tiducto.osmreview.shared.presets.PresetIndex
import eu.tiducto.osmreview.shared.presets.SelectorValidator
import eu.tiducto.osmreview.shared.presets.SmartPreset
import eu.tiducto.osmreview.shared.presets.SmartPresets
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.net.URI

data class RuleLoadResult<out T : Any>(val value: T?, val diagnostics: List<RuleDiagnostic>)

data class LoadedRuleFile(
    val path: String,
    val kind: RuleKind,
    val source: String,
    val value: Any?,
    val diagnostics: List<RuleDiagnostic>,
    val remote: Boolean,
    val hash: String = "",
)

interface RuleLoader<T : Any> {
    val kind: RuleKind

    fun load(file: String, source: String): RuleLoadResult<T>

    fun crossCheck(files: List<LoadedRuleFile>): Map<String, List<RuleDiagnostic>> = emptyMap()
}

class PlainTextLoader(override val kind: RuleKind) : RuleLoader<String> {
    override fun load(file: String, source: String) = RuleLoadResult(source, emptyList())
}

class PresetFileLoader(private val base: () -> PresetIndex) : RuleLoader<ParsedPresetFile> {
    override val kind = RuleKind.PRESET

    override fun load(file: String, source: String): RuleLoadResult<ParsedPresetFile> {
        val parsed = PresetFiles.parse(file, source)
        return RuleLoadResult(parsed, parsed.diagnostics)
    }

    override fun crossCheck(files: List<LoadedRuleFile>): Map<String, List<RuleDiagnostic>> =
        PresetFiles.crossCheck(files.mapNotNull { it.value as? ParsedPresetFile }, base())
}

class SmartPresetLoader(private val selectors: SelectorValidator = SelectorValidator.NONE) : RuleLoader<SmartPreset> {
    override val kind = RuleKind.SMART_PRESET

    override fun load(file: String, source: String): RuleLoadResult<SmartPreset> {
        val parsed = SmartPresets.parse(file, source, selectors)
        return RuleLoadResult(parsed.preset, parsed.diagnostics)
    }

    override fun crossCheck(files: List<LoadedRuleFile>): Map<String, List<RuleDiagnostic>> {
        val out = LinkedHashMap<String, List<RuleDiagnostic>>()
        val seen = HashMap<String, String>()
        for (f in files) {
            val p = f.value as? SmartPreset ?: continue
            val first = seen.putIfAbsent(p.id, f.path) ?: continue
            val sink = DiagnosticSink(f.path, f.source)
            sink.error("id", "Duplicate smart preset id '${p.id}', also defined in $first; the first one wins")
            out[f.path] = sink.diagnostics
        }
        return out
    }
}

class SourcesLoader : RuleLoader<List<RuleSource>> {
    override val kind = RuleKind.SOURCES

    override fun load(file: String, source: String): RuleLoadResult<List<RuleSource>> {
        val (root, errors) = JsonLocator.parse(file, source)
        if (root == null) return RuleLoadResult(null, errors)
        val sink = DiagnosticSink(file, source)
        if (root !is JsonArray) {
            sink.error("", "sources.json holds an array of {name, url, kind, refreshHours}")
            return RuleLoadResult(null, sink.diagnostics)
        }
        val names = HashSet<String>()
        val out = root.mapIndexedNotNull { i, e -> one(e, "[$i]", sink, names) }
        return RuleLoadResult(out, sink.diagnostics)
    }

    private fun one(e: kotlinx.serialization.json.JsonElement, path: String, sink: DiagnosticSink, names: MutableSet<String>): RuleSource? {
        if (e !is JsonObject) {
            sink.error(path, "Each source is an object {name, url, kind, refreshHours}")
            return null
        }
        val errorsBefore = sink.diagnostics.count { it.severity == DiagnosticSeverity.ERROR }
        for (k in e.keys) if (k !in KEYS) sink.warning("$path.$k", "Unknown key '$k' is ignored", atKey = true)
        fun str(k: String): String? = when (val v = e[k]) {
            null, JsonNull -> null
            is JsonPrimitive -> if (v.isString) v.content else null.also { sink.error("$path.$k", "Must be a string") }
            else -> null.also { sink.error("$path.$k", "Must be a string") }
        }
        val name = str("name")
        when {
            name == null -> sink.error(path, "Missing \"name\"")
            !NAME.matches(name) -> sink.error("$path.name", "Names use a-z, 0-9, '.', '_' and '-' (at most 64)")
            !names.add(name) -> sink.error("$path.name", "Duplicate source name '$name'")
        }
        val url = str("url")
        when {
            url == null -> sink.error(path, "Missing \"url\"")
            !isHttpUrl(url) -> sink.error("$path.url", "url must be an absolute http(s) URL")
        }
        val kindName = str("kind")
        val kind = kindName?.let { k -> runCatching { RuleKind.fromWireName(k) }.getOrNull() }
        when {
            kindName == null -> sink.error(path, "Missing \"kind\"")
            kind == null || kind == RuleKind.SOURCES -> sink.error("$path.kind", "kind must be highlight, check, preset or smart_preset")
        }
        val hours = when (val v = e["refreshHours"]) {
            null, JsonNull -> 24
            else -> (v as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it in 1..8760 }
                ?: null.also { sink.error("$path.refreshHours", "refreshHours must be a whole number from 1 to 8760") }
        }
        val licence = str("licence")
        val failed = sink.diagnostics.count { it.severity == DiagnosticSeverity.ERROR } > errorsBefore
        if (failed || name == null || url == null || kind == null || kind == RuleKind.SOURCES || hours == null) return null
        return RuleSource(name, url, kind, hours, licence)
    }

    companion object {
        private val KEYS = setOf("name", "url", "kind", "refreshHours", "licence")
        private val NAME = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")

        fun isHttpUrl(s: String): Boolean = runCatching {
            val u = URI(s)
            (u.scheme == "http" || u.scheme == "https") && !u.host.isNullOrBlank()
        }.getOrDefault(false)
    }
}
