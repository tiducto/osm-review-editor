package eu.tiducto.osmreview.server.remote

import eu.tiducto.osmreview.server.App
import eu.tiducto.osmreview.server.osm.OsmXml
import eu.tiducto.osmreview.shared.Authors
import eu.tiducto.osmreview.shared.BBox
import eu.tiducto.osmreview.shared.ChangeAction
import eu.tiducto.osmreview.shared.ImagerySource
import eu.tiducto.osmreview.shared.LngLat
import eu.tiducto.osmreview.shared.OsmRef
import eu.tiducto.osmreview.shared.OsmType
import eu.tiducto.osmreview.shared.ProposalOutcome
import eu.tiducto.osmreview.shared.ProposedChange
import eu.tiducto.osmreview.shared.TagEdit
import eu.tiducto.osmreview.shared.UiEvent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("eu.tiducto.osmreview.server.remote")

/**
 * JOSM remote control (https://josm.openstreetmap.de/wiki/Help/RemoteControlCommands) on
 * 127.0.0.1:8111, so "Edit in JOSM" buttons on Osmose, MapRoulette, OSMCha, Overpass Turbo and
 * osm.org open objects here. Requests push a `remote_command` to the open UI tab; `addtags` becomes
 * a one-change draft per object, ready to approve. Nothing here can upload.
 */
fun Route.remoteControlRoutes(app: App) {
    install(JosmCors)
    options("{path...}") {
        call.response.header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        call.response.header("Access-Control-Allow-Headers", call.request.headers["Access-Control-Request-Headers"] ?: "*")
        call.response.header("Access-Control-Allow-Private-Network", "true")
        call.respondText("", status = HttpStatusCode.NoContent)
    }

    get("/version") {
        val body = buildJsonObject {
            put("protocolversion", buildJsonObject { put("major", 1); put("minor", 13) })
            put("application", "OSM Review Editor (JOSM remote control compatible)")
            put("version", 19_999)
            putJsonObject("osm_server") { put("url", app.config.target(app.account.target).apiUrl) }
        }
        call.respondText(body.toString(), ContentType.Application.Json)
    }

    get("/zoom") {
        val bbox = call.request.queryParameters.bbox() ?: return@get call.josmError("Missing left/right/top/bottom")
        app.events.emit(UiEvent.RemoteCommand("zoom", bbox = bbox))
        call.ok()
    }

    get("/load_and_zoom") {
        val p = call.request.queryParameters
        val bbox = p.bbox() ?: return@get call.josmError("Missing left/right/top/bottom")
        val select = p["select"].orEmpty().split(",").mapNotNull { it.toRefOrNull() }
        val draftId = draftFromAddTags(app, select, p["addtags"], p["changeset_comment"])
        app.events.emit(UiEvent.RemoteCommand("load_and_zoom", bbox = bbox, select = select, draftId = draftId))
        call.ok()
    }

    get("/load_object") {
        val p = call.request.queryParameters
        val refs = p["objects"].orEmpty().split(",").mapNotNull { it.toRefOrNull() }
        if (refs.isEmpty()) return@get call.josmError("Missing objects")
        val bbox = runCatching { app.queries.mapFeatures(refs).values.map { it.geometry } }.getOrNull()?.let(::bboxOfGeoJson)
        val draftId = draftFromAddTags(app, refs, p["addtags"], p["changeset_comment"])
        app.events.emit(UiEvent.RemoteCommand("load_object", bbox = bbox, select = refs, draftId = draftId))
        call.ok()
    }

    get("/import") {
        val url = call.request.queryParameters["url"] ?: return@get call.josmError("Missing url")
        val xml = app.http.get(url) { header(HttpHeaders.UserAgent, app.config.userAgent) }.bodyAsText()
        val draftId = draftFromOsmXml(app, xml, "Imported from $url")
        app.events.emit(UiEvent.RemoteCommand("import", draftId = draftId))
        call.ok()
    }

    suspend fun loadData(call: ApplicationCall, params: Parameters) {
        val data = params["data"] ?: return call.josmError("Missing data")
        val draftId = draftFromOsmXml(app, data, params["layer_name"] ?: "Loaded data")
        app.events.emit(UiEvent.RemoteCommand("load_data", draftId = draftId))
        call.ok()
    }
    get("/load_data") { loadData(call, call.request.queryParameters) }
    post("/load_data") { loadData(call, call.receiveParameters()) }

    get("/add_node") {
        val p = call.request.queryParameters
        val lat = p["lat"]?.toDoubleOrNull() ?: return@get call.josmError("Missing lat")
        val lon = p["lon"]?.toDoubleOrNull() ?: return@get call.josmError("Missing lon")
        val tags = parseAddTags(p["addtags"])
        val draft = app.drafts.createDraft("Add node at $lat,$lon", "From JOSM remote control", Authors.REMOTE)
        app.drafts.proposeChanges(
            draft.id,
            listOf(ProposedChange(OsmRef(OsmType.NODE, -1), TagEdit(set = tags), "remote control add_node", action = ChangeAction.CREATE, location = LngLat(lon, lat))),
        )
        val pad = 0.001
        app.events.emit(UiEvent.RemoteCommand("add_node", bbox = BBox(lon - pad, lat - pad, lon + pad, lat + pad), draftId = draft.id))
        call.ok()
    }

    get("/imagery") {
        val p = call.request.queryParameters
        val url = p["url"] ?: return@get call.josmError("Missing url")
        val type = p["type"]?.lowercase() ?: "tms"
        val source = ImagerySource(
            id = "remote-${url.hashCode().toUInt()}",
            name = p["title"] ?: "Remote imagery",
            type = if (type == "wms") "wms" else "tms",
            url = url.replace("{zoom}", "{z}").replace("{proj}", "EPSG:3857").replace("{bbox}", "{bbox-epsg-3857}")
                .replace("{width}", "256").replace("{height}", "256"),
        )
        app.events.emit(UiEvent.RemoteCommand("imagery", imagery = source))
        call.ok()
    }
}

