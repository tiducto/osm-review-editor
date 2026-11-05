package eu.tiducto.osmreview.server.core

import eu.tiducto.osmreview.server.core.ChangeElements.location
import eu.tiducto.osmreview.server.db.DraftRepo
import eu.tiducto.osmreview.server.db.GeometryUpdate
import eu.tiducto.osmreview.server.db.NewChange
import eu.tiducto.osmreview.server.db.OPEN_CHANGE_STATES
import eu.tiducto.osmreview.server.osm.OsmApi
import eu.tiducto.osmreview.server.osm.OsmChangeEntry
import eu.tiducto.osmreview.server.osm.OsmChangeXml
import eu.tiducto.osmreview.server.osm.OsmElement
import eu.tiducto.osmreview.shared.Change
import eu.tiducto.osmreview.shared.ChangeAction
import eu.tiducto.osmreview.shared.ChangeStatus
import eu.tiducto.osmreview.shared.Confidence
import eu.tiducto.osmreview.shared.ElementShape
import eu.tiducto.osmreview.shared.LngLat
import eu.tiducto.osmreview.shared.MapFeature
import eu.tiducto.osmreview.shared.OpenChangeElsewhere
import eu.tiducto.osmreview.shared.OsmRef
import eu.tiducto.osmreview.shared.OsmType
import eu.tiducto.osmreview.shared.ProposalOutcome
import eu.tiducto.osmreview.shared.ProposalResult
import eu.tiducto.osmreview.shared.RelationMember
import eu.tiducto.osmreview.shared.ReviewDecision
import eu.tiducto.osmreview.shared.TagDiff
import eu.tiducto.osmreview.shared.TagEdit
import eu.tiducto.osmreview.shared.withoutPseudoTags
import kotlinx.serialization.json.JsonElement
import java.util.UUID

data class NodeInsert(val node: Long, val after: Long)

data class EditElement(
    val action: ChangeAction,
    val ref: OsmRef,
    val version: Int? = null,
    val tags: Map<String, String>? = null,
    val edit: TagEdit? = null,
    val location: LngLat? = null,
    val nodes: List<Long>? = null,
    val members: List<RelationMember>? = null,
    val insertNodes: List<NodeInsert> = emptyList(),
    val ifUnused: Boolean = false,
    val reason: String? = null,
) {
    companion object {
        fun fromOsmChange(entries: List<OsmChangeEntry>, reasons: Map<String, String>, reason: String?): List<EditElement> {
            val byRef = reasons.mapKeys { (k, _) -> OsmRef.parse(k) }
            return entries.map { (action, ifUnused, e) ->
                EditElement(
                    action = action,
                    ref = e.ref,
                    version = e.version.takeIf { it > 0 },
                    tags = if (action == ChangeAction.DELETE) null else e.tags,
                    location = e.location,
                    nodes = if (e.type == OsmType.WAY && action != ChangeAction.DELETE) e.nodeIds else null,
                    members = if (e.type == OsmType.RELATION && action != ChangeAction.DELETE) {
                        e.members.map { RelationMember(OsmType.fromApiName(it.type), it.ref, it.role) }
                    } else {
                        null
                    },
                    ifUnused = ifUnused,
                    reason = byRef[e.ref] ?: reason,
                )
            }
        }
    }
}

