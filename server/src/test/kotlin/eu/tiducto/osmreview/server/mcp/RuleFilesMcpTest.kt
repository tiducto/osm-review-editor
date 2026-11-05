package eu.tiducto.osmreview.server.mcp

import eu.tiducto.osmreview.server.App
import eu.tiducto.osmreview.server.Config
import eu.tiducto.osmreview.server.module
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RuleFilesMcpTest {
    private val docker = runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
    private lateinit var pg: PostgreSQLContainer
    private lateinit var app: App
    private lateinit var rulesDir: Path
    private var nextId = 1

    @BeforeTest
    fun setUp() {
        if (!docker) return
        pg = PostgreSQLContainer("postgres:18").also { it.start() }
        rulesDir = Files.createTempDirectory("rules")
        val env = mapOf(
            "DB_URL" to pg.jdbcUrl,
            "DB_USER" to pg.username,
            "DB_PASSWORD" to pg.password,
            "PORT" to "80",
            "MCP_TOKEN" to TOKEN,
            "DATA_DIR" to Files.createTempDirectory("data").toString(),
            "RULES_DIR" to rulesDir.toString(),
        )
        app = App(Config.fromEnv(env), HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }))
        app.ruleFiles.rescan()
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

    @Test
    fun ruleFileToolsOverHttp() = mcpTest {
        val session = initialize()
        val smart = "presets/smart/x.json"

        val refused = call(session, "write_rule_file", args("path" to smart, "content" to """{"id":"smart/x","geometry":["line"]}"""))
        assertTrue(refused.isError)
        assertTrue("Not written" in refused.text && "Missing \\\"name\\\"" in refused.text, refused.text)
        assertFalse(rulesDir.resolve(smart).exists())

        val forced = call(session, "write_rule_file", args("path" to smart, "content" to "{}", "force" to true))
        assertFalse(forced.isError, forced.text)
        assertTrue(forced.json.jsonObject["saved"]!!.jsonPrimitive.content.toBoolean())

        val valid = call(session, "validate_rule_file", args("path" to "highlights/a.mapcss", "content" to "way { }"))
        assertEquals("true", valid.json.str("valid"))

        val written = call(session, "write_rule_file", args("path" to "highlights/a.mapcss", "content" to "way { }"))
        assertFalse(written.isError, written.text)

        val listed = call(session, "list_rule_files", args()).json.jsonObject
        assertEquals(rulesDir.toRealPath().toString(), listed["rules_dir"]!!.jsonPrimitive.content)
        val listedFiles = listed["files"]!!.jsonArray
        val builtIns = listedFiles.filter { it.jsonObject["origin"]?.toString()?.contains("\"builtIn\":true") == true }.map { it.str("path") }.toSet()
        assertTrue("checks/surfaces.validator.mapcss" in builtIns, builtIns.toString())
        assertEquals(setOf("highlights/a.mapcss", smart), listedFiles.map { it.str("path") }.toSet() - builtIns)
        val onlyHighlights = call(session, "list_rule_files", args("kind" to "highlight")).json.jsonObject["files"]!!.jsonArray
        assertEquals(listOf("highlights/a.mapcss"), onlyHighlights.map { it.str("path") }.filter { it !in builtIns })

        val copied = call(session, "copy_rule_file", args("path" to "checks/surfaces.validator.mapcss"))
        assertFalse(copied.isError, copied.text)
        assertTrue(rulesDir.resolve("checks/surfaces.validator.mapcss").exists())
        assertTrue(call(session, "copy_rule_file", args("path" to "checks/surfaces.validator.mapcss")).isError)
        assertTrue(call(session, "delete_rule_file", args("path" to "checks/crossings.validator.mapcss")).isError)

        val read = call(session, "read_rule_file", args("path" to "highlights/a.mapcss")).json
        assertEquals("way { }", read.str("content"))
        val hash = read.jsonObject["file"]!!.str("hash")
        val stale = call(session, "write_rule_file", args("path" to "highlights/a.mapcss", "content" to "node { }", "expected_hash" to "x"))
        assertTrue(stale.isError)
        assertFalse(call(session, "write_rule_file", args("path" to "highlights/a.mapcss", "content" to "node { }", "expected_hash" to hash)).isError)

        val escape = call(session, "write_rule_file", args("path" to "../escape.mapcss", "content" to "x"))
        assertTrue(escape.isError)
        assertFalse(rulesDir.parent.resolve("escape.mapcss").exists())

        val deleted = call(session, "delete_rule_file", args("path" to "highlights/a.mapcss")).json
        assertEquals("node { }", rulesDir.resolve(deleted.str("trashed_to")).readText())
        assertTrue(call(session, "read_rule_file", args("path" to "highlights/a.mapcss")).isError)
    }

    @Test
    fun presetToolsOverHttp() = mcpTest {
        val session = initialize()
        val hits = call(session, "search_presets", args("query" to "chodník", "locale" to "cs", "geometry" to "line")).json.jsonArray
        assertTrue(hits.any { it.str("id") == "highway/footway/sidewalk" }, hits.toString())

        val match = call(
            session, "match_preset",
            args("tags" to buildJsonObject { put("highway", "footway"); put("footway", "sidewalk") }, "geometry" to "line", "locale" to "cs"),
        ).json.jsonObject
        assertEquals("highway/footway/sidewalk", match["preset"]!!.str("id"))
        assertEquals("Chodník podél vozovky", match["preset"]!!.str("name"))

        val imported = call(session, "import_preset", args("preset_id" to "highway/footway/sidewalk", "id" to "custom/sidewalk-cz"))
        assertFalse(imported.isError, imported.text)
        val path = imported.json.jsonObject["file"]!!.str("path")
        assertEquals("presets/custom-sidewalk-cz.json", path)

        val overlay = """
            { "id":"custom/sidewalk-cz", "extends":"highway/footway/sidewalk", "name":"Chodník", "apply":"merge",
              "addTags":{"highway":"footway","footway":"sidewalk","surface":"paving_stones"},
              "fields":["{highway/footway/sidewalk}","width","lit"] }
        """.trimIndent()
        assertFalse(call(session, "write_rule_file", args("path" to path, "content" to overlay)).isError)
        val got = call(session, "get_preset", args("id" to "custom/sidewalk-cz", "locale" to "cs")).json
        assertEquals("Chodník", got.str("name"))
        assertEquals("user", got.str("origin"))

        val badRef = call(session, "write_rule_file", args("path" to "presets/bad.json", "content" to """{"id":"custom/bad","extends":"nope"}"""))
        assertTrue(badRef.isError)
        assertTrue("unknown preset 'nope'" in badRef.text, badRef.text)

        val detached = call(session, "detach_preset", args("id" to "custom/sidewalk-cz"))
        assertFalse(detached.isError, detached.text)
        assertFalse("extends" in rulesDir.resolve(path).readText())
    }

    private companion object {
        const val TOKEN = "test-token"
    }
}
