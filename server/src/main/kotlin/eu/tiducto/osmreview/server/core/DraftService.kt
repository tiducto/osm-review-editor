package eu.tiducto.osmreview.server.core

import eu.tiducto.osmreview.rules.GolMirror
import eu.tiducto.osmreview.rules.MirrorQueries
import eu.tiducto.osmreview.rules.RuleEngine
import eu.tiducto.osmreview.rules.Scope
import eu.tiducto.osmreview.rules.Geo
import eu.tiducto.osmreview.server.db.DraftRepo
import eu.tiducto.osmreview.server.db.NewChange
import eu.tiducto.osmreview.server.osm.OsmElement
import eu.tiducto.osmreview.shared.Authors
import eu.tiducto.osmreview.shared.BBox
import eu.tiducto.osmreview.shared.Change
import eu.tiducto.osmreview.shared.ChangeAction
import eu.tiducto.osmreview.shared.ChangeStatus
import eu.tiducto.osmreview.shared.Confidence
import eu.tiducto.osmreview.shared.Draft
import eu.tiducto.osmreview.shared.DraftDetail
import eu.tiducto.osmreview.shared.DraftFromRuleRequest
import eu.tiducto.osmreview.shared.DraftStatus
import eu.tiducto.osmreview.shared.LngLat
import eu.tiducto.osmreview.shared.MapFeature
import eu.tiducto.osmreview.shared.OsmRef
import eu.tiducto.osmreview.shared.OsmType
import eu.tiducto.osmreview.shared.ProposalOutcome
import eu.tiducto.osmreview.shared.ProposalResult
import eu.tiducto.osmreview.shared.ProposedChange
import eu.tiducto.osmreview.shared.ReviewDecision
import eu.tiducto.osmreview.shared.ReviewRequest
import eu.tiducto.osmreview.shared.TagDiff
import eu.tiducto.osmreview.shared.TagEdit
import eu.tiducto.osmreview.shared.UiEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.UUID