class GeometryEdits(
    private val repo: DraftRepo,
    private val account: Account,
    private val mirrorGeometry: (List<OsmRef>) -> Map<OsmRef, MapFeature>,
) {
    private class Planned(
        val e: EditElement,
        val ref: OsmRef,
        val action: ChangeAction,
        val base: OsmElement?,
        val baseShape: ElementShape?,
        val newShape: ElementShape?,
        val newTags: Map<String, String>,
        val existing: Change?,
    ) {
        var baseGeom: JsonElement? = null
        var newGeom: JsonElement? = null
    }

    suspend fun apply(
        draftId: UUID,
        input: List<EditElement>,
        replace: Boolean,
        confidence: Confidence = Confidence.NEEDS_REVIEW,
        takeOver: (suspend (List<Change>) -> Unit)? = null,
    ): List<ProposalResult> {
        val dupes = input.groupBy { it.ref }.filterValues { it.size > 1 }.keys
        require(dupes.isEmpty()) { "Each element may appear only once: ${dupes.joinToString()}" }
        val problems = ArrayList<String>()
        fun check() = require(problems.isEmpty()) { problems.take(20).joinToString("; ") + if (problems.size > 20) " (and ${problems.size - 20} more)" else "" }

        val existing = repo.changes(draftId)
        val openInDraft = existing.filter { it.status in OPEN_CHANGE_STATES }
        val draftCreates = openInDraft.filter { it.action == ChangeAction.CREATE }.associateBy { it.ref }
        val uploadedPlaceholders = existing.filter { it.placeholderId != null }.associateBy { OsmRef(it.ref.type, it.placeholderId!!) }

        val withdrawn = LinkedHashMap<String, Change>()
        val elements = ArrayList<EditElement>()
        for (e in input) {
            if (e.ref.id < 0) {
                uploadedPlaceholders[e.ref]?.let { problems += "${e.ref} was already uploaded as ${it.ref}" }
                when (e.action) {
                    ChangeAction.CREATE -> elements += e
                    ChangeAction.MODIFY -> if (e.ref in draftCreates) elements += e.copy(action = ChangeAction.CREATE) else problems += "${e.ref} is modified but is not a new element of this draft"
                    ChangeAction.DELETE -> draftCreates[e.ref]?.let { withdrawn[it.id] = it }
                }
            } else if (e.action == ChangeAction.CREATE) {
                problems += "${e.ref}: created elements need a negative placeholder id"
            } else {
                elements += e
            }
        }
        check()
        val mentioned = elements.map { it.ref }.toSet()
        if (replace) openInDraft.filter { it.ref !in mentioned }.forEach { withdrawn[it.id] = it }
        val kept = openInDraft.filter { it.id !in withdrawn && it.ref !in mentioned }

        val fresh = elements.filter { it.action == ChangeAction.CREATE && it.ref !in draftCreates }
        var next = if (fresh.isEmpty()) 0L else repo.nextPlaceholderId()
        val idMap = HashMap<OsmRef, OsmRef>()
        elements.filter { it.action == ChangeAction.CREATE }.forEach { e ->
            idMap[e.ref] = if (e.ref in draftCreates) e.ref else OsmRef(e.ref.type, next--)
        }

        val deleted = elements.filter { it.action == ChangeAction.DELETE }.map { it.ref }.toSet() +
            kept.filter { it.action == ChangeAction.DELETE }.map { it.ref }
        val withdrawnRefs = withdrawn.values.map { it.ref }.toSet()
        fun resolve(owner: OsmRef, r: OsmRef): OsmRef? {
            if (r.id < 0) {
                idMap[r]?.let { return it }
                if (r in draftCreates && r !in withdrawnRefs) return r
                uploadedPlaceholders[r]?.let { return it.ref }
                problems += "$owner references $r, which is neither in the osmChange nor a new element of this draft"
                return null
            }
            if (r in deleted) {
                problems += "$owner references $r, which this draft deletes"
                return null
            }
            return r
        }
        for (c in kept) {
            DraftGraph.referenced(c.newShape).filter { it in deleted || it in withdrawnRefs }.forEach {
                problems += "${c.ref} (already in the draft) references $it, which this osmChange removes"
            }
        }

        val api = account.api()
        val existingRefs = elements.filter { it.action != ChangeAction.CREATE }.map { it.ref }
        val live = api.fetch(existingRefs)
        for (r in existingRefs) {
            val el = live[r]
            if (el == null) {
                problems += "$r does not exist on the ${api.target.target.name.lowercase()} API"
            } else if (!el.visible) {
                problems += "$r is already deleted (v${el.version})"
            }
        }
        val openElsewhere = repo.openChangesFor(existingRefs).filter { it.draftId != draftId.toString() }
        if (openElsewhere.isNotEmpty() && takeOver == null) {
            val elsewhere = openElsewhere.map { c ->
                OpenChangeElsewhere(c.ref, c.id, c.status, c.draftId, repo.getDraft(UUID.fromString(c.draftId))?.title.orEmpty())
            }
            if (problems.isEmpty()) throw OpenChangeElsewhereException(elsewhere)
            problems += OpenChangeElsewhereException.describe(elsewhere)
        }
        check()

        val openByRef = openInDraft.filter { it.id !in withdrawn }.associateBy { it.ref }
        val planned = elements.map { e ->
            val ref = idMap[e.ref] ?: e.ref
            val base = live[ref]
            val newTags = when (e.action) {
                ChangeAction.DELETE -> emptyMap()
                else -> (e.tags ?: e.edit?.applyTo(base?.tags.orEmpty()) ?: base?.tags.orEmpty()).withoutPseudoTags()
            }
            val baseShape = base?.let(ChangeElements::shapeOf)
            val newShape = when (e.action) {
                ChangeAction.CREATE -> createdShape(e, ref, problems, ::resolve)
                ChangeAction.MODIFY -> modifiedShape(e, base!!, problems, ::resolve)
                ChangeAction.DELETE -> null
            }
            Planned(e, ref, e.action, base, baseShape, newShape, newTags, openByRef[ref])
        }
        check()

        val newLocations = HashMap<Long, LngLat>()
        kept.filter { it.ref.type == OsmType.NODE }.forEach { c -> c.newShape?.location?.let { newLocations[c.ref.id] = it } }
        planned.filter { it.ref.type == OsmType.NODE }.forEach { p -> p.newShape?.location?.let { newLocations[p.ref.id] = it } }
        val wanted = HashSet<Long>()
        planned.filter { it.ref.type == OsmType.WAY }.forEach { p ->
            p.baseShape?.nodes?.let(wanted::addAll)
            p.newShape?.nodes?.let(wanted::addAll)
        }
        val liveCoords = coords(api, wanted.filter { it > 0 })
        val newCoords = liveCoords + newLocations
        val relationGeoms = planned.filter { it.ref.type == OsmType.RELATION && it.base != null }.map { it.ref }
            .let { if (it.isEmpty()) emptyMap() else runCatching { mirrorGeometry(it) }.getOrElse { emptyMap() } }
        planned.forEach { p ->
            p.baseGeom = if (p.ref.type == OsmType.RELATION) relationGeoms[p.ref]?.geometry else ChangeElements.geometry(p.ref.type, p.baseShape, liveCoords)
            p.newGeom = when {
                p.action == ChangeAction.DELETE -> null
                p.action == ChangeAction.MODIFY && p.newShape == null -> null
                p.ref.type == OsmType.NODE -> (p.newShape?.location ?: p.baseShape?.location)?.let(ChangeElements::point)
                else -> ChangeElements.geometry(p.ref.type, ElementShape(nodes = p.newShape?.nodes ?: p.baseShape?.nodes), newCoords)
            }
        }

        if (openElsewhere.isNotEmpty()) takeOver?.invoke(openElsewhere)
        val results = ArrayList<ProposalResult>()
        for (c in withdrawn.values) {
            val id = UUID.fromString(c.id)
            repo.updateChange(id) { status = ChangeStatus.REJECTED }
            repo.addReview(id, ReviewDecision.REJECT, "removed in the editor", null)
            results += ProposalResult(c.ref, ProposalOutcome.ACCEPTED, c.id, "withdrawn")
        }
        for (p in planned) results += store(draftId, p, confidence)
        return results
    }

    private fun createdShape(e: EditElement, ref: OsmRef, problems: MutableList<String>, resolve: (OsmRef, OsmRef) -> OsmRef?): ElementShape? =
        when (ref.type) {
            OsmType.NODE -> {
                if (e.location == null) problems += "${e.ref}: a new node needs lat and lon"
                e.location?.let { ElementShape(location = it) }
            }
            OsmType.WAY -> {
                val nodes = e.nodes.orEmpty().mapNotNull { resolve(e.ref, OsmRef(OsmType.NODE, it))?.id }
                if (e.nodes.orEmpty().size < 2) problems += "${e.ref}: a new way needs at least 2 nodes"
                ElementShape(nodes = nodes)
            }
            OsmType.RELATION -> ElementShape(
                members = e.members.orEmpty().mapNotNull { m -> resolve(e.ref, OsmRef(m.type, m.ref))?.let { m.copy(ref = it.id) } },
            )
        }

    private fun modifiedShape(e: EditElement, base: OsmElement, problems: MutableList<String>, resolve: (OsmRef, OsmRef) -> OsmRef?): ElementShape? {
        val baseShape = ChangeElements.shapeOf(base)
        return when (base.type) {
            OsmType.NODE -> e.location?.takeUnless { ChangeElements.sameLocation(it, baseShape.location) }?.let { ElementShape(location = it) }
            OsmType.WAY -> {
                var nodes = e.nodes?.mapNotNull { resolve(e.ref, OsmRef(OsmType.NODE, it))?.id } ?: base.nodeIds
                for (ins in e.insertNodes) {
                    val node = resolve(e.ref, OsmRef(OsmType.NODE, ins.node))?.id ?: continue
                    val at = nodes.indexOf(ins.after)
                    if (at < 0) {
                        problems += "${e.ref}: node ${ins.after} to insert after is not in the way"
                    } else {
                        nodes = nodes.subList(0, at + 1) + node + nodes.subList(at + 1, nodes.size)
                    }
                }
                if (nodes.size < 2) problems += "${e.ref}: a way needs at least 2 nodes"
                nodes.takeIf { it != base.nodeIds }?.let { ElementShape(nodes = it) }
            }
            OsmType.RELATION -> {
                val members = e.members?.mapNotNull { m -> resolve(e.ref, OsmRef(m.type, m.ref))?.let { m.copy(ref = it.id) } }
                members?.takeIf { it != baseShape.members }?.let { ElementShape(members = it) }
            }
        }
    }

    private suspend fun store(draftId: UUID, p: Planned, confidence: Confidence): ProposalResult {
        val source = p.e.ref.takeIf { it != p.ref }
        val base = p.base
        if (p.action == ChangeAction.MODIFY && p.newShape == null && TagDiff.between(base!!.tags, p.newTags).isNoOp) {
            val ex = p.existing
            if (ex != null) {
                val id = UUID.fromString(ex.id)
                repo.updateChange(id) { status = ChangeStatus.REJECTED }
                repo.addReview(id, ReviewDecision.REJECT, "reverted in the editor", null)
                return ProposalResult(p.ref, ProposalOutcome.NO_OP, ex.id, "reverted to the live state; change withdrawn", source)
            }
            return ProposalResult(p.ref, ProposalOutcome.NO_OP, message = "the element equals the live state", sourceRef = source)
        }
        val stale = base != null && p.e.version != null && p.e.version != base.version
        val ifUnused = p.action == ChangeAction.DELETE && p.e.ifUnused
        val centroid = ChangeElements.centroid(p.newGeom) ?: ChangeElements.centroid(p.baseGeom)
        val ex = p.existing
        if (ex == null) {
            val c = repo.insertChange(
                NewChange(
                    draftId = draftId,
                    ref = p.ref,
                    action = p.action,
                    baseVersion = base?.version,
                    baseTags = base?.tags.orEmpty(),
                    newTags = p.newTags,
                    baseGeom = p.baseGeom,
                    newGeom = p.newGeom,
                    centroid = centroid,
                    ruleId = null,
                    reason = p.e.reason,
                    confidence = confidence,
                    baseShape = p.baseShape,
                    newShape = p.newShape,
                    ifUnused = ifUnused,
                    status = if (stale) ChangeStatus.CONFLICT else ChangeStatus.PENDING,
                    liveVersion = base?.version?.takeIf { stale },
                    liveTags = base?.tags?.takeIf { stale },
                    liveShape = p.baseShape?.takeIf { stale },
                    liveGeom = p.baseGeom?.takeIf { stale },
                ),
            )
            if (stale) repo.addReview(UUID.fromString(c.id), ReviewDecision.RESET, "edited on v${p.e.version}, the live object is v${base!!.version}", null)
            return ProposalResult(p.ref, ProposalOutcome.ACCEPTED, c.id, if (stale) "conflict: edited on v${p.e.version}, live is v${base!!.version}" else null, source)
        }
        val unchanged = ex.action == p.action && ex.newTags == p.newTags && ex.newShape == p.newShape && ex.ifUnused == ifUnused &&
            ex.baseVersion == base?.version && !stale
        if (unchanged) return ProposalResult(p.ref, ProposalOutcome.NO_OP, ex.id, "unchanged", source)
        val id = UUID.fromString(ex.id)
        repo.updateChange(id) {
            geometry = GeometryUpdate(p.action, p.baseShape, p.newShape, p.baseGeom, p.newGeom, centroid, ifUnused)
            newTags = p.newTags
            base?.let {
                baseTags = it.tags
                baseVersion = it.version
            }
            p.e.reason?.let { r -> reason = listOfNotNull(ex.reason, r).distinct().joinToString(" · ") }
            status = if (stale) ChangeStatus.CONFLICT else ChangeStatus.PENDING
            if (stale) {
                liveVersion = base!!.version
                liveTags = base.tags
                p.baseShape?.let { liveShape = it }
                p.baseGeom?.let { liveGeom = it }
            } else {
                clearLive = true
            }
        }
        repo.addReview(id, ReviewDecision.RESET, "updated in the editor", p.newTags)
        return ProposalResult(p.ref, ProposalOutcome.DUPLICATE, ex.id, "updated the change already in this draft", source)
    }

    suspend fun export(changes: List<Change>): String {
        val open = changes.filter { it.status in OPEN_CHANGE_STATES }
        val needLive = open.filter {
            (it.action == ChangeAction.MODIFY && (it.newShape == null || it.baseShape == null)) ||
                (it.action == ChangeAction.DELETE && it.ref.type == OsmType.NODE && it.baseShape?.location == null)
        }
        val live = if (needLive.isEmpty()) emptyMap() else account.api().fetch(needLive.map { it.ref })
        val items = open.mapNotNull { c -> ChangeElements.element(c, live[c.ref], useLiveVersion = false)?.let { c to it } }
        return OsmChangeXml.write("OSM Review Editor", ChangeElements.blocks(items), null)
    }

    suspend fun liveGeometry(api: OsmApi, el: OsmElement): JsonElement? = when (el.type) {
        OsmType.NODE -> el.location?.let(ChangeElements::point)
        OsmType.WAY -> ChangeElements.geometry(OsmType.WAY, ChangeElements.shapeOf(el), coords(api, el.nodeIds.filter { it > 0 }))
        OsmType.RELATION -> runCatching { mirrorGeometry(listOf(el.ref))[el.ref]?.geometry }.getOrNull()
    }

    suspend fun geometryFor(api: OsmApi, type: OsmType, shape: ElementShape?, overrides: Map<Long, LngLat> = emptyMap()): JsonElement? =
        when (type) {
            OsmType.WAY -> ChangeElements.geometry(type, shape, coords(api, shape?.nodes.orEmpty().filter { it > 0 && it !in overrides }) + overrides)
            else -> ChangeElements.geometry(type, shape, emptyMap())
        }

    suspend fun coords(api: OsmApi, ids: Collection<Long>): Map<Long, LngLat> {
        if (ids.isEmpty()) return emptyMap()
        return api.fetch(ids.distinct().map { OsmRef(OsmType.NODE, it) }).values
            .mapNotNull { e -> e.location?.let { e.id to it } }
            .toMap()
    }
}

class OpenChangeElsewhereException(val openChanges: List<OpenChangeElsewhere>) : IllegalArgumentException(describe(openChanges)) {
    companion object {
        fun describe(open: List<OpenChangeElsewhere>): String =
            open.groupBy { it.draftId }.entries.joinToString("; ") { (id, list) ->
                val title = list.first().draftTitle.takeIf { it.isNotBlank() }?.let { " \"$it\"" }.orEmpty()
                "${list.joinToString(", ") { it.ref.toString() }} already ${if (list.size == 1) "has an open change" else "have open changes"} in draft $id$title"
            } + "; import with takeOver to move ${if (open.size == 1) "it" else "them"} here"
    }
}
