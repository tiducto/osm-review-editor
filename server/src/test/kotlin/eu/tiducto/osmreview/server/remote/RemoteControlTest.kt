package eu.tiducto.osmreview.server.remote

import eu.tiducto.osmreview.server.App
import eu.tiducto.osmreview.server.Config
import eu.tiducto.osmreview.server.core.FakeOsm
import eu.tiducto.osmreview.server.module
import eu.tiducto.osmreview.shared.Authors
import eu.tiducto.osmreview.shared.ChangeStatus
import eu.tiducto.osmreview.shared.DraftStatus
import eu.tiducto.osmreview.shared.OsmRef
import eu.tiducto.osmreview.shared.OsmType
import eu.tiducto.osmreview.shared.ReviewDecision
import eu.tiducto.osmreview.shared.ReviewRequest
import eu.tiducto.osmreview.shared.UiEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.server.engine.connector
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RemoteControlTest {
    private val docker = runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
    private lateinit var pg: PostgreSQLContainer
    private lateinit var app: App
    private val osm = FakeOsm()

    @BeforeTest
    fun setUp() {
        if (!docker) return
        pg = PostgreSQLContainer("postgres:18").also { it.start() }
        val config = Config.fromEnv(
            mapOf(
                "DB_URL" to pg.jdbcUrl,
                "DB_USER" to pg.username,
                "DB_PASSWORD" to pg.password,
                "DATA_DIR" to Files.createTempDirectory("gol").toString(),
                "RULES_DIR" to Files.createTempDirectory("rules").toString(),
                "OSM_CLIENT_ID_DEV" to "client",
                "TOKEN_ENCRYPTION_KEY" to "k",
            ),
        )
        app = App(config, HttpClient(MockEngine { req -> osm.handle(this, req) }))
    }

    @AfterTest
    fun tearDown() {
        if (!docker) return
        app.close()
        pg.stop()
    }

    private fun rc(block: suspend ApplicationTestBuilder.() -> Unit) {
        if (!docker) return println("Docker not available, skipping")
        testApplication {
            engine {
                connector {
                    host = "localhost"
                    port = app.config.port
                }
                connector {
                    host = "localhost"
                    port = RC
                }
            }
            application { module(app) }
            block()
        }
    }

    private suspend fun ApplicationTestBuilder.josm(path: String): HttpResponse = client.get("http://localhost:$RC$path")

    private fun remoteDrafts() = runBlocking { app.repo.listDrafts(author = Authors.REMOTE) }

    private fun commands() = app.events.connect().filterIsInstance<UiEvent.RemoteCommand>()

    @Test
    fun version() = rc {
        val r = josm("/version")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("\"protocolversion\""))
        assertEquals("*", r.headers["Access-Control-Allow-Origin"])
    }

    @Test
    fun preflightAllowsPrivateNetwork() = rc {
        val r = client.options("http://localhost:$RC/load_and_zoom") {
            header("Origin", "https://www.openstreetmap.org")
            header("Access-Control-Request-Method", "GET")
            header("Access-Control-Request-Private-Network", "true")
        }
        assertEquals(HttpStatusCode.NoContent, r.status)
        assertEquals("*", r.headers["Access-Control-Allow-Origin"])
        assertEquals("true", r.headers["Access-Control-Allow-Private-Network"])
        assertTrue(r.headers["Access-Control-Allow-Methods"]!!.contains("GET"))
    }

    @Test
    fun addTagsMakesOneDraftPerObjectWithChangesetMetadata() = rc {
        val r = josm(
            "/load_and_zoom?left=17.89&right=17.93&top=49.93&bottom=49.89&select=node101,w55" +
                "&addtags=lit%3Dyes&changeset_comment=Lamps%20from%20survey&changeset_source=survey",
        )
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("OK\r\n", r.bodyAsText())

        val drafts = remoteDrafts()
        assertEquals(2, drafts.size)
        assertTrue(drafts.all { it.counts.total == 1 && it.changesetComment == "Lamps from survey" && it.changesetSource == "survey" })
        assertTrue(drafts.none { it.title == "Lamps from survey" })

        val cmd = commands().single()
        assertEquals(drafts.map { it.id }.toSet(), cmd.draftIds.toSet())
        assertEquals(listOf(OsmRef(OsmType.NODE, 101), OsmRef(OsmType.WAY, 55)), cmd.select)

        val draft = drafts.first()
        val change = app.drafts.detail(draft.id).changes.single()
        assertEquals("yes", change.newTags["lit"])
        app.drafts.review(change.id, ReviewRequest(ReviewDecision.APPROVE, imagery = "Esri World Imagery"))
        val group = app.merge.preview(draft.id).groups.single()
        assertEquals("Lamps from survey", group.comment)
        assertEquals("Lamps from survey", group.tags["comment"])
        assertEquals("survey; Esri World Imagery", group.tags["source"])
    }

    @Test
    fun loadObjectFetchesLiveBboxAndRelationMembers() = rc {
        assertEquals(HttpStatusCode.OK, josm("/load_object?objects=r7&relation_members=true").status)
        val bbox = assertNotNull(commands().single().bbox)
        assertTrue(bbox.west < 17.90 && bbox.east > 17.92 && bbox.south < 49.90 && bbox.north > 49.92, "bbox $bbox")
        assertTrue(remoteDrafts().isEmpty())
    }

    @Test
    fun loadDataReportsSkippedGeometry() = rc {
        val data = """
            <osm version="0.6">
              <node id="-1" lat="49.95" lon="17.95"><tag k="amenity" v="bench"/></node>
              <node id="-2" lat="49.96" lon="17.96"/>
              <node id="1" version="1" lat="49.90" lon="17.90"/>
              <node id="2" version="1" lat="49.91" lon="17.92"/>
              <way id="-3"><nd ref="-2"/><nd ref="1"/></way>
              <way id="55" version="3" action="modify"><nd ref="1"/><nd ref="2"/><nd ref="-2"/><nd ref="1"/><tag k="highway" v="footway"/><tag k="surface" v="paved"/></way>
              <node id="102" version="1" action="delete" lat="49.9" lon="17.9"/>
            </osm>
        """.trimIndent()
        val r = client.submitForm("http://localhost:$RC/load_data", parameters { append("data", data) })
        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        assertTrue(body.startsWith("OK\r\n"), body)
        assertTrue(body.contains("1 geometry change, 1 deletion, 1 new way, 1 untagged new node"), body)

        val draft = remoteDrafts().single()
        assertTrue(draft.description.contains("1 geometry change, 1 deletion, 1 new way, 1 untagged new node"), draft.description)
        val changes = app.drafts.detail(draft.id).changes
        assertEquals(2, changes.size)
        assertEquals("paved", changes.first { it.ref == OsmRef(OsmType.WAY, 55) }.newTags["surface"])
        assertTrue(changes.any { it.newTags["amenity"] == "bench" && it.status == ChangeStatus.PENDING })
        assertEquals(DraftStatus.OPEN, draft.status)
    }

    @Test
    fun errorsArePlainText() = rc {
        val missing = josm("/load_and_zoom?select=n1")
        assertEquals(HttpStatusCode.BadRequest, missing.status)
        assertEquals(ContentType.Text.Plain, missing.contentType()?.withoutParameters())
        assertTrue(missing.bodyAsText().startsWith("Missing"))

        val broken = josm("/load_data?data=%3Cosm%3E%3Cnode")
        assertEquals(HttpStatusCode.BadRequest, broken.status)
        assertEquals(ContentType.Text.Plain, broken.contentType()?.withoutParameters())
        assertTrue(broken.bodyAsText().startsWith("Could not parse OSM XML"))

        val api = client.get("http://localhost:${app.config.port}/api/drafts/not-a-uuid")
        assertEquals(HttpStatusCode.NotFound, api.status)
        assertEquals(ContentType.Application.Json, api.contentType()?.withoutParameters())
    }

    private companion object {
        const val RC = 8111
    }
}
