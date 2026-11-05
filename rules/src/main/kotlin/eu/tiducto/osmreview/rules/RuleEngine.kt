package eu.tiducto.osmreview.rules

import com.geodesk.feature.Feature
import eu.tiducto.osmreview.shared.Issue
import eu.tiducto.osmreview.shared.Rule
import eu.tiducto.osmreview.shared.RuleRunResult
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.measureTimedValue

class RuleEngine(private val mirror: GolMirror, rules: List<RuleDef> = BuiltInRules.all) {
    private val byId: Map<String, RuleDef> = rules.associateBy { it.id }
    private val cache = ConcurrentHashMap<CacheKey, List<Issue>>()

    private data class CacheKey(val ruleId: String, val scopeKey: String, val generation: Long)

    fun rules(): List<Rule> = byId.values.map { it.toModel() }

    fun definition(id: String): RuleDef = byId[id] ?: throw UnknownRuleException(id)

    fun run(ruleId: String, scope: Scope, scopeKey: String, limit: Int? = null): RuleRunResult {
        val def = definition(ruleId)
        val (issues, took) = measureTimedValue {
            val key = CacheKey(ruleId, scopeKey, mirror.generation)
            cache[key] ?: mirror.query { gol -> evaluate(def, RuleContext(gol), scope) }.also { found ->
                cache.keys.removeIf { it.generation != mirror.generation }
                cache[key] = found
            }
        }
        val capped = if (limit != null && issues.size > limit) issues.take(limit) else issues
        return RuleRunResult(ruleId, issues.size, capped, capped.size < issues.size, took.inWholeMilliseconds)
    }

    fun issueFor(ruleId: String, feature: Feature, ctx: RuleContext): Issue = toIssue(definition(ruleId), ctx, feature)

    private fun evaluate(def: RuleDef, ctx: RuleContext, scope: Scope): List<Issue> =
        def.find(ctx, scope).map { toIssue(def, ctx, it) }

    private fun toIssue(def: RuleDef, ctx: RuleContext, f: Feature): Issue {
        val fix = def.fix?.invoke(ctx, f)
        val edit = fix?.toTagEdit()?.takeUnless { it.isEmpty }
        return Issue(
            ruleId = def.id,
            ref = f.ref,
            tags = f.tagMap(),
            centroid = f.centroid(),
            severity = def.severity,
            fix = edit,
            confidence = fix?.confidence ?: def.confidence,
            context = def.context?.invoke(ctx, f)?.map { it.ref }.orEmpty(),
            note = fix?.note ?: BuiltInRules.noteFor(def, f),
        )
    }
}

class UnknownRuleException(id: String) : IllegalArgumentException("Unknown rule '$id'")
