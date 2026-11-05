package eu.tiducto.osmreview.server.db

import eu.tiducto.osmreview.shared.LngLat
import eu.tiducto.osmreview.shared.OsmRef
import eu.tiducto.osmreview.shared.OsmType
import eu.tiducto.osmreview.shared.RunScanRequest
import eu.tiducto.osmreview.shared.Scan
import eu.tiducto.osmreview.shared.ScanCount
import eu.tiducto.osmreview.shared.ScanIssue
import eu.tiducto.osmreview.shared.ScanStatus
import eu.tiducto.osmreview.shared.ScanTrigger
import eu.tiducto.osmreview.shared.Severity
import eu.tiducto.osmreview.shared.TagEdit
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

val ScanStatus.wire get() = wire(ScanStatus.serializer())
val ScanTrigger.wire get() = wire(ScanTrigger.serializer())
val Severity.wire get() = wire(Severity.serializer())

data class NewScan(
    val scopeKey: String,
    val areaName: String?,
    val scopeGeom: JsonElement?,
    val request: RunScanRequest,
    val ruleSet: List<String>,
    val ruleSetKey: String,
    val ruleHashes: Map<String, String>,
    val trigger: ScanTrigger,
    val mirrorFile: String?,
)

data class NewScanIssue(
    val rule: String,
    val file: String?,
    val line: Int?,
    val severity: Severity,
    val group: String?,
    val message: String,
    val ref: OsmRef,
    val centroid: LngLat,
    val fix: TagEdit?,
    val deleteObject: Boolean,
    val fingerprint: String,
)

data class IssueFilter(
    val severity: Severity? = null,
    val group: String? = null,
    val rule: String? = null,
    val ids: List<Long>? = null,
    val fixableOnly: Boolean = false,
)

data class StoredScan(val scan: Scan, val request: RunScanRequest)

