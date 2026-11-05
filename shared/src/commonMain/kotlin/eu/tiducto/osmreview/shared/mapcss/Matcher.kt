package eu.tiducto.osmreview.shared.mapcss

import eu.tiducto.osmreview.shared.OsmType

internal class Matcher(private val sheet: Stylesheet, val host: MapCssHost) {
    private val classCache = HashMap<MapCssElement, Set<String>>()

    fun matches(sel: Selector, el: MapCssElement, classes: Set<String>, depth: Int = 0): Boolean {
        if (sel.disabledReason != null) return false
        return part(sel, sel.parts.lastIndex, el, classes, depth)
    }

    fun classesBefore(el: MapCssElement, ruleIndex: Int, depth: Int = 0): Set<String> {
        val set = LinkedHashSet<String>()
        for (rule in sheet.rules) {
            if (rule.index >= ruleIndex) break
            val names = rule.setClasses
            if (names.isEmpty()) continue
            if (rule.selectors.any { matches(it, el, set, depth) }) set += names
        }
        return set
    }

    private fun classesOf(el: MapCssElement, depth: Int): Set<String> {
        if (depth > MAX_DEPTH) return emptySet()
        classCache[el]?.let { return it }
        val set = classesBefore(el, Int.MAX_VALUE, depth)
        classCache[el] = set
        return set
    }

    private fun part(sel: Selector, idx: Int, el: MapCssElement, classes: Set<String>?, depth: Int): Boolean {
        val p = sel.parts[idx]
        if (!typeMatches(p.type, el)) return false
        val cls = classes ?: if (p.conditions.any { it is ClassCondition }) classesOf(el, depth + 1) else emptySet()
        if (!p.conditions.all { condition(it, el, cls) }) return false
        if (idx == 0) return true
        val link = sel.links[idx - 1]
        return when (link.kind) {
            LinkKind.CHILD -> el.parents.any { l ->
                linkMatches(link, l, l.element) && part(sel, idx - 1, l.element, null, depth)
            }
            LinkKind.PARENT -> el.children.any { l ->
                linkMatches(link, l, el) && part(sel, idx - 1, l.element, null, depth)
            }
            LinkKind.INSIDE -> host.elementsInside(el).any { part(sel, idx - 1, it, null, depth) }
            LinkKind.CROSSING -> host.elementsCrossing(el).any { part(sel, idx - 1, it, null, depth) }
        }
    }

    private fun linkMatches(link: Link, l: MapCssLink, parent: MapCssElement): Boolean = link.conditions.all { c ->
        when (c.keyName) {
            "role" -> if (c.op == TagOp.PRESENT) l.role.isNotEmpty() else test(c, l.role, emptyMap())
            "index" -> {
                if (c.op == TagOp.PRESENT) return@all true
                val n = c.number ?: c.value.toDoubleOrNull() ?: return@all false
                val position = if (n < 0) l.index - parent.children.size else l.index + 1
                test(c, position.toString(), emptyMap())
            }
            else -> false
        }
    }

    fun condition(c: Condition, el: MapCssElement, classes: Set<String>): Boolean = when (c) {
        is TagCondition -> tagMatches(c, el.tags)
        is ClassCondition -> (c.name in classes) != c.negated
        is PseudoCondition -> pseudo(c.pseudo, el) != c.negated
        is ExpressionCondition -> Evaluator.truthy(Evaluator.eval(c.expr, EvalContext(el, host)))
    }

