package eu.tiducto.osmreview.server.mcp

import eu.tiducto.osmreview.rules.Geo
import eu.tiducto.osmreview.rules.ParentInfo
import eu.tiducto.osmreview.rules.PolygonScopes
import eu.tiducto.osmreview.rules.containsLonLat
import eu.tiducto.osmreview.server.App
import eu.tiducto.osmreview.server.api.runRule
import eu.tiducto.osmreview.server.core.EditElement
import eu.tiducto.osmreview.server.core.NodeInsert
import eu.tiducto.osmreview.server.core.NotFoundException
import eu.tiducto.osmreview.server.core.ResolvedScope
import eu.tiducto.osmreview.server.db.DraftMeta
import eu.tiducto.osmreview.server.osm.Overpass
import eu.tiducto.osmreview.shared.Authors
import eu.tiducto.osmreview.shared.BBox
import eu.tiducto.osmreview.shared.ChangeAction
import eu.tiducto.osmreview.shared.LngLat
import eu.tiducto.osmreview.shared.RelationMember
import eu.tiducto.osmreview.shared.DraftStatus
import eu.tiducto.osmreview.shared.OsmRef
import eu.tiducto.osmreview.shared.OsmType
import eu.tiducto.osmreview.shared.ProposedChange
import eu.tiducto.osmreview.shared.TagEdit
import eu.tiducto.osmreview.shared.WireJson
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.application.ApplicationCall
import io.ktor.util.collections.ConcurrentMap
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest

private const val SESSION_HEADER = "mcp-session-id"

fun Route.mcpRoutes(app: App) {
    val transports = ConcurrentMap<String, StreamableHttpServerTransport>()

    route("/mcp") {
        install(NormalizeMcpInitialize)
        post {
            if (!call.authorized(app)) return@post
            val sessionId = call.request.header(SESSION_HEADER)
            val transport = if (sessionId != null) {
                transports[sessionId] ?: return@post call.respond(HttpStatusCode.NotFound, "Session not found")
            } else {
                StreamableHttpServerTransport(StreamableHttpServerTransport.Configuration(enableJsonResponse = true)).also { t ->
                    t.setOnSessionInitialized { transports[it] = t }
                    t.setOnSessionClosed { transports.remove(it) }
                    val server = buildServer(app)
                    server.onClose { t.sessionId?.let(transports::remove) }
                    server.createSession(t)
                }
            }
            transport.handleRequest(null, call)
        }
        get {
            if (!call.authorized(app)) return@get
            call.respond(HttpStatusCode.MethodNotAllowed, "Use POST")
        }
        delete {
            if (!call.authorized(app)) return@delete
            val t = call.request.header(SESSION_HEADER)?.let(transports::get)
                ?: return@delete call.respond(HttpStatusCode.NotFound, "Session not found")
            t.handleRequest(null, call)
        }
    }
}

private suspend fun ApplicationCall.authorized(app: App): Boolean {
    val given = request.header("Authorization")?.removePrefix("Bearer ")?.trim().orEmpty()
    val ok = MessageDigest.isEqual(given.toByteArray(), app.mcpToken.toByteArray())
    if (!ok) respond(HttpStatusCode.Unauthorized, "Missing or wrong bearer token (see Settings → MCP)")
    return ok
}

private val INSTRUCTIONS = """
    OSM Review Editor: propose OpenStreetMap tag fixes as drafts for a human to review.
    You can read the local OSM mirror (query_osm, get_object), run QA rules (list_rules, run_rule), and
    create and edit drafts (create_draft, propose_changes, propose_geometry, update_change, list_drafts, get_draft).
    You cannot upload: the human reviews every change in the UI and merges. Give every change a short,
    specific reason. Read get_draft to see the human's decisions and rejection comments, then revise
    with update_change. Areas are admin boundary names (e.g. "Opava", "Kateřinky", "Brno-střed"), a bbox
    [west, south, east, north], or a GeoJSON polygon.
""".trimIndent()

