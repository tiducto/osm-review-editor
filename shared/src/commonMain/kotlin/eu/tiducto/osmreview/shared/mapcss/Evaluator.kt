package eu.tiducto.osmreview.shared.mapcss

import eu.tiducto.osmreview.shared.OsmType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.floor

internal class EvalContext(val element: MapCssElement, val host: MapCssHost)

internal object Evaluator {
    private val CONSTANT_CONTEXT = EvalContext(SimpleElement(OsmType.NODE), MapCssHost.NONE)

    fun constant(values: List<Expr>): JsonElement = toJson(values.map { eval(it, CONSTANT_CONTEXT) }.let { if (it.size == 1) it[0] else it })

    fun eval(e: Expr, ctx: EvalContext): Any? = when (e) {
        is Lit -> e.value
        is Ident -> when (e.name) {
            "true" -> true
            "false" -> false
            else -> e.name
        }
        is Unary -> when (e.op) {
            "!" -> !truthy(eval(e.operand, ctx))
            else -> num(eval(e.operand, ctx))?.let { -it }
        }
        is Ternary -> if (truthy(eval(e.test, ctx))) eval(e.then, ctx) else eval(e.otherwise, ctx)
        is Binary -> binary(e, ctx)
        is Call -> call(e, ctx)
    }

    private fun binary(e: Binary, ctx: EvalContext): Any? {
        when (e.op) {
            "&&" -> return truthy(eval(e.left, ctx)) && truthy(eval(e.right, ctx))
            "||" -> return truthy(eval(e.left, ctx)) || truthy(eval(e.right, ctx))
        }
        val a = eval(e.left, ctx)
        val b = eval(e.right, ctx)
        return when (e.op) {
            "==" -> equal(a, b)
            "!=" -> !equal(a, b)
            "<", "<=", ">", ">=" -> {
                val x = num(a) ?: return false
                val y = num(b) ?: return false
                when (e.op) {
                    "<" -> x < y
                    "<=" -> x <= y
                    ">" -> x > y
                    else -> x >= y
                }
            }
            else -> {
                val x = num(a) ?: return null
                val y = num(b) ?: return null
                val r = when (e.op) {
                    "+" -> x + y
                    "-" -> x - y
                    "*" -> x * y
                    "/" -> x / y
                    else -> x % y
                }
                if (r.isNaN() || r.isInfinite()) null else r
            }
        }
    }

    fun equal(a: Any?, b: Any?): Boolean {
        if (a is Boolean || b is Boolean) return truthy(a) == truthy(b)
        if (a is Double || b is Double) {
            val x = num(a)
            val y = num(b)
            if (x != null && y != null) return x == y
        }
        return str(a) == str(b)
    }

    fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is Double -> v != 0.0 && !v.isNaN()
        is List<*> -> v.isNotEmpty()
        is String -> v.isNotEmpty() && v != "false" && v != "no" && v != "0"
        else -> true
    }

    fun num(v: Any?): Double? = when (v) {
        is Double -> v
        is Number -> v.toDouble()
        is Boolean -> if (v) 1.0 else 0.0
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }

    fun str(v: Any?): String = when (v) {
        null -> ""
        is Double -> formatNumber(v)
        is Number -> formatNumber(v.toDouble())
        is List<*> -> v.joinToString(";") { str(it) }
        else -> v.toString()
    }

    fun formatNumber(d: Double): String =
        if (d == floor(d) && abs(d) < 1e15) d.toLong().toString() else d.toString()

    fun normalize(v: Any?): Any? = when (v) {
        null, is String, is Boolean, is Double -> v
        is Number -> v.toDouble()
        is List<*> -> v.map { normalize(it) }
        is Array<*> -> v.map { normalize(it) }
        else -> v.toString()
    }

    fun toJson(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(v)
        is Double -> if (v == floor(v) && abs(v) < 1e15) JsonPrimitive(v.toLong()) else JsonPrimitive(v)
        is Number -> toJson(v.toDouble())
        is List<*> -> JsonArray(v.map { toJson(it) })
        else -> JsonPrimitive(v.toString())
    }

    private fun call(e: Call, ctx: EvalContext): Any? {
        if (e.kind == CallKind.UNSUPPORTED) return null
        if (e.name == "cond") {
            if (e.args.size != 3) return null
            return if (truthy(eval(e.args[0], ctx))) eval(e.args[1], ctx) else eval(e.args[2], ctx)
        }
        if (e.name == "any" || e.name == "coalesce") {
            for (a in e.args) eval(a, ctx)?.let { return it }
            return null
        }
        val args = e.args.map { eval(it, ctx) }
        if (e.kind == CallKind.HOST) return host(e.name, args, ctx)
        return Functions.builtin(e, args, ctx)
    }

    private fun host(name: String, args: List<Any?>, ctx: EvalContext): Any? {
        val r = normalize(ctx.host.call(name, ctx.element, args))
        if (r != null) return r
        return when (name) {
            "outside" -> ctx.host.call("inside", ctx.element, args)?.let { !truthy(normalize(it)) }
            "is_right_hand_traffic" -> ctx.host.pseudoClass("righthandtraffic", ctx.element)
            else -> null
        }
    }
}