    private fun pseudo(p: PseudoClass, el: MapCssElement): Boolean = when (p) {
        PseudoClass.CLOSED -> el.isClosed
        PseudoClass.UNCLOSED -> el.type == OsmType.WAY && !el.isClosed
        PseudoClass.TAGGED -> el.tags.keys.any { !Vocabulary.isUninteresting(it) }
        PseudoClass.UNTAGGED -> el.tags.keys.all { Vocabulary.isUninteresting(it) }
        PseudoClass.UNCONNECTED -> el.type == OsmType.NODE && el.parents.none { it.element.type == OsmType.WAY }
        PseudoClass.CONNECTION -> el.type == OsmType.NODE &&
            el.parents.filter { it.element.type == OsmType.WAY }.map { it.element.id }.distinct().size >= 2
        PseudoClass.RIGHT_HAND_TRAFFIC -> host.pseudoClass("righthandtraffic", el)
            ?: Evaluator.truthy(Evaluator.normalize(host.call("is_right_hand_traffic", el, emptyList())))
        PseudoClass.IN_DOWNLOADED_AREA -> host.pseudoClass("in-downloaded-area", el) ?: true
        PseudoClass.ALWAYS -> true
        PseudoClass.NEVER -> false
    }

    companion object {
        private const val MAX_DEPTH = 3
        private val TRUE_VALUES = setOf("yes", "true", "1")
        private val FALSE_VALUES = setOf("no", "false", "0")

        fun typeMatches(type: SelectorType, el: MapCssElement): Boolean = when (type) {
            SelectorType.NODE -> el.type == OsmType.NODE
            SelectorType.WAY -> el.type == OsmType.WAY
            SelectorType.RELATION -> el.type == OsmType.RELATION
            SelectorType.AREA -> el.type == OsmType.WAY ||
                (el.type == OsmType.RELATION && el.tags["type"].let { it == "multipolygon" || it == "boundary" })
            SelectorType.ANY -> true
            SelectorType.CANVAS, SelectorType.META -> false
        }

        fun tagMatches(c: TagCondition, tags: Map<String, String>): Boolean {
            val key = c.key
            if (key is KeyRef.Name) return test(c, tags[key.key], tags)
            val keys = candidateKeys(key, tags)
            return when (c.op) {
                TagOp.PRESENT -> keys.isNotEmpty()
                TagOp.ABSENT -> keys.isEmpty()
                TagOp.NEQ, TagOp.NOT_MATCH, TagOp.NOT_TRUE -> keys.all { test(c, tags[it], tags) }
                else -> keys.any { test(c, tags[it], tags) }
            }
        }

        fun matchedKey(c: TagCondition, tags: Map<String, String>): String? {
            val key = c.key
            if (key is KeyRef.Name) return key.key
            val keys = candidateKeys(key, tags)
            return keys.firstOrNull { c.op == TagOp.PRESENT || test(c, tags[it], tags) } ?: keys.firstOrNull()
        }

        private fun candidateKeys(key: KeyRef, tags: Map<String, String>): List<String> = when (key) {
            is KeyRef.Pattern -> tags.keys.filter { key.regex.containsMatchIn(it) }
            else -> tags.keys.toList()
        }

        fun test(c: TagCondition, v: String?, tags: Map<String, String>): Boolean = when (c.op) {
            TagOp.PRESENT -> v != null
            TagOp.ABSENT -> v == null
            TagOp.TRUE -> v in TRUE_VALUES
            TagOp.NOT_TRUE -> v !in TRUE_VALUES
            TagOp.FALSE -> v in FALSE_VALUES
            TagOp.EQ -> if (c.derefKey != null) v != null && v == tags[c.derefKey] else v == c.value
            TagOp.NEQ -> if (c.derefKey != null) v != tags[c.derefKey] else v != c.value
            TagOp.PREFIX -> v != null && v.startsWith(c.value)
            TagOp.SUFFIX -> v != null && v.endsWith(c.value)
            TagOp.CONTAINS -> v != null && v.contains(c.value)
            TagOp.LIST -> v != null && v.split(';').any { it.trim() == c.value }
            TagOp.LT, TagOp.LE, TagOp.GT, TagOp.GE -> {
                val x = v?.trim()?.toDoubleOrNull()
                val y = c.number
                x != null && y != null && when (c.op) {
                    TagOp.LT -> x < y
                    TagOp.LE -> x <= y
                    TagOp.GT -> x > y
                    else -> x >= y
                }
            }
            TagOp.MATCH -> v != null && c.regex!!.containsMatchIn(v)
            TagOp.NOT_MATCH -> v == null || !c.regex!!.containsMatchIn(v)
        }
    }
}