private fun buildServer(app: App): Server {
    val server = Server(
        Implementation(name = "osm-review-editor", version = app.config.version),
        ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
                resources = ServerCapabilities.Resources(subscribe = false, listChanged = false),
            ),
        ),
        INSTRUCTIONS,
    )
    val overpass = Overpass(app.http, app.config.overpassUrl, app.config.userAgent)

    server.tool("list_rules", "QA rules: id, title, severity, confidence, whether they propose fixes.", readOnly = true) {
        ok(app.rules.rules(), ListSerializer(eu.tiducto.osmreview.shared.Rule.serializer()))
    }

    server.tool(
        "run_rule",
        "Run a QA rule in an area. Returns issues with object ref, tags, centroid and the proposed fix.",
        schema(listOf("rule_id", "area")) {
            str("rule_id", "Rule id from list_rules")
            area()
            int("limit", "Max issues to return (default 200)")
        },
        readOnly = true,
    ) { args ->
        val resolved = resolveArea(app, args["area"])
        val r = app.runRule(args.str("rule_id") ?: return@tool err("rule_id is required"), resolved, args.int("limit") ?: 200)
        ok(r, eu.tiducto.osmreview.shared.RuleRunResult.serializer())
    }

    server.tool(
        "query_osm",
        "Read-only query: a GeoDesk GOQL selector (e.g. 'w[highway=footway][!surface]') over the local mirror, " +
            "or an Overpass QL query against Overpass. Returns at most 500 objects with tags and a geometry summary.",
        schema(listOf("area")) {
            str("goql", "GOQL query (preferred: fast, local)")
            str("overpass", "Overpass QL (use only when GOQL cannot express it)")
            area()
        },
        readOnly = true,
    ) { args ->
        val limit = 500
        val goql = args.str("goql")
        val ql = args.str("overpass")
        if (goql == null && ql == null) return@tool err("Give goql or overpass")
        val resolved = resolveArea(app, args["area"])
        when {
            goql != null -> {
                val (found, truncated) = app.queries.goql(goql, resolved.scope, limit)
                okJson(
                    buildJsonObject {
                        put("scope", resolved.key)
                        put("truncated", truncated)
                        putJsonArray("objects") {
                            found.forEach { f ->
                                add(
                                    buildJsonObject {
                                        put("ref", f.ref.toString())
                                        put("tags", tagsJson(f.tags))
                                        put("geometry", summary(f.geometry))
                                    },
                                )
                            }
                        }
                    },
                )
            }
            else -> {
                val (raw, truncated) = overpass.query(ql!!, limit, Geo.bbox(resolved.scope.bounds))
                val hits = if (resolved.scope.polygon == null) {
                    raw
                } else {
                    raw.filter { h -> h.center?.let { resolved.scope.containsLonLat(it.lng, it.lat) } ?: true }
                }
                okJson(
                    buildJsonObject {
                        put("scope", resolved.key)
                        put("truncated", truncated)
                        put("objects", WireJson.encodeToJsonElement(ListSerializer(Overpass.Hit.serializer()), hits))
                    },
                )
            }
        }
    }

    server.tool(
        "get_object",
        "One object with its neighbours: parents (ways and relations it belongs to, with member role) and, for nodes and ways, " +
            "connected_ways (ways sharing a node with it). From the mirror (tags, geometry summary) or, with live=true, from the OSM API " +
            "(with version; parents are live, connected ways of a way come from the mirror).",
        schema(listOf("type", "id")) {
            enum("type", "node, way or relation", listOf("node", "way", "relation"))
            long("id", "OSM id")
            bool("live", "Fetch the current version from the OSM API")
        },
        readOnly = true,
    ) { args ->
        val ref = OsmRef(OsmType.fromApiName(args.str("type")!!), args.long("id")!!)
        if (args.bool("live") == true) {
            val api = app.account.api()
            val el = api.fetchOne(ref) ?: return@tool err("$ref does not exist on the ${app.account.target.name.lowercase()} API")
            val parents = api.parents(ref).take(MAX_NEIGHBOURS).map { p ->
                ParentInfo(p.ref, p.tags, p.members.firstOrNull { it.type == ref.type.apiName && it.ref == ref.id }?.role?.takeIf { it.isNotEmpty() })
            }
            val connected = when (ref.type) {
                OsmType.NODE -> parents.filter { it.ref.type == OsmType.WAY }.map { it.copy(role = null) }
                OsmType.WAY -> runCatching { app.queries.connectedWays(ref) }.getOrDefault(emptyList())
                OsmType.RELATION -> emptyList()
            }
            okJson(
                buildJsonObject {
                    put("ref", ref.toString())
                    put("version", el.version)
                    put("visible", el.visible)
                    put("tags", tagsJson(el.tags))
                    if (el.lat != null) put("lat", el.lat)
                    if (el.lon != null) put("lon", el.lon)
                    if (el.nodeIds.isNotEmpty()) put("node_count", el.nodeIds.size)
                    if (el.members.isNotEmpty()) put("member_count", el.members.size)
                    put("parents", neighboursJson(parents))
                    if (ref.type != OsmType.RELATION) put("connected_ways", neighboursJson(connected))
                },
            )
        } else {
            val info = app.queries.objectInfo(ref) ?: return@tool err("$ref is not in the mirror")
            okJson(
                buildJsonObject {
                    put("ref", ref.toString())
                    put("tags", tagsJson(info.feature.tags))
                    put("geometry", summary(info.feature.geometry))
                    put("parents", neighboursJson(info.parents))
                    if (ref.type != OsmType.RELATION) put("connected_ways", neighboursJson(info.connectedWays))
                },
            )
        }
    }

    server.tool(
        "create_draft",
        "Create a draft (a merge request) authored by Claude. Returns draft_id.",
        schema(listOf("title")) {
            str("title", "Short title, e.g. 'Opava-Kateřinky: crossing tags'")
            str("description", "What the draft does and why")
            area()
        },
    ) { args ->
        val resolved = args["area"]?.takeUnless { it is JsonNull }?.let { resolveArea(app, it) }
        val d = app.drafts.createDraft(
            args.str("title")!!,
            args.str("description").orEmpty(),
            Authors.CLAUDE,
            resolved?.geometry,
            DraftMeta(area = resolved?.areaName),
        )
        okJson(buildJsonObject { put("draft_id", d.id) })
    }

    server.tool(
        "propose_changes",
        "Propose tag changes in a draft (max 2000 per call). Each change: {type, id, add_tags?, remove_tags?, set_tags?, reason}. " +
            "add_tags adds or overwrites, remove_tags drops keys, set_tags replaces the whole tag set. The live object is " +
            "fetched for its current version. Returns per change: accepted / no_op / duplicate / error.",
        schema(listOf("draft_id", "changes")) {
            str("draft_id", "Draft id from create_draft")
            putJsonObject("changes") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonArray("required") { add(JsonPrimitive("type")); add(JsonPrimitive("id")); add(JsonPrimitive("reason")) }
                    putJsonObject("properties") {
                        enum("type", "node, way or relation", listOf("node", "way", "relation"))
                        long("id", "OSM id")
                        putJsonObject("add_tags") { put("type", "object"); put("description", "Tags to add or overwrite") }
                        putJsonObject("remove_tags") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
                        putJsonObject("set_tags") { put("type", "object"); put("description", "Complete new tag set") }
                        str("reason", "Why this change is right, shown to the reviewer")
                    }
                }
            }
        },
    ) { args ->
        val draftId = args.str("draft_id")!!
        val proposals = args["changes"]!!.jsonArray.map { it.jsonObject.toProposal() }
        val results = app.drafts.proposeChanges(draftId, proposals)
        ok(results, ListSerializer(eu.tiducto.osmreview.shared.ProposalResult.serializer()))
    }

    server.tool(
        "propose_geometry",
        "Propose geometry edits in a draft as osmChange-style elements (max 500 per call), reviewed per object like tag changes. " +
            "Each element: {action: create|modify|delete, type: node|way|relation, id, reason, ...}. New elements use negative ids " +
            "(-1, -2, …) that other elements of the same call may reference. create node: lat, lon, tags. create way: nodes (ids, at " +
            "least 2) and tags. modify node: lat/lon to move it, add_tags/remove_tags/set_tags. modify way: nodes (the complete new " +
            "list) or insert_nodes [{node, after}] to put a node right after an existing one. modify relation: members (complete new " +
            "list of {type, ref, role}). delete node or way: if_unused (default true) skips the delete when something still uses it. " +
            "A way split is a modify of the old way (shortened node list) plus a create of the new way sharing the split node; " +
            "update relations that contain the way with a modify carrying the new member list. Creating or deleting relations is " +
            "not supported here. Live versions are fetched; omitted geometry keeps the live geometry.",
        schema(listOf("draft_id", "elements")) {
            str("draft_id", "Draft id from create_draft")
            putJsonObject("elements") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonArray("required") { add(JsonPrimitive("action")); add(JsonPrimitive("type")); add(JsonPrimitive("id")); add(JsonPrimitive("reason")) }
                    putJsonObject("properties") {
                        enum("action", "create, modify or delete", listOf("create", "modify", "delete"))
                        enum("type", "node, way or relation", listOf("node", "way", "relation"))
                        long("id", "OSM id, or a negative placeholder for a new element")
                        putJsonObject("lat") { put("type", "number") }
                        putJsonObject("lon") { put("type", "number") }
                        putJsonObject("nodes") { put("type", "array"); putJsonObject("items") { put("type", "integer") } }
                        putJsonObject("insert_nodes") {
                            put("type", "array")
                            putJsonObject("items") {
                                put("type", "object")
                                putJsonObject("properties") {
                                    long("node", "Node to insert (may be a negative placeholder)")
                                    long("after", "Existing node of the way it goes after")
                                }
                            }
                        }
                        putJsonObject("members") {
                            put("type", "array")
                            putJsonObject("items") {
                                put("type", "object")
                                putJsonObject("properties") {
                                    enum("type", "Member type", listOf("node", "way", "relation"))
                                    long("ref", "Member id")
                                    str("role", "Role, empty string for none")
                                }
                            }
                        }
                        putJsonObject("add_tags") { put("type", "object") }
                        putJsonObject("remove_tags") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
                        putJsonObject("set_tags") { put("type", "object"); put("description", "Complete tag set") }
                        bool("if_unused", "For deletes: skip when still used (default true)")
                        str("reason", "Why, shown to the reviewer")
                    }
                }
            }
        },
    ) { args ->
        val draftId = args.str("draft_id") ?: return@tool err("draft_id is required")
        val elements = (args["elements"] as? JsonArray ?: return@tool err("elements is required")).map { it.jsonObject.toEditElement() }
        val results = app.drafts.proposeGeometry(draftId, elements)
        ok(results, ListSerializer(eu.tiducto.osmreview.shared.ProposalResult.serializer()))
    }

    server.tool(
        "update_change",
        "Revise one of your proposed changes (add_tags / remove_tags / set_tags applied to the current proposal) or withdraw it. " +
            "A revised change goes back to pending for the human.",
        schema(listOf("change_id")) {
            str("change_id", "Change id from get_draft")
            putJsonObject("add_tags") { put("type", "object") }
            putJsonObject("remove_tags") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
            putJsonObject("set_tags") { put("type", "object") }
            bool("withdraw", "Withdraw the change instead")
            str("reason", "Why")
        },
    ) { args ->
        val withdraw = args.bool("withdraw") == true
        val edit = if (withdraw) null else args.toTagEdit()
        val c = app.drafts.updateProposal(args.str("change_id")!!, edit, withdraw, args.str("reason"))
        ok(c, eu.tiducto.osmreview.shared.Change.serializer())
    }

    server.tool(
        "list_drafts",
        "Drafts with change counts by status.",
        schema {
            enum("status", "Filter by status", listOf("open", "merged", "partially_merged", "closed"))
            str("author", "Filter by author: user, claude, remote or rule:<id>")
        },
        readOnly = true,
    ) { args ->
        val status = args.str("status")?.let { s -> DraftStatus.entries.first { it.name.equals(s, ignoreCase = true) } }
        ok(app.repo.listDrafts(status, args.str("author")), ListSerializer(eu.tiducto.osmreview.shared.Draft.serializer()))
    }

    server.tool(
        "get_draft",
        "A draft with every change: diff, status, the human's review decisions and rejection comments.",
        schema(listOf("draft_id")) { str("draft_id", "Draft id") },
        readOnly = true,
    ) { args -> ok(app.drafts.detail(args.str("draft_id")!!), eu.tiducto.osmreview.shared.DraftDetail.serializer()) }

    server.addResourceTemplate("draft://{id}", "Draft", "A draft with its changes and reviews", "application/json") { request, params ->
        val d = app.drafts.detail(params.getValue("id"))
        ReadResourceResult(listOf(TextResourceContents(WireJson.encodeToString(eu.tiducto.osmreview.shared.DraftDetail.serializer(), d), request.uri, "application/json")))
    }
    server.addResourceTemplate("rule://{id}", "Rule", "A QA rule's definition", "application/json") { request, params ->
        val r = app.rules.definition(params.getValue("id")).toModel()
        ReadResourceResult(listOf(TextResourceContents(WireJson.encodeToString(eu.tiducto.osmreview.shared.Rule.serializer(), r), request.uri, "application/json")))
    }
    server.addResourceTemplate("area://{name}", "Area", "Polygon of a named area (GeoJSON)", "application/json") { request, params ->
        val name = java.net.URLDecoder.decode(params.getValue("name").replace("+", "%2B"), Charsets.UTF_8)
        val a = app.area(name) ?: throw IllegalArgumentException("Unknown area '$name'")
        ReadResourceResult(listOf(TextResourceContents(WireJson.encodeToString(eu.tiducto.osmreview.shared.Area.serializer(), a), request.uri, "application/json")))
    }
    return server
}

