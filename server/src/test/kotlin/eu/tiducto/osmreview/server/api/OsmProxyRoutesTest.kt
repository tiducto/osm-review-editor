package eu.tiducto.osmreview.server.api

import eu.tiducto.osmreview.server.OsmTargetConfig
import eu.tiducto.osmreview.server.osm.OsmApi
import eu.tiducto.osmreview.shared.ApiError
import eu.tiducto.osmreview.shared.ApiErrorCodes
import eu.tiducto.osmreview.shared.OsmTarget
import eu.tiducto.osmreview.shared.WireJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OsmProxyRoutesTest {
    private val target = OsmTargetConfig(OsmTarget.DEV, "https://dev.example", "https://api.dev.example/api/0.6", null, null)
    private val requests = CopyOnWriteArrayList<HttpRequestData>()

    private fun proxy(
        maxBytes: Long = OsmApi.MAP_MAX_BYTES,
        handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
        block: suspend (suspend (String) -> HttpResponse) -> Unit,
    ) = testApplication {
        val http = HttpClient(
            MockEngine { req ->
                requests += req
                handler(req)
            },
        ) { expectSuccess = false }
        val api = OsmApi(http, target, "OSMReviewEditor/test (+me@example.org)")
        application {
            install(ContentNegotiation) { json(WireJson) }
            routing { osmProxyRoutes(maxBytes) { api } }
        }
        block { path -> client.get(path) }
    }

    private suspend fun HttpResponse.error(): ApiError = WireJson.decodeFromString(ApiError.serializer(), bodyAsText())

    @Test
    fun passesXmlThrough() = proxy(handler = { respond("<osm version=\"0.6\"><node id=\"1\"/></osm>", headers = headersOf(HttpHeaders.ContentType, "text/xml")) }) { get ->
        val resp = get("/api/osm/map?bbox=17.9,49.9,17.91,49.905")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.contentType()!!.match(ContentType.Application.Xml))
        assertEquals("<osm version=\"0.6\"><node id=\"1\"/></osm>", resp.bodyAsText())
        val req = requests.single()
        assertEquals("https://api.dev.example/api/0.6/map?bbox=17.9,49.9,17.91,49.905", req.url.toString())
        assertEquals("OSMReviewEditor/test (+me@example.org)", req.headers[HttpHeaders.UserAgent])
        assertNull(req.headers[HttpHeaders.Authorization])
    }

    @Test
    fun formatsSmallCoordinatesPlainly() = proxy(handler = { respond("<osm/>") }) { get ->
        assertEquals(HttpStatusCode.OK, get("/api/osm/map?bbox=-0.0001,-0.0002,0.0001,0.0002").status)
        assertEquals("bbox=-0.0001,-0.0002,0.0001,0.0002", requests.single().url.encodedQuery)
    }

    @Test
    fun rejectsInvalidBboxes() = proxy(handler = { respond("<osm/>") }) { get ->
        for (q in listOf("", "?bbox=1,2,3", "?bbox=a,b,c,d", "?bbox=17.9,49.9,17.8,50", "?bbox=17.9,49.9,18,49.8", "?bbox=179.9,0,180.1,0.1", "?bbox=0,89.9,0.1,90.5", "?bbox=NaN,0,1,1", "?bbox=0,0,Infinity,1")) {
            val resp = get("/api/osm/map$q")
            assertEquals(HttpStatusCode.BadRequest, resp.status, q)
            assertTrue("bbox" in resp.error().detail!!, q)
        }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun refusesLargeAreasWithoutCallingOsm() = proxy(handler = { respond("<osm/>") }) { get ->
        val resp = get("/api/osm/map?bbox=17,49,17.6,49.5")
        assertEquals(HttpStatusCode.PayloadTooLarge, resp.status)
        assertEquals(ApiErrorCodes.AREA_TOO_LARGE, resp.error().code)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun mapsTooManyNodes() = proxy(handler = { respond("You requested too many nodes (limit is 50000). Either request a smaller area, or use planet.osm", HttpStatusCode.BadRequest) }) { get ->
        val resp = get("/api/osm/map?bbox=17.9,49.9,18.0,50.0")
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        val e = resp.error()
        assertEquals(ApiErrorCodes.OSM_API_ERROR, e.code)
        assertTrue("You requested too many nodes (limit is 50000)" in e.detail!!, e.detail)
    }

    @Test
    fun mapsRateLimits() {
        proxy(handler = { respond("Bandwidth limit exceeded", HttpStatusCode(509, "Bandwidth Limit Exceeded")) }) { get ->
            val resp = get("/api/osm/map?bbox=17.9,49.9,18.0,50.0")
            assertEquals(HttpStatusCode.ServiceUnavailable, resp.status)
            assertTrue("Bandwidth limit exceeded" in resp.error().detail!!)
        }
        proxy(handler = { respond("Slow down", HttpStatusCode.TooManyRequests) }) { get ->
            assertEquals(HttpStatusCode.TooManyRequests, get("/api/osm/map?bbox=17.9,49.9,18.0,50.0").status)
        }
        proxy(handler = { respond("oops", HttpStatusCode.InternalServerError) }) { get ->
            assertEquals(HttpStatusCode.BadGateway, get("/api/osm/map?bbox=17.9,49.9,18.0,50.0").status)
        }
    }

    @Test
    fun networkFailureIsBadGateway() = proxy(handler = { throw IOException("connection refused") }) { get ->
        val resp = get("/api/osm/map?bbox=17.9,49.9,18.0,50.0")
        assertEquals(HttpStatusCode.BadGateway, resp.status)
        assertTrue("connection refused" in resp.error().detail!!)
    }

    @Test
    fun refusesOversizeResponses() = proxy(maxBytes = 1000, handler = { respond("<osm>" + "x".repeat(5000) + "</osm>") }) { get ->
        val resp = get("/api/osm/map?bbox=17.9,49.9,18.0,50.0")
        assertEquals(HttpStatusCode.PayloadTooLarge, resp.status)
        val e = resp.error()
        assertEquals(ApiErrorCodes.AREA_TOO_LARGE, e.code)
        assertTrue("zoom in" in e.detail!!, e.detail)
    }
}
