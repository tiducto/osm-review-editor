package eu.tiducto.osmreview.server.mapcss

import eu.tiducto.osmreview.rules.GolMirror
import eu.tiducto.osmreview.rules.RuleEngine
import eu.tiducto.osmreview.rules.mapcss.MapCssEngine
import eu.tiducto.osmreview.server.core.Account
import eu.tiducto.osmreview.server.core.DraftService
import eu.tiducto.osmreview.server.core.EditElement
import eu.tiducto.osmreview.server.core.NotFoundException
import eu.tiducto.osmreview.server.core.ResolvedScope
import eu.tiducto.osmreview.server.core.StateException
import eu.tiducto.osmreview.server.db.DraftMeta
import eu.tiducto.osmreview.server.db.IssueFilter
import eu.tiducto.osmreview.server.db.NewScan
import eu.tiducto.osmreview.server.db.NewScanIssue
import eu.tiducto.osmreview.server.db.ScanRepo
import eu.tiducto.osmreview.server.rulesfolder.LoadedRuleFile
import eu.tiducto.osmreview.server.rulesfolder.RulesFolder
import eu.tiducto.osmreview.shared.Authors
import eu.tiducto.osmreview.shared.BBox
import eu.tiducto.osmreview.shared.ChangeAction
import eu.tiducto.osmreview.shared.Confidence
import eu.tiducto.osmreview.shared.Issue
import eu.tiducto.osmreview.shared.OsmType
import eu.tiducto.osmreview.shared.ProposalOutcome
import eu.tiducto.osmreview.shared.ProposalResult
import eu.tiducto.osmreview.shared.ProposedChange
import eu.tiducto.osmreview.shared.Rule
import eu.tiducto.osmreview.shared.RuleKind
import eu.tiducto.osmreview.shared.RuleRunResult
import eu.tiducto.osmreview.shared.RunScanRequest
import eu.tiducto.osmreview.shared.Scan
import eu.tiducto.osmreview.shared.ScanDiff
import eu.tiducto.osmreview.shared.ScanDraftRequest
import eu.tiducto.osmreview.shared.ScanDraftResult
import eu.tiducto.osmreview.shared.ScanIssue
import eu.tiducto.osmreview.shared.ScanReport
import eu.tiducto.osmreview.shared.ScanTrigger
import eu.tiducto.osmreview.shared.Severity
import eu.tiducto.osmreview.shared.UiEvent
import eu.tiducto.osmreview.shared.mapcss.Stylesheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.time.measureTimedValue

