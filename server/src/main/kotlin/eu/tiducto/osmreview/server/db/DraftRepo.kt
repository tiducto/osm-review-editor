package eu.tiducto.osmreview.server.db

import eu.tiducto.osmreview.shared.Change
import eu.tiducto.osmreview.shared.ChangeAction
import eu.tiducto.osmreview.shared.ChangeCounts
import eu.tiducto.osmreview.shared.ChangeStatus
import eu.tiducto.osmreview.shared.Confidence
import eu.tiducto.osmreview.shared.Draft
import eu.tiducto.osmreview.shared.DraftStatus
import eu.tiducto.osmreview.shared.LngLat
import eu.tiducto.osmreview.shared.OsmRef
import eu.tiducto.osmreview.shared.OsmType
import eu.tiducto.osmreview.shared.Review
import eu.tiducto.osmreview.shared.ReviewDecision
import eu.tiducto.osmreview.shared.TagDiff
import eu.tiducto.osmreview.shared.Upload
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.jdbc.andWhere
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
)

val OPEN_CHANGE_STATES = listOf(ChangeStatus.PENDING, ChangeStatus.APPROVED, ChangeStatus.CONFLICT)

class DraftRepo(private val db: Db) {

    suspend fun createDraft(title: String, description: String, author: String, scope: JsonElement?): Draft = db.tx {
        val id = UUID.randomUUID()
        val t = now()
        DraftsTable.insert {
            it[DraftsTable.id] = id
            it[DraftsTable.title] = title
            it[DraftsTable.description] = description
            it[DraftsTable.author] = author
            it[scopeGeom] = scope
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

    suspend fun updateDraft(id: UUID, title: String? = null, description: String? = null, status: DraftStatus? = null): Draft? =
        db.tx {
            DraftsTable.update({ DraftsTable.id eq id }) {
                title?.let { v -> it[DraftsTable.title] = v }
                description?.let { v -> it[DraftsTable.description] = v }
                status?.let { v -> it[DraftsTable.status] = v.wire }
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
            it[newTags] = c.newTags
            it[baseGeom] = c.baseGeom
            it[newGeom] = c.newGeom
            it[centroidLon] = c.centroid?.lng
            it[centroidLat] = c.centroid?.lat
            it[ruleId] = c.ruleId
            it[reason] = c.reason
            it[confidence] = c.confidence.wire
            it[status] = ChangeStatus.PENDING.wire
            it[createdAt] = t
            it[updatedAt] = t
        }
        touchDraft(c.draftId)
        changeRows { ChangesTable.id eq id }.single()
    }

    suspend fun changes(draftId: UUID): List<Change> = db.tx { changeRows { ChangesTable.draftId eq draftId } }

    suspend fun change(id: UUID): Change? = db.tx { changeRows { ChangesTable.id eq id }.firstOrNull() }

    suspend fun changesByIds(ids: Collection<UUID>): List<Change> = db.tx { changeRows { ChangesTable.id inList ids } }

    suspend fun openChangeFor(ref: OsmRef): Change? = db.tx {
        val active = listOf(DraftStatus.OPEN, DraftStatus.PARTIALLY_MERGED).map { it.wire }
        val openDrafts = DraftsTable.select(DraftsTable.id).where { DraftsTable.status inList active }.map { it[DraftsTable.id] }
        if (openDrafts.isEmpty()) return@tx null
        changeRows {
            (ChangesTable.osmType eq ref.type.apiName) and (ChangesTable.osmId eq ref.id) and
                (ChangesTable.draftId inList openDrafts) and (ChangesTable.status inList OPEN_CHANGE_STATES.map { it.wire })
        }.firstOrNull()
    }

    suspend fun nextPlaceholderId(): Long = db.tx {
        val m = ChangesTable.osmId.min()
        val lowest = ChangesTable.select(m).where { ChangesTable.osmId less 0L }.firstOrNull()?.get(m) ?: 0L
        lowest - 1
    }

    suspend fun updateChange(id: UUID, block: ChangeUpdate.() -> Unit): Change = db.tx {
        val u = ChangeUpdate().apply(block)
        ChangesTable.update({ ChangesTable.id eq id }) {
            u.newTags?.let { v -> it[newTags] = v }
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
    }

    suspend fun addReview(changeId: UUID, decision: ReviewDecision, comment: String?, editedTags: Map<String, String>?) = db.tx {
        ReviewsTable.insert {
            it[ReviewsTable.changeId] = changeId
            it[ReviewsTable.decision] = decision.wire
            it[ReviewsTable.comment] = comment
            it[ReviewsTable.editedTags] = editedTags
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
    )

    private fun ResultRow.toChange(reviews: List<Review>): Change {
        val base = this[ChangesTable.baseTags]
        val new = this[ChangesTable.newTags]
        val lon = this[ChangesTable.centroidLon]
        val lat = this[ChangesTable.centroidLat]
        return Change(
            id = this[ChangesTable.id].toString(),
            draftId = this[ChangesTable.draftId].toString(),
            ref = OsmRef(OsmType.fromApiName(this[ChangesTable.osmType]), this[ChangesTable.osmId]),
            action = wireEnum(ChangeAction.serializer(), this[ChangesTable.action]),
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

    suspend fun recordRuleRun(ruleId: String, scopeKey: String, issueCount: Int, tookMs: Long) = db.tx {
        RuleRunsTable.insert {
            it[RuleRunsTable.ruleId] = ruleId
            it[RuleRunsTable.scopeKey] = scopeKey
            it[RuleRunsTable.issueCount] = issueCount
            it[RuleRunsTable.tookMs] = tookMs
            it[ranAt] = now()
        }
    }
}