private fun resolveArea(app: App, area: JsonElement?): ResolvedScope = when (area) {
    null, JsonNull -> throw IllegalArgumentException("area is required: $AREA_FORMS")
    is JsonObject -> app.drafts.resolveScope(null, null, area)
    is JsonArray -> app.drafts.resolveScope(null, bboxOf(area), null)
    is JsonPrimitive -> {
        val text = area.contentOrNull?.trim().orEmpty()
        when {
            text.isEmpty() -> throw IllegalArgumentException("area is empty: $AREA_FORMS")
            text.startsWith("{") -> app.drafts.resolveScope(null, null, PolygonScopes.parseJson(text))
            text.startsWith("[") -> resolveArea(app, PolygonScopes.parseJson(text))
            else -> bboxOf(text)?.let { app.drafts.resolveScope(null, it, null) } ?: try {
                app.drafts.resolveScope(text, null, null)
            } catch (e: NotFoundException) {
                throw IllegalArgumentException("Unknown area '$text'. $AREA_FORMS")
            }
        }
    }
}

private const val AREA_FORMS =
    "Give an area preset, admin boundary or place name (e.g. \"Opava\"), a bbox [west, south, east, north], or a GeoJSON Polygon/MultiPolygon in WGS84."

private fun bboxOf(a: JsonArray): BBox {
    val parts = a.map { (it as? JsonPrimitive)?.doubleOrNull }
    if (parts.size != 4 || parts.any { it == null }) throw IllegalArgumentException("A bbox area must be [west, south, east, north] numbers")
    return BBox(parts[0]!!, parts[1]!!, parts[2]!!, parts[3]!!)
}

