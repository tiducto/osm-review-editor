package eu.tiducto.osmreview.shared.presets

import eu.tiducto.osmreview.shared.DiagnosticSeverity
import eu.tiducto.osmreview.shared.DiagnosticSink
import eu.tiducto.osmreview.shared.JsonLocator
import eu.tiducto.osmreview.shared.RuleDiagnostic
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

@Serializable
enum class SmartParamType {
    @SerialName("combo")
    COMBO,

    @SerialName("text")
    TEXT,

    @SerialName("number")
    NUMBER,

    @SerialName("check")
    CHECK,
}

@Serializable
data class SmartParam(
    val type: SmartParamType,
    val options: List<String> = emptyList(),
    val default: String? = null,
    val label: String? = null,
)

@Serializable
data class SmartPreset(
    val id: String,
    val name: String,
    val geometry: List<PresetGeometry>,
    val match: String,
    val params: Map<String, SmartParam> = emptyMap(),
    val steps: List<JsonObject>,
    val description: String? = null,
    val names: Map<String, String> = emptyMap(),
) {
    fun localized(locale: String?): SmartPreset =
        if (names.isEmpty()) this else copy(name = LocalizedNames.resolve(names, locale ?: LocalizedNames.FALLBACK) ?: name)
}

data class SelectorProblem(val offset: Int, val severity: DiagnosticSeverity, val message: String)

fun interface SelectorValidator {
    fun validate(selector: String): List<SelectorProblem>

    companion object {
        val NONE = SelectorValidator { emptyList() }
    }
}

data class ParsedSmartPreset(val preset: SmartPreset?, val diagnostics: List<RuleDiagnostic>)

object SmartPresets {
    enum class RefKind { WAY, NODE, RELATION, ANY }

    enum class ArgType { SELECTOR, BIND, REF, REFS, ENUM, TAGS, KEYS, DISTANCE }

    data class Arg(
        val name: String,
        val type: ArgType,
        val required: Boolean = true,
        val values: List<String> = emptyList(),
        val ref: RefKind = RefKind.ANY,
    )

    data class Op(val name: String, val args: List<Arg>, val binds: RefKind? = null, val description: String)

    private val REQUIRE = listOf("one", "any", "optional")

    val OPS: Map<String, Op> = listOf(
        Op(
            "findCrossing",
            listOf(Arg("with", ArgType.SELECTOR), Arg("as", ArgType.BIND), Arg("require", ArgType.ENUM, false, REQUIRE)),
            RefKind.WAY,
            "Ways matching `with` that cross the object without sharing a node",
        ),
        Op(
            "findConnected",
            listOf(Arg("with", ArgType.SELECTOR), Arg("as", ArgType.BIND), Arg("require", ArgType.ENUM, false, REQUIRE)),
            RefKind.WAY,
            "Ways matching `with` that share a node with the object",
        ),
        Op(
            "findNearby",
            listOf(Arg("with", ArgType.SELECTOR), Arg("as", ArgType.BIND), Arg("distance", ArgType.DISTANCE), Arg("require", ArgType.ENUM, false, REQUIRE)),
            RefKind.ANY,
            "Objects matching `with` within `distance`",
        ),
        Op(
            "ensureSharedNode",
            listOf(Arg("ways", ArgType.REFS, ref = RefKind.WAY), Arg("as", ArgType.BIND)),
            RefKind.NODE,
            "The node shared by `ways`, added at their intersection when missing",
        ),
        Op(
            "splitAround",
            listOf(
                Arg("way", ArgType.REF, ref = RefKind.WAY),
                Arg("node", ArgType.REF, ref = RefKind.NODE),
                Arg("at", ArgType.ENUM, false, listOf("nearestNodeOr", "exact")),
                Arg("distance", ArgType.DISTANCE),
                Arg("as", ArgType.BIND),
            ),
            RefKind.WAY,
            "Split `way` at `distance` on both sides of `node` and bind the middle piece",
        ),
        Op(
            "tag",
            listOf(Arg("target", ArgType.REF), Arg("set", ArgType.TAGS, false), Arg("remove", ArgType.KEYS, false)),
            null,
            "Set and remove tags on `target`; values may use {param}",
        ),
    ).associateBy { it.name }

