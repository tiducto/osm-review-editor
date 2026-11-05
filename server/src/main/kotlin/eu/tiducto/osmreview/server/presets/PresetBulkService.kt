package eu.tiducto.osmreview.server.presets

import com.geodesk.feature.Feature
import eu.tiducto.osmreview.rules.GolMirror
import eu.tiducto.osmreview.rules.MirrorQueries
import eu.tiducto.osmreview.rules.tagMap
import eu.tiducto.osmreview.server.core.DraftService
import eu.tiducto.osmreview.server.core.NotFoundException
import eu.tiducto.osmreview.server.core.StateException
import eu.tiducto.osmreview.server.db.DraftMeta
import eu.tiducto.osmreview.shared.Authors
import eu.tiducto.osmreview.shared.Confidence
import eu.tiducto.osmreview.shared.OsmRef
import eu.tiducto.osmreview.shared.ProposalResult
import eu.tiducto.osmreview.shared.ProposedChange
import eu.tiducto.osmreview.shared.TagDiff
import eu.tiducto.osmreview.shared.TagEdit
import eu.tiducto.osmreview.shared.presets.PresetApplyMode
import eu.tiducto.osmreview.shared.presets.PresetBulkApplyRequest
import eu.tiducto.osmreview.shared.presets.PresetBulkApplyResult
import eu.tiducto.osmreview.shared.presets.PresetBulkItem
import eu.tiducto.osmreview.shared.presets.PresetBulkSkip
import eu.tiducto.osmreview.shared.presets.PresetGeometry
import eu.tiducto.osmreview.shared.withoutPseudoTags

data class PresetTarget(val tags: Map<String, String>, val geometry: PresetGeometry)

class PresetBulkService(
    private val presets: PresetService,
    private val drafts: DraftService,
    private val lookup: (Collection<OsmRef>) -> Map<OsmRef, PresetTarget>,
) {
    constructor(presets: PresetService, drafts: DraftService, mirror: GolMirror, queries: MirrorQueries) :
        this(presets, drafts, { refs -> mirrorTargets(mirror, queries, refs) })

    private class Planned(val ref: OsmRef, val name: String?, val from: String?, val before: Map<String, String>, val edit: TagEdit, val after: Map<String, String>)

    suspend fun apply(req: PresetBulkApplyRequest, author: String = Authors.USER): PresetBulkApplyResult {
        require(req.refs.isNotEmpty()) { "Give at least one ref" }
        require(req.refs.size <= MAX_REFS) { "At most $MAX_REFS objects per call" }
        val preset = presets.get(req.presetId, req.locale)
        val index = presets.index
        val refs = req.refs.distinct()
        val targets = lookup(refs)

        val skipped = ArrayList<PresetBulkSkip>()
        val planned = ArrayList<Planned>()
        var unchanged = 0
        for (ref in refs) {
            val t = targets[ref]
            if (t == null) {
                skipped += PresetBulkSkip(ref, "not in the mirror")
                continue
            }
            val tags = t.tags.withoutPseudoTags()
            if (t.geometry !in preset.geometry) {
                skipped += PresetBulkSkip(ref, "preset does not apply to ${t.geometry.wireName}")
                continue
            }
            if (preset.apply == PresetApplyMode.CREATE && tags.isNotEmpty()) {
                skipped += PresetBulkSkip(ref, "preset only applies to new, untagged objects")
                continue
            }
            val from = index.match(tags, t.geometry)?.preset?.id
            val attempt = runCatching { index.apply(preset.id, tags, t.geometry, from) }
            val applied = attempt.getOrNull()
            if (applied == null) {
                skipped += PresetBulkSkip(ref, attempt.exceptionOrNull()?.message ?: "could not apply")
                continue
            }
            if (TagDiff.between(tags, applied.tags).isNoOp) {
                unchanged++
                continue
            }
            planned += Planned(ref, tags["name"], from, tags, applied.edit, applied.tags)
        }

        val preview = planned.take(MAX_PREVIEW).map { p ->
            PresetBulkItem(p.ref, p.name, p.from, p.before, p.after, TagDiff.between(p.before, p.after).summary())
        }
        val base = PresetBulkApplyResult(
            presetId = preset.id,
            presetName = preset.name,
            total = refs.size,
            changed = planned.size,
            unchanged = unchanged,
            skipped = skipped,
            preview = preview,
            truncated = planned.size > MAX_PREVIEW,
        )
        if (req.dryRun || planned.isEmpty()) return base

        val draftId = req.draftId?.also { id ->
            val draft = drafts.detail(id).draft
            if (draft.status !in DraftService.ACTIVE) throw StateException("Draft ${draft.id} is ${draft.status.name.lowercase()}")
        } ?: drafts.createDraft(
            title = req.title?.takeIf { it.isNotBlank() } ?: "Preset ${preset.name} · ${planned.size} objects",
            description = "Preset \"${preset.name}\" (${preset.id}) applied to ${planned.size} objects.",
            author = author,
            meta = DraftMeta(area = req.area),
        ).id
        val reason = req.reason?.takeIf { it.isNotBlank() } ?: "Preset \"${preset.name}\""
        val results = ArrayList<ProposalResult>(planned.size)
        planned.chunked(DraftService.MAX_PROPOSALS).forEach { chunk ->
            results += drafts.proposeChanges(
                draftId,
                chunk.map { ProposedChange(it.ref, it.edit, reason, confidence = Confidence.NEEDS_REVIEW) },
            )
        }
        return base.copy(draft = drafts.detail(draftId), results = results)
    }

    companion object {
        const val MAX_REFS = 5000
        const val MAX_PREVIEW = 200

        fun geometryOf(f: Feature, tags: Map<String, String>): PresetGeometry = when {
            f.isNode -> if (f.parents().ways().isEmpty) PresetGeometry.POINT else PresetGeometry.VERTEX
            f.isWay -> if (f.isArea) PresetGeometry.AREA else PresetGeometry.LINE
            tags["type"] == "multipolygon" -> PresetGeometry.AREA
            else -> PresetGeometry.RELATION
        }

        fun mirrorTargets(mirror: GolMirror, queries: MirrorQueries, refs: Collection<OsmRef>): Map<OsmRef, PresetTarget> =
            mirror.query { gol ->
                queries.findIn(gol, refs).mapValues { (_, f) ->
                    val tags = f.tagMap()
                    PresetTarget(tags, geometryOf(f, tags))
                }
            }
    }
}
