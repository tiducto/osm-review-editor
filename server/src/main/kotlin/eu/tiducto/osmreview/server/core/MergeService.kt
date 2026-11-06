package eu.tiducto.osmreview.server.core

import eu.tiducto.osmreview.server.Config
import eu.tiducto.osmreview.server.db.DraftRepo
import eu.tiducto.osmreview.server.osm.OsmApiException
import eu.tiducto.osmreview.server.osm.OsmChangeXml
import eu.tiducto.osmreview.server.osm.OsmElement
import eu.tiducto.osmreview.server.osm.XDiffResult
import eu.tiducto.osmreview.shared.BlockedChange
import eu.tiducto.osmreview.shared.Change
import eu.tiducto.osmreview.shared.ChangeAction
import eu.tiducto.osmreview.shared.ChangeStatus
import eu.tiducto.osmreview.shared.ChangesetGroup
import eu.tiducto.osmreview.shared.Draft
import eu.tiducto.osmreview.shared.DraftStatus
import eu.tiducto.osmreview.shared.MergePreview
import eu.tiducto.osmreview.shared.MergeProgress
import eu.tiducto.osmreview.shared.OrphanNodes
import eu.tiducto.osmreview.shared.OsmRef
import eu.tiducto.osmreview.shared.OsmType
import eu.tiducto.osmreview.shared.ReviewDecision
import eu.tiducto.osmreview.shared.UiEvent
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.UUID

