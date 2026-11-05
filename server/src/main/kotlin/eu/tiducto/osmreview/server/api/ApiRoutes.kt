package eu.tiducto.osmreview.server.api

import eu.tiducto.osmreview.rules.Scope
import eu.tiducto.osmreview.server.App
import eu.tiducto.osmreview.server.core.DraftService
import eu.tiducto.osmreview.server.core.StateException
import eu.tiducto.osmreview.shared.AppStatus
import eu.tiducto.osmreview.shared.Authors
import eu.tiducto.osmreview.shared.BBox
import eu.tiducto.osmreview.shared.CreateDraftRequest
import eu.tiducto.osmreview.shared.DraftFromRuleRequest
import eu.tiducto.osmreview.shared.DraftStatus
import eu.tiducto.osmreview.shared.ManualEditRequest
import eu.tiducto.osmreview.shared.MapFeature
import eu.tiducto.osmreview.shared.OsmRef
import eu.tiducto.osmreview.shared.OsmTarget
import eu.tiducto.osmreview.shared.ProposeChangesRequest
import eu.tiducto.osmreview.shared.ResolveConflictRequest
import eu.tiducto.osmreview.shared.ReviewRequest
import eu.tiducto.osmreview.shared.SetTargetRequest
import eu.tiducto.osmreview.shared.UpdateDraftRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.launch

