package eu.tiducto.osmreview.server.mcp

import eu.tiducto.osmreview.rules.PolygonScopes
import eu.tiducto.osmreview.rules.containsLonLat
import eu.tiducto.osmreview.server.App
import eu.tiducto.osmreview.server.Config
import eu.tiducto.osmreview.server.db.RuleRunsTable
import eu.tiducto.osmreview.server.module
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
import kotlinx.coroutines.delay
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
import kotlin.test.assertTrue

class McpTest {
    private val docker = runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
    private val gol = System.getenv("OSM_REVIEW_TEST_GOL")?.takeIf { it.isNotBlank() }?.let(Path::of)?.takeIf(Files::exists)
    private lateinit var pg: PostgreSQLContainer
    private lateinit var app: App
    private val osm = FakeOsm()
    private var nextId = 1

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
            gol?.let { put("GOL_FILE", it.toString()) }
        }
        app = App(Config.fromEnv(env), HttpClient(MockEngine { req -> osm.handle(this, req) }))
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
            setOf("list_rules", "run_rule", "query_osm", "get_object", "create_draft", "propose_changes", "update_change", "list_drafts", "get_draft"),
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
    fun runRuleWithPolygonArea() = mcpTest {
        if (gol == null) return@mcpTest println("OSM_REVIEW_TEST_GOL not set, skipping")
        val session = initialize()
        val r = call(session, "run_rule", args("rule_id" to "footway-missing-surface", "area" to polygon(opavaTriangle), "limit" to 2000))
        assertFalse(r.isError, r.text)
        val o = r.json.jsonObject
        val issues = o["issues"]!!.jsonArray.map { it.jsonObject }
        assertTrue(issues.isNotEmpty())
        val box = call(session, "run_rule", args("rule_id" to "footway-missing-surface", "area" to "17.895,49.935,17.915,49.95", "limit" to 2000))
        assertTrue(box.json.jsonObject["issueCount"]!!.jsonPrimitive.content.toInt() > issues.size)

        val scope = PolygonScopes.parse(opavaTriangle).scope
        val inside = issues.count { i ->
            val c = i["centroid"]!!.jsonObject
            scope.containsLonLat(c["lng"]!!.jsonPrimitive.double, c["lat"]!!.jsonPrimitive.double)
        }
        assertTrue(inside >= issues.size * 0.8, "$inside of ${issues.size} inside")

        val named = call(session, "run_rule", args("rule_id" to "footway-missing-surface", "area" to "Opava"))
        assertFalse(named.isError, named.text)
        val unknown = call(session, "run_rule", args("rule_id" to "footway-missing-surface", "area" to "Nowhere-at-all-xyz"))
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
    }
}
