package eu.tiducto.osmreview.shared.mapcss

import eu.tiducto.osmreview.shared.mapcss.Evaluator.num
import eu.tiducto.osmreview.shared.mapcss.Evaluator.str
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.truncate

internal object Functions {
    private val INDEX = Regex("""\{(\d+)\}""")

    fun builtin(e: Call, args: List<Any?>, ctx: EvalContext): Any? {
        val el = ctx.element
        fun s(i: Int): String? = args.getOrNull(i)?.let { str(it) }
        fun n(i: Int): Double? = num(args.getOrNull(i))
        return when (e.name) {
            "tag" -> s(0)?.let { el.tags[it] }
            "has_tag_key" -> s(0)?.let { it in el.tags }
            "parent_tag" -> s(0)?.let { k -> el.parents.firstNotNullOfOrNull { it.element.tags[k] } }
            "parent_tags" -> s(0)?.let { k -> el.parents.mapNotNull { it.element.tags[k] }.distinct().sortedWith(::naturalCompare) }
            "child_tag" -> s(0)?.let { k -> el.children.firstNotNullOfOrNull { it.element.tags[k] } }
            "child_tags" -> s(0)?.let { k -> el.children.mapNotNull { it.element.tags[k] }.distinct().sortedWith(::naturalCompare) }
            "parent_osm_id" -> el.parents.firstOrNull()?.element?.id?.toDouble()
            "count_roles" -> {
                val roles = args.map { str(it) }.toSet()
                el.children.count { it.role in roles }.toDouble()
            }
            "number_of_tags" -> el.tags.size.toDouble()
            "osm_id" -> el.id.toDouble()
            "osm_type" -> el.type.apiName
            "regexp_test" -> {
                val r = regex(e, args) ?: return null
                s(1)?.let { r.matches(it) }
            }
            "regexp_match" -> {
                val r = regex(e, args) ?: return null
                val m = s(1)?.let { r.matchEntire(it) } ?: return null
                m.groups.map { it?.value }
            }
            "to_int", "to_long" -> {
                val v = args.getOrNull(0)
                (if (v is String) v.trim().toLongOrNull()?.toDouble() else num(v)?.let { truncate(it) })
            }
            "to_float", "to_double" -> n(0)
            "to_boolean", "boolean" -> args.getOrNull(0)?.let { v ->
                if (v is Boolean) v else str(v).trim().lowercase() in setOf("yes", "true", "1", "on")
            }
            "not" -> !Evaluator.truthy(args.getOrNull(0))
            "equal" -> Evaluator.equal(args.getOrNull(0), args.getOrNull(1))
            "str" -> args.getOrNull(0)?.let { str(it) }
            "split" -> {
                val sep = s(0) ?: return null
                val v = s(1) ?: return null
                if (sep.isEmpty()) v.map { it.toString() } else v.split(sep)
            }
            "count" -> (args.getOrNull(0) as? List<*>)?.size?.toDouble()
            "length" -> when (val v = args.getOrNull(0)) {
                null -> null
                is List<*> -> v.size.toDouble()
                else -> str(v).length.toDouble()
            }
            "concat" -> args.joinToString("") { str(it) }
            "join" -> {
                val sep = s(0) ?: return null
                val rest = args.drop(1)
                val items = if (rest.size == 1 && rest[0] is List<*>) rest[0] as List<*> else rest
                items.filterNotNull().joinToString(sep) { str(it) }
            }
            "join_list" -> {
                val sep = s(0) ?: return null
                (args.getOrNull(1) as? List<*>)?.filterNotNull()?.joinToString(sep) { str(it) }
            }
            "tr" -> {
                val fmt = s(0) ?: return null
                INDEX.replace(fmt) { m -> args.getOrNull(m.groupValues[1].toInt() + 1)?.let { str(it) } ?: "" }
            }
            "is_similar" -> {
                val a = s(0) ?: return null
                val b = s(1) ?: return null
                levenshtein(a, b) in 1..2
            }
            "upper" -> s(0)?.uppercase()
            "lower" -> s(0)?.lowercase()
            "trim" -> s(0)?.trim()
            "title" -> s(0)?.split(" ")?.joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
            "get" -> {
                val list = args.getOrNull(0) as? List<*> ?: return null
                val i = n(1)?.toInt() ?: return null
                list.getOrNull(i)
            }
            "list" -> args
            "uniq" -> args.distinct()
            "sort" -> args.map { str(it) }.sortedWith(::naturalCompare)
            "uniq_list" -> (args.getOrNull(0) as? List<*>)?.distinct()
            "sort_list" -> (args.getOrNull(0) as? List<*>)?.map { str(it) }?.sortedWith(::naturalCompare)
            "max" -> args.mapNotNull { num(it) }.maxOrNull()
            "min" -> args.mapNotNull { num(it) }.minOrNull()
            "abs" -> n(0)?.let { abs(it) }
            "round" -> n(0)?.let { round(it) }
            "ceil" -> n(0)?.let { ceil(it) }
            "floor" -> n(0)?.let { floor(it) }
            "sqrt" -> n(0)?.let { sqrt(it) }?.takeIf { !it.isNaN() }
            "replace" -> {
                val v = s(0) ?: return null
                val a = s(1) ?: return null
                v.replace(a, s(2) ?: "")
            }
            "substring" -> {
                val v = s(0) ?: return null
                val start = (n(1)?.toInt() ?: 0).coerceIn(0, v.length)
                val end = (n(2)?.toInt() ?: v.length).coerceIn(start, v.length)
                v.substring(start, end)
            }
            "startswith" -> s(1)?.let { p -> s(0)?.startsWith(p) }
            "endswith" -> s(1)?.let { p -> s(0)?.endsWith(p) }
            "plus" -> args.mapNotNull { num(it) }.takeIf { it.size == args.size }?.sum()
            "minus" -> arithmetic(args) { a, b -> a - b }
            "times" -> args.mapNotNull { num(it) }.takeIf { it.size == args.size }?.fold(1.0) { a, b -> a * b }
            "divided_by" -> arithmetic(args) { a, b -> a / b }
            "mod" -> arithmetic(args) { a, b -> a % b }
            "greater" -> compare(args) { it > 0 }
            "less" -> compare(args) { it < 0 }
            "greater_equal" -> compare(args) { it >= 0 }
            "less_equal" -> compare(args) { it <= 0 }
            "int", "to_short", "to_byte" -> n(0)?.let { truncate(it) }
            "signum" -> n(0)?.let { sign(it) }
            "sin" -> n(0)?.let { sin(it) }
            "cos" -> n(0)?.let { cos(it) }
            "tan" -> n(0)?.let { tan(it) }
            "asin" -> n(0)?.let { asin(it) }?.takeIf { !it.isNaN() }
            "acos" -> n(0)?.let { acos(it) }?.takeIf { !it.isNaN() }
            "atan" -> n(0)?.let { atan(it) }
            "atan2" -> n(1)?.let { x -> n(0)?.let { y -> atan2(y, x) } }
            "log" -> n(0)?.let { ln(it) }?.takeIf { !it.isNaN() && !it.isInfinite() }
            "exp" -> n(0)?.let { exp(it) }
            "degree_to_radians" -> n(0)?.let { it * PI / 180.0 }
            "siunit_length" -> s(0)?.let { siLength(it) }
            "tag_regex" -> {
                val r = regex(e, args) ?: return null
                el.tags.filterKeys { r.containsMatchIn(it) }.values.toList()
            }
            "URL_decode" -> s(0)?.let { urlDecode(it) }
            "URL_encode" -> s(0)?.let { urlEncode(it) }
            "XML_encode" -> s(0)?.let { xmlEncode(it) }
            "rgb", "rgba" -> {
                val c = (0 until 3).map { n(it) ?: return null }
                "#" + c.joinToString("") { v ->
                    val x = (if (c.all { it <= 1.0 }) v * 255 else v).toInt().coerceIn(0, 255)
                    x.toString(16).padStart(2, '0')
                }
            }
            else -> null
        }
    }