    val KNOWN_KEYS = setOf("id", "name", "description", "geometry", "match", "params", "steps")
    private val PARAM_NAME = Regex("^[a-z_][a-z0-9_]*$")
    private val BIND_NAME = Regex("^[a-z_][A-Za-z0-9_]*$")
    private val ID = Regex("^[A-Za-z0-9_][A-Za-z0-9_/.:-]*$")
    private val PLACEHOLDER = Regex("""\{([^\{\}]*)\}""")
    private val METRES = Regex("""^\d+(\.\d+)?\s*m?$""")
    private val WIDTH_FN = Regex("""^(half_width|width)\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*\)$""")

    fun parse(file: String, text: String, selectors: SelectorValidator = SelectorValidator.NONE): ParsedSmartPreset {
        val (root, parseErrors) = JsonLocator.parse(file, text)
        if (root == null) return ParsedSmartPreset(null, parseErrors)
        val sink = DiagnosticSink(file, text)
        if (root !is JsonObject) {
            sink.error("", "A smart preset file holds one JSON object")
            return ParsedSmartPreset(null, sink.diagnostics)
        }
        for (k in root.keys) if (k !in KNOWN_KEYS) sink.warning(k, "Unknown key '$k' is ignored", atKey = true)

        val id = root.string("id", sink, required = true)
        if (id != null && !ID.matches(id)) sink.error("id", "Invalid id '$id'")
        if (root["name"] == null || root["name"] == JsonNull) sink.error("", "Missing \"name\"")
        val localName = LocalizedNames.parse(root["name"], "name", sink)
        val name = localName?.resolve(LocalizedNames.FALLBACK)
        val description = root.string("description", sink, required = false)

        val geometry = when (val g = root["geometry"]) {
            null -> null.also { sink.error("", "Missing \"geometry\"") }
            is JsonArray -> g.mapIndexedNotNull { i, x ->
                val s = (x as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (s == null) {
                    sink.error("geometry[$i]", "Must be a string")
                    null
                } else {
                    runCatching { PresetGeometry.fromWireName(s) }.getOrElse { sink.error("geometry[$i]", it.message.orEmpty()); null }
                }
            }.also { if (it.isEmpty() && g.isEmpty()) sink.error("geometry", "Give at least one geometry") }
            else -> null.also { sink.error("geometry", "Must be an array such as [\"line\"]") }
        }

        val match = root.string("match", sink, required = true)
        if (match != null) selector(match, "match", sink, selectors)

        val params = parseParams(root["params"], sink)

        val selfKind = when {
            geometry.isNullOrEmpty() -> RefKind.ANY
            geometry.all { it == PresetGeometry.LINE || it == PresetGeometry.AREA } -> RefKind.WAY
            geometry.all { it == PresetGeometry.POINT || it == PresetGeometry.VERTEX } -> RefKind.NODE
            geometry.all { it == PresetGeometry.RELATION } -> RefKind.RELATION
            else -> RefKind.ANY
        }
        val bound = linkedMapOf("self" to selfKind)
        val steps = when (val s = root["steps"]) {
            null -> null.also { sink.error("", "Missing \"steps\"") }
            is JsonArray -> {
                if (s.isEmpty()) sink.error("steps", "Give at least one step")
                s.mapIndexedNotNull { i, step -> checkStep(step, "steps[$i]", sink, selectors, bound, params.keys) }
            }
            else -> null.also { sink.error("steps", "Must be an array of steps") }
        }

        val preset = if (sink.hasErrors || id == null || name == null || geometry == null || match == null || steps == null) {
            null
        } else {
            SmartPreset(id, name, geometry, match, params, steps, description, localName?.names.orEmpty())
        }
        return ParsedSmartPreset(preset, sink.diagnostics)
    }

    private fun parseParams(e: JsonElement?, sink: DiagnosticSink): Map<String, SmartParam> {
        if (e == null || e == JsonNull) return emptyMap()
        if (e !is JsonObject) {
            sink.error("params", "Must be an object of parameter name to definition")
            return emptyMap()
        }
        val out = LinkedHashMap<String, SmartParam>()
        for ((name, def) in e) {
            val path = "params.$name"
            if (!PARAM_NAME.matches(name)) sink.error(path, "Parameter names use a-z, 0-9 and _", atKey = true)
            if (def !is JsonObject) {
                sink.error(path, "Must be an object with type")
                continue
            }
            for (k in def.keys) if (k !in setOf("type", "options", "default", "label")) sink.warning("$path.$k", "Unknown key '$k' is ignored", atKey = true)
            val typeName = def.string("type", sink, required = true, base = path)
            val type = typeName?.let { t -> SmartParamType.entries.firstOrNull { it.name.equals(t, ignoreCase = true) } }
            if (typeName != null && type == null) sink.error("$path.type", "type must be combo, text, number or check")
            val options = when (val o = def["options"]) {
                null -> emptyList()
                is JsonArray -> o.mapIndexedNotNull { i, x ->
                    (x as? JsonPrimitive)?.takeIf { it.isString }?.content ?: null.also { sink.error("$path.options[$i]", "Must be a string") }
                }
                else -> emptyList<String>().also { sink.error("$path.options", "Must be an array of strings") }
            }
            val default = when (val d = def["default"]) {
                null, JsonNull -> null
                is JsonPrimitive -> d.content
                else -> null.also { sink.error("$path.default", "Must be a string, number or boolean") }
            }
            val label = def.string("label", sink, required = false, base = path)
            when (type) {
                SmartParamType.COMBO -> {
                    if (options.isEmpty()) sink.error("$path.options", "A combo parameter needs options")
                    if (default != null && options.isNotEmpty() && default !in options) {
                        sink.error("$path.default", "Default '$default' is not one of the options ${options.joinToString()}")
                    }
                }
                SmartParamType.NUMBER -> if (default != null && default.toDoubleOrNull() == null) sink.error("$path.default", "Default must be a number")
                SmartParamType.CHECK -> if (default != null && default !in setOf("yes", "no", "true", "false")) sink.error("$path.default", "Default must be yes or no")
                SmartParamType.TEXT, null -> Unit
            }
            if (type != null) out[name] = SmartParam(type, options, default, label)
        }
        return out
    }

    private fun checkStep(
        e: JsonElement,
        path: String,
        sink: DiagnosticSink,
        selectors: SelectorValidator,
        bound: MutableMap<String, RefKind>,
        params: Set<String>,
    ): JsonObject? {
        if (e !is JsonObject) {
            sink.error(path, "A step must be an object with \"op\"")
            return null
        }
        val opName = e.string("op", sink, required = true, base = path) ?: return null
        val op = OPS[opName]
        if (op == null) {
            sink.error("$path.op", "Unknown op '$opName'; known ops: ${OPS.keys.joinToString()}")
            return null
        }
        val known = op.args.map { it.name }.toSet() + "op"
        for (k in e.keys) if (k !in known) sink.warning("$path.$k", "Unknown argument '$k' for $opName; it takes ${op.args.joinToString { it.name }}", atKey = true)
        var bindName: String? = null
        for (arg in op.args) {
            val ap = "$path.${arg.name}"
            val v = e[arg.name]
            if (v == null || v == JsonNull) {
                if (arg.required) sink.error(path, "$opName needs \"${arg.name}\"")
                continue
            }
            when (arg.type) {
                ArgType.SELECTOR -> e.string(arg.name, sink, true, path)?.let { selector(it, ap, sink, selectors) }
                ArgType.BIND -> e.string(arg.name, sink, true, path)?.let { n ->
                    when {
                        !BIND_NAME.matches(n) -> sink.error(ap, "Names use letters, digits and _, starting with a lower-case letter")
                        n in bound -> sink.error(ap, "'$n' is already defined")
                        else -> bindName = n
                    }
                }
                ArgType.REF -> e.string(arg.name, sink, true, path)?.let { checkRef(it, arg.ref, ap, sink, bound) }
                ArgType.REFS -> if (v !is JsonArray) {
                    sink.error(ap, "Must be an array of names")
                } else {
                    if (v.size < 2) sink.error(ap, "Give at least two names")
                    v.forEachIndexed { i, x ->
                        val s = (x as? JsonPrimitive)?.takeIf { it.isString }?.content
                        if (s == null) sink.error("$ap[$i]", "Must be a name") else checkRef(s, arg.ref, "$ap[$i]", sink, bound)
                    }
                }
                ArgType.ENUM -> e.string(arg.name, sink, true, path)?.let {
                    if (it !in arg.values) sink.error(ap, "Must be one of ${arg.values.joinToString()}")
                }
                ArgType.TAGS -> if (v !is JsonObject) {
                    sink.error(ap, "Must be an object of tag key to value")
                } else {
                    for ((k, tv) in v) {
                        val s = (tv as? JsonPrimitive)?.takeIf { it.isString }?.content
                        if (k.isBlank()) sink.error("$ap.$k", "Tag keys must not be empty", atKey = true)
                        if (s == null) {
                            sink.error("$ap.$k", "Tag values must be strings")
                            continue
                        }
                        PLACEHOLDER.findAll(s).forEach { m ->
                            val p = m.groupValues[1]
                            if (p !in params) sink.error("$ap.$k", "Unknown parameter {$p}; declared: ${params.joinToString().ifEmpty { "none" }}")
                        }
                    }
                }
                ArgType.KEYS -> if (v !is JsonArray || v.any { (it as? JsonPrimitive)?.isString != true }) sink.error(ap, "Must be an array of tag keys")
                ArgType.DISTANCE -> checkDistance(v, ap, sink, bound)
            }
        }
        if (opName == "tag" && e["set"] == null && e["remove"] == null) sink.error(path, "tag needs \"set\" or \"remove\"")
        bindName?.let { bound[it] = op.binds ?: RefKind.ANY }
        return e
    }

    private fun checkRef(name: String, kind: RefKind, path: String, sink: DiagnosticSink, bound: Map<String, RefKind>) {
        val actual = bound[name]
        when {
            actual == null -> sink.error(path, "Unknown name '$name'; defined so far: ${bound.keys.joinToString()}")
            kind != RefKind.ANY && actual != RefKind.ANY && actual != kind ->
                sink.error(path, "'$name' is a ${actual.name.lowercase()}, expected a ${kind.name.lowercase()}")
        }
    }

    private fun checkDistance(v: JsonElement, path: String, sink: DiagnosticSink, bound: Map<String, RefKind>) {
        val p = v as? JsonPrimitive
        if (p == null) {
            sink.error(path, "Must be metres or half_width(<name>)")
            return
        }
        if (!p.isString) {
            if ((p.doubleOrNull ?: -1.0) < 0) sink.error(path, "Distance must be a non-negative number of metres")
            return
        }
        val s = p.content.trim()
        if (METRES.matches(s)) return
        val m = WIDTH_FN.matchEntire(s)
        if (m == null) {
            sink.error(path, "Distance must be metres (\"2.5\", \"2.5m\") or width(<name>) / half_width(<name>)")
            return
        }
        checkRef(m.groupValues[2], RefKind.WAY, path, sink, bound)
    }

    private fun selector(sel: String, path: String, sink: DiagnosticSink, validator: SelectorValidator) {
        if (sel.isBlank()) {
            sink.error(path, "Selector must not be empty")
            return
        }
        val start = sink.position(path)
        for (problem in validator.validate(sel)) {
            sink.diagnostics += RuleDiagnostic(sink.file, start.line, start.column + 1 + problem.offset, problem.severity, "$path: ${problem.message}")
        }
    }

    private fun JsonObject.string(key: String, sink: DiagnosticSink, required: Boolean, base: String = ""): String? {
        val path = JsonLocator.child(base, key)
        return when (val v = this[key]) {
            null, JsonNull -> null.also { if (required) sink.error(base, "Missing \"$key\"") }
            is JsonPrimitive -> if (v.isString) v.content else null.also { sink.error(path, "Must be a string") }
            else -> null.also { sink.error(path, "Must be a string") }
        }
    }
}
