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

class Scope(val bounds: Box, val polygon: Geometry? = null) {
    val prepared: PreparedGeometry? by lazy { polygon?.let(PreparedGeometryFactory::prepare) }
}

class RuleContext(val gol: FeatureLibrary)

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
    val changesetComment: String,
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
    changesetComment: String = title.replaceFirstChar(Char::lowercase),
    find: RuleContext.(Scope) -> Iterable<Feature>,
    fix: (RuleContext.(Feature) -> Fix?)?,
    context: (RuleContext.(Feature) -> Iterable<Feature>)? = null,
): RuleDef = RuleDef(id, title, description, changesetComment, severity, confidence, find, fix, context)

fun Features.within(scope: Scope): Features {
    val inBox = `in`(scope.bounds)
    return scope.prepared?.let { inBox.intersecting(it) } ?: inBox
}

fun Features.withinMeters(meters: Double, feature: Feature): List<Feature> {
    val geom = feature.toGeometry()
    return `in`(Box.metersAround(meters, feature.bounds()))
        .filter { it.id() != feature.id() || it.type() != feature.type() }
        .filter { Mercator.distance(geom, it.toGeometry()) <= meters }
}
