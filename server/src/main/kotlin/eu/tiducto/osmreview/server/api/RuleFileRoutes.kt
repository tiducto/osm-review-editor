package eu.tiducto.osmreview.server.api

import eu.tiducto.osmreview.server.core.NotFoundException
import eu.tiducto.osmreview.server.presets.PresetService
import eu.tiducto.osmreview.server.rulesfolder.RemoteRuleSources
import eu.tiducto.osmreview.server.rulesfolder.RulesFolder
import eu.tiducto.osmreview.shared.CreateRuleFileRequest
import eu.tiducto.osmreview.shared.RuleFileList
import eu.tiducto.osmreview.shared.RuleKind
import eu.tiducto.osmreview.shared.ValidateRuleFileRequest
import eu.tiducto.osmreview.shared.WriteRuleFileRequest
import eu.tiducto.osmreview.shared.WriteRuleFileResult
import eu.tiducto.osmreview.shared.presets.DetachPresetRequest
import eu.tiducto.osmreview.shared.presets.ImportPresetRequest
import eu.tiducto.osmreview.shared.presets.PresetApplyRequest
import eu.tiducto.osmreview.shared.presets.PresetGeometry
import eu.tiducto.osmreview.shared.presets.PresetMatchRequest
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.cacheControl
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

fun Route.ruleFileRoutes(rules: RulesFolder, sources: RemoteRuleSources) {
    route("/rule-files") {
        get {
            val kind = call.request.queryParameters["kind"]?.let(RuleKind::fromWireName)
            call.respond(RuleFileList(rules.root.toString(), rules.list(kind)))
        }
        post {
            val req = call.receive<CreateRuleFileRequest>()
            call.respondWrite(rules.write(req.path, req.content, req.force, createOnly = true), HttpStatusCode.Created)
        }
        post("/validate") {
            val req = call.receive<ValidateRuleFileRequest>()
            call.respond(rules.validate(req.path, req.content))
        }
        post("/sources/refresh") {
            sources.refresh(force = true)
            call.respond(RuleFileList(rules.root.toString(), rules.list()))
        }
        get("/{path...}") { call.respond(rules.read(call.tailPath())) }
        put("/{path...}") {
            val req = call.receive<WriteRuleFileRequest>()
            call.respondWrite(rules.write(call.tailPath(), req.content, req.force, req.expectedHash))
        }
        delete("/{path...}") { call.respond(rules.delete(call.tailPath())) }
    }
}

fun Route.presetRoutes(presets: PresetService) {
    route("/presets") {
        get("/search") {
            val q = call.request.queryParameters
            val geometry = q["geometry"]?.let(PresetGeometry::fromWireName)
            call.respond(presets.search(q["q"].orEmpty(), q["locale"], geometry, q["limit"]?.toIntOrNull() ?: 20))
        }
        post("/match") {
            val req = call.receive<PresetMatchRequest>()
            call.respond(presets.match(req.tags, req.geometry, req.locale))
        }
        post("/apply") {
            val req = call.receive<PresetApplyRequest>()
            call.respond(presets.apply(req.presetId, req.tags, req.geometry, req.fromPresetId))
        }
        post("/import") { call.respondWrite(presets.import(call.receive<ImportPresetRequest>()), HttpStatusCode.Created) }
        post("/detach") { call.respondWrite(presets.detach(call.receive<DetachPresetRequest>())) }
        get("/smart") { call.respond(presets.smartPresets()) }
        get("/id-schema") { call.respond(presets.data.info("/api/presets/id-schema/")) }
        get("/id-schema/{file...}") {
            val name = call.tailPath("file")
            val bytes = presets.data.file(name) ?: throw NotFoundException("No iD tagging schema file '$name'")
            call.response.header(HttpHeaders.ETag, "\"${presets.data.version}\"")
            call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = 86_400, visibility = CacheControl.Visibility.Public))
            call.respondBytes(bytes, ContentType.Application.Json)
        }
        get("/{id...}") { call.respond(presets.get(call.tailPath("id"), call.request.queryParameters["locale"])) }
    }
}

private fun ApplicationCall.tailPath(name: String = "path"): String =
    parameters.getAll(name).orEmpty().joinToString("/").ifEmpty { throw IllegalArgumentException("$name is required") }

private suspend fun ApplicationCall.respondWrite(result: WriteRuleFileResult, ok: HttpStatusCode = HttpStatusCode.OK) =
    respond(if (result.saved) ok else HttpStatusCode.UnprocessableEntity, result)
