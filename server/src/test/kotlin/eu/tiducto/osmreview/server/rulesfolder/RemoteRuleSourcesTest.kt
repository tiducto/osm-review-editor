package eu.tiducto.osmreview.server.rulesfolder

import eu.tiducto.osmreview.server.core.StateException
import eu.tiducto.osmreview.shared.RuleKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteRuleSourcesTest {
    private val dir = Files.createTempDirectory("rules")
    private val cache = Files.createTempDirectory("cache")
    private val folder = RulesFolder(dir)
    private val requests = mutableListOf<String>()
    private var body = "way[highway=footway][!surface] { throwWarning: tr(\"no surface\"); }"
    private var now = Instant.parse("2026-11-05T10:00:00Z")
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }
    private val http = HttpClient(
        MockEngine { req ->
            requests += req.url.toString()
            when (req.url.host) {
                "rules.example.org" -> respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
                else -> respond("gone", HttpStatusCode.NotFound)
            }
        },
    )
    private val sources = RemoteRuleSources(folder, cache, http, "test-agent", clock)

    @AfterTest
    fun tearDown() {
        folder.close()
        http.close()
    }

    private fun writeSources(json: String) {
        dir.resolve("sources.json").writeText(json)
        folder.rescan()
    }

    @Test
    fun fetchesCachesAndListsReadOnly() = runBlocking<Unit> {
        writeSources(
            """[
              {"name":"surface","url":"https://rules.example.org/surface.validator.mapcss","kind":"check","refreshHours":24},
              {"name":"broken","url":"https://down.example.org/x.mapcss","kind":"highlight"},
              {"name":"licensed","url":"https://rules.example.org/l.mapcss","kind":"highlight","licence":"CC0"}
            ]""",
        )
        sources.refresh()
        assertEquals(3, requests.size)
        val files = folder.list().filter { it.origin != null }.associateBy { it.path }
        val ok = files.getValue("remote/surface.validator.mapcss")
        assertEquals(RuleKind.CHECK, ok.kind)
        assertTrue(ok.readOnly)
        assertEquals("surface", ok.origin!!.source)
        assertEquals("2026-11-05T10:00:00Z", ok.origin!!.fetchedAt)
        assertTrue("GPL" in ok.origin!!.licence && "rules.example.org" in ok.origin!!.licence, ok.origin!!.licence)
        assertEquals("CC0", files.getValue("remote/licensed.mapcss").origin!!.licence)
        val broken = files.getValue("remote/broken.mapcss")
        assertEquals("HTTP 404 from https://down.example.org/x.mapcss", broken.origin!!.error)
        assertNull(broken.origin!!.fetchedAt)
        assertEquals(body, folder.read("remote/surface.validator.mapcss").content)
        assertTrue(Files.exists(cache.resolve("surface.validator.mapcss")))

        assertFailsWith<StateException> { folder.write("remote/surface.validator.mapcss", "x") }
        assertFailsWith<StateException> { folder.delete("remote/surface.validator.mapcss") }
    }

    @Test
    fun refetchesOnlyWhenDue() = runBlocking<Unit> {
        writeSources("""[{"name":"s","url":"https://rules.example.org/a.mapcss","kind":"highlight","refreshHours":6}]""")
        sources.refresh()
        sources.refresh()
        assertEquals(1, requests.size)
        now = now.plus(Duration.ofHours(7))
        body = "node { }"
        sources.refresh()
        assertEquals(2, requests.size)
        assertEquals("node { }", folder.read("remote/s.mapcss").content)
        sources.refresh(force = true)
        assertEquals(3, requests.size)
    }

    @Test
    fun keepsTheLastGoodCopyWhenAFetchFails() = runBlocking<Unit> {
        writeSources("""[{"name":"s","url":"https://rules.example.org/a.mapcss","kind":"highlight","refreshHours":1}]""")
        sources.refresh()
        writeSources("""[{"name":"s","url":"https://rules.example.org/a.mapcss","kind":"highlight","refreshHours":1}, {"name":"t","url":"https://down.example.org/b","kind":"preset"}]""")
        now = now.plus(Duration.ofHours(2))
        body = "ignored"
        val failing = RemoteRuleSources(folder, cache, HttpClient(MockEngine { respond("", HttpStatusCode.InternalServerError) }), "ua", clock)
        failing.refresh()
        val s = assertNotNull(folder.info("remote/s.mapcss"))
        assertEquals("HTTP 500 from https://rules.example.org/a.mapcss", s.origin!!.error)
        assertEquals("2026-11-05T10:00:00Z", s.origin!!.fetchedAt)
        assertTrue(folder.read("remote/s.mapcss").content.startsWith("way[highway=footway]"))
    }

    @Test
    fun removedSourcesDisappear() = runBlocking<Unit> {
        writeSources("""[{"name":"s","url":"https://rules.example.org/a.mapcss","kind":"highlight"}]""")
        sources.refresh()
        assertNotNull(folder.info("remote/s.mapcss"))
        writeSources("[]")
        sources.refresh()
        assertNull(folder.info("remote/s.mapcss"))
        assertTrue(Files.list(cache).use { it.toList() }.isEmpty())
    }

    @Test
    fun validatesSourcesJson() {
        writeSources(
            """[{"name":"Bad Name","url":"ftp://x","kind":"sources","refreshHours":0,"extra":1},
                {"name":"ok","url":"https://a.example/x","kind":"check"},
                {"name":"ok","url":"https://a.example/y","kind":"check"}]""",
        )
        val msgs = folder.info("sources.json")!!.diagnostics.map { it.message }
        assertTrue(msgs.any { "[0].name" in it }, msgs.toString())
        assertTrue(msgs.any { "[0].url" in it })
        assertTrue(msgs.any { "[0].kind" in it })
        assertTrue(msgs.any { "[0].refreshHours" in it })
        assertTrue(msgs.any { "Unknown key 'extra'" in it })
        assertTrue(msgs.any { "Duplicate source name 'ok'" in it })
        assertEquals(listOf("ok"), sources.sources().map { it.name })
    }
}