private fun bboxOf(text: String): BBox? {
    val parts = text.split(",").map { it.trim().toDoubleOrNull() }
    if (parts.size != 4 || parts.any { it == null }) return null
    return BBox(parts[0]!!, parts[1]!!, parts[2]!!, parts[3]!!)
}

private fun neighboursJson(list: List<ParentInfo>): JsonElement = WireJson.encodeToJsonElement(ListSerializer(ParentInfo.serializer()), list)

private const val MAX_NEIGHBOURS = 50

private fun summary(g: JsonElement): JsonObject {
    val o = g as? JsonObject ?: return buildJsonObject { }
    val pts = ArrayList<Pair<Double, Double>>()
    fun walk(e: JsonElement) {
        if (e is JsonArray && e.size >= 2 && e[0] is JsonPrimitive) {
            pts += e[0].jsonPrimitive.double to e[1].jsonPrimitive.double
        } else if (e is JsonArray) {
            e.forEach(::walk)
        }
    }
    o["coordinates"]?.let(::walk)
    return buildJsonObject {
        put("type", o["type"]?.jsonPrimitive?.content)
        if (pts.isNotEmpty()) {
            putJsonArray("center") {
                add(JsonPrimitive((pts.minOf { it.first } + pts.maxOf { it.first }) / 2))
                add(JsonPrimitive((pts.minOf { it.second } + pts.maxOf { it.second }) / 2))
            }
            put("points", pts.size)
        }
    }
}

