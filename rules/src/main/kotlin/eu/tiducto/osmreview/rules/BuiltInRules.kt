package eu.tiducto.osmreview.rules

import com.geodesk.feature.Feature
import eu.tiducto.osmreview.shared.Confidence
import eu.tiducto.osmreview.shared.Severity

object BuiltInRules {
    private const val PLATFORM_WAYS = "w[public_transport=platform], w[highway=platform], w[railway=platform]"

    val deprecatedTags = rule(
        id = "deprecated-tags",
        title = "Deprecated tags",
        description = "Tags listed in iD's deprecated.json (id-tagging-schema ${DeprecatedTags.schemaVersion}), upgraded the way iD does.",
        changesetComment = "upgrade deprecated tags",
        severity = Severity.WARNING,
        confidence = Confidence.NEEDS_REVIEW,
        find = { scope ->
            gol.select(DeprecatedTags.goql).within(scope).filter { DeprecatedTags.upgrade(it.tagMap()) != null }
        },
        fix = { f ->
            val tags = f.tagMap()
            DeprecatedTags.upgrade(tags)?.let { (upgraded, applied) ->
                val removed = tags.keys - upgraded.keys
                val set = upgraded.filter { (k, v) -> tags[k] != v }
                Fix(
                    addTags = set,
                    removeTags = removed.toList(),
                    confidence = if (applied.all { it.isOneToOne }) Confidence.DETERMINISTIC else Confidence.NEEDS_REVIEW,
                    note = applied.joinToString("; ") { e ->
                        e.old.entries.joinToString("+") { "${it.key}=${it.value}" } + " → " +
                            (e.replace?.entries?.joinToString("+") { "${it.key}=${it.value}" } ?: "(remove)")
                    },
                )
            }
        },
    )

    val platformNodeOnly = rule(
        id = "platform-node-only",
        title = "Platform mapped only as a node",
        description = "A platform node with no platform way within 15 m. If imagery shows the platform, draw it as an area in iD.",
        severity = Severity.INFO,
        confidence = Confidence.NEEDS_REVIEW,
        find = { scope ->
            val platformWays = gol.select(PLATFORM_WAYS)
            gol.select("n[public_transport=platform]").within(scope)
                .filter { node -> platformWays.withinMeters(15.0, node).isEmpty() }
        },
        fix = null,
    )

    val sidewalkSeparate = rule(
        id = "sidewalk-separate",
        title = "Sidewalk tagged on road but mapped separately",
        description = "The road says sidewalk=* while a footway=sidewalk runs parallel within 10 m. The side is computed from geometry.",
        changesetComment = "mark separately mapped sidewalks on roads",
        severity = Severity.WARNING,
        confidence = Confidence.NEEDS_REVIEW,
        find = { scope ->
            val sidewalks = gol.select("w[footway=sidewalk]")
            gol.select("w[highway][sidewalk=both,left,right,yes]").within(scope)
                .filter { road -> SidewalkSide.sides(road, sidewalks.withinMeters(10.0, road)).isNotEmpty() }
        },
        fix = { road -> sidewalkFix(road, gol.select("w[footway=sidewalk]").withinMeters(10.0, road)) },
        context = { road -> gol.select("w[footway=sidewalk]").withinMeters(10.0, road) },
    )

    val all: List<RuleDef> = listOf(deprecatedTags, platformNodeOnly, sidewalkSeparate)

    fun noteFor(rule: RuleDef, f: Feature): String? = when (rule.id) {
        platformNodeOnly.id -> "no platform way within 15 m"
        else -> null
    }

    fun sidewalkFix(road: Feature, nearby: List<Feature>): Fix? {
        val sides = SidewalkSide.sides(road, nearby)
        if (sides.isEmpty()) return null
        val remove = listOf("sidewalk", "sidewalk:both", "sidewalk:left", "sidewalk:right").filter { road.tag(it).isNotEmpty() }
        return if (sides.size == 2) {
            Fix(addTags = mapOf("sidewalk:both" to "separate"), removeTags = remove - "sidewalk:both")
        } else {
            val side = sides.single().name.lowercase()
            val other = if (side == "left") "right" else "left"
            Fix(
                addTags = mapOf("sidewalk:$side" to "separate"),
                removeTags = remove - "sidewalk:$side",
                note = "sidewalk found on the $side only; set sidewalk:$other to no or separate",
            )
        }
    }
}
