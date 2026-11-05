package eu.tiducto.osmreview.server.mcp

import eu.tiducto.osmreview.server.App
import eu.tiducto.osmreview.server.db.IssueFilter
import eu.tiducto.osmreview.shared.CoverageStats
import eu.tiducto.osmreview.shared.HighlightFile
import eu.tiducto.osmreview.shared.RunScanRequest
import eu.tiducto.osmreview.shared.Scan
import eu.tiducto.osmreview.shared.ScanDiff
import eu.tiducto.osmreview.shared.ScanDraftRequest
import eu.tiducto.osmreview.shared.ScanDraftResult
import eu.tiducto.osmreview.shared.ScanReport
import eu.tiducto.osmreview.shared.Severity
import eu.tiducto.osmreview.shared.WireJson
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal fun registerMapCssTools(server: Server, app: App) {
    val scans = app.scans
    val highlights = app.highlights

    server.tool(
        "run_scan",
        "Run a validation scan over an area: every MapCSS check file (rules folder and remote sources) and every built-in rule, " +
            "or the ones named. The issues are stored; returns the scan with counts by severity. Use get_scan for the issues " +
            "and diff_scans to compare with the previous scan of the same area and rule set.",
        schema(listOf("area")) {
            area()
            putJsonObject("files") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
                put("description", "Check files to run (paths from list_rules); default all")
            }
            putJsonObject("built_in") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
                put("description", "Built-in rule ids to run; default all, [] for none")
            }
        },
    ) { args ->
        val resolved = resolveArea(app, args["area"])
        val req = RunScanRequest(
            area = resolved.areaName,
            bbox = if (resolved.areaName == null && resolved.polygon == null) eu.tiducto.osmreview.rules.Geo.bbox(resolved.scope.bounds) else null,
            polygon = if (resolved.areaName == null && resolved.polygon != null) resolved.geometry else null,
            files = (args["files"] as? JsonArray)?.map { (it as JsonPrimitive).content },
            builtIn = (args["built_in"] as? JsonArray)?.map { (it as JsonPrimitive).content },
        )
        ok(scans.run(req), Scan.serializer())
    }

    server.tool(
        "get_scan",
        "A scan with issue counts per group and severity and a page of its issues (rule, file:line, severity, group, message, " +
            "object ref, centroid, fix). Without scan_id, lists recent scans.",
        schema {
            str("scan_id", "Scan id from run_scan; omit to list recent scans")
            enum("severity", "Only issues of this severity", listOf("error", "warning", "info"))
            str("group", "Only issues of this group")
            str("rule", "Only issues of this rule (e.g. checks/crossings.validator.mapcss:6 or builtin:deprecated-tags)")
            int("limit", "Issues per page (default 100)")
            int("offset", "Skip this many issues")
        },
        readOnly = true,
    ) { args ->
        val id = args.str("scan_id") ?: return@tool ok(scans.list(), ListSerializer(Scan.serializer()))
        val filter = IssueFilter(
            severity = args.str("severity")?.let { s -> Severity.entries.first { it.name.equals(s, ignoreCase = true) } },
            group = args.str("group"),
            rule = args.str("rule"),
        )
        ok(scans.report(id, filter, args.int("limit") ?: 100, args.long("offset") ?: 0), ScanReport.serializer())
    }

    server.tool(
        "diff_scans",
        "Compare a scan with the previous finished scan of the same area and rule set (or with against_id): new issues, " +
            "resolved issues and how many are unchanged.",
        schema(listOf("scan_id")) {
            str("scan_id", "The newer scan")
            str("against_id", "The older scan (default: the previous one of the same area and rule set)")
            int("limit", "Max new and resolved issues listed (default 200)")
        },
        readOnly = true,
    ) { args -> ok(scans.diff(args.str("scan_id")!!, args.str("against_id"), args.int("limit") ?: 200), ScanDiff.serializer()) }

    server.tool(
        "draft_from_scan",
        "Turn the fixes of a scan's issues into a draft for the human to review (tag fixes, and deletes for fixDeleteObject). " +
            "Select issues by ids, group or rule; default every issue with a fix.",
        schema(listOf("scan_id")) {
            str("scan_id", "Scan id")
            putJsonObject("issue_ids") { put("type", "array"); putJsonObject("items") { put("type", "integer") } }
            str("group", "Only issues of this group")
            str("rule", "Only issues of this rule")
            str("title", "Draft title")
            str("draft_id", "Add to this existing draft instead of creating one")
        },
    ) { args ->
        val req = ScanDraftRequest(
            issueIds = (args["issue_ids"] as? JsonArray)?.map { (it as JsonPrimitive).longOrNull ?: throw IllegalArgumentException("issue_ids are numbers") },
            group = args.str("group"),
            rule = args.str("rule"),
            title = args.str("title"),
            draftId = args.str("draft_id"),
        )
        ok(scans.toDraft(args.str("scan_id")!!, req, eu.tiducto.osmreview.shared.Authors.CLAUDE), ScanDraftResult.serializer())
    }

    server.tool(
        "list_highlights",
        "MapCSS highlight files: title, legend, coverage measure (-ore-stats), whether every rule compiles to a MapLibre filter, " +
            "and diagnostics.",
        readOnly = true,
    ) {
        val list = highlights.list()
        okJson(
            buildJsonObject {
                put("tile_properties", WireJson.encodeToJsonElement(eu.tiducto.osmreview.shared.TileProperties.serializer(), list.tileProperties))
                putJsonArray("files") {
                    list.files.forEach { f ->
                        add(
                            WireJson.encodeToJsonElement(
                                HighlightFile.serializer(),
                                f.copy(rules = f.rules.map { it.copy(filter = null) }),
                            ),
                        )
                    }
                }
            },
        )
    }

    server.tool(
        "highlight_stats",
        "Coverage numbers of a highlight over an area: per legend entry the count, length (km) or area (km²) as set by the file's " +
            "-ore-stats, and its share in percent; lengths and areas are clipped to the area.",
        schema(listOf("path", "area")) {
            str("path", "Highlight file, e.g. highlights/sidewalks.mapcss")
            area()
        },
        readOnly = true,
    ) { args ->
        val resolved = resolveArea(app, args["area"])
        ok(withContext(Dispatchers.Default) { highlights.stats(args.str("path")!!, resolved) }, CoverageStats.serializer())
    }

    server.tool(
        "evaluate_highlight",
        "Evaluate a highlight file over an area on the server: ids of the matching objects with their style (color, width, " +
            "legend, classes, rule indexes).",
        schema(listOf("path", "area")) {
            str("path", "Highlight file")
            area()
            int("limit", "Max objects (default 500)")
            str("legend", "Only objects with this legend entry")
        },
        readOnly = true,
    ) { args ->
        val resolved = resolveArea(app, args["area"])
        val limit = args.int("limit") ?: 500
        val legend = args.str("legend")
        val r = withContext(Dispatchers.Default) { highlights.evaluate(args.str("path")!!, resolved, if (legend == null) limit else 100_000) }
        val features = r.features.filter { legend == null || it.style.legend == legend }
        okJson(
            buildJsonObject {
                put("path", r.path)
                put("scope", resolved.key)
                put("count", features.size)
                put("truncated", r.truncated || features.size > limit)
                put("took_ms", r.tookMs)
                putJsonArray("objects") {
                    features.take(limit).forEach { f ->
                        add(
                            buildJsonObject {
                                put("ref", f.ref.toString())
                                f.style.legend?.let { put("legend", it) }
                                put("style", JsonObject(f.style.properties))
                                if (f.style.classes.isNotEmpty()) putJsonArray("classes") { f.style.classes.forEach { add(JsonPrimitive(it)) } }
                            },
                        )
                    }
                }
            },
        )
    }
}
