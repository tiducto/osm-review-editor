package eu.tiducto.osmreview.server.db

import eu.tiducto.osmreview.shared.Change
import eu.tiducto.osmreview.shared.ChangeAction
import eu.tiducto.osmreview.shared.ChangeCounts
import eu.tiducto.osmreview.shared.ChangeStatus
import eu.tiducto.osmreview.shared.Confidence
import eu.tiducto.osmreview.shared.Draft
import eu.tiducto.osmreview.shared.ElementShape
import eu.tiducto.osmreview.server.core.DraftGraph
import eu.tiducto.osmreview.shared.DraftStatus
import eu.tiducto.osmreview.shared.LngLat
import eu.tiducto.osmreview.shared.OsmRef
import eu.tiducto.osmreview.shared.OsmType
import eu.tiducto.osmreview.shared.Review
import eu.tiducto.osmreview.shared.ReviewDecision
import eu.tiducto.osmreview.shared.TagDiff
import eu.tiducto.osmreview.shared.Upload
import eu.tiducto.osmreview.shared.withoutPseudoTags
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

inline fun <reified E : Enum<E>> E.wire(serializer: KSerializer<E>): String = serializer.descriptor.getElementName(ordinal)

inline fun <reified E : Enum<E>> wireEnum(serializer: KSerializer<E>, value: String): E {
    val i = serializer.descriptor.getElementIndex(value)
    require(i >= 0) { "Unknown ${E::class.simpleName} '$value'" }
    return enumValues<E>()[i]
}

val DraftStatus.wire get() = wire(DraftStatus.serializer())
val ChangeStatus.wire get() = wire(ChangeStatus.serializer())
val ChangeAction.wire get() = wire(ChangeAction.serializer())
val Confidence.wire get() = wire(Confidence.serializer())
val ReviewDecision.wire get() = wire(ReviewDecision.serializer())

private fun now(): OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)

data class NewChange(
    val draftId: UUID,
    val ref: OsmRef,
    val action: ChangeAction,
    val baseVersion: Int?,
    val baseTags: Map<String, String>,
    val newTags: Map<String, String>,
    val baseGeom: JsonElement?,
    val newGeom: JsonElement?,
    val centroid: LngLat?,
    val ruleId: String?,
    val reason: String?,
    val confidence: Confidence,
    val baseShape: ElementShape? = null,
    val newShape: ElementShape? = null,
    val ifUnused: Boolean = false,
    val status: ChangeStatus = ChangeStatus.PENDING,
    val liveVersion: Int? = null,
    val liveTags: Map<String, String>? = null,
    val liveShape: ElementShape? = null,
    val liveGeom: JsonElement? = null,
)

data class GeometryUpdate(
    val action: ChangeAction,
    val baseShape: ElementShape?,
    val newShape: ElementShape?,
    val baseGeom: JsonElement?,
    val newGeom: JsonElement?,
    val centroid: LngLat?,
    val ifUnused: Boolean,
)

data class DraftMeta(
    val area: String? = null,
    val changesetComment: String? = null,
    val changesetSource: String? = null,
)

val OPEN_CHANGE_STATES = listOf(ChangeStatus.PENDING, ChangeStatus.APPROVED, ChangeStatus.CONFLICT)

class DraftRepo(private val db: Db) {

    suspend fun createDraft(
        title: String,
        description: String,
        author: String,
        scope: JsonElement?,
        meta: DraftMeta = DraftMeta(),
    ): Draft = db.tx {
        val id = UUID.randomUUID()
        val t = now()
        DraftsTable.insert {
            it[DraftsTable.id] = id
            it[DraftsTable.title] = title
            it[DraftsTable.description] = description
            it[DraftsTable.author] = author
            it[scopeGeom] = scope
            it[area] = meta.area
            it[changesetComment] = meta.changesetComment
            it[changesetSource] = meta.changesetSource
            it[status] = DraftStatus.OPEN.wire
            it[createdAt] = t
            it[updatedAt] = t
        }
        draftRow(id)!!
    }

