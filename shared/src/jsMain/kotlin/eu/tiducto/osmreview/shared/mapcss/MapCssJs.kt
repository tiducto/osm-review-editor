@file:Suppress("UnsafeCastFromDynamic")

package eu.tiducto.osmreview.shared.mapcss

import eu.tiducto.osmreview.shared.OsmType
import eu.tiducto.osmreview.shared.WireJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

@JsExport
fun mapCssParse(source: String, file: String): dynamic {
    val r = MapCss.parse(source, file)
    val out: dynamic = js("({})")
    out.stylesheet = r.stylesheet
    out.ok = r.stylesheet != null
    out.diagnostics = toPlain(WireJson.encodeToJsonElement(r.diagnostics))
    val sheet = r.stylesheet
    out.meta = if (sheet == null) null else toPlain(WireJson.encodeToJsonElement(sheet.meta.properties))
    out.legend = if (sheet == null) js("[]") else toPlain(WireJson.encodeToJsonElement(sheet.legend()))
    out.ruleCount = sheet?.rules?.size ?: 0
    out.hasChecks = sheet?.hasChecks ?: false
    out.hasStyles = sheet?.hasStyles ?: false
    return out
}

@JsExport
fun mapCssStyles(stylesheet: dynamic, feature: dynamic, host: dynamic): dynamic =
    toPlain(WireJson.encodeToJsonElement(sheet(stylesheet).styles(JsElement(feature), JsHost(host))))

@JsExport
fun mapCssChecks(stylesheet: dynamic, feature: dynamic, host: dynamic): dynamic =
    toPlain(WireJson.encodeToJsonElement(sheet(stylesheet).checks(JsElement(feature), JsHost(host))))

@JsExport
fun mapCssEvaluate(stylesheet: dynamic, feature: dynamic, host: dynamic): dynamic =
    toPlain(WireJson.encodeToJsonElement(sheet(stylesheet).evaluate(JsElement(feature), JsHost(host))))

@JsExport
fun mapCssSelfTest(stylesheet: dynamic): dynamic = toPlain(WireJson.encodeToJsonElement(sheet(stylesheet).selfTest()))

@JsExport
fun mapCssGoqlPrefilter(stylesheet: dynamic): String? = sheet(stylesheet).toGoqlPrefilter()

@JsExport
fun mapCssMapLibreFilter(stylesheet: dynamic, ruleIndex: Int, options: dynamic): dynamic {
    val s = sheet(stylesheet)
    if (ruleIndex < 0 || ruleIndex >= s.rules.size) return null
    return s.toMapLibreFilter(ruleIndex, options(options))?.let { toPlain(it) }
}

@JsExport
fun mapCssRules(stylesheet: dynamic): dynamic {
    val s = sheet(stylesheet)
    val out = kotlinx.serialization.json.buildJsonArray {
        for (rule in s.rules) {
            add(
                kotlinx.serialization.json.buildJsonObject {
                    put("index", kotlinx.serialization.json.JsonPrimitive(rule.index))
                    put("line", kotlinx.serialization.json.JsonPrimitive(rule.line))
                    put("hasStyle", kotlinx.serialization.json.JsonPrimitive(rule.hasStyle))
                    put("isCheck", kotlinx.serialization.json.JsonPrimitive(rule.isCheck))
                    put("selectors", kotlinx.serialization.json.JsonArray(rule.selectors.map { kotlinx.serialization.json.JsonPrimitive(it.text) }))
                    put("style", kotlinx.serialization.json.JsonObject(s.staticStyle(rule.index)))
                },
            )
        }
    }
    return toPlain(out)
}

@JsExport
fun mapCssSelectorToMapLibre(selector: String, options: dynamic): dynamic {
    val (sel, _) = MapCss.parseSelector(selector)
    return sel?.let { MapCss.toMapLibreFilter(it, null, options(options)) }?.let { toPlain(it) }
}

