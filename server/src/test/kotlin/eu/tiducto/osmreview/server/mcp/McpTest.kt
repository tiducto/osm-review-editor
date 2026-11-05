package eu.tiducto.osmreview.server.mcp

import eu.tiducto.osmreview.rules.MirrorUnavailableException
import eu.tiducto.osmreview.rules.PolygonScopes
import eu.tiducto.osmreview.rules.containsLonLat
import eu.tiducto.osmreview.server.App
import eu.tiducto.osmreview.server.Config
import eu.tiducto.osmreview.server.db.RuleRunsTable
import eu.tiducto.osmreview.server.module
import eu.tiducto.osmreview.server.presets.PresetTarget
import eu.tiducto.osmreview.shared.presets.PresetGeometry
import eu.tiducto.osmreview.shared.BBox
import eu.tiducto.osmreview.shared.LngLat
import eu.tiducto.osmreview.shared.OsmRef
import eu.tiducto.osmreview.shared.OsmType
import eu.tiducto.osmreview.shared.UiEvent
import eu.tiducto.osmreview.shared.WireJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpTest {
    private val docker = runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
    private val gol = System.getenv("OSM_REVIEW_TEST_GOL")?.takeIf { it.isNotBlank() }?.let(Path::of)?.takeIf(Files::exists)
    private lateinit var pg: PostgreSQLContainer
    private lateinit var app: App
    private val osm = FakeOsm()
    private var nextId = 1

    private val bulkMirror = mapOf(
        OsmRef(OsmType.WAY, 55) to PresetTarget(mapOf("highway" to "footway"), PresetGeometry.LINE),
        OsmRef(OsmType.WAY, 70) to PresetTarget(mapOf("highway" to "footway", "footway" to "sidewalk"), PresetGeometry.LINE),
        OsmRef(OsmType.NODE, 101) to PresetTarget(mapOf("highway" to "crossing"), PresetGeometry.VERTEX),
    )

    private val opavaTriangle = """{"type":"Polygon","coordinates":[[[17.895,49.935],[17.915,49.935],[17.905,49.95],[17.895,49.935]]]}"""

    @BeforeTest
    fun setUp() {
        if (!docker) return
        pg = PostgreSQLContainer("postgres:18").also { it.start() }
        val env = buildMap {
            put("DB_URL", pg.jdbcUrl)
            put("DB_USER", pg.username)
            put("DB_PASSWORD", pg.password)
            put("PORT", "80")
            put("MCP_TOKEN", TOKEN)
            put("OVERPASS_URL", "https://overpass.test/api/interpreter")
            put("DATA_DIR", Files.createTempDirectory("gol").toString())
            put("RULES_DIR", Files.createTempDirectory("rules").toString())
            gol?.let { put("GOL_FILE", it.toString()) }
            val presets = Files.createTempFile("areas", ".geojson")
            Files.writeString(
                presets,
                """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"name":"$PRESET"},"geometry":$opavaTriangle}]}""",
            )
            put("DEFAULT_AREAS", presets.toString())
        }
        app = App(Config.fromEnv(env), HttpClient(MockEngine { req -> osm.handle(this, req) })) { refs ->
            if (OsmRef(OsmType.WAY, 404) in refs) throw MirrorUnavailableException("No GOL is open yet")
            bulkMirror.filterKeys { it in refs }
        }
        val example = listOf(Path.of("../user-rules.example"), Path.of("user-rules.example")).first { Files.isDirectory(it) }
        Files.copy(example.resolve(SURFACES), app.ruleFiles.root.resolve(SURFACES))
        app.ruleFiles.rescan()
        if (gol != null) app.mirror.reloadIfChanged()
    }

    @AfterTest
    fun tearDown() {
        if (!docker) return
        app.close()
        pg.stop()
    }

    private fun mcpTest(block: suspend ApplicationTestBuilder.() -> Unit) {
        if (!docker) return println("Docker not available, skipping")
        testApplication {
            application { module(app) }
            block()
        }
    }

    private suspend fun ApplicationTestBuilder.rpc(method: String, params: JsonObject, session: String?, notification: Boolean = false): HttpResponse =
        client.post("/mcp") {
            bearerAuth(TOKEN)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            contentType(ContentType.Application.Json)
            session?.let { header("mcp-session-id", it) }
            setBody(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    if (!notification) put("id", nextId++)
                    put("method", method)
                    put("params", params)
                }.toString(),
            )
        }

    private suspend fun ApplicationTestBuilder.initialize(): String {
        val resp = rpc(
            "initialize",
            buildJsonObject {
                put("protocolVersion", "2025-06-18")
                putJsonObject("capabilities") { }
                putJsonObject("clientInfo") {
                    put("name", "mcp-test")
                    put("version", "1")
                }
            },
            null,
        )
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val session = assertNotNull(resp.headers["mcp-session-id"])
        val result = body(resp)["result"]!!.jsonObject
        assertEquals("osm-review-editor", result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        rpc("notifications/initialized", JsonObject(emptyMap()), session, notification = true)
        return session
    }

    private suspend fun body(resp: HttpResponse): JsonObject {
        val text = resp.bodyAsText()
        val json = text.lineSequence().firstOrNull { it.startsWith("data:") }?.removePrefix("data:") ?: text
        return Json.parseToJsonElement(json).jsonObject
    }

    private data class ToolResult(val isError: Boolean, val text: String) {
        val json: JsonElement get() = Json.parseToJsonElement(text)
    }

    private suspend fun ApplicationTestBuilder.call(session: String, tool: String, args: JsonObject): ToolResult {
        val resp = rpc(
            "tools/call",
            buildJsonObject {
                put("name", tool)
                put("arguments", args)
            },
            session,
        )
        assertEquals(HttpStatusCode.OK, resp.status)
        val result = body(resp)["result"]!!.jsonObject
        val isError = (result["isError"] as? JsonPrimitive)?.booleanOrNull ?: false
        val text = result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
        return ToolResult(isError, text)
    }

    private fun args(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) ->
            when (v) {
                is String -> put(k, v)
                is Number -> put(k, v)
                is Boolean -> put(k, v)
                is JsonElement -> put(k, v)
                else -> error("unsupported $v")
            }
        }
    }

    private fun polygon(text: String) = Json.parseToJsonElement(text)

    @Test
    fun rejectsMissingOrWrongToken() = mcpTest {
        assertEquals(HttpStatusCode.Unauthorized, client.post("/mcp") { setBody("{}") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/mcp") { bearerAuth("wrong"); setBody("{}") }.status)
    }

    @Test
    fun draftToolsOverHttp() = mcpTest {
        val session = initialize()

        val tools = body(rpc("tools/list", JsonObject(emptyMap()), session))["result"]!!.jsonObject["tools"]!!.jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        assertEquals(
            setOf(
                "list_rules", "run_rule", "query_osm", "get_object", "create_draft", "propose_changes", "propose_geometry", "update_change", "list_drafts", "get_draft",
                "list_rule_files", "read_rule_file", "validate_rule_file", "write_rule_file", "delete_rule_file", "copy_rule_file",
                "search_presets", "get_preset", "match_preset", "import_preset", "detach_preset", "apply_preset_bulk",
                "run_scan", "get_scan", "diff_scans", "draft_from_scan", "list_highlights", "highlight_stats", "evaluate_highlight",
                "push_view",
            ),
            tools,
        )

        val created = call(session, "create_draft", args("title" to "Opava: surfaces", "description" to "test", "area" to polygon(opavaTriangle)))
        assertFalse(created.isError, created.text)
        val draftId = created.json.jsonObject["draft_id"]!!.jsonPrimitive.content

        val bad = call(session, "create_draft", args("title" to "x", "area" to polygon("""{"type":"Polygon","coordinates":[[[17.9,49.9],[18.0,49.9],[18.0,50.0]]]}""")))
        assertTrue(bad.isError)
        assertTrue("at least 4" in bad.text, bad.text)
        val badBox = call(session, "create_draft", args("title" to "x", "area" to buildJsonArray { add(JsonPrimitive(17.9)); add(JsonPrimitive(49.9)) }))
        assertTrue(badBox.isError)
        assertTrue("[west, south, east, north]" in badBox.text, badBox.text)
        val boxed = call(session, "create_draft", args("title" to "boxed", "area" to "17.88,49.93,17.92,49.95"))
        assertFalse(boxed.isError, boxed.text)

        val proposed = call(
            session, "propose_changes",
            args(
                "draft_id" to draftId,
                "changes" to buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "way")
                            put("id", 55)
                            putJsonObject("add_tags") { put("surface", "asphalt") }
                            put("reason", "paved on imagery")
                        },
                    )
                },
            ),
        )
        assertFalse(proposed.isError, proposed.text)
        assertEquals("accepted", proposed.json.jsonArray.single().jsonObject["outcome"]!!.jsonPrimitive.content)

        val listed = call(session, "list_drafts", args("author" to "claude"))
        assertEquals(2, listed.json.jsonArray.size)

        val detail = call(session, "get_draft", args("draft_id" to draftId)).json.jsonObject
        val draft = detail["draft"]!!.jsonObject
        assertEquals("Polygon", draft["scope"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val change = detail["changes"]!!.jsonArray.single().jsonObject
        assertEquals("paved on imagery", change["reason"]!!.jsonPrimitive.content)
        assertEquals("asphalt", change["newTags"]!!.jsonObject["surface"]!!.jsonPrimitive.content)
    }

    @Test
    fun proposeGeometryInsertsNodeIntoWay() = mcpTest {
        val session = initialize()
        val draftId = call(session, "create_draft", args("title" to "Bumps", "area" to "17.88,49.93,17.92,49.95"))
            .json.jsonObject["draft_id"]!!.jsonPrimitive.content
        val rejected = call(
            session, "propose_geometry",
            args(
                "draft_id" to draftId,
                "elements" to buildJsonArray {
                    add(buildJsonObject { put("action", "create"); put("type", "relation"); put("id", -1); put("reason", "x") })
                },
            ),
        )
        assertTrue(rejected.isError)
        assertTrue("relations can only be modified" in rejected.text, rejected.text)

        val proposed = call(
            session, "propose_geometry",
            args(
                "draft_id" to draftId,
                "elements" to buildJsonArray {
                    add(
                        buildJsonObject {
                            put("action", "create"); put("type", "node"); put("id", -1)
                            put("lat", 49.941); put("lon", 17.901)
                            putJsonObject("add_tags") { put("traffic_calming", "bump") }
                            put("reason", "bump visible on imagery")
                        },
                    )
                    add(
                        buildJsonObject {
                            put("action", "modify"); put("type", "way"); put("id", 55)
                            put("insert_nodes", buildJsonArray { add(buildJsonObject { put("node", -1); put("after", 101) }) })
                            put("reason", "bump sits on the footway")
                        },
                    )
                },
            ),
        )
        assertFalse(proposed.isError, proposed.text)
        val results = proposed.json.jsonArray.map { it.jsonObject }
        assertEquals(listOf("accepted", "accepted"), results.map { it["outcome"]!!.jsonPrimitive.content })
        val nodeId = results.first()["ref"]!!.jsonObject["id"]!!.jsonPrimitive.content.toLong()
        assertTrue(nodeId < 0)

        val changes = call(session, "get_draft", args("draft_id" to draftId)).json.jsonObject["changes"]!!.jsonArray.map { it.jsonObject }
        val way = changes.first { refKey(it["ref"]!!) == "way:55" }
        assertEquals(listOf(101L, nodeId, 2L), way["newShape"]!!.jsonObject["nodes"]!!.jsonArray.map { it.jsonPrimitive.content.toLong() })
        assertEquals("node:$nodeId", refKey(way["dependsOn"]!!.jsonArray.single().jsonObject["ref"]!!))
        assertEquals("bump", changes.first { refKey(it["ref"]!!) == "node:$nodeId" }["newTags"]!!.jsonObject["traffic_calming"]!!.jsonPrimitive.content)
    }

    @Test
    fun liveObjectWithNeighbours() = mcpTest {
        val session = initialize()
        val node = call(session, "get_object", args("type" to "node", "id" to 101, "live" to true))
        assertFalse(node.isError, node.text)
        val o = node.json.jsonObject
        assertEquals(4, o["version"]!!.jsonPrimitive.content.toInt())
        val parents = o["parents"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf("way:55", "relation:7"), parents.map { refKey(it["ref"]!!) }.toSet())
        assertEquals("stop", parents.first { refKey(it["ref"]!!) == "relation:7" }["role"]!!.jsonPrimitive.content)
        assertEquals(listOf("way:55"), o["connected_ways"]!!.jsonArray.map { refKey(it.jsonObject["ref"]!!) })
    }

    @Test
    fun overpassIsConfinedToTheArea() = mcpTest {
        val session = initialize()
        val r = call(session, "query_osm", args("overpass" to "node[highway=crossing];", "area" to polygon(opavaTriangle)))
        assertFalse(r.isError, r.text)
        val q = osm.overpassQueries.single()
        assertTrue("[bbox:49.935,17.895,49.950001,17.915]" in q, q)
        val refs = r.json.jsonObject["objects"]!!.jsonArray.map { refKey(it.jsonObject["ref"]!!) }
        assertEquals(listOf("node:1"), refs)

        val missing = call(session, "query_osm", args("overpass" to "node;"))
        assertTrue(missing.isError)
        assertTrue("area is required" in missing.text, missing.text)
    }

    @Test
    fun presetAreaFromDefaultAreasFile() = mcpTest {
        val resolved = app.drafts.resolveScope(PRESET.lowercase(), null, null)
        assertEquals(PRESET, resolved.areaName)
        assertEquals("area:$PRESET#${PolygonScopes.hash(PolygonScopes.parse(opavaTriangle).scope.polygon!!)}", resolved.key)
        assertTrue(resolved.scope.containsLonLat(17.905, 49.94))
        assertFalse(resolved.scope.containsLonLat(17.896, 49.949))
        assertEquals(null, app.drafts.resolveScope(null, null, polygon(opavaTriangle)).areaName)
        assertEquals(null, app.drafts.resolveScope(null, eu.tiducto.osmreview.shared.BBox(17.8, 49.9, 17.9, 50.0), null).areaName)

        val session = initialize()
        val r = call(session, "query_osm", args("overpass" to "node[highway=crossing];", "area" to PRESET))
        assertFalse(r.isError, r.text)
        assertTrue("[bbox:49.935,17.895,49.950001,17.915]" in osm.overpassQueries.single(), osm.overpassQueries.single())
        assertEquals(listOf("node:1"), r.json.jsonObject["objects"]!!.jsonArray.map { refKey(it.jsonObject["ref"]!!) })

        val created = call(session, "create_draft", args("title" to "preset", "area" to PRESET))
        assertFalse(created.isError, created.text)
        val draft = call(session, "get_draft", args("draft_id" to created.json.jsonObject["draft_id"]!!.jsonPrimitive.content))
            .json.jsonObject["draft"]!!.jsonObject
        assertEquals(PRESET, draft["area"]!!.jsonPrimitive.content)
        assertEquals("Polygon", draft["scope"]!!.jsonObject["type"]!!.jsonPrimitive.content)

        val resource = body(rpc("resources/read", buildJsonObject { put("uri", "area://$PRESET") }, session))
        val contents = resource["result"]!!.jsonObject["contents"]!!.jsonArray.single().jsonObject
        val area = Json.parseToJsonElement(contents["text"]!!.jsonPrimitive.content).jsonObject
        assertEquals(PRESET, area["name"]!!.jsonPrimitive.content)
        assertEquals("Polygon", area["geometry"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val encoded = body(rpc("resources/read", buildJsonObject { put("uri", "area://Kate%C5%99inky%20Triangle") }, session))
        assertTrue(PRESET in encoded["result"]!!.jsonObject["contents"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)

        if (gol == null) return@mcpTest println("OSM_REVIEW_TEST_GOL not set, skipping the rule run")
        val run = call(session, "run_rule", args("rule_id" to SURFACES, "area" to PRESET, "limit" to 2000))
        assertFalse(run.isError, run.text)
        val polygonRun = call(session, "run_rule", args("rule_id" to SURFACES, "area" to polygon(opavaTriangle), "limit" to 2000))
        assertEquals(
            polygonRun.json.jsonObject["issueCount"]!!.jsonPrimitive.content,
            run.json.jsonObject["issueCount"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun runRuleWithPolygonArea() = mcpTest {
        if (gol == null) return@mcpTest println("OSM_REVIEW_TEST_GOL not set, skipping")
        val session = initialize()
        val r = call(session, "run_rule", args("rule_id" to SURFACES, "area" to polygon(opavaTriangle), "limit" to 2000))
        assertFalse(r.isError, r.text)
        val o = r.json.jsonObject
        val issues = o["issues"]!!.jsonArray.map { it.jsonObject }
        assertTrue(issues.isNotEmpty())
        val box = call(session, "run_rule", args("rule_id" to SURFACES, "area" to "17.895,49.935,17.915,49.95", "limit" to 2000))
        assertTrue(box.json.jsonObject["issueCount"]!!.jsonPrimitive.content.toInt() > issues.size)

        val scope = PolygonScopes.parse(opavaTriangle).scope
        val inside = issues.count { i ->
            val c = i["centroid"]!!.jsonObject
            scope.containsLonLat(c["lng"]!!.jsonPrimitive.double, c["lat"]!!.jsonPrimitive.double)
        }
        assertTrue(inside >= issues.size * 0.8, "$inside of ${issues.size} inside")

        val named = call(session, "run_rule", args("rule_id" to SURFACES, "area" to "Opava"))
        assertFalse(named.isError, named.text)
        val unknown = call(session, "run_rule", args("rule_id" to SURFACES, "area" to "Nowhere-at-all-xyz"))
        assertTrue(unknown.isError)
        assertTrue("Unknown area" in unknown.text, unknown.text)

        val goql = call(session, "query_osm", args("goql" to "w[highway=footway]", "area" to polygon(opavaTriangle)))
        assertFalse(goql.isError, goql.text)
        assertTrue(goql.json.jsonObject["scope"]!!.jsonPrimitive.content.startsWith("polygon:"))
        val way = goql.json.jsonObject["objects"]!!.jsonArray.first().jsonObject["ref"]!!.toString().let { Regex("\\d+").findAll(it).last().value }
        val obj = call(session, "get_object", args("type" to "way", "id" to way.toLong()))
        assertFalse(obj.isError, obj.text)
        assertTrue(obj.json.jsonObject["connected_ways"]!!.jsonArray.isNotEmpty(), obj.text)

        var keys = emptyList<String>()
        for (attempt in 0 until 50) {
            keys = app.db.tx { RuleRunsTable.selectAll().filter { it[RuleRunsTable.scopeGeom] != null }.map { it[RuleRunsTable.scopeKey] } }
            if (keys.size >= 3) break
            delay(100)
        }
        assertTrue(keys.any { it.startsWith("polygon:") }, keys.toString())
        assertTrue(keys.any { it.startsWith("area:Opava#") }, keys.toString())
    }

    private fun pushed(r: ToolResult): UiEvent.PushView {
        assertFalse(r.isError, r.text)
        val o = r.json.jsonObject
        assertEquals("push_view", o["event"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        return WireJson.decodeFromJsonElement(UiEvent.serializer(), o["event"]!!) as UiEvent.PushView
    }

    private fun assertBBox(expected: BBox, actual: BBox?) {
        assertNotNull(actual)
        listOf(expected.west to actual.west, expected.south to actual.south, expected.east to actual.east, expected.north to actual.north)
            .forEach { (e, a) -> assertEquals(e, a, 1e-5, "$expected vs $actual") }
    }

    @Test
    fun pushViewFramesAreaByNameAndBbox() = mcpTest {
        val session = initialize()
        val named = pushed(call(session, "push_view", args("area" to PRESET, "highlights" to strings("highlights/sidewalks.mapcss"), "message" to "sidewalk gaps")))
        assertEquals(PRESET, named.areaName)
        assertBBox(BBox(17.895, 49.935, 17.915, 49.95), named.bbox)
        assertEquals(listOf("highlights/sidewalks.mapcss"), named.highlights)
        assertFalse(named.replaceHighlights)
        assertEquals("sidewalk gaps", named.message)
        assertNull(named.center)

        val boxed = pushed(call(session, "push_view", args("area" to "17.88,49.93,17.92,49.95", "replace_highlights" to true, "highlights" to strings())))
        assertNull(boxed.areaName)
        assertBBox(BBox(17.88, 49.93, 17.92, 49.95), boxed.bbox)
        assertTrue(boxed.replaceHighlights)
    }

    @Test
    fun pushViewCenterZoomAndSelection() = mcpTest {
        val session = initialize()
        val centered = pushed(call(session, "push_view", args("center" to numbers(17.9, 49.94), "zoom" to 18.5, "select" to strings("n101", "way55"))))
        assertEquals(LngLat(17.9, 49.94), centered.center)
        assertEquals(18.5, centered.zoom)
        assertNull(centered.bbox, "a center frames the view, select does not add a bbox")
        assertEquals(listOf(OsmRef(OsmType.NODE, 101), OsmRef(OsmType.WAY, 55)), centered.select)

        val defaultZoom = pushed(call(session, "push_view", args("center" to numbers(17.9, 49.94))))
        assertEquals(17.0, defaultZoom.zoom)

        val selected = pushed(call(session, "push_view", args("select" to strings("w55"))))
        val box = assertNotNull(selected.bbox, "only select: bbox from the objects")
        assertTrue(box.west <= 17.9 && box.east >= 17.9 && box.south <= 49.94 && box.north >= 49.94, box.toString())
        assertNull(selected.zoom)

        val created = call(session, "create_draft", args("title" to "Shown")).json.jsonObject["draft_id"]!!.jsonPrimitive.content
        val draft = pushed(call(session, "push_view", args("draft_id" to created)))
        assertEquals(created, draft.draftId)
        assertNull(draft.bbox)
    }

    @Test
    fun pushViewRejectsBadArguments() = mcpTest {
        val session = initialize()
        fun failsWith(text: String, r: ToolResult) {
            assertTrue(r.isError, r.text)
            assertTrue(text in r.text, r.text)
        }
        failsWith("at least one of", call(session, "push_view", args()))
        failsWith("at least one of", call(session, "push_view", args("message" to "hi")))
        failsWith("highlights/nope.mapcss", call(session, "push_view", args("highlights" to strings("highlights/sidewalks.mapcss", "highlights/nope.mapcss"))))
        failsWith("Unknown draft", call(session, "push_view", args("draft_id" to "00000000-0000-0000-0000-000000000000")))
        failsWith("Unknown draft", call(session, "push_view", args("draft_id" to "not-a-uuid")))
        failsWith("Unknown scan", call(session, "push_view", args("scan_id" to "00000000-0000-0000-0000-000000000000")))
        failsWith("not both", call(session, "push_view", args("area" to PRESET, "center" to numbers(17.9, 49.94))))
        failsWith("between 0 and 22", call(session, "push_view", args("center" to numbers(17.9, 49.94), "zoom" to 23)))
        failsWith("Not an OSM ref", call(session, "push_view", args("select" to strings("x12"))))
        assertTrue(app.events.connect().isEmpty(), "nothing is emitted for a rejected call")
    }

    @Test
    fun pushViewIsQueuedWithoutATabAndDeliveredWithOne() = mcpTest {
        val session = initialize()
        app.events.emit(UiEvent.RemoteCommand("zoom", bbox = BBox(17.0, 49.0, 18.0, 50.0)))
        val queued = call(session, "push_view", args("area" to PRESET)).json.jsonObject
        assertEquals(false, queued["delivered"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(true, queued["queued"]!!.jsonPrimitive.booleanOrNull)
        app.events.emit(UiEvent.DraftUpdated("x"))

        val replayed = app.events.connect()
        assertEquals(2, replayed.size, replayed.toString())
        assertTrue(replayed[0] is UiEvent.RemoteCommand)
        assertEquals(PRESET, (replayed[1] as UiEvent.PushView).areaName)

        val delivered = call(session, "push_view", args("select" to strings("n101"))).json.jsonObject
        assertEquals(true, delivered["delivered"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(false, delivered["queued"]!!.jsonPrimitive.booleanOrNull)
        app.events.disconnect()
        assertTrue(app.events.connect().isEmpty())
        app.events.disconnect()
    }

    @Test
    fun applyPresetBulkDryRunAndDraft() = mcpTest {
        val session = initialize()
        val refs = buildJsonArray {
            add(JsonPrimitive("w55"))
            add(buildJsonObject { put("type", "way"); put("id", 70) })
            add(JsonPrimitive("n101"))
            add(JsonPrimitive("w999"))
        }
        val dry = call(session, "apply_preset_bulk", args("preset_id" to "highway/footway/sidewalk", "refs" to refs, "locale" to "cs"))
        assertFalse(dry.isError, dry.text)
        val d = dry.json.jsonObject
        assertEquals("Chodník podél vozovky", d["preset_name"]!!.jsonPrimitive.content)
        assertEquals(true, d["dry_run"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(listOf(4, 1, 1, 2), listOf("total", "changed", "unchanged", "skipped").map { d[it]!!.jsonPrimitive.content.toInt() })
        assertEquals(listOf("n101", "w999"), d["skipped_objects"]!!.jsonArray.map { it.jsonObject["ref"]!!.jsonPrimitive.content })
        val row = d["preview"]!!.jsonArray.single().jsonObject
        assertEquals("w55", row["ref"]!!.jsonPrimitive.content)
        assertEquals("highway/footway", row["from_preset_id"]!!.jsonPrimitive.content)
        assertEquals("+footway=sidewalk", row["summary"]!!.jsonPrimitive.content)
        assertEquals(false, d["truncated"]!!.jsonPrimitive.booleanOrNull)
        assertNull(d["draft_id"])
        assertTrue(call(session, "list_drafts", args("author" to "claude")).json.jsonArray.isEmpty())

        val seen = java.util.concurrent.CopyOnWriteArrayList<UiEvent>()
        val collector = app.scope.launch(start = CoroutineStart.UNDISPATCHED) { app.events.events.collect { seen += it } }
        val created = call(
            session, "apply_preset_bulk",
            args("preset_id" to "highway/footway/sidewalk", "refs" to strings("w55", "w70"), "dry_run" to false, "area" to "Opava", "reason" to "sidewalk on imagery"),
        )
        assertFalse(created.isError, created.text)
        val c = created.json.jsonObject
        val draftId = c["draft_id"]!!.jsonPrimitive.content
        assertEquals("Preset Sidewalk · 1 objects", c["title"]!!.jsonPrimitive.content)
        assertEquals("claude", c["author"]!!.jsonPrimitive.content)
        assertEquals(1, c["results"]!!.jsonObject["accepted"]!!.jsonPrimitive.content.toInt())
        var waited = 0
        while (seen.none { it is UiEvent.DraftUpdated && it.draftId == draftId } && waited < 2000) {
            delay(20)
            waited += 20
        }
        collector.cancel()
        assertTrue(seen.any { it is UiEvent.DraftUpdated && it.draftId == draftId }, seen.toString())
        val detail = call(session, "get_draft", args("draft_id" to draftId)).json.jsonObject
        assertEquals("Opava", detail["draft"]!!.jsonObject["area"]!!.jsonPrimitive.content)
        val change = detail["changes"]!!.jsonArray.single().jsonObject
        assertEquals("sidewalk on imagery", change["reason"]!!.jsonPrimitive.content)
        assertEquals("sidewalk", change["newTags"]!!.jsonObject["footway"]!!.jsonPrimitive.content)

        val nothing = call(session, "apply_preset_bulk", args("preset_id" to "highway/footway/sidewalk", "refs" to strings("w70"), "dry_run" to false))
        assertFalse(nothing.isError, nothing.text)
        assertNull(nothing.json.jsonObject["draft_id"])
    }

    @Test
    fun applyPresetBulkErrors() = mcpTest {
        val session = initialize()
        val unknown = call(session, "apply_preset_bulk", args("preset_id" to "highway/nope", "refs" to strings("w55")))
        assertTrue(unknown.isError)
        assertTrue("Unknown preset 'highway/nope'" in unknown.text && "search_presets" in unknown.text, unknown.text)
        val badRef = call(session, "apply_preset_bulk", args("preset_id" to "highway/footway", "refs" to strings("w55", "x1")))
        assertTrue(badRef.isError)
        assertTrue("refs[1]" in badRef.text, badRef.text)
        val many = buildJsonArray { repeat(5001) { add(JsonPrimitive("n${it + 1}")) } }
        val tooMany = call(session, "apply_preset_bulk", args("preset_id" to "highway/footway", "refs" to many))
        assertTrue(tooMany.isError)
        assertTrue("at most 5000" in tooMany.text, tooMany.text)
        val noGol = call(session, "apply_preset_bulk", args("preset_id" to "highway/footway", "refs" to strings("w404")))
        assertTrue(noGol.isError)
        assertTrue("No GOL mirror is open" in noGol.text, noGol.text)
    }

    private fun strings(vararg s: String) = buildJsonArray { s.forEach { add(JsonPrimitive(it)) } }
    private fun numbers(vararg n: Double) = buildJsonArray { n.forEach { add(JsonPrimitive(it)) } }

    private fun refKey(ref: JsonElement): String {
        val o = ref.jsonObject
        return "${o["type"]!!.jsonPrimitive.content}:${o["id"]!!.jsonPrimitive.content}"
    }

    private class FakeOsm {
        val overpassQueries = mutableListOf<String>()

        private val elements = mapOf(
            "way/55" to """<way id="55" version="3" visible="true"><nd ref="101"/><nd ref="2"/><tag k="highway" v="footway"/></way>""",
            "node/101" to """<node id="101" version="4" visible="true" lat="49.94" lon="17.9"><tag k="highway" v="crossing"/></node>""",
        )
        private val parents = mapOf(
            "node/101/ways" to listOf(elements.getValue("way/55")),
            "node/101/relations" to listOf(
                """<relation id="7" version="1" visible="true"><member type="node" ref="101" role="stop"/><tag k="type" v="route"/></relation>""",
            ),
        )

        fun handle(scope: MockRequestHandleScope, req: HttpRequestData): HttpResponseData = with(scope) {
            val path = req.url.encodedPath
            when {
                path.endsWith("/interpreter") -> {
                    val body = (req.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
                    overpassQueries += URLDecoder.decode(body.removePrefix("data="), Charsets.UTF_8)
                    respond(
                        """{"elements":[
                            {"type":"node","id":1,"lat":49.94,"lon":17.905,"tags":{"highway":"crossing"}},
                            {"type":"node","id":2,"lat":49.949,"lon":17.896,"tags":{"highway":"crossing"}}]}""",
                        headers = headersOf("Content-Type", "application/json"),
                    )
                }
                req.method != HttpMethod.Get -> respond("unexpected ${req.method} $path", HttpStatusCode.BadRequest)
                path.endsWith("/way/55/full") -> respond(osm(listOf(elements.getValue("way/55"), elements.getValue("node/101"))))
                parents.keys.any { path.endsWith(it) } -> respond(osm(parents.entries.first { path.endsWith(it.key) }.value))
                else -> {
                    val type = path.substringAfterLast("/").removeSuffix("s")
                    val ids = req.url.parameters["${type}s"]?.split(",").orEmpty()
                    val found = ids.mapNotNull { elements["$type/$it"] }
                    if (ids.isEmpty() || found.size < ids.size) respond("", HttpStatusCode.NotFound) else respond(osm(found))
                }
            }
        }

        private fun osm(list: List<String>) = "<osm version=\"0.6\">" + list.joinToString("") + "</osm>"
    }

    private companion object {
        const val TOKEN = "test-token"
        const val PRESET = "Kateřinky Triangle"
        const val SURFACES = "checks/surfaces.validator.mapcss"
    }
}
