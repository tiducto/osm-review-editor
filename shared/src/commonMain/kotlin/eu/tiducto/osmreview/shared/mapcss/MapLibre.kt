package eu.tiducto.osmreview.shared.mapcss

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

internal object MapLibre {
    private const val MAX_DEPTH = 3

    fun forRule(sheet: Stylesheet, rule: Rule, options: MapLibreOptions): JsonElement? {
        val parts = rule.selectors.filter { it.disabledReason == null }.map { compile(it, sheet, rule.index, options, 0) ?: return null }
        return when (parts.size) {
            0 -> JsonPrimitive(false)
            1 -> parts[0]
            else -> arr("any", *parts.toTypedArray())
        }
    }

    fun forSelector(sel: Selector, sheet: Stylesheet?, options: MapLibreOptions): JsonElement? =
        if (sel.disabledReason != null) JsonPrimitive(false) else compile(sel, sheet, Int.MAX_VALUE, options, 0)

    private fun arr(vararg items: Any?): JsonArray = JsonArray(
        items.map {
            when (it) {
                is JsonElement -> it
                null -> kotlinx.serialization.json.JsonNull
                is String -> JsonPrimitive(it)
                is Double -> if (it == kotlin.math.floor(it) && kotlin.math.abs(it) < 1e15) JsonPrimitive(it.toLong()) else JsonPrimitive(it)
                is Number -> JsonPrimitive(it)
                is Boolean -> JsonPrimitive(it)
                is List<*> -> JsonArray(it.map { s -> JsonPrimitive(s as String) })
                else -> JsonPrimitive(it.toString())
            }
        },
    )

    private fun literal(values: List<String>) = arr("literal", values)

    private fun compile(sel: Selector, sheet: Stylesheet?, before: Int, o: MapLibreOptions, depth: Int): JsonElement? {
        if (sel.isLinked) return null
        val s = sel.subject
        val conds = mutableListOf<JsonElement>()
        val t = typeFilter(s.type, o)
        if (t != null) conds += t else if (s.type != SelectorType.ANY) return null
        for (c in s.conditions) conds += condition(c, sheet, before, o, depth) ?: return null
        return when (conds.size) {
            0 -> JsonPrimitive(true)
            1 -> conds[0]
            else -> arr("all", *conds.toTypedArray())
        }
    }

    private fun typeFilter(type: SelectorType, o: MapLibreOptions): JsonElement? {
        val tp = o.typeProperty
        val geometry = arr("geometry-type")
        val notPoint = arr("!", arr("in", geometry, literal(listOf("Point", "MultiPoint"))))
        return when (type) {
            SelectorType.ANY -> null
            SelectorType.NODE -> if (tp != null) arr("==", arr("get", tp), "node") else arr("in", geometry, literal(listOf("Point", "MultiPoint")))
            SelectorType.WAY -> if (tp != null) arr("==", arr("get", tp), "way") else notPoint
            SelectorType.AREA -> if (tp != null) {
                arr(
                    "any",
                    arr("==", arr("get", tp), "way"),
                    arr("all", arr("==", arr("get", tp), "relation"), arr("in", get("type", o), literal(listOf("multipolygon", "boundary")))),
                )
            } else {
                notPoint
            }
            SelectorType.RELATION -> if (tp != null) arr("==", arr("get", tp), "relation") else null
            SelectorType.CANVAS, SelectorType.META -> null
        }
    }

    private fun get(key: String, o: MapLibreOptions) = arr("get", o.tagPrefix + key)

    private fun has(key: String, o: MapLibreOptions) = arr("has", o.tagPrefix + key)