class DraftService(
    private val repo: DraftRepo,
    private val account: Account,
    private val mirror: GolMirror,
    private val queries: MirrorQueries,
    private val rules: RuleEngine,
    private val events: EventBus,
) {
    private val log = LoggerFactory.getLogger(DraftService::class.java)

    suspend fun createDraft(title: String, description: String, author: String, scope: kotlinx.serialization.json.JsonElement? = null): Draft =
        repo.createDraft(title.ifBlank { "Untitled draft" }, description, author, scope).also { emitUpdated(it.id) }

    suspend fun detail(id: String): DraftDetail {
        val uuid = parseId(id)
        val draft = repo.getDraft(uuid) ?: throw NotFoundException("Draft $id not found")
        return DraftDetail(draft, repo.changes(uuid))
    }

    suspend fun draftFromRule(req: DraftFromRuleRequest): DraftDetail {
        val def = rules.definition(req.ruleId)
        val (scope, scopeKey, scopeGeom) = resolveScope(req.area, req.bbox)
        val run = rules.run(req.ruleId, scope, scopeKey, req.limit ?: MAX_FROM_RULE)
        val withFix = run.issues.filter { it.fix != null }
        require(withFix.isNotEmpty()) { "Rule ${def.id} found ${run.issueCount} issues in $scopeKey, none with an automatic fix" }
        val draft = repo.createDraft(
            title = req.title ?: "${def.title} · ${req.area ?: "map area"}",
            description = "${run.issueCount} issues found by rule ${def.id}; ${withFix.size} with a proposed fix.",
            author = Authors.rule(def.id),
            scope = scopeGeom,
        )
        val proposals = withFix.map { issue ->
            ProposedChange(
                ref = issue.ref,
                edit = issue.fix!!,
                reason = listOfNotNull(def.title, issue.note).joinToString(": "),
                ruleId = def.id,
                confidence = issue.confidence,
            )
        }
        val hint = run.issues.map { it.centroid }.boundingBox()
        val results = proposeChanges(draft.id, proposals, hint)
        log.info(
            "Draft {} from rule {} in {}: {}",
            draft.id, def.id, scopeKey, results.groupingBy { it.outcome }.eachCount(),
        )
        if (results.none { it.outcome == ProposalOutcome.ACCEPTED || it.outcome == ProposalOutcome.DUPLICATE }) {
            repo.updateDraft(UUID.fromString(draft.id), status = DraftStatus.CLOSED)
            val why = results.firstOrNull { it.outcome == ProposalOutcome.ERROR }?.message
                ?: "every proposed fix was a no-op or already proposed"
            throw StateException("No change could be proposed (${results.size} tried): $why")
        }
        return detail(draft.id)
    }

    fun resolveScope(area: String?, bbox: BBox?): Triple<Scope, String, kotlinx.serialization.json.JsonElement?> {
        if (area != null) {
            val (a, scope) = queries.area(area) ?: throw NotFoundException("Unknown area '$area'")
            return Triple(scope, "area:${a.name}", a.geometry)
        }
        val b = bbox ?: throw IllegalArgumentException("Give an area name or a bbox")
        return Triple(Scope(Geo.box(b)), "bbox:${b.west},${b.south},${b.east},${b.north}", bboxPolygon(b))
    }

    suspend fun proposeChanges(draftId: String, proposals: List<ProposedChange>, near: BBox? = null): List<ProposalResult> {
        val draftUuid = parseId(draftId)
        val draft = repo.getDraft(draftUuid) ?: throw NotFoundException("Draft $draftId not found")
        if (draft.status !in ACTIVE) throw StateException("Draft ${draft.id} is ${draft.status.name.lowercase()}")
        require(proposals.size <= MAX_PROPOSALS) { "At most $MAX_PROPOSALS changes per call" }

        val api = account.api()
        val modifies = proposals.filter { it.action != ChangeAction.CREATE }
        val live = api.fetch(modifies.map { it.ref })
        val geoms = geometries(modifies.map { it.ref }, near)
        val results = ArrayList<ProposalResult>(proposals.size)

        for (p in proposals) {
            results += runCatching {
                when (p.action) {
                    ChangeAction.CREATE -> proposeCreate(draftUuid, p)
                    else -> proposeModify(draftUuid, p, live[p.ref], geoms[p.ref], api.target.target.name.lowercase())
                }
            }.getOrElse { e -> ProposalResult(p.ref, ProposalOutcome.ERROR, message = e.message) }
        }
        emitUpdated(draft.id)
        return results
    }

    private suspend fun proposeModify(draftId: UUID, p: ProposedChange, el: OsmElement?, geom: MapFeature?, target: String): ProposalResult {
        if (el == null) {
            return ProposalResult(
                p.ref, ProposalOutcome.ERROR,
                message = "${p.ref} does not exist on the $target API" +
                    if (target == "dev") " (the mirror holds production data; switch to prod to review real objects)" else "",
            )
        }
        if (!el.visible) return ProposalResult(p.ref, ProposalOutcome.ERROR, message = "${p.ref} is deleted (v${el.version})")

        val existing = repo.openChangeFor(p.ref)
        if (existing != null) {
            val merged = p.edit.applyTo(existing.newTags)
            if (merged == existing.newTags) return ProposalResult(p.ref, ProposalOutcome.NO_OP, existing.id, "already proposed in draft ${existing.draftId}")
            val reason = listOfNotNull(existing.reason, p.reason).distinct().joinToString(" · ")
            repo.updateChange(UUID.fromString(existing.id)) {
                newTags = merged
                this.reason = reason
                status = if (existing.status == ChangeStatus.CONFLICT) ChangeStatus.CONFLICT else ChangeStatus.PENDING
                confidence = Confidence.NEEDS_REVIEW
            }
            if (existing.draftId != draftId.toString()) emitUpdated(existing.draftId)
            return ProposalResult(p.ref, ProposalOutcome.DUPLICATE, existing.id, "merged into the open change in draft ${existing.draftId}")
        }

        val newTags = if (p.action == ChangeAction.DELETE) emptyMap() else p.edit.applyTo(el.tags)
        if (p.action == ChangeAction.MODIFY && TagDiff.between(el.tags, newTags).isNoOp) {
            return ProposalResult(p.ref, ProposalOutcome.NO_OP, message = "the edit leaves the live tags unchanged")
        }
        val centroid = when {
            el.lat != null && el.lon != null -> LngLat(el.lon.toDouble(), el.lat.toDouble())
            else -> geom?.let(::centroidOf)
        }
        val c = repo.insertChange(
            NewChange(
                draftId = draftId,
                ref = p.ref,
                action = p.action,
                baseVersion = el.version,
                baseTags = el.tags,
                newTags = newTags,
                baseGeom = geom?.geometry ?: el.pointGeom(),
                newGeom = null,
                centroid = centroid,
                ruleId = p.ruleId,
                reason = p.reason,
                confidence = p.confidence,
            ),
        )
        return ProposalResult(p.ref, ProposalOutcome.ACCEPTED, c.id)
    }

    private suspend fun proposeCreate(draftId: UUID, p: ProposedChange): ProposalResult {
        require(p.ref.type == OsmType.NODE) { "Only nodes can be created here; draw ways in iD" }
        val loc = p.location ?: throw IllegalArgumentException("A created node needs a location")
        val tags = p.edit.applyTo(emptyMap())
        require(tags.isNotEmpty()) { "A created node needs tags" }
        val ref = OsmRef(OsmType.NODE, repo.nextPlaceholderId())
        val point = pointGeom(loc)
        val c = repo.insertChange(
            NewChange(draftId, ref, ChangeAction.CREATE, null, emptyMap(), tags, null, point, loc, p.ruleId, p.reason, p.confidence),
        )
        return ProposalResult(ref, ProposalOutcome.ACCEPTED, c.id)
    }

    suspend fun review(changeId: String, req: ReviewRequest): Change {
        val id = parseId(changeId)
        val c = repo.change(id) ?: throw NotFoundException("Change $changeId not found")
        val draft = repo.getDraft(UUID.fromString(c.draftId))!!
        if (draft.status !in ACTIVE) throw StateException("Draft is ${draft.status.name.lowercase()}")
        if (c.status == ChangeStatus.UPLOADED) throw StateException("Change is already uploaded")
        if (c.status == ChangeStatus.CONFLICT && req.decision in setOf(ReviewDecision.APPROVE, ReviewDecision.EDIT)) {
            throw StateException("Resolve the conflict first: the live object moved to v${c.liveVersion}")
        }
        when (req.decision) {
            ReviewDecision.APPROVE -> repo.updateChange(id) { status = ChangeStatus.APPROVED }
            ReviewDecision.EDIT -> {
                val tags = req.tags ?: throw IllegalArgumentException("An edit needs the full tag set")
                val noOp = c.action == ChangeAction.MODIFY && TagDiff.between(c.baseTags, tags).isNoOp
                repo.updateChange(id) {
                    newTags = tags
                    status = if (noOp) ChangeStatus.REJECTED else ChangeStatus.APPROVED
                }
            }
            ReviewDecision.REJECT, ReviewDecision.SEND_TO_ID -> repo.updateChange(id) { status = ChangeStatus.REJECTED }
            ReviewDecision.RESET -> repo.updateChange(id) { status = if (c.liveVersion != null) ChangeStatus.CONFLICT else ChangeStatus.PENDING }
        }
        repo.addReview(id, req.decision, req.comment?.takeIf { it.isNotBlank() }, req.tags.takeIf { req.decision == ReviewDecision.EDIT })
        emitUpdated(c.draftId)
        return repo.change(id)!!
    }

    suspend fun updateProposal(changeId: String, edit: TagEdit?, withdraw: Boolean, reason: String?): Change {
        val id = parseId(changeId)
        val c = repo.change(id) ?: throw NotFoundException("Change $changeId not found")
        if (c.status == ChangeStatus.UPLOADED) throw StateException("Change is already uploaded")
        if (withdraw) {
            repo.updateChange(id) { status = ChangeStatus.REJECTED }
            repo.addReview(id, ReviewDecision.REJECT, "withdrawn by the author" + (reason?.let { ": $it" } ?: ""), null)
        } else {
            val e = edit ?: throw IllegalArgumentException("Give new tags or withdraw")
            val tags = e.applyTo(c.newTags)
            repo.updateChange(id) {
                newTags = tags
                status = if (c.status == ChangeStatus.CONFLICT) ChangeStatus.CONFLICT else ChangeStatus.PENDING
                reason?.let { r -> this.reason = listOfNotNull(c.reason, r).joinToString(" · ") }
            }
            repo.addReview(id, ReviewDecision.RESET, "revised by the author" + (reason?.let { ": $it" } ?: ""), tags)
        }
        emitUpdated(c.draftId)
        return repo.change(id)!!
    }

    suspend fun approveRemaining(draftId: String, ruleId: String?): DraftDetail {
        val d = detail(draftId)
        d.changes.filter {
            it.status == ChangeStatus.PENDING && it.confidence == Confidence.DETERMINISTIC && (ruleId == null || it.ruleId == ruleId)
        }.forEach { c ->
            repo.updateChange(UUID.fromString(c.id)) { status = ChangeStatus.APPROVED }
            repo.addReview(UUID.fromString(c.id), ReviewDecision.APPROVE, "approved in bulk (deterministic rule)", null)
        }
        emitUpdated(draftId)
        return detail(draftId)
    }

    suspend fun manualEdit(ref: OsmRef, tags: Map<String, String>, reason: String?): Change {
        val draft = repo.findOpenDraft(Authors.USER, MANUAL_TITLE)
            ?: repo.createDraft(MANUAL_TITLE, "Edits made in the inspector", Authors.USER, null)
        val r = proposeChanges(draft.id, listOf(ProposedChange(ref, TagEdit(replaceAll = tags), reason ?: "manual edit")))
            .single()
        if (r.outcome == ProposalOutcome.ERROR || r.changeId == null) {
            throw IllegalArgumentException(r.message ?: "Could not record the edit (${r.outcome})")
        }
        return repo.change(UUID.fromString(r.changeId))!!
    }

    suspend fun refresh(draftId: String): DraftDetail {
        val uuid = parseId(draftId)
        val changes = repo.changes(uuid).filter {
            it.status in setOf(ChangeStatus.PENDING, ChangeStatus.APPROVED, ChangeStatus.CONFLICT) && it.action != ChangeAction.CREATE
        }
        val live = account.api().fetch(changes.map { it.ref })
        for (c in changes) checkConflict(c, live[c.ref])
        emitUpdated(draftId)
        return detail(draftId)
    }

    suspend fun checkConflict(c: Change, el: OsmElement?): Boolean {
        val id = UUID.fromString(c.id)
        val base = c.baseVersion ?: return true
        if (el == null || !el.visible) {
            repo.updateChange(id) {
                status = ChangeStatus.CONFLICT
                liveVersion = el?.version ?: (base + 1)
                liveTags = emptyMap()
            }
            return false
        }
        if (el.version == base) return c.status != ChangeStatus.CONFLICT
        val mine = TagDiff.between(c.baseTags, c.newTags)
        val upstream = TagDiff.between(c.baseTags, el.tags)
        return if ((mine.touchedKeys intersect upstream.touchedKeys).isEmpty()) {
            val rebased = mine.applyTo(el.tags)
            repo.updateChange(id) {
                baseTags = el.tags
                baseVersion = el.version
                newTags = rebased
                status = ChangeStatus.PENDING
                clearLive = true
            }
            repo.addReview(id, ReviewDecision.RESET, "auto-rebased from v$base onto v${el.version}", null)
            false
        } else {
            repo.updateChange(id) {
                status = ChangeStatus.CONFLICT
                liveVersion = el.version
                liveTags = el.tags
            }
            false
        }
    }

    suspend fun resolveConflict(changeId: String, tags: Map<String, String>): Change {
        val id = parseId(changeId)
        val c = repo.change(id) ?: throw NotFoundException("Change $changeId not found")
        if (c.status != ChangeStatus.CONFLICT) throw StateException("Change is not in conflict")
        val el = account.api().fetchOne(c.ref)
        if (el == null || !el.visible) throw StateException("${c.ref} no longer exists; reject the change instead")
        repo.updateChange(id) {
            baseTags = el.tags
            baseVersion = el.version
            newTags = tags
            status = ChangeStatus.PENDING
            clearLive = true
        }
        repo.addReview(id, ReviewDecision.EDIT, "conflict resolved onto v${el.version}", tags)
        emitUpdated(c.draftId)
        return repo.change(id)!!
    }

    private fun geometries(refs: List<OsmRef>, near: BBox?): Map<OsmRef, MapFeature> =
        if (refs.isEmpty() || !mirror.isAvailable) emptyMap() else runCatching { queries.mapFeatures(refs, near) }.getOrElse { emptyMap() }

    private fun centroidOf(f: MapFeature): LngLat? = runCatching {
        val pts = ArrayList<Pair<Double, Double>>()
        fun walk(e: kotlinx.serialization.json.JsonElement) {
            if (e is JsonArray && e.size >= 2 && e[0] is JsonPrimitive) {
                pts += (e[0] as JsonPrimitive).content.toDouble() to (e[1] as JsonPrimitive).content.toDouble()
            } else if (e is JsonArray) {
                e.forEach(::walk)
            }
        }
        walk((f.geometry as kotlinx.serialization.json.JsonObject)["coordinates"]!!)
        LngLat((pts.minOf { it.first } + pts.maxOf { it.first }) / 2, (pts.minOf { it.second } + pts.maxOf { it.second }) / 2)
    }.getOrNull()

    private fun OsmElement.pointGeom() = if (lat != null && lon != null) pointGeom(LngLat(lon.toDouble(), lat.toDouble())) else null

    private fun pointGeom(p: LngLat) = buildJsonObject {
        put("type", "Point")
        put("coordinates", JsonArray(listOf(JsonPrimitive(p.lng), JsonPrimitive(p.lat))))
    }

    private fun bboxPolygon(b: BBox) = buildJsonObject {
        put("type", "Polygon")
        val ring = listOf(b.west to b.south, b.east to b.south, b.east to b.north, b.west to b.north, b.west to b.south)
        put("coordinates", JsonArray(listOf(JsonArray(ring.map { JsonArray(listOf(JsonPrimitive(it.first), JsonPrimitive(it.second))) }))))
    }

    private fun List<LngLat>.boundingBox(): BBox? = if (isEmpty()) {
        null
    } else {
        val pad = 0.005
        BBox(minOf { it.lng } - pad, minOf { it.lat } - pad, maxOf { it.lng } + pad, maxOf { it.lat } + pad)
    }

    private fun emitUpdated(draftId: String) = events.emit(UiEvent.DraftUpdated(draftId))

    companion object {
        val ACTIVE = setOf(DraftStatus.OPEN, DraftStatus.PARTIALLY_MERGED)
        const val MAX_PROPOSALS = 2000
        const val MAX_FROM_RULE = 2000
        const val MANUAL_TITLE = "Manual edits"

        fun parseId(id: String): UUID = runCatching { UUID.fromString(id) }.getOrElse { throw NotFoundException("Unknown id $id") }
    }
}

class NotFoundException(message: String) : RuntimeException(message)

class StateException(message: String) : RuntimeException(message)