fun Route.apiRoutes(app: App) {
    val drafts = app.drafts
    val queries = app.queries

    route("/api") {
        get("/status") {
            call.respond(
                AppStatus(
                    version = app.config.version,
                    mirror = app.mirror.status(),
                    account = app.account.status(),
                    mcpUrl = "${app.config.publicUrl}/mcp",
                    remoteControlPort = app.config.remoteControlPort,
                ),
            )
        }

        // ---- areas, imagery, search ----

        get("/areas") { call.respond(app.areas.presets()) }
        get("/areas/{name}") {
            val name = call.parameters["name"]!!
            val (area, _) = queries.area(name) ?: throw eu.tiducto.osmreview.server.core.NotFoundException("Unknown area '$name'")
            call.respond(area)
        }
        get("/imagery") { call.respond(app.imagery) }
        get("/search") {
            val q = call.request.queryParameters["q"].orEmpty()
            call.respond(app.search.search(q, call.bboxParam()))
        }

        // ---- mirror ----

        get("/features") {
            val bbox = call.bboxParam() ?: throw IllegalArgumentException("bbox is required")
            require((bbox.east - bbox.west) * (bbox.north - bbox.south) <= MAX_FEATURE_BBOX_AREA) {
                "bbox too large for object rendering; zoom in to 15 or more"
            }
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 20_000) ?: 5000
            call.respond(queries.features(bbox, limit))
        }
        get("/objects/{ref}") {
            val ref = OsmRef.parse(call.parameters["ref"]!!)
            if (call.request.queryParameters["live"] == "true") {
                val el = app.account.api().fetchOne(ref) ?: throw eu.tiducto.osmreview.server.core.NotFoundException(
                    "$ref does not exist on the ${app.account.target.name.lowercase()} API",
                )
                val mirrored = runCatching { queries.mapFeature(ref) }.getOrNull()
                val geom = mirrored?.geometry ?: if (el.lat != null && el.lon != null) {
                    buildJsonObject {
                        put("type", "Point")
                        put("coordinates", JsonArray(listOf(JsonPrimitive(el.lon.toDouble()), JsonPrimitive(el.lat.toDouble()))))
                    }
                } else {
                    kotlinx.serialization.json.JsonNull
                }
                call.respond(MapFeature(ref, el.tags, geom, el.version))
            } else {
                call.respond(queries.mapFeature(ref) ?: throw eu.tiducto.osmreview.server.core.NotFoundException("$ref is not in the mirror"))
            }
        }
        get("/objects/{ref}/links") {
            val ref = OsmRef.parse(call.parameters["ref"]!!)
            call.respond(app.editorLinks(ref))
        }

        // ---- rules ----

        get("/rules") { call.respond(app.rules.rules()) }
        get("/rules/{id}/issues") {
            val id = call.parameters["id"]!!
            val area = call.request.queryParameters["area"]
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()
            val (scope: Scope, key, _) = drafts.resolveScope(area, call.bboxParam())
            val result = app.rules.run(id, scope, key, limit ?: 5000)
            app.launchRecord(id, key, result.issueCount, result.tookMs)
            call.respond(result)
        }

        // ---- drafts ----

        route("/drafts") {
            get {
                val status = call.request.queryParameters["status"]?.let { s ->
                    DraftStatus.entries.first { it.name.equals(s, ignoreCase = true) }
                }
                call.respond(app.repo.listDrafts(status, call.request.queryParameters["author"]))
            }
            post {
                val req = call.receive<CreateDraftRequest>()
                call.respond(HttpStatusCode.Created, drafts.createDraft(req.title, req.description, Authors.USER, req.scope))
            }
            post("/from-rule") { call.respond(HttpStatusCode.Created, drafts.draftFromRule(call.receive<DraftFromRuleRequest>())) }
            route("/{id}") {
                get { call.respond(drafts.detail(call.parameters["id"]!!)) }
                patch {
                    val req = call.receive<UpdateDraftRequest>()
                    require(req.status == null || req.status in setOf(DraftStatus.OPEN, DraftStatus.CLOSED)) {
                        "Only open and closed can be set by hand"
                    }
                    val d = app.repo.updateDraft(DraftService.parseId(call.parameters["id"]!!), req.title, req.description, req.status)
                        ?: throw eu.tiducto.osmreview.server.core.NotFoundException("Draft not found")
                    app.events.emit(eu.tiducto.osmreview.shared.UiEvent.DraftUpdated(d.id))
                    call.respond(d)
                }
                post("/changes") {
                    val req = call.receive<ProposeChangesRequest>()
                    call.respond(drafts.proposeChanges(call.parameters["id"]!!, req.changes))
                }
                post("/refresh") { call.respond(drafts.refresh(call.parameters["id"]!!)) }
                post("/approve-remaining") {
                    call.respond(drafts.approveRemaining(call.parameters["id"]!!, call.request.queryParameters["ruleId"]))
                }
                get("/merge-preview") { call.respond(app.merge.preview(call.parameters["id"]!!)) }
                post("/merge") {
                    val id = call.parameters["id"]!!
                    app.account.requireToken()
                    if (app.merge.isMerging) throw StateException("Another merge is running")
                    app.scope.launch {
                        runCatching { app.merge.merge(id) }.onFailure { e ->
                            app.events.emit(
                                eu.tiducto.osmreview.shared.UiEvent.MergeProgressEvent(
                                    eu.tiducto.osmreview.shared.MergeProgress(id, "", 0, 0, null, "Merge failed: ${e.message}", finished = true, failed = true),
                                ),
                            )
                        }
                    }
                    call.respond(HttpStatusCode.Accepted, mapOf("status" to "started"))
                }
                get("/uploads") { call.respond(app.repo.uploads(DraftService.parseId(call.parameters["id"]!!))) }
            }
        }

        post("/changes/{id}/review") { call.respond(drafts.review(call.parameters["id"]!!, call.receive<ReviewRequest>())) }
        post("/changes/{id}/resolve") {
            call.respond(drafts.resolveConflict(call.parameters["id"]!!, call.receive<ResolveConflictRequest>().tags))
        }
        post("/manual-edits") {
            val req = call.receive<ManualEditRequest>()
            call.respond(drafts.manualEdit(req.ref, req.tags, req.reason))
        }

        // ---- account ----

        get("/account") { call.respond(app.account.status()) }
        post("/account/logout") {
            app.account.logout()
            call.respond(app.account.status())
        }
        get("/settings/mcp") {
            val url = "${app.config.publicUrl}/mcp"
            call.respond(
                eu.tiducto.osmreview.shared.McpInfo(
                    url = url,
                    token = app.mcpToken,
                    claudeCodeCommand = "claude mcp add --transport http osm-review $url --header \"Authorization: Bearer ${app.mcpToken}\"",
                ),
            )
        }
        put("/settings/target") {
            val req = call.receive<SetTargetRequest>()
            require(req.target != OsmTarget.PROD || req.confirm) { "Switching to the production API needs confirm: true" }
            if (app.merge.isMerging) throw StateException("A merge is running")
            app.account.setTarget(req.target)
            call.respond(app.account.status())
        }
    }

    route("/auth") {
        get("/login") { call.respondRedirect(app.account.authorizeUrl()) }
        get("/callback") {
            val p = call.request.queryParameters
            p["error"]?.let { throw IllegalArgumentException("OSM authorization failed: $it ${p["error_description"].orEmpty()}") }
            app.account.completeAuth(p["code"] ?: throw IllegalArgumentException("Missing code"), p["state"].orEmpty())
            call.respondRedirect("/?connected=1")
        }
    }
}

/** Query-string bbox `west,south,east,north`. */
fun ApplicationCall.bboxParam(): BBox? = request.queryParameters["bbox"]?.let { s ->
    val p = s.split(",").map { it.trim().toDouble() }
    require(p.size == 4) { "bbox must be west,south,east,north" }
    BBox(p[0], p[1], p[2], p[3])
}

/** About 0.05° × 0.03°: a zoom-14-ish viewport at Czech latitudes. */
private const val MAX_FEATURE_BBOX_AREA = 0.05 * 0.03
