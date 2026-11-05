package eu.tiducto.osmreview.server.mcp

import eu.tiducto.osmreview.server.App
import eu.tiducto.osmreview.server.Config
import eu.tiducto.osmreview.server.db.NewScan
import eu.tiducto.osmreview.server.db.NewScanIssue
import eu.tiducto.osmreview.server.mapcss.ScanService
import eu.tiducto.osmreview.server.module
import eu.tiducto.osmreview.shared.LngLat
import eu.tiducto.osmreview.shared.OsmRef
import eu.tiducto.osmreview.shared.OsmType
import eu.tiducto.osmreview.shared.RunScanRequest
import eu.tiducto.osmreview.shared.ScanTrigger
import eu.tiducto.osmreview.shared.Severity
import eu.tiducto.osmreview.shared.TagEdit
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.measureTimedValue

class ScansMcpTest {
    private val docker = runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
    private val gol = System.getenv("OSM_REVIEW_TEST_GOL")?.takeIf { it.isNotBlank() }?.let(Path::of)?.takeIf(Files::exists)
    private lateinit var pg: PostgreSQLContainer
    private lateinit var app: App
    private lateinit var dataDir: Path
    private var nextId = 1

    private val triangle = """{"type":"Polygon","coordinates":[[[17.895,49.935],[17.915,49.935],[17.905,49.95],[17.895,49.935]]]}"""

