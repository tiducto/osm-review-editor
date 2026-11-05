package eu.tiducto.osmreview.shared.mapcss

internal object Regexes {
    private val INLINE_FLAGS = Regex("""\(\?([iuU]+)\)""")

    fun compile(pattern: String, flags: String = ""): Regex {
        var ignoreCase = 'i' in flags
        val cleaned = INLINE_FLAGS.replace(pattern) { m ->
            if ('i' in m.groupValues[1]) ignoreCase = true
            ""
        }
        val unknown = flags.filter { it != 'i' && it != 'u' }
        require(unknown.isEmpty()) { "unsupported flags '$unknown'" }
        return if (ignoreCase) Regex(cleaned, RegexOption.IGNORE_CASE) else Regex(cleaned)
    }

    private val META = "\\^$.|?*+()[]{}"

    fun literalAlternatives(regex: Regex): List<String>? {
        var p = regex.pattern
        if (!p.startsWith("^") || !p.endsWith("$") || p.endsWith("\\$")) return null
        p = p.substring(1, p.length - 1)
        if (p.startsWith("(?:") && p.endsWith(")")) {
            p = p.substring(3, p.length - 1)
        } else if (p.startsWith("(") && p.endsWith(")")) {
            p = p.substring(1, p.length - 1)
        }
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < p.length) {
            val c = p[i]
            when {
                c == '\\' -> {
                    val n = p.getOrNull(i + 1) ?: return null
                    if (n.isLetterOrDigit()) return null
                    sb.append(n)
                    i += 2
                    continue
                }
                c == '|' -> {
                    out += sb.toString()
                    sb.clear()
                }
                c in META -> return null
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }
}
