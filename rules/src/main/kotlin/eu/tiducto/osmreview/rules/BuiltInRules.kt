package eu.tiducto.osmreview.rules

import com.geodesk.feature.Feature
import eu.tiducto.osmreview.shared.Confidence
import eu.tiducto.osmreview.shared.Severity

/**
 * The initial rule set from the spec. Each rule is plain Kotlin over GOQL, so new ones are easy to
 * add by hand (or by Claude): define a `rule(...)` and list it in [all].
 */
object BuiltInRules {
    private const val PLATFORM_WAYS = "w[public_transport=platform], w[highway=platform], w[railway=platform]"

    val platformArea = rule(
        id = "platform-area",
        title = "Closed platform way without area=yes",
        description = "A closed platform way is drawn as an outline; area=yes makes renderers and routers treat it as a surface.",
        severity = Severity.WARNING,
        confidence = Confidence.DETERMINISTIC,
        find = { scope ->
            gol.select("w[public_transport=platform][!area], w[highway=platform][!area], w[railway=platform][!area]")
                .within(scope)
                .filter { it.isClosedRing() }
        },
        fix = { Fix(addTags = mapOf("area" to "yes")) },
    )

    val deprecatedTags = rule(
        id = "deprecated-tags",
        title = "Deprecated tags",
        description = "Tags listed in iD's deprecated.json (id-tagging-schema ${DeprecatedTags.schemaVersion}), upgraded the way iD does.",
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

    val crossingMissingType = rule(
        id = "crossing-missing-type",
        title = "Crossing without crossing=*",
        description = "highway=crossing without crossing=*. A type is suggested only when crossing_ref implies it.",
        severity = Severity.INFO,
        confidence = Confidence.NEEDS_REVIEW,
        find = { scope -> gol.select("n[highway=crossing][!crossing]").within(scope) },
        fix = { node ->
            when (node.tag("crossing_ref")) {
                "zebra" -> Fix(addTags = mapOf("crossing" to "uncontrolled"))
                "pelican", "toucan", "pegasus", "puffin", "tiger" -> Fix(addTags = mapOf("crossing" to "traffic_signals"))
                else -> null
            }
        },
    )

    val footwayMissingSurface = rule(
        id = "footway-missing-surface",
        title = "Footway, path or cycleway without surface",
        description = "Suggests the surface of connected footways, paths and cycleways when they all agree.",
        severity = Severity.INFO,
        confidence = Confidence.NEEDS_REVIEW,
        find = { scope -> gol.select("w[highway=footway,path,cycleway][!surface]").within(scope) },
        fix = { way ->
            val surfaces = gol.select("w[highway=footway,path,cycleway,pedestrian,steps][surface]")
                .connectedTo(way)
                .filter { it.id() != way.id() }
                .map { it.tag("surface") }
                .toSet()
            surfaces.singleOrNull()?.let { Fix(addTags = mapOf("surface" to it), note = "connected ways all have surface=$it") }
        },
    )

    val platformDetails = rule(
        id = "platform-details",
        title = "Platform missing shelter, bench or tactile_paving",
        description = "Platforms without shelter, bench or tactile_paving. Issue only: these need a survey or street-level imagery.",
        severity = Severity.INFO,
        confidence = Confidence.NEEDS_REVIEW,
        find = { scope ->
            gol.select("n[public_transport=platform], $PLATFORM_WAYS").within(scope)
                .filter { p -> DETAIL_KEYS.any { p.tag(it).isEmpty() } }
        },
        fix = null,
    )

    private val DETAIL_KEYS = listOf("shelter", "bench", "tactile_paving")

    val all: List<RuleDef> = listOf(
        deprecatedTags, platformArea, platformNodeOnly, sidewalkSeparate,
        crossingMissingType, footwayMissingSurface, platformDetails,
    )

    /** Notes shown with an issue that has no fix (what is missing, what to do). */
    fun noteFor(rule: RuleDef, f: Feature): String? = when (rule.id) {
        platformDetails.id -> "missing: " + DETAIL_KEYS.filter { f.tag(it).isEmpty() }.joinToString(", ")
        platformNodeOnly.id -> "no platform way within 15 m"
        else -> null
    }

    /**
     * sidewalk=* on a road whose sidewalks are mapped separately: both sides found → sidewalk:both=
     * separate; one side → that side separate and the other left for the reviewer to set.
     */
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