    private fun condition(c: Condition, sheet: Stylesheet?, before: Int, o: MapLibreOptions, depth: Int): JsonElement? = when (c) {
        is TagCondition -> tag(c, o)
        is ClassCondition -> {
            val setters = classFilter(c.name, sheet, before, o, depth)
            when {
                setters == null -> null
                c.negated -> arr("!", setters)
                else -> setters
            }
        }
        is PseudoCondition -> when (c.pseudo) {
            PseudoClass.CLOSED -> o.closedProperty?.let { p ->
                val e = arr("==", arr("get", p), true)
                if (c.negated) arr("!", e) else e
            }
            PseudoClass.ALWAYS -> JsonPrimitive(!c.negated)
            PseudoClass.NEVER -> JsonPrimitive(c.negated)
            else -> null
        }
        is ExpressionCondition -> null
    }

    private fun classFilter(name: String, sheet: Stylesheet?, before: Int, o: MapLibreOptions, depth: Int): JsonElement? {
        if (sheet == null || depth >= MAX_DEPTH) return null
        val parts = mutableListOf<JsonElement>()
        for (rule in sheet.rules) {
            if (rule.index >= before) break
            if (name !in rule.setClasses) continue
            for (sel in rule.selectors) {
                if (sel.disabledReason != null) continue
                parts += compile(sel, sheet, rule.index, o, depth + 1) ?: return null
            }
        }
        return when (parts.size) {
            0 -> JsonPrimitive(false)
            1 -> parts[0]
            else -> arr("any", *parts.toTypedArray())
        }
    }

    private fun tag(c: TagCondition, o: MapLibreOptions): JsonElement? {
        val k = c.keyName ?: return null
        val v = get(k, o)
        val h = has(k, o)
        val s = arr("to-string", v)
        return when (c.op) {
            TagOp.PRESENT -> h
            TagOp.ABSENT -> arr("!", h)
            TagOp.TRUE -> arr("all", h, arr("in", s, literal(listOf("yes", "true", "1"))))
            TagOp.NOT_TRUE -> arr("!", arr("all", h, arr("in", s, literal(listOf("yes", "true", "1")))))
            TagOp.FALSE -> arr("all", h, arr("in", s, literal(listOf("no", "false", "0"))))
            TagOp.EQ -> if (c.derefKey != null) arr("all", h, arr("==", v, get(c.derefKey, o))) else arr("==", v, c.value)
            TagOp.NEQ -> if (c.derefKey != null) arr("!=", v, get(c.derefKey, o)) else arr("!=", v, c.value)
            TagOp.PREFIX -> arr("all", h, arr("==", arr("slice", s, 0, c.value.length), c.value))
            TagOp.SUFFIX -> arr(
                "all",
                h,
                arr(">=", arr("length", s), c.value.length),
                arr("==", arr("slice", s, arr("-", arr("length", s), c.value.length)), c.value),
            )
            TagOp.CONTAINS -> arr("all", h, arr("in", c.value, s))
            TagOp.LIST -> null
            TagOp.LT, TagOp.LE, TagOp.GT, TagOp.GE -> {
                val n = c.number ?: return null
                val op = when (c.op) {
                    TagOp.LT -> "<"
                    TagOp.LE -> "<="
                    TagOp.GT -> ">"
                    else -> ">="
                }
                val fallback = if (c.op == TagOp.LT || c.op == TagOp.LE) n + 1 else n - 1
                arr("all", h, arr(op, arr("to-number", v, fallback), n))
            }
            TagOp.MATCH -> match(c, o)
            TagOp.NOT_MATCH -> match(c, o)?.let { arr("!", it) }
        }
    }

    private fun match(c: TagCondition, o: MapLibreOptions): JsonElement? {
        val k = c.keyName ?: return null
        val lits = c.regex?.let { Regexes.literalAlternatives(it) } ?: return null
        val s = arr("to-string", get(k, o))
        return if (c.ignoreCase) {
            arr("all", has(k, o), arr("in", arr("downcase", s), literal(lits.map { it.lowercase() })))
        } else {
            arr("all", has(k, o), arr("in", s, literal(lits)))
        }
    }
}
