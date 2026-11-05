package eu.tiducto.osmreview.shared.mapcss

enum class SelectorType { NODE, WAY, RELATION, AREA, ANY, CANVAS, META }

sealed interface KeyRef {
    data class Name(val key: String) : KeyRef

    class Pattern(val regex: Regex, val source: String) : KeyRef

    data object AnyKey : KeyRef
}

enum class TagOp { PRESENT, ABSENT, TRUE, NOT_TRUE, FALSE, EQ, NEQ, PREFIX, SUFFIX, CONTAINS, LIST, LT, LE, GT, GE, MATCH, NOT_MATCH }

enum class PseudoClass { CLOSED, UNCLOSED, TAGGED, UNTAGGED, UNCONNECTED, CONNECTION, RIGHT_HAND_TRAFFIC, IN_DOWNLOADED_AREA, ALWAYS, NEVER }

sealed class Condition

class TagCondition(
    val key: KeyRef,
    val op: TagOp,
    val value: String = "",
    val regex: Regex? = null,
    val number: Double? = null,
    val derefKey: String? = null,
) : Condition() {
    val keyName: String? get() = (key as? KeyRef.Name)?.key

    val ignoreCase: Boolean get() = regex?.options?.contains(RegexOption.IGNORE_CASE) == true
}

class ClassCondition(val name: String, val negated: Boolean) : Condition()

class PseudoCondition(val pseudo: PseudoClass, val name: String, val negated: Boolean) : Condition()

class ExpressionCondition(val expr: Expr) : Condition()

enum class LinkKind { CHILD, PARENT, INSIDE, CROSSING }

class Link(val kind: LinkKind, val conditions: List<TagCondition>)

class SimpleSelector(val type: SelectorType, val conditions: List<Condition>)

class Selector(
    val parts: List<SimpleSelector>,
    val links: List<Link>,
    val text: String,
    val line: Int,
    val column: Int,
    val disabledReason: String? = null,
) {
    val subject: SimpleSelector get() = parts.last()

    val isLinked: Boolean get() = parts.size > 1
}

sealed class Declaration {
    abstract val line: Int
    abstract val column: Int
}

class SetClass(val name: String, override val line: Int, override val column: Int) : Declaration()

class Property(val name: String, val values: List<Expr>, override val line: Int, override val column: Int) : Declaration()

class Rule(
    val index: Int,
    val selectors: List<Selector>,
    val declarations: List<Declaration>,
    val line: Int,
    val column: Int,
) {
    val properties: List<Property> = declarations.filterIsInstance<Property>()

    val setClasses: List<String> = declarations.filterIsInstance<SetClass>().map { it.name }

    val isCheck: Boolean = properties.any { it.name in Vocabulary.THROWS }

    val hasStyle: Boolean = properties.any { it.name !in Vocabulary.VALIDATOR }
}

sealed class Expr

class Lit(val value: Any?) : Expr()

class Ident(val name: String) : Expr()

enum class CallKind { BUILTIN, HOST, UNSUPPORTED }

class Call(val name: String, val args: List<Expr>, val kind: CallKind, val constRegex: Regex? = null) : Expr()

class Unary(val op: String, val operand: Expr) : Expr()

class Binary(val op: String, val left: Expr, val right: Expr) : Expr()

class Ternary(val test: Expr, val then: Expr, val otherwise: Expr) : Expr()
