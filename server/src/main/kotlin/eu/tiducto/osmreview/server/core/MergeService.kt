package eu.tiducto.osmreview.server.core

import eu.tiducto.osmreview.server.Config
import eu.tiducto.osmreview.server.db.DraftRepo
import eu.tiducto.osmreview.server.osm.OsmApiException
import eu.tiducto.osmreview.server.osm.OsmElement
import eu.tiducto.osmreview.server.osm.OsmXml
import eu.tiducto.osmreview.server.osm.XBlock
import eu.tiducto.osmreview.server.osm.XDiffEntry
import eu.tiducto.osmreview.server.osm.XNode
import eu.tiducto.osmreview.server.osm.XOsmChange
import eu.tiducto.osmreview.server.osm.XRelation
import eu.tiducto.osmreview.server.osm.XWay
import eu.tiducto.osmreview.shared.Change
import eu.tiducto.osmreview.shared.ChangeAction
import eu.tiducto.osmreview.shared.ChangeStatus
import eu.tiducto.osmreview.shared.ChangesetGroup
import eu.tiducto.osmreview.shared.Draft
import eu.tiducto.osmreview.shared.DraftStatus
import eu.tiducto.osmreview.shared.MergePreview
import eu.tiducto.osmreview.shared.MergeProgress
import eu.tiducto.osmreview.shared.OsmType
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
        return MergePreview(
            draftId = d.draft.id,
            groups = groups(d.draft, d.changes.filter { it.status == ChangeStatus.APPROVED }),
            conflicts = d.changes.filter { it.status == ChangeStatus.CONFLICT }.map { it.id },
            target = account.target,
        )
    }

    fun groups(draft: Draft, approved: List<Change>): List<ChangesetGroup> {
        val area = draft.title.substringAfter(" · ", "").ifBlank { null }
        return approved.groupBy { it.ruleId ?: "manual" }.flatMap { (ruleId, changes) ->
            val chunks = changes.sortedBy { it.centroid?.lng ?: 0.0 }.chunked(MAX_PER_CHANGESET)
            chunks.mapIndexed { i, chunk ->
                val what = if (ruleId == "manual") {
                    draft.title
                } else {
                    val verb = runCatching { rules.definition(ruleId).changesetComment }.getOrDefault(ruleId)
                    "$verb (rule $ruleId)"
                }
                val comment = (area?.let { "$it: " } ?: "") + what + ", reviewed individually"
                ChangesetGroup(
                    key = if (chunks.size > 1) "$ruleId#${i + 1}" else ruleId,
                    comment = comment,
                    tags = mapOf(
                        "comment" to comment,
                        "created_by" to "OSM Review Editor ${config.version}",
                    ),
                    changeIds = chunk.map { it.id },
                )
            }
        }
    }

    suspend fun merge(draftId: String) = lock.withLock {
        val token = account.requireToken()
        val api = account.api()
        val start = drafts.detail(draftId)
        if (start.draft.status !in DraftService.ACTIVE) throw StateException("Draft is ${start.draft.status.name.lowercase()}")

        val approved = start.changes.filter { it.status == ChangeStatus.APPROVED }
        if (approved.isEmpty()) throw StateException("Nothing approved to merge")
        val live = api.fetch(approved.filter { it.action != ChangeAction.CREATE }.map { it.ref })
        val ready = approved.filter { c -> c.action == ChangeAction.CREATE || drafts.checkConflict(c, live[c.ref]) }
        val skipped = approved.size - ready.size

        val groups = groups(start.draft, ready)
        var done = 0
        val total = ready.size
        progress(draftId, "", done, total, null, "Uploading ${groups.size} changeset(s)" + if (skipped > 0) "; $skipped changed upstream" else "")

        for (g in groups) {
            val changes = g.changeIds.map { id -> ready.first { it.id == id } }
            val uploadId = repo.insertUpload(UUID.fromString(draftId), g.comment, changes.map { UUID.fromString(it.id) })
            var changesetId: Long? = null
            try {
                changesetId = api.createChangeset(token, g.tags)
                val diff = api.upload(token, changesetId, osmChange(changes, live, changesetId))
                applyDiff(changes, diff.nodes + diff.ways + diff.relations, changesetId)
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
        val status = when {
            after.all { it.status == ChangeStatus.UPLOADED } -> DraftStatus.MERGED
            after.any { it.status == ChangeStatus.UPLOADED } -> DraftStatus.PARTIALLY_MERGED
            else -> start.draft.status
        }
        repo.updateDraft(UUID.fromString(draftId), status = status)
        progress(draftId, "", done, total, null, "Merge finished: $done of $total uploaded", finished = true)
        events.emit(UiEvent.DraftUpdated(draftId))
    }

    fun osmChange(changes: List<Change>, live: Map<eu.tiducto.osmreview.shared.OsmRef, OsmElement>, changesetId: Long): String {
        fun block(cs: List<Change>, build: (Change) -> OsmElement): XBlock? {
            if (cs.isEmpty()) return null
            val els = cs.map { OsmXml.toXml(build(it), changesetId) }
            return XBlock(els.filterIsInstance<XNode>(), els.filterIsInstance<XWay>(), els.filterIsInstance<XRelation>())
        }
        val byAction = changes.groupBy { it.action }
        return OsmXml.osmChange(
            XOsmChange(
                generator = "OSM Review Editor ${config.version}",
                create = block(byAction[ChangeAction.CREATE].orEmpty()) { c ->
                    val p = c.centroid ?: error("created node ${c.ref} has no location")
                    OsmElement(OsmType.NODE, c.ref.id, 0, true, c.newTags, p.lat.toString(), p.lng.toString())
                },
                modify = block(byAction[ChangeAction.MODIFY].orEmpty()) { c -> live.getValue(c.ref).copy(tags = c.newTags) },
                delete = block(byAction[ChangeAction.DELETE].orEmpty()) { c -> live.getValue(c.ref) },
            ),
        )
    }

    private suspend fun applyDiff(changes: List<Change>, entries: List<XDiffEntry>, changesetId: Long) {
        val byOld = entries.associateBy { it.oldId }
        for (c in changes) {
            val e = byOld[c.ref.id]
            repo.updateChange(UUID.fromString(c.id)) {
                status = ChangeStatus.UPLOADED
                this.changesetId = changesetId
                if (c.action == ChangeAction.CREATE) e?.newId?.let { osmId = it }
                e?.newVersion?.let { baseVersion = it }
            }
        }
    }

    private fun progress(
        draftId: String, group: String, done: Int, total: Int, changeset: Long?, message: String,
        finished: Boolean = false, failed: Boolean = false,
    ) = events.emit(UiEvent.MergeProgressEvent(MergeProgress(draftId, group, done, total, changeset, message, finished, failed)))

    companion object {
        const val MAX_PER_CHANGESET = 500
    }
}