    @BeforeTest
    fun setUp() {
        if (!docker) return
        pg = PostgreSQLContainer("postgres:18").also { it.start() }
        dataDir = Files.createTempDirectory("data")
        val rulesDir = Files.createTempDirectory("rules")
        val env = buildMap {
            put("DB_URL", pg.jdbcUrl)
            put("DB_USER", pg.username)
            put("DB_PASSWORD", pg.password)
            put("PORT", "80")
            put("MCP_TOKEN", TOKEN)
            put("DATA_DIR", dataDir.toString())
            put("RULES_DIR", rulesDir.toString())
            gol?.let { put("GOL_FILE", it.toString()) }
        }
        app = App(Config.fromEnv(env), HttpClient(MockEngine { req -> FakeApi.handle(this, req) }))
        val example = listOf(Path.of("../user-rules.example"), Path.of("user-rules.example")).first { Files.isDirectory(it) }
        Files.walk(example).use { s -> s.toList() }.filter { Files.isRegularFile(it) && it.toString().endsWith(".mapcss") }.forEach { p ->
            val target = app.ruleFiles.root.resolve(example.relativize(p).toString())
            Files.createDirectories(target.parent)
            Files.copy(p, target)
        }
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

    private suspend fun body(resp: HttpResponse): JsonObject {
        val text = resp.bodyAsText()
        val json = text.lineSequence().firstOrNull { it.startsWith("data:") }?.removePrefix("data:") ?: text
        return Json.parseToJsonElement(json).jsonObject
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
        rpc("notifications/initialized", JsonObject(emptyMap()), session, notification = true)
        return session
    }

    private data class ToolResult(val isError: Boolean, val text: String) {
        val json: JsonElement get() = Json.parseToJsonElement(text)
    }

    private suspend fun ApplicationTestBuilder.call(session: String, tool: String, args: JsonObject): ToolResult {
        val resp = rpc("tools/call", buildJsonObject { put("name", tool); put("arguments", args) }, session)
        assertEquals(HttpStatusCode.OK, resp.status)
        val result = body(resp)["result"]!!.jsonObject
        val isError = (result["isError"] as? JsonPrimitive)?.booleanOrNull ?: false
        return ToolResult(isError, result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content)
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

    private fun JsonElement.str(k: String) = jsonObject[k]!!.jsonPrimitive.content
    private fun JsonElement.int(k: String) = jsonObject[k]!!.jsonPrimitive.int

    private fun issue(rule: String, ref: OsmRef, message: String, severity: Severity = Severity.WARNING, group: String = "g", fix: TagEdit? = null, delete: Boolean = false) =
        NewScanIssue(rule, rule.substringBefore(':'), 1, severity, group, message, ref, LngLat(17.9, 49.94), fix, delete, ScanService.fingerprint(rule.substringBefore(':'), ref.toString(), message))

    private fun storedScan(issues: List<NewScanIssue>): String = runBlocking {
        val id = app.scanRepo.create(
            NewScan("bbox:test", null, null, RunScanRequest(bbox = eu.tiducto.osmreview.shared.BBox(17.8, 49.9, 17.9, 50.0)), listOf("checks/a.validator.mapcss"), "k", emptyMap(), ScanTrigger.MANUAL, null),
        )
        app.scanRepo.finish(id, issues, 5)
        Thread.sleep(5)
        id.toString()
    }

    @Test
    fun highlightAndRuleFileToolsWithoutMirror() = mcpTest {
        val session = initialize()
        val listed = call(session, "list_highlights", args()).json.jsonObject
        assertEquals("\$type", listed["tile_properties"]!!.str("typeProperty"))
        val files = listed["files"]!!.jsonArray.associateBy { it.str("path") }
        assertEquals("Sidewalks", files.getValue("highlights/sidewalks.mapcss").str("title"))
        assertEquals("true", files.getValue("highlights/sidewalks.mapcss").str("allCompiled"))
        assertEquals("false", files.getValue("highlights/missing-surface.mapcss").str("allCompiled"))

        val rules = call(session, "list_rules", args()).json.jsonArray.map { it.str("id") }
        assertTrue("checks/crossings.validator.mapcss" in rules && "deprecated-tags" in rules, rules.toString())
        assertFalse("platform-area" in rules)

        val invalid = call(session, "validate_rule_file", args("path" to "checks/x.validator.mapcss", "content" to "node[a] {\n  throwWarning: \"x\"\n  group: ;\n}"))
        val diag = invalid.json.jsonObject["diagnostics"]!!.jsonArray.first { it.str("severity") == "error" }
        assertEquals("false", invalid.json.str("valid"))
        assertTrue(diag.int("line") >= 2, diag.toString())
        val refused = call(session, "write_rule_file", args("path" to "checks/x.validator.mapcss", "content" to "node[a] { throwWarning: \"x\"; assertMatch: \"node b=c\"; }"))
        assertTrue(refused.isError)
        assertTrue("assertMatch failed" in refused.text, refused.text)

        val noMirror = client.post("/api/scans") { contentType(ContentType.Application.Json); setBody("""{"bbox":{"west":17.89,"south":49.93,"east":17.92,"north":49.945}}""") }
        if (gol == null) assertEquals(HttpStatusCode.ServiceUnavailable, noMirror.status)
    }

    @Test
    fun reportDiffGeoJsonAndDraftFromStoredScans() = mcpTest {
        val way = OsmRef(OsmType.WAY, 55)
        val node = OsmRef(OsmType.NODE, 101)
        val older = storedScan(
            listOf(
                issue("checks/a.validator.mapcss:3", way, "no surface", fix = TagEdit(set = mapOf("surface" to "asphalt"))),
                issue("checks/a.validator.mapcss:9", OsmRef(OsmType.NODE, 7), "old problem", Severity.ERROR, "other"),
            ),
        )
        val newer = storedScan(
            listOf(
                issue("checks/a.validator.mapcss:4", way, "no surface", fix = TagEdit(set = mapOf("surface" to "asphalt"))),
                issue("checks/a.validator.mapcss:12", node, "crossing is unused", Severity.ERROR, "crossings", delete = true),
                issue("checks/a.validator.mapcss:12", OsmRef(OsmType.NODE, 8), "new problem", Severity.INFO, "crossings"),
            ),
        )
        val session = initialize()
        val report = call(session, "get_scan", args("scan_id" to newer)).json.jsonObject
        assertEquals(3, report["scan"]!!.int("issueCount"))
        assertEquals(1, report["scan"]!!.int("errors"))
        val counts = report["counts"]!!.jsonArray.map { "${it.str("group")}/${it.str("severity")}=${it.int("count")}" }
        assertEquals(listOf("crossings/error=1", "g/warning=1", "crossings/info=1"), counts)
        val errorsOnly = call(session, "get_scan", args("scan_id" to newer, "severity" to "error")).json.jsonObject["issues"]!!.jsonArray
        assertEquals(listOf("n101"), errorsOnly.map { it.jsonObject["ref"]!!.let { r -> "${r.str("type")[0]}${r.str("id")}" } })
        val recent = call(session, "get_scan", args()).json.jsonArray
        assertEquals(newer, recent.first().str("id"))

        val diff = call(session, "diff_scans", args("scan_id" to newer)).json.jsonObject
        assertEquals(older, diff["against"]!!.jsonPrimitive.content)
        assertEquals(2, diff.int("newCount"))
        assertEquals(1, diff.int("resolvedCount"))
        assertEquals(1, diff.int("unchangedCount"))
        assertEquals("old problem", diff["resolved"]!!.jsonArray.single().str("message"))

        val geo = client.get("/api/scans/$newer/geojson?severity=error")
        assertEquals(HttpStatusCode.OK, geo.status)
        val features = Json.parseToJsonElement(geo.bodyAsText()).jsonObject["features"]!!.jsonArray
        assertEquals("n101", features.single().jsonObject["properties"]!!.str("ref"))
        assertEquals(listOf(17.9, 49.94), features.single().jsonObject["geometry"]!!.jsonObject["coordinates"]!!.jsonArray.map { it.jsonPrimitive.content.toDouble() })

        val rest = client.get("/api/scans/$newer/diff")
        assertEquals(HttpStatusCode.OK, rest.status)

        val drafted = call(session, "draft_from_scan", args("scan_id" to newer, "title" to "Fixes"))
        assertFalse(drafted.isError, drafted.text)
        val result = drafted.json.jsonObject
        assertEquals(1, result.int("skipped"))
        assertEquals(listOf("accepted", "accepted"), result["results"]!!.jsonArray.map { it.str("outcome") })
        val changes = result["draft"]!!.jsonObject["changes"]!!.jsonArray.associateBy { "${it.jsonObject["ref"]!!.str("type")}/${it.jsonObject["ref"]!!.str("id")}" }
        assertEquals("asphalt", changes.getValue("way/55").jsonObject["newTags"]!!.str("surface"))
        assertEquals("delete", changes.getValue("node/101").str("action"))
        assertEquals("Fixes", result["draft"]!!.jsonObject["draft"]!!.str("title"))
    }

    @Test
    fun scansHighlightsAndRunRuleOnTheMirror() = mcpTest {
        if (gol == null) return@mcpTest println("OSM_REVIEW_TEST_GOL not set, skipping")
        val session = initialize()
        val area = Json.parseToJsonElement(triangle)

        val run = call(session, "run_rule", args("rule_id" to "checks/crossings.validator.mapcss", "area" to area, "limit" to 1000))
        assertFalse(run.isError, run.text)
        val issues = run.json.jsonObject["issues"]!!.jsonArray
        assertTrue(issues.isNotEmpty())
        assertTrue(issues.all { it.str("ruleId").startsWith("checks/crossings.validator.mapcss:") })

        val first = call(session, "run_scan", args("area" to area))
        assertFalse(first.isError, first.text)
        val firstId = first.json.str("id")
        assertTrue(first.json.int("issueCount") > 0)
        val ruleSet = first.json.jsonObject["ruleSet"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("builtin:deprecated-tags" in ruleSet && "checks/platforms.validator.mapcss" in ruleSet, ruleSet.toString())

        val second = call(session, "run_scan", args("area" to area)).json.str("id")
        val same = call(session, "diff_scans", args("scan_id" to second)).json.jsonObject
        assertEquals(firstId, same["against"]!!.jsonPrimitive.content)
        assertEquals(0, same.int("newCount"))
        assertEquals(0, same.int("resolvedCount"))

        val crossings = app.ruleFiles.root.resolve("checks/crossings.validator.mapcss")
        crossings.writeText(Files.readString(crossings).replace("throwOther: \"Crossing without crossing=*\";", "throwOther: \"Crossing type missing\";"))
        app.ruleFiles.rescan()
        val third = call(session, "run_scan", args("area" to area)).json.str("id")
        val changed = call(session, "diff_scans", args("scan_id" to third)).json.jsonObject
        assertTrue(changed.int("newCount") > 0 && changed.int("newCount") == changed.int("resolvedCount"), changed.toString())

        val stats = call(session, "highlight_stats", args("path" to "highlights/sidewalks.mapcss", "area" to "Opava"))
        assertFalse(stats.isError, stats.text)
        println("Sidewalks stats for Opava: ${stats.json.str("tookMs")} ms, ${stats.json.jsonObject["entries"]}")
        val evaluated = call(session, "evaluate_highlight", args("path" to "highlights/sidewalks.mapcss", "area" to area, "legend" to "missing", "limit" to 5))
        assertFalse(evaluated.isError, evaluated.text)
        assertTrue(evaluated.json.jsonObject["objects"]!!.jsonArray.all { it.str("legend") == "missing" })

        val (opava, took) = measureTimedValue {
            client.post("/api/scans") { contentType(ContentType.Application.Json); setBody("""{"area":"Opava"}""") }
        }
        assertEquals(HttpStatusCode.Created, opava.status, opava.bodyAsText())
        val scan = Json.parseToJsonElement(opava.bodyAsText()).jsonObject
        println("Full scan of Opava: ${scan.int("issueCount")} issues, ${scan.str("tookMs")} ms in the service, $took over HTTP")
        val report = Json.parseToJsonElement(client.get("/api/scans/${scan.str("id")}?limit=5").bodyAsText()).jsonObject
        println("Opava by group: ${report["counts"]!!.jsonArray.joinToString { "${it.str("group")}/${it.str("severity")}=${it.int("count")}" }}")

        val link = dataDir.resolve("b.gol")
        Files.createSymbolicLink(link, gol)
        dataDir.resolve("current").writeText("b.gol")
        app.mirror.close()
        assertTrue(app.mirror.reloadIfChanged(), app.mirror.status().toString())
        val rerun = app.scans.rerunRecent(7)
        assertEquals(2, rerun.size, rerun.map { it.scope }.toString())
        assertTrue(rerun.all { it.trigger == ScanTrigger.MIRROR_SWAP })
        assertTrue(app.scans.rerunRecent(7).isEmpty())
    }

    private object FakeApi {
        private val PARENTS = Regex("/(node|way|relation)/\\d+/(ways|relations)$")
        private val elements = mapOf(
            "way/55" to """<way id="55" version="3" visible="true"><nd ref="101"/><nd ref="2"/><tag k="highway" v="footway"/></way>""",
            "node/101" to """<node id="101" version="4" visible="true" lat="49.94" lon="17.9"><tag k="highway" v="crossing"/></node>""",
        )

        fun handle(scope: MockRequestHandleScope, req: HttpRequestData): HttpResponseData = with(scope) {
            val path = req.url.encodedPath
            if (req.method != HttpMethod.Get) return respond("unexpected ${req.method} $path", HttpStatusCode.BadRequest)
            if (PARENTS.containsMatchIn(path)) return respond("<osm version=\"0.6\"></osm>")
            val type = path.substringAfterLast("/").removeSuffix("s")
            val ids = req.url.parameters["${type}s"]?.split(",").orEmpty()
            val found = ids.mapNotNull { elements["$type/$it"] }
            if (ids.isEmpty() || found.size < ids.size) respond("", HttpStatusCode.NotFound) else respond("<osm version=\"0.6\">" + found.joinToString("") + "</osm>")
        }
    }

    private companion object {
        const val TOKEN = "test-token"
    }
}
