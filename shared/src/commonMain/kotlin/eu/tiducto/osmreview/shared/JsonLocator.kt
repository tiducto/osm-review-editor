package eu.tiducto.osmreview.shared

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

class JsonLocator(private val text: String) {
    data class Pos(val line: Int, val column: Int)

    private val values = HashMap<String, Int>()
    private val keys = HashMap<String, Int>()
    private val lineStarts: IntArray = buildList {
        add(0)
        text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) }
    }.toIntArray()

    init {
        runCatching { Scanner().value("") }
    }

    fun value(path: String): Pos = at(lookup(path, values, keys))

    fun key(path: String): Pos = at(lookup(path, keys, values))

    fun at(offset: Int): Pos {
        val o = offset.coerceIn(0, text.length)
        var lo = 0
        var hi = lineStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (lineStarts[mid] <= o) lo = mid else hi = mid - 1
        }
        return Pos(lo + 1, o - lineStarts[lo] + 1)
    }

    private fun lookup(path: String, first: Map<String, Int>, second: Map<String, Int>): Int {
        var p = path
        while (true) {
            first[p]?.let { return it }
            second[p]?.let { return it }
            if (p.isEmpty()) return 0
            p = parent(p)
        }
    }

    private fun parent(path: String): String {
        val dot = path.lastIndexOf('.')
        val bracket = path.lastIndexOf('[')
        val cut = maxOf(dot, bracket)
        return if (cut <= 0) "" else path.substring(0, cut)
    }

    private inner class Scanner {
        var i = 0

        fun ws() {
            while (i < text.length && text[i].isWhitespace()) i++
        }

        fun value(path: String) {
            ws()
            values[path] = i
            when (text[i]) {
                '{' -> obj(path)
                '[' -> arr(path)
                '"' -> str()
                else -> literal()
            }
        }

        fun obj(path: String) {
            i++
            ws()
            if (text[i] == '}') {
                i++
                return
            }
            while (true) {
                ws()
                val keyStart = i
                val k = str()
                val child = child(path, k)
                keys[child] = keyStart
                ws()
                if (text[i] == ':') i++
                value(child)
                ws()
                when (text[i]) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return
                    }
                    else -> error("unexpected")
                }
            }
        }

        fun arr(path: String) {
            i++
            ws()
            if (text[i] == ']') {
                i++
                return
            }
            var n = 0
            while (true) {
                value("$path[$n]")
                n++
                ws()
                when (text[i]) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return
                    }
                    else -> error("unexpected")
                }
            }
        }

        fun str(): String {
            val sb = StringBuilder()
            i++
            while (text[i] != '"') {
                if (text[i] == '\\') {
                    i++
                    if (text[i] == 'u') {
                        sb.append(text.substring(i + 1, i + 5).toInt(16).toChar())
                        i += 4
                    } else {
                        sb.append(text[i])
                    }
                } else {
                    sb.append(text[i])
                }
                i++
            }
            i++
            return sb.toString()
        }

        fun literal() {
            while (i < text.length && text[i] !in ",]}" && !text[i].isWhitespace()) i++
        }
    }

    companion object {
        fun child(path: String, key: String): String = if (path.isEmpty()) key else "$path.$key"

        private val OFFSET = Regex("""offset (\d+)""")

        fun parse(file: String, text: String): Pair<JsonElement?, List<RuleDiagnostic>> = try {
            Json.parseToJsonElement(text) to emptyList()
        } catch (e: SerializationException) {
            val msg = e.message.orEmpty()
            val offset = OFFSET.find(msg)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val pos = JsonLocator(text).at(offset)
            val first = msg.lineSequence().first().replace(Regex("""^Unexpected JSON token at offset \d+:\s*"""), "")
            null to listOf(RuleDiagnostic(file, pos.line, pos.column, DiagnosticSeverity.ERROR, "Invalid JSON: $first"))
        }
    }
}

class DiagnosticSink(val file: String, text: String) {
    private val locator = JsonLocator(text)
    val diagnostics = mutableListOf<RuleDiagnostic>()

    fun error(path: String, message: String, atKey: Boolean = false) = add(DiagnosticSeverity.ERROR, path, message, atKey)

    fun warning(path: String, message: String, atKey: Boolean = false) = add(DiagnosticSeverity.WARNING, path, message, atKey)

    fun info(path: String, message: String, atKey: Boolean = false) = add(DiagnosticSeverity.INFO, path, message, atKey)

    fun position(path: String): JsonLocator.Pos = locator.value(path)

    fun add(severity: DiagnosticSeverity, path: String, message: String, atKey: Boolean = false) {
        val p = if (atKey) locator.key(path) else locator.value(path)
        val where = if (path.isEmpty()) "" else "$path: "
        diagnostics += RuleDiagnostic(file, p.line, p.column, severity, where + message)
    }

    val hasErrors: Boolean get() = diagnostics.hasErrors()
}
