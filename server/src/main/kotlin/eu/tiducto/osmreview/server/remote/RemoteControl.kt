package eu.tiducto.osmreview.server.remote

import eu.tiducto.osmreview.server.App
import eu.tiducto.osmreview.server.db.DraftMeta
import eu.tiducto.osmreview.server.osm.OsmElement
import eu.tiducto.osmreview.server.osm.OsmXml
import eu.tiducto.osmreview.shared.Authors
import eu.tiducto.osmreview.shared.BBox
import eu.tiducto.osmreview.shared.ChangeAction
import eu.tiducto.osmreview.shared.DraftStatus
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
import io.ktor.http.isSuccess
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.math.abs

private val log = LoggerFactory.getLogger("eu.tiducto.osmreview.server.remote")

private const val MAX_LIVE_BBOX_FETCHES = 20

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
        val select = parseRefs(p["select"])
        val draftIds = draftsFromAddTags(app, select, p)
        app.events.emit(UiEvent.RemoteCommand("load_and_zoom", bbox = bbox, select = select, draftId = draftIds.firstOrNull(), draftIds = draftIds))
        call.ok()
    }

    get("/load_object") {
        val p = call.request.queryParameters
        val refs = parseRefs(p["objects"])
        if (refs.isEmpty()) return@get call.josmError("Missing objects")
        val members = if (p["relation_members"].toBoolean()) relationMembers(app, refs) else emptyList()
        val bbox = bboxFor(app, (refs + members).distinct())
        val draftIds = draftsFromAddTags(app, refs, p)
        app.events.emit(UiEvent.RemoteCommand("load_object", bbox = bbox, select = refs, draftId = draftIds.firstOrNull(), draftIds = draftIds))
        call.ok()
    }

    get("/import") {
        val p = call.request.queryParameters
        val url = p["url"] ?: return@get call.josmError("Missing url")
        val resp = app.http.get(url) { header(HttpHeaders.UserAgent, app.config.userAgent) }
        check(resp.status.isSuccess()) { "Could not fetch $url: ${resp.status}" }
        val result = draftFromOsmXml(app, resp.bodyAsText(), "Imported from $url", p.meta())
        app.events.emit(UiEvent.RemoteCommand("import", draftId = result.draftId, draftIds = listOf(result.draftId), message = result.skipped))
        call.ok(result.skipped)
    }

    suspend fun loadData(call: ApplicationCall, params: Parameters) {
        val data = params["data"] ?: return call.josmError("Missing data")
        val result = draftFromOsmXml(app, data, params["layer_name"] ?: "Loaded data", params.meta())
        app.events.emit(UiEvent.RemoteCommand("load_data", draftId = result.draftId, draftIds = listOf(result.draftId), message = result.skipped))
        call.ok(result.skipped)
    }
    get("/load_data") { loadData(call, call.request.queryParameters) }
    post("/load_data") { loadData(call, call.receiveParameters()) }

    get("/add_node") {
        val p = call.request.queryParameters
        val lat = p["lat"]?.toDoubleOrNull() ?: return@get call.josmError("Missing lat")
        val lon = p["lon"]?.toDoubleOrNull() ?: return@get call.josmError("Missing lon")
        val tags = parseAddTags(p["addtags"])
        val draft = app.drafts.createDraft("Add node at $lat,$lon", "From JOSM remote control", Authors.REMOTE, meta = p.meta())
        app.drafts.proposeChanges(
            draft.id,
            listOf(ProposedChange(OsmRef(OsmType.NODE, -1), TagEdit(set = tags), "remote control add_node", action = ChangeAction.CREATE, location = LngLat(lon, lat))),
        )
        val pad = 0.001
        app.events.emit(UiEvent.RemoteCommand("add_node", bbox = BBox(lon - pad, lat - pad, lon + pad, lat + pad), draftId = draft.id, draftIds = listOf(draft.id)))
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

private fun Parameters.meta() = DraftMeta(changesetComment = this["changeset_comment"], changesetSource = this["changeset_source"])

private suspend fun draftsFromAddTags(app: App, refs: List<OsmRef>, p: Parameters): List<String> {
    val tags = parseAddTags(p["addtags"])
    if (tags.isEmpty() || refs.isEmpty()) return emptyList()
    val summary = tags.entries.joinToString(", ") { "${it.key}=${it.value}" }
    val comment = p["changeset_comment"]?.trim()?.ifBlank { null }
    val ids = LinkedHashSet<String>()
    for (ref in refs) {
        val draft = app.drafts.createDraft(
            "Remote control: $summary on $ref",
            "From JOSM remote control (addtags=${p["addtags"]})",
            Authors.REMOTE,
            meta = p.meta(),
        )
        val r = app.drafts.proposeChanges(draft.id, listOf(ProposedChange(ref, TagEdit(set = tags), comment ?: "remote control addtags"))).single()
        if (r.outcome == ProposalOutcome.ACCEPTED) {
            ids += draft.id
            continue
        }
        app.repo.updateDraft(UUID.fromString(draft.id), status = DraftStatus.CLOSED)
        val existing = r.changeId?.let { app.repo.change(UUID.fromString(it)) }
        if (existing != null) ids += existing.draftId else log.warn("addtags on {}: {} {}", ref, r.outcome, r.message)
    }
    return ids.toList()
}

private data class ImportResult(val draftId: String, val skipped: String?)

private suspend fun draftFromOsmXml(app: App, xml: String, title: String, meta: DraftMeta): ImportResult {
    val els = runCatching { OsmXml.parseElements(xml) }.getOrElse { throw IllegalArgumentException("Could not parse OSM XML: ${it.message}") }
    val marked = els.any { it.action != null }
    val deleted = els.filter { it.action == "delete" || !it.visible }
    val kept = els.filterNot { it.action == "delete" || !it.visible }
    val existing = kept.filter { it.id > 0 && (!marked || it.action == "modify") }
    val newNodes = kept.filter { it.id <= 0 && it.type == OsmType.NODE }
    val placeable = newNodes.filter { it.tags.isNotEmpty() && it.lat != null && it.lon != null }
    val live = if (existing.isEmpty()) emptyMap() else app.account.api().fetch(existing.map { it.ref })
    val geometry = existing.count { e -> live[e.ref]?.let { geometryDiffers(e, it) } == true }

    val skipped = listOf(
        geometry to "geometry change",
        deleted.size to "deletion",
        kept.count { it.id <= 0 && it.type == OsmType.WAY } to "new way",
        kept.count { it.id <= 0 && it.type == OsmType.RELATION } to "new relation",
        (newNodes.size - placeable.size) to "untagged new node",
    ).filter { it.first > 0 }.joinToString(", ") { (n, what) -> "$n $what${if (n == 1) "" else "s"}" }
        .ifEmpty { null }
        ?.let { "Skipped (geometry editing is not supported yet): $it" }

    val draft = app.drafts.createDraft(title, "From JOSM remote control: ${els.size} objects", Authors.REMOTE, meta = meta)
    val proposals = existing.map { ProposedChange(it.ref, TagEdit(replaceAll = it.tags), title) } +
        placeable.map { ProposedChange(it.ref, TagEdit(set = it.tags), title, action = ChangeAction.CREATE, location = LngLat(it.lon!!.toDouble(), it.lat!!.toDouble())) }
    val results = if (proposals.isEmpty()) emptyList() else app.drafts.proposeChanges(draft.id, proposals)
    val accepted = results.count { it.outcome == ProposalOutcome.ACCEPTED }
    val description = buildString {
        append("From JOSM remote control: ${els.size} objects; $accepted change(s) proposed")
        val other = results.size - accepted
        if (other > 0) append(", $other not proposed (no-op, already proposed elsewhere or missing)")
        append('.')
        skipped?.let { append(' ').append(it).append('.') }
    }
    app.repo.updateDraft(UUID.fromString(draft.id), description = description)
    return ImportResult(draft.id, skipped)
}

private fun geometryDiffers(sent: OsmElement, live: OsmElement): Boolean = when (sent.type) {
    OsmType.NODE -> {
        val a = sent.lat?.toDoubleOrNull() to sent.lon?.toDoubleOrNull()
        val b = live.lat?.toDoubleOrNull() to live.lon?.toDoubleOrNull()
        val (lat1, lon1) = a
        val (lat2, lon2) = b
        lat1 != null && lon1 != null && lat2 != null && lon2 != null && (abs(lat1 - lat2) > 1e-7 || abs(lon1 - lon2) > 1e-7)
    }
    OsmType.WAY -> sent.nodeIds != live.nodeIds
    OsmType.RELATION -> sent.members != live.members
}

private suspend fun relationMembers(app: App, refs: List<OsmRef>): List<OsmRef> {
    val rels = refs.filter { it.type == OsmType.RELATION }
    if (rels.isEmpty()) return emptyList()
    val live = runCatching { app.account.api().fetch(rels) }.getOrElse {
        log.warn("Could not fetch relation members: {}", it.message)
        emptyMap()
    }
    return live.values.flatMap { r -> r.members.mapNotNull { m -> runCatching { OsmRef(OsmType.fromApiName(m.type), m.ref) }.getOrNull() } }
}

private suspend fun bboxFor(app: App, refs: List<OsmRef>): BBox? {
    val pts = ArrayList<Pair<Double, Double>>()
    val mirrored = runCatching { app.queries.mapFeatures(refs) }.getOrElse { emptyMap() }
    mirrored.values.forEach { collectPoints(it.geometry, pts) }
    val missing = refs.filter { it !in mirrored && it.id > 0 }
    if (missing.isNotEmpty()) {
        val api = app.account.api()
        for (ref in missing.take(MAX_LIVE_BBOX_FETCHES)) {
            runCatching { api.fetchFull(ref) }
                .onFailure { log.warn("Could not fetch {} live for its bbox: {}", ref, it.message) }
                .getOrDefault(emptyList())
                .forEach { e ->
                    val lat = e.lat?.toDoubleOrNull()
                    val lon = e.lon?.toDoubleOrNull()
                    if (lat != null && lon != null) pts += lon to lat
                }
        }
    }
    if (pts.isEmpty()) return null
    val pad = 0.0005
    return BBox(pts.minOf { it.first } - pad, pts.minOf { it.second } - pad, pts.maxOf { it.first } + pad, pts.maxOf { it.second } + pad)
}

private fun collectPoints(e: JsonElement, pts: MutableList<Pair<Double, Double>>) {
    when (e) {
        is JsonArray -> if (e.size >= 2 && e[0] is JsonPrimitive) {
            pts += (e[0] as JsonPrimitive).content.toDouble() to (e[1] as JsonPrimitive).content.toDouble()
        } else {
            e.forEach { collectPoints(it, pts) }
        }
        is JsonObject -> {
            e["coordinates"]?.let { collectPoints(it, pts) }
            e["geometries"]?.let { collectPoints(it, pts) }
        }
        else -> Unit
    }
}

fun parseAddTags(s: String?): Map<String, String> = s.orEmpty().split('|').mapNotNull { kv ->
    val i = kv.indexOf('=')
    if (i <= 0) null else kv.substring(0, i).trim() to kv.substring(i + 1).trim()
}.filter { it.first.isNotEmpty() && it.second.isNotEmpty() }.toMap()

private val LONG_REF = Regex("""^(node|way|relation)(-?\d+)$""", RegexOption.IGNORE_CASE)

fun parseRefs(s: String?): List<OsmRef> = s.orEmpty().split(",").mapNotNull { it.toRefOrNull() }.distinct()

private fun String.toRefOrNull(): OsmRef? {
    val t = trim()
    if (t.isEmpty()) return null
    LONG_REF.matchEntire(t)?.let { m -> return OsmRef(OsmType.fromApiName(m.groupValues[1]), m.groupValues[2].toLong()) }
    return runCatching { OsmRef.parse(t) }.getOrNull()
}

private fun Parameters.bbox(): BBox? {
    val l = this["left"]?.toDoubleOrNull() ?: return null
    val r = this["right"]?.toDoubleOrNull() ?: return null
    val t = this["top"]?.toDoubleOrNull() ?: return null
    val b = this["bottom"]?.toDoubleOrNull() ?: return null
    return BBox(minOf(l, r), minOf(b, t), maxOf(l, r), maxOf(b, t))
}

private suspend fun ApplicationCall.ok(detail: String? = null) =
    respondText("OK\r\n" + (detail?.let { "$it\r\n" } ?: ""), ContentType.Text.Plain)

private suspend fun ApplicationCall.josmError(msg: String) = respondText(msg, ContentType.Text.Plain, HttpStatusCode.BadRequest)

private val JosmCors = createRouteScopedPlugin("JosmCors") {
    onCall { call -> call.response.header("Access-Control-Allow-Origin", "*") }
}