private fun JsonObject.toProposal(): ProposedChange = ProposedChange(
    ref = OsmRef(OsmType.fromApiName(str("type")!!), long("id")!!),
    edit = toTagEdit(),
    reason = str("reason"),
    confidence = eu.tiducto.osmreview.shared.Confidence.NEEDS_REVIEW,
)

private fun JsonObject.toEditElement(): EditElement {
    val action = ChangeAction.entries.firstOrNull { it.name.equals(str("action"), ignoreCase = true) }
        ?: throw IllegalArgumentException("action must be create, modify or delete")
    val type = OsmType.fromApiName(str("type") ?: throw IllegalArgumentException("type is required"))
    val ref = OsmRef(type, long("id") ?: throw IllegalArgumentException("id is required"))
    require(type != OsmType.RELATION || action == ChangeAction.MODIFY) { "$ref: relations can only be modified here" }
    val lat = (this["lat"] as? JsonPrimitive)?.doubleOrNull
    val lon = (this["lon"] as? JsonPrimitive)?.doubleOrNull
    require((lat == null) == (lon == null)) { "$ref: give both lat and lon" }
    return EditElement(
        action = action,
        ref = ref,
        edit = toTagEdit().takeUnless { it.isEmpty },
        location = if (lat != null && lon != null) LngLat(lon, lat) else null,
        nodes = (this["nodes"] as? JsonArray)?.map { it.jsonPrimitive.longOrNull ?: throw IllegalArgumentException("$ref: nodes must be ids") },
        members = (this["members"] as? JsonArray)?.map { m ->
            val o = m.jsonObject
            RelationMember(
                OsmType.fromApiName(o.str("type") ?: throw IllegalArgumentException("$ref: member type is required")),
                o.long("ref") ?: throw IllegalArgumentException("$ref: member ref is required"),
                o.str("role").orEmpty(),
            )
        },
        insertNodes = (this["insert_nodes"] as? JsonArray)?.map { i ->
            val o = i.jsonObject
            NodeInsert(o.long("node") ?: throw IllegalArgumentException("$ref: insert_nodes needs node"), o.long("after") ?: throw IllegalArgumentException("$ref: insert_nodes needs after"))
        }.orEmpty(),
        ifUnused = action == ChangeAction.DELETE && bool("if_unused") != false,
        reason = str("reason"),
    )
}