class ScanService(
    private val repo: ScanRepo,
    private val files: RulesFolder,
    private val engine: MapCssEngine,
    private val rules: RuleEngine,
    private val mirror: GolMirror,
    private val drafts: DraftService,
    private val account: Account,
    private val emit: (UiEvent) -> Unit = {},
) {
    private val log = LoggerFactory.getLogger(ScanService::class.java)
    private val running = Mutex()

    fun checkFiles(): List<LoadedRuleFile> = files.loaded(RuleKind.CHECK).sortedBy { it.path }

    fun checkRules(): List<Rule> = checkFiles().map { f ->
        val sheet = f.value as? Stylesheet
        Rule(
            id = f.path,
            title = sheet?.meta?.title ?: f.path.substringAfterLast('/').removeSuffix(".validator.mapcss"),
            description = sheet?.meta?.description ?: if (sheet == null) "Has errors: ${f.diagnostics.firstOrNull()?.message}" else "MapCSS check file",
            severity = Severity.WARNING,
            confidence = Confidence.NEEDS_REVIEW,
            hasFix = sheet?.rules?.any { r -> r.isCheck && r.properties.any { it.name.startsWith("fix") } } == true,
            enabled = sheet != null,
        )
    }

    fun isCheckFile(id: String): Boolean = checkFiles().any { it.path == id }

    private fun sheetOf(path: String): Pair<LoadedRuleFile, Stylesheet> {
        val f = checkFiles().firstOrNull { it.path == path } ?: throw NotFoundException("No check file '$path'")
        val sheet = f.value as? Stylesheet ?: throw StateException("'$path' has errors and cannot run: ${f.diagnostics.firstOrNull()?.message}")
        return f to sheet
    }

    fun runCheck(path: String, resolved: ResolvedScope, limit: Int): RuleRunResult {
        val (f, sheet) = sheetOf(path)
        val (issues, took) = measureTimedValue { checkIssues(f.path, sheet, resolved) }
        val out = issues.map { i ->
            Issue(
                ruleId = i.rule,
                ref = i.ref,
                tags = emptyMap(),
                centroid = i.centroid,
                severity = i.severity,
                fix = i.fix,
                confidence = Confidence.NEEDS_REVIEW,
                note = listOfNotNull(i.group?.let { "[$it]" }, i.message, if (i.deleteObject) "(fix: delete the object)" else null).joinToString(" "),
            )
        }
        val capped = out.take(limit)
        return RuleRunResult(path, out.size, capped, capped.size < out.size, took.inWholeMilliseconds)
    }

    private fun checkIssues(path: String, sheet: Stylesheet, resolved: ResolvedScope): List<NewScanIssue> {
        val run = engine.checks(sheet, resolved.scope, MAX_ISSUES)
        return run.hits.flatMap { hit ->
            hit.results.map { r ->
                NewScanIssue(
                    rule = "$path:${r.line}",
                    file = path,
                    line = r.line,
                    severity = r.severity,
                    group = r.group,
                    message = r.message,
                    ref = hit.ref,
                    centroid = hit.centroid,
                    fix = r.fix,
                    deleteObject = r.deleteObject,
                    fingerprint = fingerprint(path, hit.ref.toString(), r.message),
                )
            }
        }
    }

    private fun builtInIssues(id: String, resolved: ResolvedScope): List<NewScanIssue> {
        val def = rules.definition(id)
        return rules.run(id, resolved.scope, resolved.key, null).issues.map { i ->
            val message = listOfNotNull(def.title, i.note).joinToString(": ")
            NewScanIssue(
                rule = "builtin:$id",
                file = null,
                line = null,
                severity = i.severity,
                group = def.title,
                message = message,
                ref = i.ref,
                centroid = i.centroid,
                fix = i.fix,
                deleteObject = false,
                fingerprint = fingerprint("builtin:$id", i.ref.toString(), message),
            )
        }
    }

    suspend fun run(req: RunScanRequest, trigger: ScanTrigger = ScanTrigger.MANUAL): Scan {
        val resolved = drafts.resolveScope(req.area, req.bbox, req.polygon)
        if (!mirror.isAvailable) throw eu.tiducto.osmreview.rules.MirrorUnavailableException(mirror.status().message ?: "No GOL is open yet")
        val available = checkFiles()
        req.files?.forEach { p -> require(available.any { it.path == p }) { "No check file '$p'" } }
        val builtInIds = rules.rules().map { it.id }
        req.builtIn?.forEach { id -> require(id in builtInIds) { "Unknown built-in rule '$id'; use one of $builtInIds" } }
        val wantedFiles = req.files
        val wantedBuiltIns = req.builtIn
        val chosen = available.filter { wantedFiles == null || it.path in wantedFiles }.filter { it.value is Stylesheet }
        val builtIns = builtInIds.filter { wantedBuiltIns == null || it in wantedBuiltIns }
        val ruleSet = (chosen.map { it.path } + builtIns.map { "builtin:$it" }).sorted()
        require(ruleSet.isNotEmpty()) { "Nothing to run: no valid check files or built-in rules selected" }
        val id = repo.create(
            NewScan(
                scopeKey = resolved.key,
                areaName = resolved.areaName,
                scopeGeom = resolved.runGeometry,
                request = req,
                ruleSet = ruleSet,
                ruleSetKey = sha(ruleSet.joinToString("\n")).take(16),
                ruleHashes = chosen.associate { it.path to it.hash },
                trigger = trigger,
                mirrorFile = mirror.status().file,
            ),
        )
        val start = System.nanoTime()
        try {
            val issues = running.withLock {
                withContext(Dispatchers.Default) {
                    val out = ArrayList<NewScanIssue>()
                    for (f in chosen) out += checkIssues(f.path, f.value as Stylesheet, resolved)
                    for (b in builtIns) out += builtInIssues(b, resolved)
                    out.distinctBy { Triple(it.rule, it.ref, it.message) }.take(MAX_ISSUES)
                }
            }
            repo.finish(id, issues, (System.nanoTime() - start) / 1_000_000)
        } catch (e: Exception) {
            log.warn("Scan {} of {} failed", id, resolved.key, e)
            repo.fail(id, e.message ?: e.toString(), (System.nanoTime() - start) / 1_000_000)
            throw e
        }
        val scan = repo.get(id)!!.scan
        log.info("Scan {} of {}: {} issues in {} ms ({})", id, scan.scope, scan.issueCount, scan.tookMs, trigger)
        emit(UiEvent.ScanFinished(scan.id, scan.scope, scan.issueCount))
        return scan
    }

    suspend fun rerunRecent(days: Int): List<Scan> {
        if (days <= 0) return emptyList()
        val since = Instant.now().minus(Duration.ofDays(days.toLong()))
        val out = ArrayList<Scan>()
        val openedAt = mirror.status().openedAt?.let(Instant::parse)
        for (s in repo.recent(since)) {
            if (openedAt != null && Instant.parse(s.scan.startedAt) >= openedAt) continue
            runCatching { run(s.request, ScanTrigger.MIRROR_SWAP) }
                .onSuccess { out += it }
                .onFailure { log.warn("Re-running scan of {} after the mirror swap failed: {}", s.scan.scope, it.message) }
        }
        return out
    }

    suspend fun scan(id: String): Scan = stored(id).scan

    private suspend fun stored(id: String) = repo.get(parse(id)) ?: throw NotFoundException("Scan $id not found")

    suspend fun list(scope: String? = null, limit: Int = 50): List<Scan> = repo.list(scope, limit.coerceIn(1, 500))

    suspend fun report(id: String, filter: IssueFilter = IssueFilter(), limit: Int = 500, offset: Long = 0): ScanReport {
        val scan = scan(id)
        val uuid = parse(id)
        val lim = limit.coerceIn(0, MAX_PAGE)
        val issues = repo.issues(uuid, filter, lim + 1, offset)
        return ScanReport(scan, repo.counts(uuid), issues.take(lim), issues.size > lim)
    }

    suspend fun geoJson(id: String, filter: IssueFilter = IssueFilter(), limit: Int = MAX_GEOJSON): JsonObject {
        val scan = scan(id)
        val issues = repo.issues(parse(id), filter, limit.coerceIn(1, MAX_GEOJSON) + 1)
        return buildJsonObject {
            put("type", "FeatureCollection")
            put("scan", scan.id)
            put("truncated", issues.size > limit)
            putJsonArray("features") {
                issues.take(limit).forEach { i ->
                    add(
                        buildJsonObject {
                            put("type", "Feature")
                            put("id", i.id)
                            putJsonObject("geometry") {
                                put("type", "Point")
                                put("coordinates", JsonArray(listOf(JsonPrimitive(i.centroid.lng), JsonPrimitive(i.centroid.lat))))
                            }
                            putJsonObject("properties") {
                                put("id", i.id)
                                put("rule", i.rule)
                                i.file?.let { put("file", it) }
                                i.line?.let { put("line", it) }
                                put("severity", i.severity.name.lowercase())
                                i.group?.let { put("group", it) }
                                put("message", i.message)
                                put("ref", i.ref.toString())
                                put("osmType", i.ref.type.apiName)
                                put("osmId", i.ref.id)
                                put("hasFix", i.fix != null)
                                put("deleteObject", i.deleteObject)
                            }
                        },
                    )
                }
            }
        }
    }

    suspend fun diff(id: String, againstId: String? = null, limit: Int = 500): ScanDiff {
        val scan = scan(id)
        val against = againstId?.let { scan(it) } ?: repo.previous(scan)
        val mine = repo.fingerprints(parse(scan.id))
        if (against == null) {
            val all = repo.issues(parse(scan.id), limit = limit + 1)
            return ScanDiff(scan.id, null, mine.size, 0, 0, all.take(limit), emptyList(), all.size > limit)
        }
        val theirs = repo.fingerprints(parse(against.id))
        val newIds = mine.filterKeys { it !in theirs }.values.toList()
        val resolvedIds = theirs.filterKeys { it !in mine }.values.toList()
        val lim = limit.coerceIn(0, MAX_PAGE)
        val newIssues = if (newIds.isEmpty()) emptyList() else repo.issues(parse(scan.id), IssueFilter(ids = newIds.take(lim)))
        val resolvedIssues = if (resolvedIds.isEmpty()) emptyList() else repo.issues(parse(against.id), IssueFilter(ids = resolvedIds.take(lim)))
        return ScanDiff(
            scan = scan.id,
            against = against.id,
            newCount = newIds.size,
            resolvedCount = resolvedIds.size,
            unchangedCount = mine.size - newIds.size,
            new = newIssues,
            resolved = resolvedIssues,
            truncated = newIds.size > lim || resolvedIds.size > lim,
        )
    }

    suspend fun toDraft(id: String, req: ScanDraftRequest, author: String = Authors.USER): ScanDraftResult {
        val stored = stored(id)
        val scan = stored.scan
        val filter = IssueFilter(group = req.group, rule = req.rule, ids = req.issueIds, fixableOnly = true)
        val all = repo.issues(parse(id), filter.copy(fixableOnly = false))
        val issues = all.filter { it.fix != null || it.deleteObject }
        require(issues.isNotEmpty()) { "None of the ${all.size} selected issues has a fix" }
        require(issues.size <= DraftService.MAX_PROPOSALS) { "At most ${DraftService.MAX_PROPOSALS} fixes per draft; filter by group or rule" }
        val draftId = req.draftId ?: drafts.createDraft(
            title = req.title ?: "Scan fixes · ${scan.areaName ?: scan.scope}",
            description = "Fixes from scan ${scan.id} (${issues.size} of ${scan.issueCount} issues).",
            author = author,
            scope = scan.geometry,
            meta = DraftMeta(area = scan.areaName),
        ).id
        val near = bboxOf(issues)
        val tagFixes = issues.filter { !it.deleteObject && it.fix != null }
        val byRef = tagFixes.groupBy { it.ref }
        val proposals = byRef.map { (ref, list) ->
            val first = list.first()
            ProposedChange(
                ref = ref,
                edit = list.drop(1).fold(first.fix!!) { acc, i ->
                    val fix = i.fix!!
                    acc.copy(set = acc.set + fix.set, remove = (acc.remove + fix.remove).distinct())
                },
                reason = list.joinToString(" · ") { it.message },
                ruleId = first.rule,
                confidence = Confidence.NEEDS_REVIEW,
            )
        }
        val results = ArrayList<ProposalResult>()
        if (proposals.isNotEmpty()) results += drafts.proposeChanges(draftId, proposals, near)
        val deletes = issues.filter { it.deleteObject }.distinctBy { it.ref }
        if (deletes.isNotEmpty()) results += proposeDeletes(draftId, deletes)
        return ScanDraftResult(drafts.detail(draftId), results, all.size - issues.size)
    }

    private suspend fun proposeDeletes(draftId: String, issues: List<ScanIssue>): List<ProposalResult> {
        val out = ArrayList<ProposalResult>()
        val elements = ArrayList<EditElement>()
        val api = account.api()
        for (i in issues) {
            when (i.ref.type) {
                OsmType.RELATION -> out += ProposalResult(i.ref, ProposalOutcome.ERROR, message = "Deleting relations is not supported; delete it in iD")
                OsmType.NODE -> elements += EditElement(ChangeAction.DELETE, i.ref, ifUnused = true, reason = i.message)
                OsmType.WAY -> {
                    elements += EditElement(ChangeAction.DELETE, i.ref, ifUnused = true, reason = i.message)
                    val full = runCatching { api.fetchFull(i.ref) }.getOrDefault(emptyList())
                    full.filter { it.type == OsmType.NODE && it.tags.isEmpty() }.forEach { n ->
                        elements += EditElement(ChangeAction.DELETE, eu.tiducto.osmreview.shared.OsmRef(OsmType.NODE, n.id), ifUnused = true, reason = "untagged node of deleted ${i.ref}")
                    }
                }
            }
        }
        if (elements.isNotEmpty()) {
            elements.distinctBy { it.ref }.chunked(DraftService.MAX_GEOMETRY_ELEMENTS).forEach { chunk ->
                out += drafts.proposeGeometry(draftId, chunk)
            }
        }
        return out
    }

    private fun bboxOf(issues: List<ScanIssue>): BBox? {
        if (issues.isEmpty()) return null
        val pad = 0.005
        return BBox(
            issues.minOf { it.centroid.lng } - pad, issues.minOf { it.centroid.lat } - pad,
            issues.maxOf { it.centroid.lng } + pad, issues.maxOf { it.centroid.lat } + pad,
        )
    }

    private fun parse(id: String): UUID = runCatching { UUID.fromString(id) }.getOrElse { throw NotFoundException("Unknown scan id $id") }

    companion object {
        const val MAX_ISSUES = 200_000
        const val MAX_PAGE = 5000
        const val MAX_GEOJSON = 50_000

        fun sha(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        fun fingerprint(rule: String, ref: String, message: String): String = sha("$rule\u0000$ref\u0000$message").take(24)
    }
}
