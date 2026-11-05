package eu.tiducto.osmreview.rules

import com.geodesk.feature.Feature
import com.geodesk.feature.FeatureLibrary
import com.geodesk.feature.Features
import com.geodesk.geom.Box
import com.geodesk.geom.Mercator
import eu.tiducto.osmreview.shared.Confidence
import eu.tiducto.osmreview.shared.Rule
import eu.tiducto.osmreview.shared.Severity
import eu.tiducto.osmreview.shared.TagEdit
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.prep.PreparedGeometry
import org.locationtech.jts.geom.prep.PreparedGeometryFactory

/**
 * The area a rule runs in: a bbox, optionally refined by a polygon. Geometry is in GeoDesk's
 * Mercator integer space (see [Geo.toMercator]).
 */
class Scope(val bounds: Box, val polygon: Geometry? = null) {
    val prepared: PreparedGeometry? by lazy { polygon?.let(PreparedGeometryFactory::prepare) }
}

/** What a rule's lambdas see as `this`: the open GOL as `gol`. */
class RuleContext(val gol: FeatureLibrary)

/**
 * A rule's proposed fix. `addTags` sets (adds or overwrites), `removeTags` drops keys,
 * `replaceTags` replaces the whole tag set. [confidence] overrides the rule's for this one object
 * (e.g. deprecated-tags is deterministic only for 1:1 mappings).
 */
data class Fix(
    val addTags: Map<String, String> = emptyMap(),
    val removeTags: List<String> = emptyList(),
    val replaceTags: Map<String, String>? = null,
    val confidence: Confidence? = null,
    val note: String? = null,
) {
    fun toTagEdit() = TagEdit(set = addTags, remove = removeTags, replaceAll = replaceTags)
}

class RuleDef(
    val id: String,
    val title: String,
    val description: String,
    val severity: Severity,
    val confidence: Confidence,
    val find: RuleContext.(Scope) -> Iterable<Feature>,
    val fix: (RuleContext.(Feature) -> Fix?)?,
    val context: (RuleContext.(Feature) -> Iterable<Feature>)?,
) {
    fun toModel(enabled: Boolean = true) = Rule(id, title, description, severity, confidence, fix != null, enabled)
}

fun rule(
    id: String,
    title: String,
    severity: Severity,
    confidence: Confidence,
    description: String = "",
    find: RuleContext.(Scope) -> Iterable<Feature>,
    fix: (RuleContext.(Feature) -> Fix?)?,
    context: (RuleContext.(Feature) -> Iterable<Feature>)? = null,
): RuleDef = RuleDef(id, title, description, severity, confidence, find, fix, context)

/** Restricts a query to the scope: bbox first (index), then the polygon if there is one. */
fun Features.within(scope: Scope): Features {
    val inBox = `in`(scope.bounds)
    return scope.prepared?.let { inBox.intersecting(it) } ?: inBox
}

/**
 * Features within [meters] of [feature]. GeoDesk for Java 2.1.0 declares `maxMetersFrom(double,
 * Feature)` but throws "todo"; this does the same with a bbox prefilter and an exact JTS distance.
 */
fun Features.withinMeters(meters: Double, feature: Feature): List<Feature> {
    val geom = feature.toGeometry()
    return `in`(Box.metersAround(meters, feature.bounds()))
        .filter { it.id() != feature.id() || it.type() != feature.type() }
        .filter { Mercator.distance(geom, it.toGeometry()) <= meters }
}