class MergeService(
    private val config: Config,
    private val repo: DraftRepo,
    private val drafts: DraftService,
    private val account: Account,
    private val rules: eu.tiducto.osmreview.rules.RuleEngine,
    private val events: EventBus,
) {
    private val log = LoggerFactory.getLogger(MergeService::class.java)
    private val lock = Mutex()

    val isMerging: Boolean get() = lock.isLocked

    suspend fun preview(draftId: String): MergePreview {
        val d = drafts.detail(draftId)
        val approved = d.changes.filter { it.status == ChangeStatus.APPROVED }
        val blocked = blocked(d.changes, approved)
        val blockedIds = blocked.map { it.changeId }.toSet()
        return MergePreview(
            draftId = d.draft.id,
            groups = groups(d.draft, approved.filter { it.id !in blockedIds }, d.changes),
            conflicts = d.changes.filter { it.status == ChangeStatus.CONFLICT }.map { it.id },
            target = account.target,
            blocked = blocked,
        )
    }

    fun blocked(all: List<Change>, candidates: List<Change>): List<BlockedChange> {
        val edges = DraftGraph.edges(all)
        val users = OrphanNodes.users(all)
        if (edges.isEmpty() && candidates.none { OrphanNodes.isUntaggedNewNode(it) }) return emptyList()
        val byId = all.associateBy { it.id }
        val ready = candidates.map { it.id }.toMutableSet()
        val out = LinkedHashMap<String, BlockedChange>()
        var changed = true
        while (changed) {
            changed = false
            for (id in ready.toList()) {
                val c = byId.getValue(id)
                val missing = closure(edges, id).mapNotNull { byId[it] }.filter { it.id !in ready && it.status != ChangeStatus.UPLOADED }
                if (missing.isNotEmpty()) {
                    ready -= id
                    out[id] = BlockedChange(
                        id, c.ref,
                        "${c.ref} needs " + missing.joinToString { "${it.ref} (${it.status.name.lowercase()})" } + " in the same upload",
                    )
                    changed = true
                    continue
                }
                if (!OrphanNodes.isUntaggedNewNode(c)) continue
                val owners = users[c.ref].orEmpty()
                if (owners.none { it.id in ready || it.status == ChangeStatus.UPLOADED }) {
                    ready -= id
                    out[id] = BlockedChange(
                        id, c.ref,
                        if (owners.isEmpty()) {
                            "${c.ref} is an untagged new node that no way uses; it would be uploaded as an orphan node"
                        } else {
                            "${c.ref} is an untagged new node and none of " + owners.joinToString { "${it.ref} (${it.status.name.lowercase()})" } +
                                " is in the same upload; it would be uploaded as an orphan node"
                        },
                    )
                    changed = true
                }
            }
        }
        return out.values.toList()
    }

    fun requireNoOrphans(all: List<Change>) {
        val approved = all.filter { it.status == ChangeStatus.APPROVED }.map { it.id }.toSet()
        val orphans = OrphanNodes.unowned(all, approved)
        if (orphans.isEmpty()) return
        throw OrphanNodeException(
            "Approved untagged new node${if (orphans.size == 1) "" else "s"} " + orphans.joinToString { it.ref.toString() } +
                " would be uploaded as orphan nodes: " + orphans.take(5).joinToString("; ") { OrphanNodes.describe(it, OrphanNodes.owners(all, it)) },
            orphans.map { it.ref },
            orphans.flatMap { OrphanNodes.owners(all, it) }.map { it.ref }.distinct(),
        )
    }

    private fun closure(edges: Map<String, Set<String>>, start: String): Set<String> {
        val seen = LinkedHashSet<String>()
        val queue = ArrayDeque(edges[start].orEmpty())
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (id == start || !seen.add(id)) continue
            queue += edges[id].orEmpty()
        }
        return seen
    }

    fun groups(draft: Draft, approved: List<Change>, all: List<Change> = approved): List<ChangesetGroup> {
        val area = draft.area?.ifBlank { null }
        val ids = approved.map { it.id }.toSet()
        val components = DraftGraph.components(all.filter { it.id in ids })
        val byKey = components.groupBy { comp -> comp.firstNotNullOfOrNull { it.ruleId } ?: "manual" }
        return byKey.flatMap { (ruleId, comps) ->
            val chunks = pack(comps.sortedBy { comp -> comp.minOf { it.centroid?.lng ?: 0.0 } })
            chunks.mapIndexed { i, chunk ->
                val comment = draft.changesetComment?.ifBlank { null } ?: run {
                    val what = if (ruleId == "manual") {
                        draft.title
                    } else {
                        val verb = runCatching { rules.definition(ruleId).changesetComment }.getOrDefault(ruleId)
                        "$verb (rule $ruleId)"
                    }
                    (area?.let { "$it: " } ?: "") + what + ", reviewed individually"
                }
                val source = sourceOf(draft, chunk)
                ChangesetGroup(
                    key = if (chunks.size > 1) "$ruleId#${i + 1}" else ruleId,
                    comment = comment,
                    tags = buildMap {
                        put("comment", comment)
                        put("created_by", "OSM Review Editor ${config.version}")
                        source?.let { put("source", it) }
                    },
                    changeIds = chunk.map { it.id },
                )
            }
        }
    }

    private fun pack(components: List<List<Change>>): List<List<Change>> {
        val out = ArrayList<List<Change>>()
        var cur = ArrayList<Change>()
        for (comp in components) {
            if (comp.size > OsmChangeXml.MAX_ELEMENTS) {
                throw StateException(
                    "${comp.size} changes depend on each other (${comp.first().ref} …), more than the ${OsmChangeXml.MAX_ELEMENTS} " +
                        "elements one changeset can hold; split the edit into smaller drafts",
                )
            }
            if (cur.isNotEmpty() && cur.size + comp.size > MAX_PER_CHANGESET) {
                out += cur
                cur = ArrayList()
            }
            cur += comp
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    private fun sourceOf(draft: Draft, changes: List<Change>): String? {
        val reviewed = changes.flatMap { c ->
            c.reviews.filter { it.decision == ReviewDecision.APPROVE || it.decision == ReviewDecision.EDIT }.mapNotNull { it.imagery }
        }
        return (listOfNotNull(draft.changesetSource) + reviewed)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("; ")
            .ifEmpty { null }
    }

    suspend fun merge(draftId: String) = lock.withLock {
        val token = account.requireToken()
        val api = account.api()
        val start = drafts.detail(draftId)
        if (start.draft.status !in DraftService.ACTIVE) throw StateException("Draft is ${start.draft.status.name.lowercase()}")

        val approved = start.changes.filter { it.status == ChangeStatus.APPROVED }
        if (approved.isEmpty()) throw StateException("Nothing approved to merge")
        requireNoOrphans(start.changes)
        val live = api.fetch(approved.filter { it.action != ChangeAction.CREATE }.map { it.ref })
        val checked = approved.filter { c -> c.action == ChangeAction.CREATE || drafts.checkConflict(c, live[c.ref]) }
        val current = repo.changes(UUID.fromString(draftId))
        val checkedIds = checked.map { it.id }.toSet()
        val blocked = blocked(current, current.filter { it.id in checkedIds })
        val blockedIds = blocked.map { it.changeId }.toSet()
        val ready = current.filter { it.id in checkedIds && it.id !in blockedIds }
        val skipped = approved.size - checked.size

        val groups = groups(start.draft, ready, current)
        var done = 0
        val total = ready.size
        val notes = listOfNotNull(
            "Uploading ${groups.size} changeset(s)",
            if (skipped > 0) "$skipped changed upstream" else null,
            if (blocked.isNotEmpty()) "${blocked.size} skipped: " + blocked.take(3).joinToString("; ") { it.message } else null,
        )
        progress(draftId, "", done, total, null, notes.joinToString("; "))

        for (g in groups) {
            val changes = repo.changesByIds(g.changeIds.map(UUID::fromString)).associateBy { it.id }.let { m -> g.changeIds.mapNotNull(m::get) }
            val uploadId = repo.insertUpload(UUID.fromString(draftId), g.comment, changes.map { UUID.fromString(it.id) })
            var changesetId: Long? = null
            try {
                changesetId = api.createChangeset(token, g.tags)
                val diff = api.upload(token, changesetId, osmChange(changes, live, changesetId))
                applyDiff(UUID.fromString(draftId), changes, diff, changesetId)
                repo.finishUpload(uploadId, "uploaded", changesetId, null)
                done += changes.size
                progress(draftId, g.key, done, total, changesetId, "Uploaded ${changes.size} change(s) in changeset $changesetId")
            } catch (e: OsmApiException) {
                val conflict = e.status == HttpStatusCode.Conflict || e.status == HttpStatusCode.PreconditionFailed
                log.warn("Upload of group {} failed: {}", g.key, e.message)
                repo.finishUpload(uploadId, if (conflict) "conflict" else "failed", changesetId, e.message)
                if (conflict) {
                    val fresh = api.fetch(changes.filter { it.action != ChangeAction.CREATE }.map { it.ref })
                    changes.filter { it.action != ChangeAction.CREATE }.forEach { drafts.checkConflict(it, fresh[it.ref]) }
                }
                progress(draftId, g.key, done, total, changesetId, "Group ${g.key} not uploaded: ${e.message}", failed = true)
            } finally {
                changesetId?.let { runCatching { api.closeChangeset(token, it) } }
            }
        }

        val after = repo.changes(UUID.fromString(draftId))
        val status = statusAfterMerge(after, start.draft.status)
        repo.updateDraft(UUID.fromString(draftId), status = status)
        progress(draftId, "", done, total, null, "Merge finished: $done of $total uploaded", finished = true)
        events.emit(UiEvent.DraftUpdated(draftId))
    }

    fun osmChange(changes: List<Change>, live: Map<OsmRef, OsmElement>, changesetId: Long): String {
        val items = changes.map { c ->
            val el = ChangeElements.element(c, live[c.ref], useLiveVersion = true)
                ?: throw StateException("${c.ref} cannot be written: its live state or geometry is missing")
            c to el
        }
        return OsmChangeXml.write("OSM Review Editor ${config.version}", ChangeElements.blocks(items), changesetId)
    }

    private suspend fun applyDiff(draftId: UUID, changes: List<Change>, diff: XDiffResult, changesetId: Long) {
        val entries = diff.nodes.map { OsmRef(OsmType.NODE, it.oldId) to it } +
            diff.ways.map { OsmRef(OsmType.WAY, it.oldId) to it } +
            diff.relations.map { OsmRef(OsmType.RELATION, it.oldId) to it }
        val byOld = entries.toMap()
        val remap = HashMap<OsmRef, Long>()
        for (c in changes) {
            val e = byOld[c.ref]
            val created = c.action == ChangeAction.CREATE && e?.newId != null
            if (created) remap[c.ref] = e.newId!!
            repo.updateChange(UUID.fromString(c.id)) {
                status = ChangeStatus.UPLOADED
                this.changesetId = changesetId
                if (created) {
                    osmId = e.newId
                    placeholderId = c.ref.id
                }
                e?.newVersion?.let { baseVersion = it }
            }
        }
        repo.remapRefs(draftId, remap)
    }

    private fun progress(
        draftId: String, group: String, done: Int, total: Int, changeset: Long?, message: String,
        finished: Boolean = false, failed: Boolean = false,
    ) = events.emit(UiEvent.MergeProgressEvent(MergeProgress(draftId, group, done, total, changeset, message, finished, failed)))

    companion object {
        const val MAX_PER_CHANGESET = 500

        private val REMAINING = setOf(ChangeStatus.PENDING, ChangeStatus.APPROVED, ChangeStatus.CONFLICT)

        fun statusAfterMerge(changes: List<Change>, current: DraftStatus): DraftStatus {
            val uploaded = changes.any { it.status == ChangeStatus.UPLOADED }
            val remaining = changes.any { it.status in REMAINING }
            return when {
                !uploaded -> current
                remaining -> DraftStatus.PARTIALLY_MERGED
                else -> DraftStatus.MERGED
            }
        }
    }
}