class ScanRepo(private val db: Db) {
    private fun now(): OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)

    suspend fun create(s: NewScan): UUID = db.tx {
        val id = UUID.randomUUID()
        ScansTable.insert {
            it[ScansTable.id] = id
            it[scopeKey] = s.scopeKey
            it[areaName] = s.areaName
            it[scopeGeom] = s.scopeGeom
            it[request] = s.request
            it[ruleSet] = s.ruleSet
            it[ruleSetKey] = s.ruleSetKey
            it[ruleHashes] = s.ruleHashes
            it[status] = ScanStatus.RUNNING.wire
            it[trigger] = s.trigger.wire
            it[mirrorFile] = s.mirrorFile
            it[issueCount] = 0
            it[errorCount] = 0
            it[warningCount] = 0
            it[infoCount] = 0
            it[startedAt] = now()
        }
        id
    }

    suspend fun finish(id: UUID, issues: List<NewScanIssue>, tookMs: Long) = db.tx {
        issues.chunked(BATCH).forEach { chunk ->
            ScanIssuesTable.batchInsert(chunk, shouldReturnGeneratedValues = false) { i ->
                this[ScanIssuesTable.scanId] = id
                this[ScanIssuesTable.rule] = i.rule
                this[ScanIssuesTable.file] = i.file
                this[ScanIssuesTable.line] = i.line
                this[ScanIssuesTable.severity] = i.severity.wire
                this[ScanIssuesTable.group] = i.group
                this[ScanIssuesTable.message] = i.message
                this[ScanIssuesTable.osmType] = i.ref.type.apiName
                this[ScanIssuesTable.osmId] = i.ref.id
                this[ScanIssuesTable.centroidLon] = i.centroid.lng
                this[ScanIssuesTable.centroidLat] = i.centroid.lat
                this[ScanIssuesTable.fix] = i.fix
                this[ScanIssuesTable.deleteObject] = i.deleteObject
                this[ScanIssuesTable.fingerprint] = i.fingerprint
            }
        }
        val by = issues.groupingBy { it.severity }.eachCount()
        ScansTable.update({ ScansTable.id eq id }) {
            it[status] = ScanStatus.DONE.wire
            it[issueCount] = issues.size
            it[errorCount] = by[Severity.ERROR] ?: 0
            it[warningCount] = by[Severity.WARNING] ?: 0
            it[infoCount] = by[Severity.INFO] ?: 0
            it[ScansTable.tookMs] = tookMs
            it[finishedAt] = now()
        }
    }

    suspend fun fail(id: UUID, message: String, tookMs: Long) = db.tx {
        ScansTable.update({ ScansTable.id eq id }) {
            it[status] = ScanStatus.FAILED.wire
            it[error] = message
            it[ScansTable.tookMs] = tookMs
            it[finishedAt] = now()
        }
    }

    suspend fun failRunning(message: String) = db.tx {
        ScansTable.update({ ScansTable.status eq ScanStatus.RUNNING.wire }) {
            it[status] = ScanStatus.FAILED.wire
            it[error] = message
            it[finishedAt] = now()
        }
    }

    suspend fun get(id: UUID): StoredScan? = db.tx {
        ScansTable.selectAll().where { ScansTable.id eq id }.firstOrNull()?.let { StoredScan(it.toScan(), it[ScansTable.request]) }
    }

    suspend fun list(scopeKey: String? = null, limit: Int = 50): List<Scan> = db.tx {
        ScansTable.selectAll()
            .apply { scopeKey?.let { andWhere { ScansTable.scopeKey eq it } } }
            .orderBy(ScansTable.startedAt, SortOrder.DESC)
            .limit(limit)
            .map { it.toScan() }
    }

    suspend fun previous(scan: Scan): Scan? = db.tx {
        val started = OffsetDateTime.parse(scan.startedAt)
        ScansTable.selectAll()
            .where {
                (ScansTable.scopeKey eq scan.scope) and (ScansTable.ruleSetKey eq scan.ruleSetKey) and
                    (ScansTable.status eq ScanStatus.DONE.wire) and (ScansTable.startedAt less started) and
                    (ScansTable.id neq UUID.fromString(scan.id))
            }
            .orderBy(ScansTable.startedAt, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.toScan()
    }

    suspend fun recent(since: Instant): List<StoredScan> = db.tx {
        ScansTable.selectAll()
            .where { (ScansTable.status eq ScanStatus.DONE.wire) and (ScansTable.startedAt greaterEq since.atOffset(ZoneOffset.UTC)) }
            .orderBy(ScansTable.startedAt, SortOrder.DESC)
            .map { StoredScan(it.toScan(), it[ScansTable.request]) }
            .distinctBy { it.scan.scope to it.scan.ruleSetKey }
    }

    suspend fun counts(id: UUID): List<ScanCount> = db.tx {
        val n = ScanIssuesTable.id.count()
        ScanIssuesTable.select(ScanIssuesTable.group, ScanIssuesTable.severity, n)
            .where { ScanIssuesTable.scanId eq id }
            .groupBy(ScanIssuesTable.group, ScanIssuesTable.severity)
            .map { ScanCount(it[ScanIssuesTable.group] ?: "", wireEnum(Severity.serializer(), it[ScanIssuesTable.severity]), it[n].toInt()) }
            .sortedWith(compareByDescending<ScanCount> { it.severity.ordinal }.thenByDescending { it.count }.thenBy { it.group })
    }

    suspend fun issues(id: UUID, filter: IssueFilter = IssueFilter(), limit: Int = Int.MAX_VALUE, offset: Long = 0): List<ScanIssue> = db.tx {
        ScanIssuesTable.selectAll()
            .where { ScanIssuesTable.scanId eq id }
            .apply { filter.severity?.let { s -> andWhere { ScanIssuesTable.severity eq s.wire } } }
            .apply { filter.group?.let { g -> andWhere { ScanIssuesTable.group eq g } } }
            .apply { filter.rule?.let { r -> andWhere { ScanIssuesTable.rule eq r } } }
            .apply { filter.ids?.let { ids -> andWhere { ScanIssuesTable.id inList ids } } }
            .orderBy(ScanIssuesTable.id)
            .limit(limit)
            .offset(offset)
            .map { it.toIssue() }
            .filter { !filter.fixableOnly || it.fix != null || it.deleteObject }
    }

    suspend fun fingerprints(id: UUID): Map<String, Long> = db.tx {
        ScanIssuesTable.select(ScanIssuesTable.id, ScanIssuesTable.fingerprint)
            .where { ScanIssuesTable.scanId eq id }
            .associate { it[ScanIssuesTable.fingerprint] to it[ScanIssuesTable.id] }
    }

    private fun ResultRow.toScan() = Scan(
        id = this[ScansTable.id].toString(),
        scope = this[ScansTable.scopeKey],
        areaName = this[ScansTable.areaName],
        geometry = this[ScansTable.scopeGeom],
        ruleSet = this[ScansTable.ruleSet],
        ruleSetKey = this[ScansTable.ruleSetKey],
        status = wireEnum(ScanStatus.serializer(), this[ScansTable.status]),
        trigger = wireEnum(ScanTrigger.serializer(), this[ScansTable.trigger]),
        issueCount = this[ScansTable.issueCount],
        errors = this[ScansTable.errorCount],
        warnings = this[ScansTable.warningCount],
        infos = this[ScansTable.infoCount],
        tookMs = this[ScansTable.tookMs],
        startedAt = this[ScansTable.startedAt].toInstant().toString(),
        finishedAt = this[ScansTable.finishedAt]?.toInstant()?.toString(),
        mirrorFile = this[ScansTable.mirrorFile],
        error = this[ScansTable.error],
    )

    private fun ResultRow.toIssue() = ScanIssue(
        id = this[ScanIssuesTable.id],
        rule = this[ScanIssuesTable.rule],
        file = this[ScanIssuesTable.file],
        line = this[ScanIssuesTable.line],
        severity = wireEnum(Severity.serializer(), this[ScanIssuesTable.severity]),
        group = this[ScanIssuesTable.group],
        message = this[ScanIssuesTable.message],
        ref = OsmRef(OsmType.fromApiName(this[ScanIssuesTable.osmType]), this[ScanIssuesTable.osmId]),
        centroid = LngLat(this[ScanIssuesTable.centroidLon], this[ScanIssuesTable.centroidLat]),
        fix = this[ScanIssuesTable.fix],
        deleteObject = this[ScanIssuesTable.deleteObject],
    )

    private companion object {
        const val BATCH = 2000
    }
}