private fun sheet(v: dynamic): Stylesheet {
    val s: Any? = v
    return s as? Stylesheet ?: throw IllegalArgumentException("Not a stylesheet returned by mapCssParse")
}

private fun options(o: dynamic): MapLibreOptions {
    if (o == null) return MapLibreOptions()
    return MapLibreOptions(
        tagPrefix = (o.tagPrefix as? String) ?: "",
        typeProperty = o.typeProperty as? String,
        closedProperty = o.closedProperty as? String,
    )
}

private fun toPlain(e: JsonElement): dynamic = JSON.parse(e.toString())

private fun fromJs(v: dynamic): Any? = when (jsTypeOf(v)) {
    "string" -> v as String
    "number" -> (v as Number).toDouble()
    "boolean" -> v as Boolean
    "bigint" -> v.toString().toDouble()
    "object" -> when {
        v == null -> null
        js("Array").isArray(v) as Boolean -> (v as Array<dynamic>).map { fromJs(it) }
        else -> v.toString()
    }
    else -> null
}

private fun toJs(v: Any?): dynamic = when (v) {
    null -> null
    is List<*> -> v.map { toJs(it) }.toTypedArray()
    else -> v
}

private fun stringMap(obj: dynamic): Map<String, String> {
    if (obj == null) return emptyMap()
    val keys: Array<String> = js("Object.keys")(obj)
    return keys.associateWith { key -> obj[key].toString() }
}

private fun osmType(v: dynamic): OsmType = when (val s = (v as? String)?.lowercase()) {
    "node", "n" -> OsmType.NODE
    "way", "w" -> OsmType.WAY
    "relation", "r" -> OsmType.RELATION
    else -> throw IllegalArgumentException("Unknown feature type '$s'")
}

private class JsElement(val obj: dynamic) : MapCssElement {
    override val type: OsmType = osmType(obj.type)
    override val id: Long = if (obj.id == null) 0L else (fromJs(obj.id) as? Double)?.toLong() ?: 0L
    override val tags: Map<String, String> by lazy { stringMap(obj.tags) }
    override val isClosed: Boolean get() = obj.closed == true
    override val parents: List<MapCssLink> by lazy { links(obj.parents) }
    override val children: List<MapCssLink> by lazy { links(obj.children) }

    private fun links(v: dynamic): List<MapCssLink> {
        val list: dynamic = if (jsTypeOf(v) == "function") v.call(obj) else v
        if (list == null) return emptyList()
        val arr = list as Array<dynamic>
        return arr.mapIndexed { i, l ->
            val target: dynamic = if (l.element != null) l.element else l.feature
            MapCssLink(
                JsElement(target),
                (l.role as? String) ?: "",
                if (l.index == null) i else (l.index as Number).toInt(),
            )
        }
    }
}

private class JsHost(val h: dynamic) : MapCssHost {
    private fun has(name: String): Boolean = h != null && jsTypeOf(h[name]) == "function"

    override fun call(name: String, element: MapCssElement, args: List<Any?>): Any? {
        if (!has("call")) return null
        return fromJs(h.call(name, (element as? JsElement)?.obj, toJs(args)))
    }

    override fun pseudoClass(name: String, element: MapCssElement): Boolean? {
        if (!has("pseudoClass")) return null
        val r: dynamic = h.pseudoClass(name, (element as? JsElement)?.obj)
        return if (jsTypeOf(r) == "boolean") r as Boolean else null
    }

    override fun elementsInside(area: MapCssElement): List<MapCssElement> = elements("inside", area)

    override fun elementsCrossing(element: MapCssElement): List<MapCssElement> = elements("crossing", element)

    private fun elements(fn: String, element: MapCssElement): List<MapCssElement> {
        if (!has(fn)) return emptyList()
        val r: dynamic = h[fn].call(h, (element as? JsElement)?.obj)
        if (r == null) return emptyList()
        return (r as Array<dynamic>).map { JsElement(it) }
    }
}