    private fun arithmetic(args: List<Any?>, op: (Double, Double) -> Double): Double? {
        if (args.isEmpty()) return null
        val ns = args.map { num(it) ?: return null }
        val r = ns.drop(1).fold(ns[0], op)
        return if (r.isNaN() || r.isInfinite()) null else r
    }

    private fun compare(args: List<Any?>, test: (Int) -> Boolean): Boolean? {
        val a = num(args.getOrNull(0)) ?: return null
        val b = num(args.getOrNull(1)) ?: return null
        return test(a.compareTo(b))
    }

    private val UNITS = mapOf(
        "" to 1.0, "m" to 1.0, "km" to 1000.0, "cm" to 0.01, "mm" to 0.001, "mi" to 1609.344, "nmi" to 1852.0,
        "ft" to 0.3048, "'" to 0.3048, "in" to 0.0254, "\"" to 0.0254,
    )
    private val LENGTH = Regex("""^(-?\d+(?:\.\d+)?)\s*(m|km|cm|mm|mi|nmi|ft|'|in|")?$""")
    private val FEET_INCHES = Regex("""^(\d+(?:\.\d+)?)'\s*(\d+(?:\.\d+)?)"$""")

    fun siLength(text: String): Double? {
        val t = text.trim()
        FEET_INCHES.matchEntire(t)?.let { m ->
            return m.groupValues[1].toDouble() * 0.3048 + m.groupValues[2].toDouble() * 0.0254
        }
        val m = LENGTH.matchEntire(t) ?: return null
        return m.groupValues[1].toDouble() * UNITS.getValue(m.groupValues[2])
    }

    private fun urlDecode(s: String): String? {
        val bytes = mutableListOf<Byte>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '%' && i + 2 < s.length -> {
                    bytes += (s.substring(i + 1, i + 3).toIntOrNull(16) ?: return null).toByte()
                    i += 3
                }
                c == '%' -> return null
                c == '+' -> {
                    bytes += ' '.code.toByte()
                    i++
                }
                else -> {
                    bytes += c.toString().encodeToByteArray().toList()
                    i++
                }
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun urlEncode(s: String): String = buildString {
        for (b in s.encodeToByteArray()) {
            val c = (b.toInt() and 0xff)
            val ch = c.toChar()
            if (ch.isLetterOrDigit() && c < 128 || ch in "-_.*") append(ch) else if (ch == ' ') append('+') else {
                append('%')
                append(c.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }

    private fun xmlEncode(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    private fun regex(e: Call, args: List<Any?>): Regex? {
        e.constRegex?.let { return it }
        val p = args.getOrNull(0)?.let { str(it) } ?: return null
        val flags = (if (e.name == "tag_regex") args.getOrNull(1) else args.getOrNull(2))?.let { str(it) } ?: ""
        return try {
            Regexes.compile(p, flags)
        } catch (_: Throwable) {
            null
        }
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }

    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ei
                j = ej
            } else {
                if (ca != cb) return ca - cb
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
