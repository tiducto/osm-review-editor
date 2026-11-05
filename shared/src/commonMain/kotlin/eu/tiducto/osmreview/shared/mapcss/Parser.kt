package eu.tiducto.osmreview.shared.mapcss

import eu.tiducto.osmreview.shared.Severity
import kotlinx.serialization.json.JsonElement

internal class MapCssParser(
    private val src: String,
    private val file: String,
    private val hostFunctions: Set<String>,
) {
    private var pos = 0
    private val diagnostics = mutableListOf<Diagnostic>()
    private val lineStarts: IntArray = buildList {
        add(0)
        src.forEachIndexed { i, c -> if (c == '\n') add(i + 1) }
    }.toIntArray()
    private val reportedProperties = HashSet<String>()

    private class Fail(val at: Int, override val message: String) : RuntimeException(message)

    fun parse(): ParseResult {
        val rules = mutableListOf<Rule>()
        val meta = LinkedHashMap<String, JsonElement>()
        val canvas = LinkedHashMap<String, JsonElement>()
        while (true) {
            skipWs()
            if (eof) break
            if (peek() == '@') {
                atRule()
                continue
            }
            rule(rules, meta, canvas)
        }
        val sheet = Stylesheet(file, rules, StylesheetMeta(meta), canvas)
        val ok = diagnostics.none { it.severity == Severity.ERROR }
        return ParseResult(if (ok) sheet else null, diagnostics.sortedWith(compareBy({ it.line }, { it.column })))
    }

    fun parseSelectorOnly(): Pair<Selector?, List<Diagnostic>> {
        val sel = try {
            skipWs()
            val s = selector(allowEnd = true)
            skipWs()
            if (!eof) throw Fail(pos, "Unexpected '${peek()}' after selector")
            s
        } catch (f: Fail) {
            report(f.at, Severity.ERROR, f.message)
            null
        }
        return (if (diagnostics.any { it.severity == Severity.ERROR }) null else sel) to diagnostics.toList()
    }

    private fun lineCol(at: Int): Pair<Int, Int> {
        var lo = 0
        var hi = lineStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (lineStarts[mid] <= at) lo = mid else hi = mid - 1
        }
        return (lo + 1) to (at - lineStarts[lo] + 1)
    }

    private fun report(at: Int, severity: Severity, message: String) {
        val (l, c) = lineCol(at.coerceIn(0, src.length))
        diagnostics += Diagnostic(file, l, c, severity, message)
    }

    private val eof: Boolean get() = pos >= src.length

    private fun peek(offset: Int = 0): Char = if (pos + offset < src.length) src[pos + offset] else '\u0000'

    private fun startsWith(s: String): Boolean = src.startsWith(s, pos)

    private fun expect(c: Char, message: String = "Expected '$c'") {
        skipWs()
        if (peek() != c) throw Fail(pos, if (eof) "$message, found end of file" else "$message, found '${peek()}'")
        pos++
    }

    private fun skipWs(): Boolean {
        val start = pos
        while (!eof) {
            val c = src[pos]
            when {
                c.isWhitespace() -> pos++
                c == '/' && peek(1) == '*' -> {
                    val end = src.indexOf("*/", pos + 2)
                    if (end < 0) {
                        report(pos, Severity.ERROR, "Unterminated comment")
                        pos = src.length
                    } else {
                        pos = end + 2
                    }
                }
                c == '/' && peek(1) == '/' -> {
                    while (!eof && src[pos] != '\n') pos++
                }
                else -> break
            }
        }
        return pos > start
    }

    private fun atRule() {
        val start = pos
        pos++
        val name = identifier() ?: ""
        report(start, Severity.WARNING, "Unsupported @$name rule, block ignored")
        while (!eof && peek() != '{' && peek() != ';') pos++
        if (peek() == ';') {
            pos++
            return
        }
        skipBlock()
    }

    private fun skipBlock() {
        var depth = 0
        while (!eof) {
            when (val c = src[pos]) {
                '"', '\'' -> {
                    skipQuoted(c)
                    continue
                }
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth <= 0) {
                        pos++
                        return
                    }
                }
            }
            pos++
        }
    }

    private fun skipQuoted(q: Char) {
        pos++
        while (!eof && src[pos] != q && src[pos] != '\n') {
            if (src[pos] == '\\') pos++
            pos++
        }
        if (!eof && src[pos] == q) pos++
    }

    private fun recover(inBody: Boolean) {
        var depth = if (inBody) 1 else 0
        while (!eof) {
            val c = src[pos]
            when {
                c == '"' || c == '\'' -> {
                    skipQuoted(c)
                    continue
                }
                c == '/' && peek(1) == '*' -> {
                    skipWs()
                    continue
                }
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth <= 0) {
                        pos++
                        return
                    }
                }
            }
            pos++
        }
    }

    private fun rule(rules: MutableList<Rule>, meta: MutableMap<String, JsonElement>, canvas: MutableMap<String, JsonElement>) {
        val start = pos
        var inBody = false
        try {
            val selectors = selectorGroup()
            expect('{', "Expected '{' after selector")
            inBody = true
            val special = selectors.map { it.subject.type }.filter { it == SelectorType.META || it == SelectorType.CANVAS }
            val isMeta = special.isNotEmpty()
            if (isMeta && (special.size != selectors.size || special.toSet().size != 1 || selectors.any { it.isLinked })) {
                throw Fail(start, "meta and canvas blocks cannot be combined with other selectors")
            }
            val declarations = declarations(if (isMeta) special.first() else null)
            when {
                special.firstOrNull() == SelectorType.META -> collectConstants(declarations, meta)
                special.firstOrNull() == SelectorType.CANVAS -> collectConstants(declarations, canvas)
                else -> {
                    val (l, c) = lineCol(start)
                    rules += Rule(rules.size, selectors, declarations, l, c)
                }
            }
        } catch (f: Fail) {
            report(f.at, Severity.ERROR, f.message)
            recover(inBody)
        }
    }

    private fun collectConstants(declarations: List<Declaration>, into: MutableMap<String, JsonElement>) {
        for (d in declarations) {
            if (d !is Property) continue
            into[d.name] = Evaluator.constant(d.values)
        }
    }

    private fun selectorGroup(): List<Selector> {
        val out = mutableListOf<Selector>()
        while (true) {
            skipWs()
            out += selector(allowEnd = false)
            skipWs()
            if (peek() == ',') {
                pos++
                continue
            }
            return out
        }
    }

    private fun selector(allowEnd: Boolean): Selector {
        val start = pos
        val disabled = mutableListOf<String>()
        val parts = mutableListOf(simple(disabled))
        val links = mutableListOf<Link>()
        var end = pos
        while (true) {
            val hadWs = skipWs()
            if (eof) {
                if (allowEnd) break
                throw Fail(pos, "Expected '{' after selector, found end of file")
            }
            val c = peek()
            val kind: LinkKind = when {
                c == '{' || c == ',' -> break
                c == '>' -> LinkKind.CHILD
                c == '<' -> LinkKind.PARENT
                c == '∈' || c == '⊆' -> LinkKind.INSIDE
                c == '⧉' -> LinkKind.CROSSING
                c == '⊇' || c == '⊈' || c == '⊉' -> {
                    report(pos, Severity.WARNING, "Unsupported link selector '$c', selector ignored")
                    disabled += "link $c"
                    LinkKind.INSIDE
                }
                hadWs && (c.isLetter() || c == '*') -> {
                    links += Link(LinkKind.CHILD, emptyList())
                    parts += simple(disabled)
                    end = pos
                    continue
                }
                else -> throw Fail(pos, "Unexpected '$c' in selector")
            }
            pos++
            val linkConditions = linkConditions(disabled)
            links += Link(kind, linkConditions)
            skipWs()
            parts += simple(disabled)
            end = pos
        }
        val (l, col) = lineCol(start)
        return Selector(parts, links, src.substring(start, end).trim(), l, col, disabled.firstOrNull())
    }

    private fun linkConditions(disabled: MutableList<String>): List<TagCondition> {
        val out = mutableListOf<TagCondition>()
        while (true) {
            when (peek()) {
                '[' -> {
                    val at = pos
                    val c = bracket(disabled)
                    val key = (c as? TagCondition)?.keyName
                    if (c !is TagCondition || (key != "role" && key != "index")) {
                        report(at, Severity.WARNING, "Unsupported link condition, selector ignored")
                        disabled += "link condition"
                    } else {
                        if (key == "index" && c.op !in setOf(TagOp.EQ, TagOp.NEQ, TagOp.LT, TagOp.LE, TagOp.GT, TagOp.GE, TagOp.PRESENT)) {
                            report(at, Severity.WARNING, "Unsupported index condition, selector ignored")
                            disabled += "index"
                        }
                        out += c
                    }
                }
                ':' -> {
                    val at = pos
                    pos++
                    val name = identifier() ?: throw Fail(pos, "Expected pseudo-class name")
                    report(at, Severity.WARNING, "Unsupported link pseudo-class :$name, selector ignored")
                    disabled += ":$name"
                }
                else -> return out
            }
        }
    }

    private fun simple(disabled: MutableList<String>): SimpleSelector {
        val start = pos
        val name = if (peek() == '*') {
            pos++
            "*"
        } else {
            identifier() ?: throw Fail(pos, if (eof) "Expected selector, found end of file" else "Expected selector, found '${peek()}'")
        }
        val type = when (name) {
            "node" -> SelectorType.NODE
            "way" -> SelectorType.WAY
            "relation" -> SelectorType.RELATION
            "area" -> SelectorType.AREA
            "*" -> SelectorType.ANY
            "canvas" -> SelectorType.CANVAS
            "meta" -> SelectorType.META
            else -> throw Fail(start, "Unknown selector type '$name'")
        }
        val conditions = mutableListOf<Condition>()
        while (true) {
            when {
                peek() == '|' -> zoom()
                peek() == '[' -> conditions += bracket(disabled)
                peek() == '.' -> {
                    pos++
                    conditions += ClassCondition(identifier() ?: throw Fail(pos, "Expected class name"), false)
                }
                peek() == '!' && peek(1) == '.' -> {
                    pos += 2
                    conditions += ClassCondition(identifier() ?: throw Fail(pos, "Expected class name"), true)
                }
                peek() == '!' && peek(1) == ':' -> {
                    pos++
                    pseudo(true, disabled)?.let { conditions += it }
                }
                startsWith("::") -> {
                    val at = pos
                    pos += 2
                    if (peek() == '*') pos++ else identifier() ?: throw Fail(pos, "Expected layer name")
                    report(at, Severity.INFO, "Layers are ignored; '${src.substring(at, pos)}' is treated as the default layer")
                }
                peek() == ':' -> pseudo(false, disabled)?.let { conditions += it }
                else -> break
            }
        }
        if ((type == SelectorType.META || type == SelectorType.CANVAS) && conditions.isNotEmpty()) {
            throw Fail(start, "$name blocks take no conditions")
        }
        return SimpleSelector(type, conditions)
    }

    private fun zoom() {
        val at = pos
        pos++
        if (peek() != 'z') throw Fail(pos, "Expected zoom range like |z12-")
        pos++
        while (!eof && (peek().isDigit() || peek() == '-')) pos++
        report(at, Severity.INFO, "Zoom range '${src.substring(at, pos)}' is ignored")
    }

    private fun pseudo(negated: Boolean, disabled: MutableList<String>): PseudoCondition? {
        val at = pos
        pos++
        val name = identifier() ?: throw Fail(pos, "Expected pseudo-class name")
        val kind = when (name) {
            "closed", "closed2" -> PseudoClass.CLOSED
            "unclosed" -> PseudoClass.UNCLOSED
            "tagged" -> PseudoClass.TAGGED
            "untagged" -> PseudoClass.UNTAGGED
            "unconnected" -> PseudoClass.UNCONNECTED
            "connection" -> PseudoClass.CONNECTION
            "righthandtraffic" -> PseudoClass.RIGHT_HAND_TRAFFIC
            "in-downloaded-area" -> PseudoClass.IN_DOWNLOADED_AREA
            "completely_downloaded" -> PseudoClass.ALWAYS
            "selected", "modified", "highlighted", "new", "hover", "active", "focus" -> {
                report(at, Severity.INFO, "Pseudo-class :$name is ignored and never matches")
                PseudoClass.NEVER
            }
            else -> {
                report(at, Severity.WARNING, "Unsupported pseudo-class :$name, selector ignored")
                disabled += ":$name"
                return null
            }
        }
        return PseudoCondition(kind, name, negated)
    }

    private fun bracket(disabled: MutableList<String>): Condition {
        pos++
        skipWs()
        val condition = when {
            peek() == '!' -> {
                pos++
                skipWs()
                when {
                    peek() == '(' || identifierFollowedByParen() -> ExpressionCondition(Unary("!", expression()))
                    peek() == '/' -> {
                        val ref = regexKey()
                        if (ref == null) disabled += "regex"
                        TagCondition(ref ?: KeyRef.AnyKey, TagOp.ABSENT)
                    }
                    else -> {
                        val k = key()
                        skipWs()
                        if (peek() == '?') {
                            pos++
                            TagCondition(k, TagOp.NOT_TRUE)
                        } else {
                            TagCondition(k, TagOp.ABSENT)
                        }
                    }
                }
            }
            peek() == '(' || peek() == '-' || peek().isDigit() || identifierFollowedByParen() -> ExpressionCondition(expression())
            else -> tagCondition(disabled)
        }
        expect(']')
        return condition
    }

    private fun identifierFollowedByParen(): Boolean {
        var i = pos
        if (i >= src.length || !(src[i].isLetter() || src[i] == '_')) return false
        while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++
        while (i < src.length && src[i].isWhitespace()) i++
        return i < src.length && src[i] == '('
    }

    private fun regexKey(): KeyRef? {
        val at = pos
        val (pattern, flags) = regexLiteral()
        val regex = compileRegex(pattern, flags, at) ?: return null
        return KeyRef.Pattern(regex, pattern)
    }

    private fun key(): KeyRef {
        skipWs()
        return when (val c = peek()) {
            '"', '\'' -> KeyRef.Name(quoted(c))
            '*' -> {
                pos++
                KeyRef.AnyKey
            }
            else -> {
                val start = pos
                while (!eof && !peek().isWhitespace() && peek() !in KEY_STOP) pos++
                if (pos == start) throw Fail(pos, if (eof) "Expected key, found end of file" else "Expected key, found '${peek()}'")
                KeyRef.Name(src.substring(start, pos))
            }
        }
    }

    private fun tagCondition(disabled: MutableList<String>): TagCondition {
        val key = if (peek() == '/') {
            regexKey() ?: run {
                disabled += "regex"
                KeyRef.AnyKey
            }
        } else {
            key()
        }
        skipWs()
        if (peek() == ']') return TagCondition(key, TagOp.PRESENT)
        if (peek() == '?') {
            pos++
            if (peek() == '!') {
                pos++
                return TagCondition(key, TagOp.FALSE)
            }
            return TagCondition(key, TagOp.TRUE)
        }
        val opAt = pos
        val op = OPERATORS.firstOrNull { startsWith(it.first) } ?: throw Fail(pos, "Expected operator or ']', found '${peek()}'")
        pos += op.first.length
        skipWs()
        val tagOp = op.second
        if (tagOp == TagOp.MATCH || tagOp == TagOp.NOT_MATCH) {
            val at = pos
            val (pattern, flags) = when (val c = peek()) {
                '/' -> regexLiteral()
                '"', '\'' -> quoted(c) to ""
                '*' -> {
                    pos++
                    val ref = bareValue()
                    report(at, Severity.WARNING, "Unsupported key reference *$ref in regular expression, selector ignored")
                    disabled += "deref"
                    return TagCondition(key, tagOp, ref, NEVER_REGEX)
                }
                else -> throw Fail(pos, "Expected /regular expression/")
            }
            val regex = compileRegex(pattern, flags, at)
            if (regex == null) disabled += "regex"
            return TagCondition(key, tagOp, pattern, regex ?: NEVER_REGEX)
        }
        if (peek() == '*' && (tagOp == TagOp.EQ || tagOp == TagOp.NEQ) && peek(1) != ']') {
            pos++
            val ref = if (peek() == '"' || peek() == '\'') quoted(peek()) else bareValue()
            return TagCondition(key, tagOp, derefKey = ref)
        }
        val value = when (val c = peek()) {
            '"', '\'' -> quoted(c)
            else -> bareValue()
        }
        if (tagOp in NUMERIC) {
            val n = value.toDoubleOrNull() ?: throw Fail(opAt, "Expected a number after '${op.first}', found '$value'")
            return TagCondition(key, tagOp, value, number = n)
        }
        return TagCondition(key, tagOp, value)
    }

    private fun bareValue(): String {
        val start = pos
        while (!eof && peek() != ']' && peek() != '[' && peek() != '{' && !peek().isWhitespace()) pos++
        val v = src.substring(start, pos).trim()
        if (v.isEmpty()) throw Fail(start, "Expected value")
        return v
    }

    private fun quoted(q: Char): String {
        val start = pos
        pos++
        val sb = StringBuilder()
        while (true) {
            if (eof || src[pos] == '\n') throw Fail(start, "Unterminated string")
            val c = src[pos]
            if (c == q) {
                pos++
                return sb.toString()
            }
            if (c == '\\' && pos + 1 < src.length) {
                pos++
                sb.append(
                    when (val e = src[pos]) {
                        'n' -> '\n'
                        't' -> '\t'
                        else -> e
                    },
                )
                pos++
                continue
            }
            sb.append(c)
            pos++
        }
    }

    private fun regexLiteral(): Pair<String, String> {
        val start = pos
        pos++
        val sb = StringBuilder()
        var inClass = false
        while (true) {
            if (eof || src[pos] == '\n') throw Fail(start, "Unterminated regular expression")
            val c = src[pos]
            if (c == '\\' && pos + 1 < src.length) {
                if (src[pos + 1] == '/') {
                    sb.append('/')
                } else {
                    sb.append(c).append(src[pos + 1])
                }
                pos += 2
                continue
            }
            if (c == '[') inClass = true
            if (c == ']') inClass = false
            if (c == '/' && !inClass) break
            sb.append(c)
            pos++
        }
        pos++
        val flagsStart = pos
        while (!eof && peek().isLetter()) pos++
        return sb.toString() to src.substring(flagsStart, pos)
    }

    private fun compileRegex(pattern: String, flags: String, at: Int): Regex? = try {
        Regexes.compile(pattern, flags)
    } catch (e: Throwable) {
        report(at, Severity.ERROR, "Invalid regular expression /$pattern/: ${e.message ?: e::class.simpleName}")
        null
    }

    private fun identifier(): String? {
        val start = pos
        if (eof || !(peek().isLetter() || peek() == '_' || (peek() == '-' && peek(1).isLetter()))) return null
        pos++
        while (!eof && (peek().isLetterOrDigit() || peek() == '_' || peek() == '-')) pos++
        return src.substring(start, pos)
    }

    private fun declarations(special: SelectorType?): List<Declaration> {
        val out = mutableListOf<Declaration>()
        while (true) {
            skipWs()
            if (eof) throw Fail(pos, "Missing '}' at end of file")
            when (peek()) {
                '}' -> {
                    pos++
                    return out
                }
                ';' -> {
                    pos++
                    continue
                }
            }
            try {
                out += declaration(special)
            } catch (f: Fail) {
                report(f.at, Severity.ERROR, f.message)
                skipDeclaration()
            }
        }
    }

    private fun skipDeclaration() {
        var depth = 0
        while (!eof) {
            when (val c = src[pos]) {
                '"', '\'' -> {
                    skipQuoted(c)
                    continue
                }
                '(' -> depth++
                ')' -> depth--
                ';' -> if (depth <= 0) {
                    pos++
                    return
                }
                '}' -> if (depth <= 0) return
            }
            pos++
        }
    }

    private fun declaration(special: SelectorType?): List<Declaration> {
        val start = pos
        val (line, col) = lineCol(start)
        val name = identifier() ?: throw Fail(pos, "Expected property name, found '${peek()}'")
        skipWs()
        if (name == "set" && peek() != ':') {
            if (special != null) throw Fail(start, "set is not allowed in ${special.name.lowercase()} blocks")
            return setDeclaration(line, col)
        }
        expect(':', "Expected ':' after '$name'")
        skipWs()
        if (Vocabulary.isIgnoredProperty(name)) {
            skipDeclarationValue()
            report(start, Severity.INFO, "Property '$name' is ignored")
            return emptyList()
        }
        val known = isKnown(name, special)
        val valueStart = pos
        val values = try {
            val v = valueList()
            skipWs()
            when {
                peek() == ';' -> pos++
                peek() == '}' -> Unit
                else -> throw Fail(pos, if (eof) "Expected ';' or '}', found end of file" else "Expected ';' or '}', found '${peek()}'")
            }
            v
        } catch (f: Fail) {
            if (known) throw f
            pos = valueStart
            val raw = skipDeclarationValue()
            listOf(Lit(raw))
        }
        if (!known && reportedProperties.add(name)) {
            report(start, Severity.INFO, "Unknown property '$name' passed through")
        }
        validateProperty(name, values, start)
        return listOf(Property(name, values, line, col))
    }

    private fun skipDeclarationValue(): String {
        val start = pos
        while (!eof && peek() != ';' && peek() != '}') {
            if (peek() == '"' || peek() == '\'') skipQuoted(peek()) else pos++
        }
        val raw = src.substring(start, pos).trim()
        if (peek() == ';') pos++
        return raw
    }

    private fun isKnown(name: String, special: SelectorType?): Boolean = when (special) {
        SelectorType.META -> name in Vocabulary.META || name in Vocabulary.EXTENSIONS
        SelectorType.CANVAS -> true
        else -> name in Vocabulary.STYLE || name in Vocabulary.EXTENSIONS || name in Vocabulary.VALIDATOR
    }

    private fun validateProperty(name: String, values: List<Expr>, at: Int) {
        val single = values.singleOrNull()
        when (name) {
            Vocabulary.STATS -> {
                val v = (single as? Ident)?.name ?: (single as? Lit)?.value as? String
                if (v != null && v !in Vocabulary.STATS_VALUES) {
                    report(at, Severity.WARNING, "-ore-stats must be one of ${Vocabulary.STATS_VALUES.joinToString("|")}, found '$v'")
                }
            }
            "fixAdd" -> {
                val v = (single as? Lit)?.value as? String
                if (v != null && '=' !in v) report(at, Severity.ERROR, "fixAdd expects \"key=value\", found \"$v\"")
            }
            "fixChangeKey" -> {
                val v = (single as? Lit)?.value as? String
                if (v != null && "=>" !in v) report(at, Severity.ERROR, "fixChangeKey expects \"old=>new\", found \"$v\"")
            }
            "assertMatch", "assertNoMatch" -> {
                val v = (single as? Lit)?.value as? String
                if (v == null) {
                    report(at, Severity.ERROR, "$name expects a string")
                } else {
                    AssertionElement.parse(v).exceptionOrNull()?.let { report(at, Severity.ERROR, "$name: ${it.message}") }
                }
            }
        }
    }

    private fun setDeclaration(line: Int, col: Int): List<Declaration> {
        val names = mutableListOf<String>()
        while (true) {
            skipWs()
            if (peek() == '.') pos++
            val at = pos
            val n = identifier() ?: throw Fail(pos, "Expected class name after 'set'")
            skipWs()
            if (peek() == '=' || peek() == ':') {
                report(at, Severity.WARNING, "Unsupported 'set $n=...' (setting tags), declaration ignored")
                skipDeclarationValue()
                return emptyList()
            }
            names += n
            if (peek() == ',') {
                pos++
                continue
            }
            break
        }
        when {
            peek() == ';' -> pos++
            peek() == '}' -> Unit
            else -> throw Fail(pos, "Expected ';' after set")
        }
        return names.map { SetClass(it, line, col) }
    }

    private fun valueList(): List<Expr> {
        val out = mutableListOf(expression())
        while (true) {
            skipWs()
            if (peek() != ',') return out
            pos++
            out += expression()
        }
    }

    private fun expression(): Expr = ternary()

    private fun ternary(): Expr {
        val test = or()
        skipWs()
        if (peek() != '?') return test
        pos++
        val a = expression()
        expect(':', "Expected ':' in conditional expression")
        val b = expression()
        return Ternary(test, a, b)
    }

    private fun or(): Expr {
        var left = and()
        while (true) {
            skipWs()
            if (!startsWith("||")) return left
            pos += 2
            left = Binary("||", left, and())
        }
    }

    private fun and(): Expr {
        var left = comparison()
        while (true) {
            skipWs()
            if (!startsWith("&&")) return left
            pos += 2
            left = Binary("&&", left, comparison())
        }
    }

    private fun comparison(): Expr {
        val left = additive()
        skipWs()
        val op = COMPARISONS.firstOrNull { startsWith(it) && !(it == "=" && peek(1) == '~') } ?: return left
        pos += op.length
        val normalized = when (op) {
            "=" -> "=="
            "<>" -> "!="
            else -> op
        }
        return Binary(normalized, left, additive())
    }

    private fun additive(): Expr {
        var left = multiplicative()
        while (true) {
            skipWs()
            val c = peek()
            if (c != '+' && c != '-') return left
            pos++
            left = Binary(c.toString(), left, multiplicative())
        }
    }

    private fun multiplicative(): Expr {
        var left = unary()
        while (true) {
            skipWs()
            val c = peek()
            if (c != '*' && c != '/' && c != '%') return left
            if (c == '/' && (peek(1) == '*' || peek(1) == '/')) return left
            pos++
            left = Binary(c.toString(), left, unary())
        }
    }

    private fun unary(): Expr {
        skipWs()
        if (peek() == '!' && peek(1) != '=') {
            pos++
            return Unary("!", unary())
        }
        if (peek() == '-') {
            pos++
            return Unary("-", unary())
        }
        return primary()
    }

    private fun primary(): Expr {
        skipWs()
        val start = pos
        val c = peek()
        return when {
            c == '(' -> {
                pos++
                val e = expression()
                expect(')')
                e
            }
            c == '"' || c == '\'' -> Lit(quoted(c))
            c.isDigit() || (c == '.' && peek(1).isDigit()) -> number()
            c == '#' -> {
                pos++
                val s = pos
                while (!eof && (peek().isLetterOrDigit())) pos++
                val hex = src.substring(s, pos)
                if (hex.isEmpty() || hex.any { it !in HEX } || hex.length !in setOf(3, 4, 6, 8)) throw Fail(start, "Invalid colour '#$hex'")
                Lit("#" + hex.lowercase())
            }
            c.isLetter() || c == '_' -> {
                val name = identifier()!!
                val afterName = pos
                skipWs()
                if (peek() == '(') call(name, start) else {
                    pos = afterName
                    Ident(name)
                }
            }
            eof -> throw Fail(pos, "Expected expression, found end of file")
            else -> throw Fail(pos, "Expected expression, found '$c'")
        }
    }

    private fun number(): Expr {
        val start = pos
        while (!eof && (peek().isDigit() || peek() == '.')) pos++
        if ((peek() == 'e' || peek() == 'E') && (peek(1).isDigit() || ((peek(1) == '-' || peek(1) == '+') && peek(2).isDigit()))) {
            pos += 2
            while (!eof && peek().isDigit()) pos++
        }
        val n = src.substring(start, pos).toDoubleOrNull() ?: throw Fail(start, "Invalid number '${src.substring(start, pos)}'")
        if (startsWith("px")) pos += 2
        return Lit(n)
    }

    private fun call(name: String, start: Int): Expr {
        pos++
        val args = mutableListOf<Expr>()
        skipWs()
        if (peek() == ')') {
            pos++
        } else {
            while (true) {
                args += expression()
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    ')' -> {
                        pos++
                        break
                    }
                    else -> throw Fail(pos, if (eof) "Expected ')', found end of file" else "Expected ',' or ')', found '${peek()}'")
                }
            }
        }
        if (name == "eval") {
            if (args.size != 1) throw Fail(start, "eval() takes one argument")
            return args[0]
        }
        val kind = when (name) {
            in Vocabulary.BUILTINS -> CallKind.BUILTIN
            in Vocabulary.HOST, in hostFunctions -> CallKind.HOST
            in Vocabulary.UNSUPPORTED -> {
                report(start, Severity.WARNING, "Unsupported function $name(), it evaluates to null")
                CallKind.UNSUPPORTED
            }
            else -> {
                report(start, Severity.ERROR, "Unknown function $name()")
                CallKind.UNSUPPORTED
            }
        }
        val constRegex = if (name in REGEX_FUNCTIONS) {
            val p = (args.getOrNull(0) as? Lit)?.value as? String
            val flagIndex = if (name == "tag_regex") 1 else 2
            val flags = if (args.size > flagIndex) (args[flagIndex] as? Lit)?.value as? String else ""
            if (p != null && flags != null) compileRegex(p, flags, start) else null
        } else {
            null
        }
        return Call(name, args, kind, constRegex)
    }

    companion object {
        private const val KEY_STOP = "=!<>~^$*?][\"'"
        private const val HEX = "0123456789abcdefABCDEF"
        private val NEVER_REGEX = Regex("(?!)")
        private val REGEX_FUNCTIONS = setOf("regexp_test", "regexp_match", "tag_regex")
        private val NUMERIC = setOf(TagOp.LT, TagOp.LE, TagOp.GT, TagOp.GE)
        private val COMPARISONS = listOf("==", "!=", "<>", "<=", ">=", "<", ">", "=")
        private val OPERATORS = listOf(
            "=~" to TagOp.MATCH,
            "!~" to TagOp.NOT_MATCH,
            "!=" to TagOp.NEQ,
            "^=" to TagOp.PREFIX,
            "$=" to TagOp.SUFFIX,
            "*=" to TagOp.CONTAINS,
            "~=" to TagOp.LIST,
            "<=" to TagOp.LE,
            ">=" to TagOp.GE,
            "==" to TagOp.EQ,
            "<" to TagOp.LT,
            ">" to TagOp.GT,
            "=" to TagOp.EQ,
        )
    }
}