private fun JsonObject.toTagEdit() = TagEdit(
    set = (this["add_tags"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
    remove = (this["remove_tags"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty(),
    replaceAll = (this["set_tags"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content },
)

private fun Server.tool(
    name: String,
    description: String,
    inputSchema: ToolSchema = schema { },
    readOnly: Boolean = false,
    handler: suspend (JsonObject) -> CallToolResult,
) = addTool(
    name = name,
    description = description,
    inputSchema = inputSchema,
    toolAnnotations = ToolAnnotations(readOnlyHint = readOnly, destructiveHint = false, openWorldHint = false),
) { request: CallToolRequest ->
    try {
        handler(request.arguments ?: JsonObject(emptyMap()))
    } catch (e: Exception) {
        err(e.message ?: e.toString())
    }
}

private fun schema(required: List<String> = emptyList(), props: JsonObjectBuilder.() -> Unit) =
    ToolSchema(properties = buildJsonObject(props), required = required)

private fun JsonObjectBuilder.str(name: String, d: String) = putJsonObject(name) { put("type", "string"); put("description", d) }
private fun JsonObjectBuilder.int(name: String, d: String) = putJsonObject(name) { put("type", "integer"); put("description", d) }
private fun JsonObjectBuilder.long(name: String, d: String) = putJsonObject(name) { put("type", "integer"); put("description", d) }
private fun JsonObjectBuilder.bool(name: String, d: String) = putJsonObject(name) { put("type", "boolean"); put("description", d) }
private fun JsonObjectBuilder.enum(name: String, d: String, values: List<String>) = putJsonObject(name) {
    put("type", "string")
    put("description", d)
    putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }
}
private fun JsonObjectBuilder.area() = putJsonObject("area") {
    put(
        "description",
        "Area name (admin boundary, e.g. \"Opava\"), bbox [west,south,east,north], or a GeoJSON Polygon/MultiPolygon " +
            "(geometry, Feature or one-feature FeatureCollection) in WGS84, at most 10 degrees across and 20000 vertices",
    )
}

private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull

private fun <T> ok(value: T, serializer: KSerializer<T>) = okJson(WireJson.encodeToJsonElement(serializer, value))
private fun okJson(json: JsonElement) = CallToolResult(content = listOf(TextContent(json.toString())))
private fun err(text: String) = CallToolResult(content = listOf(TextContent(text)), isError = true)
private fun tagsJson(tags: Map<String, String>): JsonElement = JsonObject(tags.mapValues { JsonPrimitive(it.value) })
