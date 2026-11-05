package eu.tiducto.osmreview.server.api

import eu.tiducto.osmreview.server.installErrorPages
import eu.tiducto.osmreview.server.presets.PresetService
import eu.tiducto.osmreview.server.presets.PresetServiceTest
import eu.tiducto.osmreview.server.rulesfolder.BuiltInRules
import eu.tiducto.osmreview.server.rulesfolder.MapCssLoader
import eu.tiducto.osmreview.server.rulesfolder.PresetFileLoader
import eu.tiducto.osmreview.server.rulesfolder.RemoteRuleSources
import eu.tiducto.osmreview.server.rulesfolder.RulesFolder
import eu.tiducto.osmreview.server.rulesfolder.SmartPresetLoader
import eu.tiducto.osmreview.shared.ApiError
import eu.tiducto.osmreview.shared.CopyRuleFileRequest
import eu.tiducto.osmreview.shared.CreateRuleFileRequest
import eu.tiducto.osmreview.shared.DeleteRuleFileResult
import eu.tiducto.osmreview.shared.RuleFileContent
import eu.tiducto.osmreview.shared.RuleFileList
import eu.tiducto.osmreview.shared.RuleKind
import eu.tiducto.osmreview.shared.ValidateRuleFileRequest
import eu.tiducto.osmreview.shared.ValidateRuleFileResult
import eu.tiducto.osmreview.shared.WireJson
import eu.tiducto.osmreview.shared.WriteRuleFileRequest
import eu.tiducto.osmreview.shared.WriteRuleFileResult
import eu.tiducto.osmreview.shared.presets.DetachPresetRequest
import eu.tiducto.osmreview.shared.presets.IdSchemaInfo
import eu.tiducto.osmreview.shared.presets.ImportPresetRequest
import eu.tiducto.osmreview.shared.presets.PresetApplyRequest
import eu.tiducto.osmreview.shared.presets.PresetApplyResult
import eu.tiducto.osmreview.shared.presets.PresetGeometry
import eu.tiducto.osmreview.shared.presets.PresetMatch
import eu.tiducto.osmreview.shared.presets.PresetMatchRequest
import eu.tiducto.osmreview.shared.presets.PresetSummary
import eu.tiducto.osmreview.shared.presets.ResolvedPreset
import eu.tiducto.osmreview.shared.presets.SmartPreset
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuleFilesApiTest {
    private val dir = Files.createTempDirectory("rules")
    private val folder = RulesFolder(dir, null, listOf(PresetFileLoader { PresetServiceTest.data.index }, SmartPresetLoader()))
    private val presets = PresetService(PresetServiceTest.data, folder)
    private val http = HttpClient(MockEngine { respond("way { }") })
    private val sources = RemoteRuleSources(folder, Files.createTempDirectory("cache"), http, "ua")

    @AfterTest
    fun tearDown() {
        folder.close()
        http.close()
    }

    private fun api(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { testModule(folder, sources, presets) }
        block()
    }

    @Test
    fun builtInFilesOverRest() {
        val d = Files.createTempDirectory("rules")
        RulesFolder(d, null, listOf(MapCssLoader(RuleKind.HIGHLIGHT), MapCssLoader(RuleKind.CHECK)), builtInFiles = BuiltInRules.fromResources()).use { f ->
            testApplication {
                application { testModule(f, RemoteRuleSources(f, Files.createTempDirectory("cache"), http, "ua"), PresetService(PresetServiceTest.data, f)) }
                val path = "checks/surfaces.validator.mapcss"
                val checks = send("GET", "/api/rule-files?kind=check").body(RuleFileList.serializer()).files
                val builtIn = checks.single { it.path == path }
                assertTrue(builtIn.readOnly && builtIn.origin!!.builtIn, builtIn.toString())
                val json = send("GET", "/api/rule-files/$path").bodyAsText()
                assertTrue("\"builtIn\":true" in json, json)
                val content = send("GET", "/api/rule-files/$path").body(RuleFileContent.serializer()).content

                val v = send("POST", "/api/rule-files/validate", enc(ValidateRuleFileRequest.serializer(), ValidateRuleFileRequest(path, content)))
                assertTrue(v.body(ValidateRuleFileResult.serializer()).valid)

                val refused = send("DELETE", "/api/rule-files/$path")
                assertEquals(HttpStatusCode.Conflict, refused.status)
                assertTrue("built in" in refused.bodyAsText())

                val copy = send("POST", "/api/rule-files/copy", enc(CopyRuleFileRequest.serializer(), CopyRuleFileRequest(path)))
                assertEquals(HttpStatusCode.Created, copy.status)
                val copied = copy.body(WriteRuleFileResult.serializer()).file!!
                assertFalse(copied.readOnly)
                assertEquals(null, copied.origin)
                assertEquals(content, d.resolve(path).readText())
                assertEquals(HttpStatusCode.Conflict, send("POST", "/api/rule-files/copy", enc(CopyRuleFileRequest.serializer(), CopyRuleFileRequest(path))).status)
                assertEquals(
                    HttpStatusCode.NotFound,
                    send("POST", "/api/rule-files/copy", enc(CopyRuleFileRequest.serializer(), CopyRuleFileRequest("checks/none.validator.mapcss"))).status,
                )
                assertEquals(1, send("GET", "/api/rule-files?kind=check").body(RuleFileList.serializer()).files.count { it.path == path })

                val put = send("PUT", "/api/rule-files/highlights/sidewalks.mapcss", enc(WriteRuleFileRequest.serializer(), WriteRuleFileRequest("way { }")))
                assertEquals(HttpStatusCode.OK, put.status)
                assertEquals("way { }", send("GET", "/api/rule-files/highlights/sidewalks.mapcss").body(RuleFileContent.serializer()).content)
                assertEquals(HttpStatusCode.OK, send("DELETE", "/api/rule-files/highlights/sidewalks.mapcss").status)
                val restored = send("GET", "/api/rule-files/highlights/sidewalks.mapcss").body(RuleFileContent.serializer())
                assertTrue(restored.file.origin!!.builtIn)
                assertTrue(restored.file.readOnly)
            }
        }
    }

    private suspend inline fun <reified T> HttpResponse.body(s: KSerializer<T>): T = WireJson.decodeFromString(s, bodyAsText())

    private suspend fun ApplicationTestBuilder.send(method: String, path: String, json: String? = null, origin: String? = null): HttpResponse {
        val block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            if (json != null) {
                contentType(ContentType.Application.Json)
                setBody(json)
            }
            origin?.let { header(HttpHeaders.Origin, it) }
        }
        return when (method) {
            "GET" -> client.get(path, block)
            "POST" -> client.post(path, block)
            "PUT" -> client.put(path, block)
            else -> client.delete(path, block)
        }
    }

    private fun <T> enc(s: KSerializer<T>, v: T) = WireJson.encodeToString(s, v)

    @Test
    fun crudOverRest() = api {
        val created = send("POST", "/api/rule-files", enc(CreateRuleFileRequest.serializer(), CreateRuleFileRequest("highlights/a.mapcss", "way { }")))
        assertEquals(HttpStatusCode.Created, created.status)
        assertTrue(created.body(WriteRuleFileResult.serializer()).saved)
        assertEquals(HttpStatusCode.Conflict, send("POST", "/api/rule-files", enc(CreateRuleFileRequest.serializer(), CreateRuleFileRequest("highlights/a.mapcss", "x"))).status)

        val list = send("GET", "/api/rule-files").body(RuleFileList.serializer())
        assertEquals(dir.toRealPath().toString(), list.rulesDir)
        assertEquals(listOf("highlights/a.mapcss"), list.files.map { it.path })
        assertEquals(listOf<String>(), send("GET", "/api/rule-files?kind=preset").body(RuleFileList.serializer()).files.map { it.path })

        val read = send("GET", "/api/rule-files/highlights/a.mapcss").body(RuleFileContent.serializer())
        assertEquals("way { }", read.content)

        val stale = send("PUT", "/api/rule-files/highlights/a.mapcss", enc(WriteRuleFileRequest.serializer(), WriteRuleFileRequest("node { }", expectedHash = "nope")))
        assertEquals(HttpStatusCode.Conflict, stale.status)
        val put = send("PUT", "/api/rule-files/highlights/a.mapcss", enc(WriteRuleFileRequest.serializer(), WriteRuleFileRequest("node { }", expectedHash = read.file.hash)))
        assertEquals(HttpStatusCode.OK, put.status)
        assertEquals("node { }", dir.resolve("highlights/a.mapcss").readText())

        val invalid = send("PUT", "/api/rule-files/presets/smart/s.json", enc(WriteRuleFileRequest.serializer(), WriteRuleFileRequest("""{"id":"smart/s"}""")))
        assertEquals(HttpStatusCode.UnprocessableEntity, invalid.status)
        val r = invalid.body(WriteRuleFileResult.serializer())
        assertFalse(r.saved)
        assertTrue(r.diagnostics.any { it.message == "Missing \"name\"" && it.line == 1 })
        assertFalse(dir.resolve("presets/smart/s.json").exists())

        val v = send("POST", "/api/rule-files/validate", enc(ValidateRuleFileRequest.serializer(), ValidateRuleFileRequest("presets/p.json", """{"id":"x","extends":"amenity/bench"}""")))
        assertEquals(ValidateRuleFileResult("presets/p.json", RuleKind.PRESET, true, emptyList()), v.body(ValidateRuleFileResult.serializer()))

        val del = send("DELETE", "/api/rule-files/highlights/a.mapcss").body(DeleteRuleFileResult.serializer())
        assertTrue(dir.resolve(del.trashedTo).exists())
        assertEquals(HttpStatusCode.NotFound, send("GET", "/api/rule-files/highlights/a.mapcss").status)
    }

    @Test
    fun guardsPathsAndOrigins() = api {
        for (p in listOf("/api/rule-files/..%2F..%2Fetc%2Fpasswd", "/api/rule-files/highlights/x.sh", "/api/rule-files/.trash/x/highlights/a.mapcss")) {
            val resp = send("PUT", p, enc(WriteRuleFileRequest.serializer(), WriteRuleFileRequest("x")))
            assertEquals(HttpStatusCode.BadRequest, resp.status, p)
            assertTrue(resp.body(ApiError.serializer()).detail!!.isNotBlank())
        }
        val cross = send("PUT", "/api/rule-files/highlights/a.mapcss", enc(WriteRuleFileRequest.serializer(), WriteRuleFileRequest("x")), origin = "https://evil.example")
        assertEquals(HttpStatusCode.Forbidden, cross.status)
        assertFalse(dir.resolve("highlights/a.mapcss").exists())
        val wrongHost = client.get("/api/rule-files") { header(HttpHeaders.Host, "evil.example") }
        assertEquals(HttpStatusCode.Forbidden, wrongHost.status)
    }

    @Test
    fun remoteSourcesOverRest() = api {
        val sourcesJson = """[{"name":"josm-unnecessary","url":"https://josm.example/unnecessary.mapcss","kind":"check"}]"""
        assertEquals(HttpStatusCode.Created, send("POST", "/api/rule-files", enc(CreateRuleFileRequest.serializer(), CreateRuleFileRequest("sources.json", sourcesJson))).status)
        val list = send("POST", "/api/rule-files/sources/refresh").body(RuleFileList.serializer())
        val remote = list.files.single { it.origin != null }
        assertEquals("remote/josm-unnecessary.validator.mapcss", remote.path)
        assertTrue(remote.readOnly)
        assertEquals("way { }", send("GET", "/api/rule-files/remote/josm-unnecessary.validator.mapcss").body(RuleFileContent.serializer()).content)
        assertEquals(HttpStatusCode.Conflict, send("DELETE", "/api/rule-files/remote/josm-unnecessary.validator.mapcss").status)
    }

    @Test
    fun presetsOverRest() = api {
        val hits = send("GET", "/api/presets/search?q=chodnik&locale=cs&geometry=line").body(ListSerializer(PresetSummary.serializer()))
        assertTrue(hits.any { it.id == "highway/footway/sidewalk" })
        val preset = send("GET", "/api/presets/highway/footway/sidewalk?locale=cs").body(ResolvedPreset.serializer())
        assertEquals("Chodník podél vozovky", preset.name)
        assertEquals(HttpStatusCode.NotFound, send("GET", "/api/presets/no/such").status)

        val match = send("POST", "/api/presets/match", enc(PresetMatchRequest.serializer(), PresetMatchRequest(mapOf("amenity" to "bench"), PresetGeometry.POINT, "en")))
        assertEquals("amenity/bench", match.body(PresetMatch.serializer()).preset.id)

        val imported = send("POST", "/api/presets/import", enc(ImportPresetRequest.serializer(), ImportPresetRequest("amenity/bench", "custom/bench")))
        assertEquals(HttpStatusCode.Created, imported.status)
        assertEquals("presets/custom-bench.json", imported.body(WriteRuleFileResult.serializer()).file!!.path)
        val user = send("GET", "/api/presets/user?locale=cs").body(ListSerializer(ResolvedPreset.serializer()))
        assertEquals(listOf("custom/bench"), user.map { it.id })
        assertEquals("cs", user.single().locale)

        val applied = send("POST", "/api/presets/apply", enc(PresetApplyRequest.serializer(), PresetApplyRequest("custom/bench", mapOf("leisure" to "picnic_table"))))
        assertEquals(mapOf("amenity" to "bench", "leisure" to "picnic_table"), applied.body(PresetApplyResult.serializer()).tags)

        val detached = send("POST", "/api/presets/detach", enc(DetachPresetRequest.serializer(), DetachPresetRequest(path = "presets/custom-bench.json")))
        assertEquals(HttpStatusCode.OK, detached.status, detached.bodyAsText())
        assertFalse("extends" in dir.resolve("presets/custom-bench.json").readText())
    }

    @Test
    fun smartPresetsInTheLocale() = api {
        val saved = folder.write(
            "presets/smart/x.json",
            """{"id":"smart/x","name":{"cs":"Přechod","en":"Crossing"},"geometry":["line"],"match":"way[highway=footway]",
               "steps":[{"op":"tag","target":"self","set":{"footway":"crossing"}}]}""",
        )
        assertTrue(saved.saved, saved.toString())
        val en = send("GET", "/api/presets/smart").body(ListSerializer(SmartPreset.serializer())).single()
        assertEquals("Crossing", en.name)
        assertEquals(mapOf("cs" to "Přechod", "en" to "Crossing"), en.names)
        val cs = send("GET", "/api/presets/smart?locale=cs").body(ListSerializer(SmartPreset.serializer())).single()
        assertEquals("Přechod", cs.name)
    }

    @Test
    fun servesIdSchemaFiles() = api {
        val info = send("GET", "/api/presets/id-schema").body(IdSchemaInfo.serializer())
        assertEquals("7.3.0", info.version)
        assertEquals("/api/presets/id-schema/", info.baseUrl)
        for (f in info.files) {
            val resp = send("GET", info.baseUrl + f)
            assertEquals(HttpStatusCode.OK, resp.status, f)
            assertEquals("\"7.3.0\"", resp.headers[HttpHeaders.ETag])
        }
        val cs = Json.parseToJsonElement(send("GET", "/api/presets/id-schema/translations/cs.json").bodyAsText()).jsonObject
        assertEquals("Chodník podél vozovky", cs["cs"]!!.jsonObject["presets"]!!.jsonObject["presets"]!!.jsonObject["highway/footway/sidewalk"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.NotFound, send("GET", "/api/presets/id-schema/taginfo.json").status)
    }
}

private fun Application.testModule(folder: RulesFolder, sources: RemoteRuleSources, presets: PresetService) {
    install(ContentNegotiation) { json(WireJson) }
    installErrorPages(-1)
    routing {
        route("/api") {
            install(LocalOnly)
            ruleFileRoutes(folder, sources)
            presetRoutes(presets)
        }
    }
}
