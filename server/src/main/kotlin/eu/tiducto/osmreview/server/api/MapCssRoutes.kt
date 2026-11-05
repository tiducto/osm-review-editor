package eu.tiducto.osmreview.server.api

import eu.tiducto.osmreview.rules.PolygonScopes
import eu.tiducto.osmreview.server.App
import eu.tiducto.osmreview.server.db.IssueFilter
import eu.tiducto.osmreview.shared.HighlightStatsRequest
import eu.tiducto.osmreview.shared.RunScanRequest
import eu.tiducto.osmreview.shared.ScanDraftRequest
import eu.tiducto.osmreview.shared.Severity
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun Route.mapCssRoutes(app: App) {
    val highlights = app.highlights
    val scans = app.scans

    route("/highlights") {
        get { call.respond(highlights.list()) }
        get("/file") { call.respond(highlights.describe(call.requiredParam("path"))) }
        get("/evaluate") {
            val q = call.request.queryParameters
            val path = call.requiredParam("path")
            val result = withContext(Dispatchers.Default) {
                highlights.evaluate(path, call.bboxParam(), q["tile"], q["limit"]?.toIntOrNull() ?: eu.tiducto.osmreview.server.mapcss.HighlightService.DEFAULT_LIMIT)
            }
            call.respond(result)
        }
        get("/stats") {
            val q = call.request.queryParameters
            val resolved = app.drafts.resolveScope(q["area"], call.bboxParam(), q["polygon"]?.let(PolygonScopes::parseJson))
            call.respond(withContext(Dispatchers.Default) { highlights.stats(call.requiredParam("path"), resolved) })
        }
        post("/stats") {
            val req = call.receive<HighlightStatsRequest>()
            val resolved = app.drafts.resolveScope(req.area, req.bbox, req.polygon)
            call.respond(withContext(Dispatchers.Default) { highlights.stats(req.path, resolved) })
        }
    }

    route("/scans") {
        get {
            val q = call.request.queryParameters
            call.respond(scans.list(q["scope"], q["limit"]?.toIntOrNull() ?: 50))
        }
        post { call.respond(HttpStatusCode.Created, scans.run(call.receive<RunScanRequest>())) }
        route("/{id}") {
            get {
                val q = call.request.queryParameters
                call.respond(scans.report(call.parameters["id"]!!, call.issueFilter(), q["limit"]?.toIntOrNull() ?: 500, q["offset"]?.toLongOrNull() ?: 0))
            }
            get("/geojson") {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: eu.tiducto.osmreview.server.mapcss.ScanService.MAX_GEOJSON
                call.respondText(scans.geoJson(call.parameters["id"]!!, call.issueFilter(), limit).toString(), ContentType.parse("application/geo+json"))
            }
            get("/diff") {
                val q = call.request.queryParameters
                call.respond(scans.diff(call.parameters["id"]!!, q["against"], q["limit"]?.toIntOrNull() ?: 500))
            }
            post("/draft") {
                call.respond(HttpStatusCode.Created, scans.toDraft(call.parameters["id"]!!, call.receive<ScanDraftRequest>()))
            }
        }
    }
}

private fun ApplicationCall.requiredParam(name: String): String =
    request.queryParameters[name]?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("$name is required")

private fun ApplicationCall.issueFilter(): IssueFilter {
    val q = request.queryParameters
    return IssueFilter(
        severity = q["severity"]?.let { s -> Severity.entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: throw IllegalArgumentException("severity is error, warning or info") },
        group = q["group"],
        rule = q["rule"],
    )
}
