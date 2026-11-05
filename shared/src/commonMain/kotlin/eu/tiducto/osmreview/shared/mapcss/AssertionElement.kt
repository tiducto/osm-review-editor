package eu.tiducto.osmreview.shared.mapcss

import eu.tiducto.osmreview.shared.OsmType

internal class AssertionElement(val type: OsmType, val tags: Map<String, String>, val closed: Boolean?) {
    fun element(closedIfUnknown: Boolean): SimpleElement = SimpleElement(type, -1, tags, closed ?: closedIfUnknown)

    companion object {
        fun parse(text: String): Result<AssertionElement> = runCatching {
            val tokens = tokenize(text)
            require(tokens.isNotEmpty()) { "empty test object" }
            val (type, closed) = when (tokens[0].first) {
                "node" -> OsmType.NODE to null
                "way" -> OsmType.WAY to null
                "closedway" -> OsmType.WAY to true
                "relation" -> OsmType.RELATION to null
                else -> throw IllegalArgumentException("test object must start with node, way, closedway or relation")
            }
            require(tokens[0].second == null) { "test object must start with its type" }
            val tags = LinkedHashMap<String, String>()
            tokens.drop(1).forEach { (k, v) ->
                require(v != null) { "expected key=value, found '$k'" }
                tags[k] = v
            }
            AssertionElement(type, tags, closed)
        }

        private fun wordEnd(text: String, from: Int): Int {
            var i = from
            while (i < text.length && !text[i].isWhitespace()) i++
            return i
        }

        private fun tokenize(text: String): List<Pair<String, String?>> {
            val out = mutableListOf<Pair<String, String?>>()
            var i = 0
            fun part(): String {
                val sb = StringBuilder()
                if (i < text.length && text[i] == '"') {
                    i++
                    while (i < text.length && text[i] != '"') {
                        if (text[i] == '\\' && i + 1 < text.length) i++
                        sb.append(text[i])
                        i++
                    }
                    require(i < text.length) { "unterminated quote" }
                    i++
                } else {
                    while (i < text.length && !text[i].isWhitespace() && text[i] != '=') {
                        sb.append(text[i])
                        i++
                    }
                }
                return sb.toString()
            }
            while (true) {
                while (i < text.length && text[i].isWhitespace()) i++
                if (i >= text.length) return out
                val k = part()
                if (i < text.length && text[i] == '=') {
                    i++
                    if (i < text.length && text[i] == '"') {
                        out += k to part()
                    } else {
                        val start = i
                        var end = wordEnd(text, i)
                        while (true) {
                            var j = end
                            while (j < text.length && text[j].isWhitespace()) j++
                            if (j >= text.length) break
                            val next = wordEnd(text, j)
                            if ('=' in text.substring(j, next) || text[j] == '"') break
                            end = next
                        }
                        i = end
                        out += k to text.substring(start, end)
                    }
                } else {
                    out += k to null
                }
            }
        }
    }
}
