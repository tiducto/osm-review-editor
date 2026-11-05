package eu.tiducto.osmreview.shared.mapcss

internal object Goql {
    private data class Alt(val types: Set<Char>?, val clauses: List<String>)

    private const val CAP = 32
    private const val MAX_DEPTH = 3
    private val ALL_TYPES = setOf('n', 'w', 'a', 'r')
    private val IMPLY_PRESENCE = setOf(
        TagOp.EQ, TagOp.PREFIX, TagOp.SUFFIX, TagOp.CONTAINS, TagOp.LIST, TagOp.LT, TagOp.LE, TagOp.GT, TagOp.GE,
        TagOp.MATCH, TagOp.TRUE, TagOp.FALSE,
    )
    private val PLAIN = Regex("[A-Za-z0-9_]+")

    fun forStylesheet(sheet: Stylesheet): String? {
        val all = mutableListOf<Alt>()
        for (rule in sheet.rules) {
            if (!rule.isCheck && !rule.hasStyle) continue
            for (sel in rule.selectors) all += alternatives(sel, sheet, 0) ?: return null
        }
        return render(all)
    }

    fun forRule(sheet: Stylesheet, rule: Rule): String? {
        val all = mutableListOf<Alt>()
        for (sel in rule.selectors) all += alternatives(sel, sheet, 0) ?: return null
        return render(all)
    }

    fun forSelector(sel: Selector, sheet: Stylesheet?): String? = render(alternatives(sel, sheet, 0) ?: return null)

    private fun render(alts: List<Alt>): String? {
        if (alts.isEmpty()) return null
        if (alts.any { (it.types == null || it.types.containsAll(ALL_TYPES)) && it.clauses.isEmpty() }) return null
        return alts.map { a ->
            val t = if (a.types == null || a.types.containsAll(ALL_TYPES)) "*" else "nwra".filter { it in a.types }
            t + a.clauses.joinToString("")
        }.distinct().joinToString(", ")
    }

    private fun alternatives(sel: Selector, sheet: Stylesheet?, depth: Int): List<Alt>? {
        if (sel.disabledReason != null) return emptyList()
        val subject = sel.subject
        val types: Set<Char>? = when (subject.type) {
            SelectorType.NODE -> setOf('n')
            SelectorType.WAY, SelectorType.AREA -> setOf('w', 'a')
            SelectorType.RELATION -> setOf('r', 'a')
            SelectorType.ANY -> null
            SelectorType.CANVAS, SelectorType.META -> return emptyList()
        }
        val implied = subject.conditions.filterIsInstance<TagCondition>()
            .filter { it.op in IMPLY_PRESENCE }
            .mapNotNull { it.keyName }
            .toSet()
        var alts = listOf(Alt(types, emptyList()))
        for (c in subject.conditions) {
            val opts = options(c, sheet, depth, implied) ?: continue
            if (alts.size * opts.size > CAP) continue
            alts = alts.flatMap { a -> opts.mapNotNull { merge(a, it) } }
        }
        return alts.distinct()
    }

    private fun merge(a: Alt, b: Alt): Alt? {
        val types = when {
            a.types == null -> b.types
            b.types == null -> a.types
            else -> (a.types intersect b.types).ifEmpty { return null }
        }
        return Alt(types, (a.clauses + b.clauses).distinct())
    }

    private fun clause(s: String) = listOf(Alt(null, listOf(s)))

    private fun options(c: Condition, sheet: Stylesheet?, depth: Int, implied: Set<String>): List<Alt>? = when (c) {
        is TagCondition -> tagOptions(c, implied)
        is ClassCondition -> if (c.negated || sheet == null || depth >= MAX_DEPTH) null else classOptions(c.name, sheet, depth)
        else -> null
    }

    private fun classOptions(name: String, sheet: Stylesheet, depth: Int): List<Alt>? {
        val out = mutableListOf<Alt>()
        for (rule in sheet.rules) {
            if (name !in rule.setClasses) continue
            for (sel in rule.selectors) out += alternatives(sel, sheet, depth + 1) ?: return null
        }
        if (out.any { (it.types == null || it.types.containsAll(ALL_TYPES)) && it.clauses.isEmpty() }) return null
        return out.distinct()
    }

    private fun tagOptions(c: TagCondition, implied: Set<String>): List<Alt>? {
        val k = c.keyName ?: return null
        val key = quote(k)
        fun presence(): List<Alt> = listOf(Alt(null, listOf("[$key]")), Alt(null, listOf("[$key=no]")))
        fun values(vs: List<String>): List<Alt> =
            if (vs.isEmpty() || vs.any { it.isEmpty() || '*' in it }) presence() else clause("[$key=${vs.joinToString(",") { quote(it) }}]")
        fun wildcard(pattern: String): List<Alt> =
            if (c.value.isEmpty() || '*' in c.value) presence() else clause("[$key=\"${escape(pattern)}\"]")
        return when (c.op) {
            TagOp.PRESENT -> if (k in implied) null else presence()
            TagOp.ABSENT -> clause("[!$key]")
            TagOp.TRUE -> clause("[$key=yes,true,1]")
            TagOp.FALSE -> clause("[$key=no,false,0]")
            TagOp.EQ -> if (c.derefKey != null) presence() else values(listOf(c.value))
            TagOp.PREFIX -> wildcard(c.value + "*")
            TagOp.SUFFIX -> wildcard("*" + c.value)
            TagOp.CONTAINS -> presence()
            TagOp.LIST, TagOp.LT, TagOp.LE, TagOp.GT, TagOp.GE -> presence()
            TagOp.MATCH -> {
                val lits = if (c.ignoreCase) null else c.regex?.let { Regexes.literalAlternatives(it) }
                if (lits == null) presence() else values(lits)
            }
            TagOp.NEQ, TagOp.NOT_MATCH, TagOp.NOT_TRUE -> null
        }
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun quote(s: String): String = if (PLAIN.matches(s)) s else "\"" + escape(s) + "\""
}