/** `addtags=k=v|k2=v2` → a one-change draft per selected object. Returns the draft id, if any. */
private suspend fun draftFromAddTags(app: App, refs: List<OsmRef>, addtags: String?, comment: String?): String? {
    val tags = parseAddTags(addtags)
    if (tags.isEmpty() || refs.isEmpty()) return null
    val summary = tags.entries.joinToString(", ") { "${it.key}=${it.value}" }
    val draft = app.drafts.createDraft(comment ?: "Remote control: $summary", "From JOSM remote control (addtags=$addtags)", Authors.REMOTE)
    val results = app.drafts.proposeChanges(draft.id, refs.map { ProposedChange(it, TagEdit(set = tags), comment ?: "remote control addtags") })
    results.filter { it.outcome == ProposalOutcome.ERROR }.forEach { log.warn("addtags on {}: {}", it.ref, it.message) }
    return draft.id
}

/** OSM XML (from /import or /load_data) → a draft of modifications (existing ids) and creations (negative ids). */
private suspend fun draftFromOsmXml(app: App, xml: String, title: String): String {
    val els = OsmXml.parseElements(xml)
    val draft = app.drafts.createDraft(title, "From JOSM remote control: ${els.size} objects", Authors.REMOTE)
    val proposals = els.mapNotNull { e ->
        when {
            e.id > 0 -> ProposedChange(e.ref, TagEdit(replaceAll = e.tags), title)
            e.type == OsmType.NODE && e.lat != null && e.lon != null ->
                ProposedChange(e.ref, TagEdit(set = e.tags), title, action = ChangeAction.CREATE, location = LngLat(e.lon.toDouble(), e.lat.toDouble()))
            else -> null // new ways/relations need geometry editing: hand off to iD
        }
    }
    if (proposals.isNotEmpty()) app.drafts.proposeChanges(draft.id, proposals)
    return draft.id
}

fun parseAddTags(s: String?): Map<String, String> = s.orEmpty().split('|').mapNotNull { kv ->
    val i = kv.indexOf('=')
    if (i <= 0) null else kv.substring(0, i).trim() to kv.substring(i + 1).trim()
}.filter { it.first.isNotEmpty() && it.second.isNotEmpty() }.toMap()

private fun String.toRefOrNull(): OsmRef? = trim().takeIf { it.isNotEmpty() }?.let {
    runCatching { OsmRef.parse(it) }.getOrNull()
}

private fun Parameters.bbox(): BBox? {
    val l = this["left"]?.toDoubleOrNull() ?: return null
    val r = this["right"]?.toDoubleOrNull() ?: return null
    val t = this["top"]?.toDoubleOrNull() ?: return null
    val b = this["bottom"]?.toDoubleOrNull() ?: return null
    return BBox(minOf(l, r), minOf(b, t), maxOf(l, r), maxOf(b, t))
}

private fun bboxOfGeoJson(geoms: List<kotlinx.serialization.json.JsonElement>): BBox? {
    val pts = ArrayList<Pair<Double, Double>>()
    fun walk(e: kotlinx.serialization.json.JsonElement) {
        if (e is kotlinx.serialization.json.JsonArray) {
            if (e.size >= 2 && e[0] is kotlinx.serialization.json.JsonPrimitive) {
                pts += (e[0] as kotlinx.serialization.json.JsonPrimitive).content.toDouble() to (e[1] as kotlinx.serialization.json.JsonPrimitive).content.toDouble()
            } else {
                e.forEach(::walk)
            }
        } else if (e is kotlinx.serialization.json.JsonObject) {
            e["coordinates"]?.let(::walk)
            e["geometries"]?.let(::walk)
        }
    }
    geoms.forEach(::walk)
    if (pts.isEmpty()) return null
    val pad = 0.0005
    return BBox(pts.minOf { it.first } - pad, pts.minOf { it.second } - pad, pts.maxOf { it.first } + pad, pts.maxOf { it.second } + pad)
}

private suspend fun ApplicationCall.ok() = respondText("OK\r\n", ContentType.Text.Plain)

private suspend fun ApplicationCall.josmError(msg: String) = respondText(msg, ContentType.Text.Plain, HttpStatusCode.BadRequest)

/**
 * CORS for public HTTPS sites calling localhost: any origin may call. Preflights (answered by the
 * `options` route) also carry `Access-Control-Allow-Private-Network: true` for Chrome.
 */
private val JosmCors = createRouteScopedPlugin("JosmCors") {
    onCall { call -> call.response.header("Access-Control-Allow-Origin", "*") }
}