    suspend fun listDrafts(status: DraftStatus? = null, author: String? = null): List<Draft> = db.tx {
        val rows = DraftsTable.selectAll()
            .apply { status?.let { andWhere { DraftsTable.status eq it.wire } } }
            .apply { author?.let { andWhere { DraftsTable.author eq it } } }
            .orderBy(DraftsTable.updatedAt, SortOrder.DESC)
            .toList()
        val counts = countsFor(rows.map { it[DraftsTable.id] })
        rows.map { it.toDraft(counts[it[DraftsTable.id]] ?: ChangeCounts()) }
    }

    suspend fun getDraft(id: UUID): Draft? = db.tx { draftRow(id) }

    suspend fun findOpenDraft(author: String, title: String): Draft? = db.tx {
        DraftsTable.selectAll()
            .where { (DraftsTable.author eq author) and (DraftsTable.title eq title) and (DraftsTable.status eq DraftStatus.OPEN.wire) }
            .orderBy(DraftsTable.createdAt, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.let { draftRow(it[DraftsTable.id]) }
    }

    suspend fun updateDraft(
        id: UUID,
        title: String? = null,
        description: String? = null,
        status: DraftStatus? = null,
        changesetComment: String? = null,
        changesetSource: String? = null,
    ): Draft? =
        db.tx {
            DraftsTable.update({ DraftsTable.id eq id }) {
                title?.let { v -> it[DraftsTable.title] = v }
                description?.let { v -> it[DraftsTable.description] = v }
                status?.let { v -> it[DraftsTable.status] = v.wire }
                changesetComment?.let { v -> it[DraftsTable.changesetComment] = v.trim().ifBlank { null } }
                changesetSource?.let { v -> it[DraftsTable.changesetSource] = v.trim().ifBlank { null } }
                it[updatedAt] = now()
            }
            draftRow(id)
        }

    private fun draftRow(id: UUID): Draft? {
        val row = DraftsTable.selectAll().where { DraftsTable.id eq id }.firstOrNull() ?: return null
        return row.toDraft(countsFor(listOf(id))[id] ?: ChangeCounts())
    }

    private fun countsFor(ids: List<UUID>): Map<UUID, ChangeCounts> {
        if (ids.isEmpty()) return emptyMap()
        val n = ChangesTable.id.count()
        val raw = ChangesTable.select(ChangesTable.draftId, ChangesTable.status, n)
            .where { ChangesTable.draftId inList ids }
            .groupBy(ChangesTable.draftId, ChangesTable.status)
            .map { Triple(it[ChangesTable.draftId], it[ChangesTable.status], it[n].toInt()) }
        return raw.groupBy { it.first }.mapValues { (_, list) ->
            val by = list.associate { it.second to it.third }
            ChangeCounts(
                total = by.values.sum(),
                pending = by[ChangeStatus.PENDING.wire] ?: 0,
                approved = by[ChangeStatus.APPROVED.wire] ?: 0,
                rejected = by[ChangeStatus.REJECTED.wire] ?: 0,
                conflict = by[ChangeStatus.CONFLICT.wire] ?: 0,
                uploaded = by[ChangeStatus.UPLOADED.wire] ?: 0,
            )
        }
    }

    private fun ResultRow.toDraft(counts: ChangeCounts) = Draft(
        id = this[DraftsTable.id].toString(),
        title = this[DraftsTable.title],
        description = this[DraftsTable.description],
        author = this[DraftsTable.author],
        scope = this[DraftsTable.scopeGeom],
        status = wireEnum(DraftStatus.serializer(), this[DraftsTable.status]),
        createdAt = this[DraftsTable.createdAt].toInstant().toString(),
        updatedAt = this[DraftsTable.updatedAt].toInstant().toString(),
        counts = counts,
        area = this[DraftsTable.area],
        changesetComment = this[DraftsTable.changesetComment],
        changesetSource = this[DraftsTable.changesetSource],
    )

    suspend fun insertChange(c: NewChange): Change = db.tx {
        val id = UUID.randomUUID()
        val t = now()
        ChangesTable.insert {
            it[ChangesTable.id] = id
            it[draftId] = c.draftId
            it[osmType] = c.ref.type.apiName
            it[osmId] = c.ref.id
            it[action] = c.action.wire
            it[baseVersion] = c.baseVersion
            it[baseTags] = c.baseTags
            it[newTags] = c.newTags.withoutPseudoTags()
            it[baseGeom] = c.baseGeom
            it[newGeom] = c.newGeom
            it[centroidLon] = c.centroid?.lng
            it[centroidLat] = c.centroid?.lat
            it[ruleId] = c.ruleId
            it[reason] = c.reason
            it[confidence] = c.confidence.wire
            it[status] = c.status.wire
            it[baseShape] = c.baseShape
            it[newLat] = c.newShape?.location?.lat
            it[newLon] = c.newShape?.location?.lng
            it[newNodes] = c.newShape?.nodes
            it[newMembers] = c.newShape?.members
            it[ifUnused] = c.ifUnused
            it[liveVersion] = c.liveVersion
            it[liveTags] = c.liveTags
            it[liveShape] = c.liveShape
            it[liveGeom] = c.liveGeom
            it[createdAt] = t
            it[updatedAt] = t
        }
        touchDraft(c.draftId)
        changeRows { ChangesTable.id eq id }.single()
    }

    suspend fun changes(draftId: UUID): List<Change> = db.tx { DraftGraph.annotate(changeRows { ChangesTable.draftId eq draftId }) }

    suspend fun change(id: UUID): Change? = db.tx {
        val c = changeRows { ChangesTable.id eq id }.firstOrNull() ?: return@tx null
        DraftGraph.annotate(changeRows { ChangesTable.draftId eq UUID.fromString(c.draftId) }).first { it.id == c.id }
    }

    suspend fun changesByIds(ids: Collection<UUID>): List<Change> = db.tx {
        val found = changeRows { ChangesTable.id inList ids }
        val drafts = found.map { UUID.fromString(it.draftId) }.distinct()
        val wanted = found.map { it.id }.toSet()
        if (drafts.isEmpty()) emptyList() else DraftGraph.annotate(changeRows { ChangesTable.draftId inList drafts }).filter { it.id in wanted }
    }

    suspend fun deleteDraft(id: UUID) = db.tx {
        DraftsTable.deleteWhere { DraftsTable.id eq id }
    }

    suspend fun remapRefs(draftId: UUID, mapping: Map<OsmRef, Long>) {
        if (mapping.isEmpty()) return
        db.tx {
            val rows = ChangesTable.selectAll().where { ChangesTable.draftId eq draftId }.toList()
            for (row in rows) {
                val nodes = row[ChangesTable.newNodes]
                val members = row[ChangesTable.newMembers]
                val newNodes = nodes?.map { n -> mapping[OsmRef(OsmType.NODE, n)] ?: n }
                val newMembers = members?.map { m -> mapping[OsmRef(m.type, m.ref)]?.let { m.copy(ref = it) } ?: m }
                if (newNodes != nodes || newMembers != members) {
                    ChangesTable.update({ ChangesTable.id eq row[ChangesTable.id] }) {
                        it[ChangesTable.newNodes] = newNodes
                        it[ChangesTable.newMembers] = newMembers
                        it[updatedAt] = now()
                    }
                }
            }
        }
    }

    suspend fun openChangeFor(ref: OsmRef): Change? = db.tx {
        val active = listOf(DraftStatus.OPEN, DraftStatus.PARTIALLY_MERGED).map { it.wire }
        val openDrafts = DraftsTable.select(DraftsTable.id).where { DraftsTable.status inList active }.map { it[DraftsTable.id] }
        if (openDrafts.isEmpty()) return@tx null
        changeRows {
            (ChangesTable.osmType eq ref.type.apiName) and (ChangesTable.osmId eq ref.id) and
                (ChangesTable.draftId inList openDrafts) and (ChangesTable.status inList OPEN_CHANGE_STATES.map { it.wire })
        }.firstOrNull()
    }

    suspend fun openChangesFor(refs: Collection<OsmRef>): List<Change> {
        if (refs.isEmpty()) return emptyList()
        val wanted = refs.toSet()
        return db.tx {
            val active = listOf(DraftStatus.OPEN, DraftStatus.PARTIALLY_MERGED).map { it.wire }
            val openDrafts = DraftsTable.select(DraftsTable.id).where { DraftsTable.status inList active }.map { it[DraftsTable.id] }
            if (openDrafts.isEmpty()) return@tx emptyList()
            wanted.map { it.id }.distinct().chunked(5000).flatMap { ids ->
                changeRows {
                    (ChangesTable.osmId inList ids) and (ChangesTable.draftId inList openDrafts) and
                        (ChangesTable.status inList OPEN_CHANGE_STATES.map { it.wire })
                }
            }.filter { it.ref in wanted }
        }
    }

    suspend fun nextPlaceholderId(): Long = db.tx {
        val m = ChangesTable.osmId.min()
        val lowest = ChangesTable.select(m).where { ChangesTable.osmId less 0L }.firstOrNull()?.get(m) ?: 0L
        val p = ChangesTable.placeholderId.min()
        val lowestUploaded = ChangesTable.select(p).firstOrNull()?.get(p) ?: 0L
        minOf(lowest, lowestUploaded) - 1
    }

    suspend fun updateChange(id: UUID, block: ChangeUpdate.() -> Unit): Change = db.tx {
        val u = ChangeUpdate().apply(block)
        ChangesTable.update({ ChangesTable.id eq id }) {
            u.newTags?.let { v -> it[newTags] = v.withoutPseudoTags() }
            u.baseTags?.let { v -> it[baseTags] = v }
            u.baseVersion?.let { v -> it[baseVersion] = v }
            u.status?.let { v -> it[status] = v.wire }
            u.reason?.let { v -> it[reason] = v }
            u.confidence?.let { v -> it[confidence] = v.wire }
            if (u.clearLive) {
                it[liveVersion] = null
                it[liveTags] = null
            }
            u.liveVersion?.let { v -> it[liveVersion] = v }
            u.liveTags?.let { v -> it[liveTags] = v }
            u.changesetId?.let { v -> it[changesetId] = v }
            u.osmId?.let { v -> it[osmId] = v }
            u.placeholderId?.let { v -> it[placeholderId] = v }
            if (u.clearLive) {
                it[liveShape] = null
                it[liveGeom] = null
            }
            u.liveShape?.let { v -> it[liveShape] = v }
            u.liveGeom?.let { v -> it[liveGeom] = v }
            u.geometry?.let { g ->
                it[action] = g.action.wire
                it[baseShape] = g.baseShape
                it[newLat] = g.newShape?.location?.lat
                it[newLon] = g.newShape?.location?.lng
                it[newNodes] = g.newShape?.nodes
                it[newMembers] = g.newShape?.members
                it[baseGeom] = g.baseGeom
                it[newGeom] = g.newGeom
                it[centroidLon] = g.centroid?.lng
                it[centroidLat] = g.centroid?.lat
                it[ifUnused] = g.ifUnused
            }
            it[updatedAt] = now()
        }
        val c = changeRows { ChangesTable.id eq id }.single()
        touchDraft(UUID.fromString(c.draftId))
        c
    }

    class ChangeUpdate {
        var newTags: Map<String, String>? = null
        var baseTags: Map<String, String>? = null
        var baseVersion: Int? = null
        var status: ChangeStatus? = null
        var reason: String? = null
        var confidence: Confidence? = null
        var liveVersion: Int? = null
        var liveTags: Map<String, String>? = null
        var clearLive: Boolean = false
        var changesetId: Long? = null
        var osmId: Long? = null
        var placeholderId: Long? = null
        var liveShape: ElementShape? = null
        var liveGeom: JsonElement? = null
        var geometry: GeometryUpdate? = null
    }

    suspend fun addReview(
        changeId: UUID,
        decision: ReviewDecision,
        comment: String?,
        editedTags: Map<String, String>?,
        imagery: String? = null,
    ) = db.tx {
        ReviewsTable.insert {
            it[ReviewsTable.changeId] = changeId
            it[ReviewsTable.decision] = decision.wire
            it[ReviewsTable.comment] = comment
            it[ReviewsTable.editedTags] = editedTags?.withoutPseudoTags()
            it[ReviewsTable.imagery] = imagery
            it[createdAt] = now()
        }
    }

    private fun touchDraft(id: UUID) {
        DraftsTable.update({ DraftsTable.id eq id }) { it[updatedAt] = now() }
    }

    private fun changeRows(where: () -> org.jetbrains.exposed.v1.core.Op<Boolean>): List<Change> {
        val rows = ChangesTable.selectAll().where(where).orderBy(ChangesTable.createdAt to SortOrder.ASC, ChangesTable.osmId to SortOrder.ASC).toList()
        if (rows.isEmpty()) return emptyList()
        val reviews = ReviewsTable.selectAll()
            .where { ReviewsTable.changeId inList rows.map { it[ChangesTable.id] } }
            .orderBy(ReviewsTable.id)
            .map { it.toReview() }
            .groupBy { it.changeId }
        return rows.map { it.toChange(reviews[it[ChangesTable.id].toString()].orEmpty()) }
    }

    private fun ResultRow.toReview() = Review(
        id = this[ReviewsTable.id],
        changeId = this[ReviewsTable.changeId].toString(),
        decision = wireEnum(ReviewDecision.serializer(), this[ReviewsTable.decision]),
        comment = this[ReviewsTable.comment],
        editedTags = this[ReviewsTable.editedTags],
        createdAt = this[ReviewsTable.createdAt].toInstant().toString(),
        imagery = this[ReviewsTable.imagery],
    )

    private fun ResultRow.toChange(reviews: List<Review>): Change {
        val base = this[ChangesTable.baseTags]
        val new = this[ChangesTable.newTags]
        val lon = this[ChangesTable.centroidLon]
        val lat = this[ChangesTable.centroidLat]
        val nLat = this[ChangesTable.newLat]
        val nLon = this[ChangesTable.newLon]
        val nNodes = this[ChangesTable.newNodes]
        val nMembers = this[ChangesTable.newMembers]
        val newShape = if (nLat == null && nNodes == null && nMembers == null) {
            null
        } else {
            ElementShape(if (nLat != null && nLon != null) LngLat(nLon, nLat) else null, nNodes, nMembers)
        }
        val action = wireEnum(ChangeAction.serializer(), this[ChangesTable.action])
        val baseShape = this[ChangesTable.baseShape]
        val ifUnused = this[ChangesTable.ifUnused]
        return Change(
            id = this[ChangesTable.id].toString(),
            draftId = this[ChangesTable.draftId].toString(),
            ref = OsmRef(OsmType.fromApiName(this[ChangesTable.osmType]), this[ChangesTable.osmId]),
            action = action,
            baseVersion = this[ChangesTable.baseVersion],
            baseTags = base,
            newTags = new,
            diff = TagDiff.between(base, new),
            baseGeom = this[ChangesTable.baseGeom],
            newGeom = this[ChangesTable.newGeom],
            centroid = if (lon != null && lat != null) LngLat(lon, lat) else null,
            ruleId = this[ChangesTable.ruleId],
            reason = this[ChangesTable.reason],
            confidence = wireEnum(Confidence.serializer(), this[ChangesTable.confidence]),
            status = wireEnum(ChangeStatus.serializer(), this[ChangesTable.status]),
            liveVersion = this[ChangesTable.liveVersion],
            liveTags = this[ChangesTable.liveTags],
            changesetId = this[ChangesTable.changesetId],
            reviews = reviews,
            createdAt = this[ChangesTable.createdAt].toInstant().toString(),
            updatedAt = this[ChangesTable.updatedAt].toInstant().toString(),
            baseShape = baseShape,
            newShape = newShape,
            liveShape = this[ChangesTable.liveShape],
            liveGeom = this[ChangesTable.liveGeom],
            geometryChanged = action != ChangeAction.MODIFY || newShape != null,
            geometrySummary = DraftGraph.summary(action, baseShape, newShape, ifUnused),
            ifUnused = ifUnused,
            placeholderId = this[ChangesTable.placeholderId],
        )
    }

    suspend fun insertUpload(draftId: UUID, comment: String, changeIds: List<UUID>): Long = db.tx {
        UploadsTable.insert {
            it[UploadsTable.draftId] = draftId
            it[UploadsTable.comment] = comment
            it[UploadsTable.changeIds] = changeIds
            it[status] = "uploading"
            it[createdAt] = now()
        }[UploadsTable.id]
    }

    suspend fun finishUpload(id: Long, status: String, changesetId: Long?, error: String?) = db.tx {
        UploadsTable.update({ UploadsTable.id eq id }) {
            it[UploadsTable.status] = status
            it[UploadsTable.changesetId] = changesetId
            it[UploadsTable.error] = error
            it[finishedAt] = now()
        }
    }

    suspend fun uploadedSince(since: OffsetDateTime): List<UploadedChange> = db.tx {
        val uploads = UploadsTable.selectAll()
            .where { (UploadsTable.status eq "uploaded") and (UploadsTable.finishedAt greater since) }
            .map { Triple(it[UploadsTable.changeIds], it[UploadsTable.finishedAt]!!, it[UploadsTable.changesetId]) }
        val ids = uploads.flatMap { it.first }.distinct()
        if (ids.isEmpty()) return@tx emptyList()
        val byId = changeRows { ChangesTable.id inList ids }.associateBy { it.id }
        uploads.flatMap { (changeIds, at, cs) ->
            changeIds.mapNotNull { byId[it.toString()] }
                .filter { it.status == ChangeStatus.UPLOADED }
                .map { UploadedChange(it, at.toInstant(), cs ?: it.changesetId) }
        }
    }

    suspend fun uploads(draftId: UUID): List<Upload> = db.tx {
        UploadsTable.selectAll().where { UploadsTable.draftId eq draftId }.orderBy(UploadsTable.id).map {
            Upload(
                id = it[UploadsTable.id],
                draftId = it[UploadsTable.draftId].toString(),
                changesetId = it[UploadsTable.changesetId],
                comment = it[UploadsTable.comment],
                changeIds = it[UploadsTable.changeIds].map(UUID::toString),
                status = it[UploadsTable.status],
                error = it[UploadsTable.error],
                createdAt = it[UploadsTable.createdAt].toInstant().toString(),
            )
        }
    }

    suspend fun recordRuleRun(ruleId: String, scopeKey: String, scopeGeom: JsonElement?, issueCount: Int, tookMs: Long) = db.tx {
        RuleRunsTable.insert {
            it[RuleRunsTable.ruleId] = ruleId
            it[RuleRunsTable.scopeKey] = scopeKey
            it[RuleRunsTable.scopeGeom] = scopeGeom
            it[RuleRunsTable.issueCount] = issueCount
            it[RuleRunsTable.tookMs] = tookMs
            it[ranAt] = now()
        }
    }
}

data class UploadedChange(val change: Change, val uploadedAt: java.time.Instant, val changesetId: Long?)
