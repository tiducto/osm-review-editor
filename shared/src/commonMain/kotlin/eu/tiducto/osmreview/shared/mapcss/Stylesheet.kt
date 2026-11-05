package eu.tiducto.osmreview.shared.mapcss

import eu.tiducto.osmreview.shared.Severity
import eu.tiducto.osmreview.shared.TagEdit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class Evaluation(val style: StyleResult, val checks: List<CheckResult>)

class Stylesheet internal constructor(
    val file: String,
    val rules: List<Rule>,
    val meta: StylesheetMeta,
    val canvas: Map<String, JsonElement>,
) {
    val hasChecks: Boolean get() = rules.any { it.isCheck }

    val hasStyles: Boolean get() = rules.any { it.hasStyle }

    fun styles(element: MapCssElement, host: MapCssHost = MapCssHost.NONE): StyleResult = evaluate(element, host, false).style

    fun checks(element: MapCssElement, host: MapCssHost = MapCssHost.NONE): List<CheckResult> = evaluate(element, host, true).checks

    fun evaluate(element: MapCssElement, host: MapCssHost = MapCssHost.NONE): Evaluation = evaluate(element, host, true)

    private fun evaluate(element: MapCssElement, host: MapCssHost, withChecks: Boolean): Evaluation {
        val matcher = Matcher(this, host)
        val ctx = EvalContext(element, host)
        val classes = LinkedHashSet<String>()
        val props = LinkedHashMap<String, JsonElement>()
        val matched = mutableListOf<Int>()
        val checks = mutableListOf<CheckResult>()
        for (rule in rules) {
            val sel = rule.selectors.firstOrNull { matcher.matches(it, element, classes) } ?: continue
            for (d in rule.declarations) {
                when (d) {
                    is SetClass -> classes += d.name
                    is Property -> if (d.name !in Vocabulary.VALIDATOR) styleValue(d, ctx)?.let { props[d.name] = it }
                }
            }
            if (rule.hasStyle) matched += rule.index
            if (withChecks && rule.isCheck) check(rule, sel, ctx)?.let { checks += it }
        }
        return Evaluation(StyleResult(props, classes.toList(), matched), checks)
    }

    private fun styleValue(p: Property, ctx: EvalContext): JsonElement? {
        val single = p.values.singleOrNull()
        if (p.name == "text" && single is Ident) {
            val key = if (single.name == "auto") "name" else single.name
            return ctx.element.tags[key]?.let { Evaluator.toJson(it) }
        }
        val values = p.values.map { Evaluator.eval(it, ctx) }
        if (values.size == 1) return values[0]?.let { Evaluator.toJson(it) }
        return Evaluator.toJson(values)
    }

    private fun check(rule: Rule, sel: Selector, ctx: EvalContext): CheckResult? {
        val el = ctx.element
        var severity: Severity? = null
        var message = ""
        var group: String? = null
        val set = LinkedHashMap<String, String>()
        val remove = mutableListOf<String>()
        var delete = false
        val alternatives = mutableListOf<String>()
        for (p in rule.properties) {
            fun texts(): List<String> = p.values.map { resolvePlaceholders(Evaluator.str(Evaluator.eval(it, ctx)), sel, el) }
            fun text(): String = texts().joinToString(", ")
            when (p.name) {
                "throwError" -> {
                    severity = Severity.ERROR
                    message = text()
                }
                "throwWarning" -> {
                    severity = Severity.WARNING
                    message = text()
                }
                "throwOther" -> {
                    severity = Severity.INFO
                    message = text()
                }
                "group" -> group = text()
                "fixAdd" -> texts().forEach { t ->
                    val i = t.indexOf('=')
                    if (i > 0) {
                        val k = t.substring(0, i).trim()
                        set[k] = t.substring(i + 1).trim()
                        remove -= k
                    }
                }
                "fixRemove" -> texts().map { it.trim() }.filter { it.isNotEmpty() }.forEach { k ->
                    remove += k
                    set.remove(k)
                }
                "fixChangeKey" -> {
                    val t = text()
                    val i = t.indexOf("=>")
                    if (i > 0) {
                        val old = t.substring(0, i).trim()
                        val new = t.substring(i + 2).trim()
                        val v = el.tags[old]
                        if (v != null && new.isNotEmpty() && new != old) {
                            set[new] = v
                            remove += old
                        }
                    }
                }
                "fixDeleteObject" -> delete = true
                "suggestAlternative" -> alternatives += texts()
            }
        }
        val sev = severity ?: return null
        val fix = if (set.isEmpty() && remove.isEmpty()) null else TagEdit(set, remove.distinct())
        return CheckResult(sev, message, group, fix, delete, alternatives, file, rule.line, rule.index)
    }

    fun selfTest(): List<Diagnostic> {
        val out = mutableListOf<Diagnostic>()
        for (rule in rules) {
            for (p in rule.properties) {
                if (p.name != "assertMatch" && p.name != "assertNoMatch") continue
                val text = (p.values.singleOrNull() as? Lit)?.value as? String ?: continue
                val a = AssertionElement.parse(text).getOrElse {
                    out += Diagnostic(file, p.line, p.column, Severity.ERROR, "${p.name}: ${it.message}")
                    continue
                }
                val matcher = Matcher(this, SelfTestHost)
                val matched = rule.selectors.any { sel ->
                    val wantsClosed = sel.subject.conditions.any { it is PseudoCondition && it.pseudo == PseudoClass.CLOSED && !it.negated }
                    val el = a.element(wantsClosed)
                    matcher.matches(sel, el, matcher.classesBefore(el, rule.index))
                }
                val expected = p.name == "assertMatch"
                if (matched != expected) {
                    val verb = if (expected) "does not match" else "matches"
                    out += Diagnostic(file, p.line, p.column, Severity.ERROR, "${p.name} failed: rule $verb \"$text\"")
                }
            }
        }
        return out
    }

    fun legend(): List<LegendEntry> = rules.mapNotNull { rule ->
        val props = rule.properties.associateBy { it.name }
        val label = (props[Vocabulary.LEGEND]?.values?.singleOrNull() as? Lit)?.value as? String ?: return@mapNotNull null
        val color = listOf("color", "fill-color").firstNotNullOfOrNull { (props[it]?.values?.singleOrNull() as? Lit)?.value as? String }
        LegendEntry(label, color, rule.index)
    }

    fun toGoqlPrefilter(): String? = Goql.forStylesheet(this)

    fun toGoqlPrefilter(ruleIndex: Int): String? = Goql.forRule(this, rules[ruleIndex])

    fun toMapLibreFilter(ruleIndex: Int, options: MapLibreOptions = MapLibreOptions()): JsonElement? =
        MapLibre.forRule(this, rules[ruleIndex], options)

    private object SelfTestHost : MapCssHost {
        override fun call(name: String, element: MapCssElement, args: List<Any?>): Any? = when (name) {
            "inside" -> false
            "outside" -> true
            else -> null
        }

        override fun pseudoClass(name: String, element: MapCssElement): Boolean? = if (name == "in-downloaded-area") false else null
    }

    companion object {
        private val PLACEHOLDER = Regex("""\{(\d+)\.(key|value|tag)\}""")

        internal fun resolvePlaceholders(text: String, sel: Selector, el: MapCssElement): String {
            if ('{' !in text) return text
            return PLACEHOLDER.replace(text) { m ->
                val c = sel.subject.conditions.getOrNull(m.groupValues[1].toInt()) as? TagCondition ?: return@replace m.value
                val key = Matcher.matchedKey(c, el.tags) ?: return@replace m.value
                val value = el.tags[key] ?: c.value
                when (m.groupValues[2]) {
                    "key" -> key
                    "value" -> value
                    else -> "$key=$value"
                }
            }
        }
    }
}
