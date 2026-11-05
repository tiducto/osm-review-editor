package eu.tiducto.osmreview.server.api

import eu.tiducto.osmreview.server.App
import eu.tiducto.osmreview.server.Config
import eu.tiducto.osmreview.server.core.FakeOsm
import eu.tiducto.osmreview.server.module
import eu.tiducto.osmreview.shared.ApiError
import eu.tiducto.osmreview.shared.ApiErrorCodes
import eu.tiducto.osmreview.shared.ChangeStatus
import eu.tiducto.osmreview.shared.CreateDraftRequest
import eu.tiducto.osmreview.shared.Draft
import eu.tiducto.osmreview.shared.DraftFromOsmChangeRequest
import eu.tiducto.osmreview.shared.OsmChangeImportRequest
import eu.tiducto.osmreview.shared.OsmChangeImportResult
import eu.tiducto.osmreview.shared.OsmRef
import eu.tiducto.osmreview.shared.OsmType
import eu.tiducto.osmreview.shared.WireJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OsmChangeImportApiTest {
    private val docker = runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
    private lateinit var pg: PostgreSQLContainer
    private lateinit var app: App
    private val osm = FakeOsm()

    private val bench = OsmRef(OsmType.NODE, 21)
    private val benchModify =
        """<?xml version="1.0" encoding="UTF-8"?><osmChange version="0.6" generator="iD"><create/>""" +
            """<modify><node id="21" version="3" lat="49.940" lon="17.940"><tag k="amenity" v="bench"/><tag k="backrest" v="yes"/></node></modify>""" +
            """<delete if-unused="true"/></osmChange>"""

    @BeforeTest
    fun setUp() {
        if (!docker) return
        pg = PostgreSQLContainer("postgres:18").also { it.start() }
        val config = Config.fromEnv(
            mapOf(
                "DB_URL" to pg.jdbcUrl,
                "DB_USER" to pg.username,
                "DB_PASSWORD" to pg.password,
                "PORT" to "80",
                "DATA_DIR" to Files.createTempDirectory("gol").toString(),
                "RULES_DIR" to Files.createTempDirectory("rules").toString(),
                "OSM_CLIENT_ID_DEV" to "client",
                "TOKEN_ENCRYPTION_KEY" to "k",
            ),
        )
        app = App(config, HttpClient(MockEngine { req -> osm.handle(this, req) }))
        runBlocking {
            val state = Regex("state=([^&]+)").find(app.account.authorizeUrl())!!.groupValues[1]
            app.account.completeAuth("code", state)
        }
    }

    @AfterTest
    fun tearDown() {
        if (!docker) return
        app.close()
        pg.stop()
    }

    private fun api(block: suspend ApplicationTestBuilder.() -> Unit) {
        if (!docker) return println("Docker not available, skipping")
        testApplication {
            application { module(app) }
            block()
        }
    }

    private suspend fun <T> ApplicationTestBuilder.postJson(path: String, body: T, serializer: KSerializer<T>): HttpResponse =
        client.post(path) {
            contentType(ContentType.Application.Json)
            setBody(WireJson.encodeToString(serializer, body))
        }

    private suspend fun <T> HttpResponse.decode(serializer: KSerializer<T>): T = WireJson.decodeFromString(serializer, bodyAsText())

    private suspend fun ApplicationTestBuilder.firstDraft(): OsmChangeImportResult {
        val r = postJson("/api/drafts/from-osmchange", DraftFromOsmChangeRequest("Bench backrest", benchModify), DraftFromOsmChangeRequest.serializer())
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        return r.decode(OsmChangeImportResult.serializer())
    }

    private fun assertOpenElsewhere(error: ApiError, first: OsmChangeImportResult) {
        assertEquals(ApiErrorCodes.OPEN_CHANGE_ELSEWHERE, error.code)
        assertTrue(error.detail.orEmpty().contains(first.draft.draft.id), error.detail)
        val open = assertNotNull(error.openChanges).single()
        assertEquals(bench, open.ref)
        assertEquals(first.draft.changes.single().id, open.changeId)
        assertEquals(ChangeStatus.PENDING, open.status)
        assertEquals(first.draft.draft.id, open.draftId)
        assertEquals("Bench backrest", open.draftTitle)
    }

    @Test
    fun draftFromOsmChangeReportsOpenChangeElsewhereAndTakesOver() = api {
        val first = firstDraft()

        val refused = postJson("/api/drafts/from-osmchange", DraftFromOsmChangeRequest("Second", benchModify), DraftFromOsmChangeRequest.serializer())
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertOpenElsewhere(refused.decode(ApiError.serializer()), first)
        assertTrue(app.repo.listDrafts().none { it.title == "Second" }, "a refused import leaves no draft behind")

        val taken = postJson(
            "/api/drafts/from-osmchange", DraftFromOsmChangeRequest("Second", benchModify, takeOver = true), DraftFromOsmChangeRequest.serializer(),
        )
        assertEquals(HttpStatusCode.Created, taken.status, taken.bodyAsText())
        val second = taken.decode(OsmChangeImportResult.serializer())
        assertEquals(ChangeStatus.PENDING, second.draft.changes.single().status)
        assertEquals(ChangeStatus.REJECTED, app.drafts.detail(first.draft.draft.id).changes.single().status)
    }

    @Test
    fun importIntoDraftReportsOpenChangeElsewhereAndTakesOver() = api {
        val first = firstDraft()
        val created = postJson("/api/drafts", CreateDraftRequest("Target"), CreateDraftRequest.serializer())
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val target = created.decode(Draft.serializer())

        val refused = postJson("/api/drafts/${target.id}/osmchange", OsmChangeImportRequest(benchModify), OsmChangeImportRequest.serializer())
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertOpenElsewhere(refused.decode(ApiError.serializer()), first)

        val xml = client.post("/api/drafts/${target.id}/osmchange") {
            contentType(ContentType.Application.Xml)
            setBody(benchModify)
        }
        assertEquals(HttpStatusCode.BadRequest, xml.status)
        assertOpenElsewhere(xml.decode(ApiError.serializer()), first)
        assertTrue(app.drafts.detail(target.id).changes.isEmpty())

        val taken = postJson("/api/drafts/${target.id}/osmchange", OsmChangeImportRequest(benchModify, takeOver = true), OsmChangeImportRequest.serializer())
        assertEquals(HttpStatusCode.OK, taken.status, taken.bodyAsText())
        val change = taken.decode(OsmChangeImportResult.serializer()).draft.changes.single()
        assertEquals(bench, change.ref)
        assertEquals("yes", change.newTags["backrest"])
        val dropped = app.drafts.detail(first.draft.draft.id).changes.single()
        assertEquals(ChangeStatus.REJECTED, dropped.status)
        assertTrue(dropped.reviews.last().comment.orEmpty().contains(target.id))
    }

    @Test
    fun xmlImportTakesOverWithQueryParameter() = api {
        val first = firstDraft()
        val target = postJson("/api/drafts", CreateDraftRequest("Target"), CreateDraftRequest.serializer()).decode(Draft.serializer())
        val r = client.post("/api/drafts/${target.id}/osmchange?takeOver=true") {
            contentType(ContentType.Application.Xml)
            setBody(benchModify)
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals(bench, r.decode(OsmChangeImportResult.serializer()).draft.changes.single().ref)
        assertEquals(ChangeStatus.REJECTED, app.drafts.detail(first.draft.draft.id).changes.single().status)
    }
}
