package eu.tiducto.osmreview.server.osm

import eu.tiducto.osmreview.server.OsmTargetConfig
import eu.tiducto.osmreview.shared.OsmRef
import eu.tiducto.osmreview.shared.OsmType
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
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
import io.ktor.http.isSuccess
import org.slf4j.LoggerFactory

class OsmApi(
    private val http: HttpClient,
    val target: OsmTargetConfig,
    private val userAgent: String,
) {
    private val log = LoggerFactory.getLogger(OsmApi::class.java)

    suspend fun fetch(refs: Collection<OsmRef>): Map<OsmRef, OsmElement> {
        val out = HashMap<OsmRef, OsmElement>()
        refs.filter { it.id > 0 }.distinct().groupBy { it.type }.forEach { (type, ofType) ->
            ofType.map { it.id }.chunked(BATCH).forEach { ids -> fetchBatch(type, ids, out) }
        }
        return out
    }

    suspend fun fetchOne(ref: OsmRef): OsmElement? = fetch(listOf(ref))[ref]

    suspend fun fetchFull(ref: OsmRef): List<OsmElement> {
        if (ref.type == OsmType.NODE) return listOfNotNull(fetchOne(ref))
        val resp = http.get("${target.apiUrl}/${ref.type.apiName}/${ref.id}/full") { header(HttpHeaders.UserAgent, userAgent) }
        return when {
            resp.status.isSuccess() -> OsmXml.parseElements(resp.bodyAsText())
            resp.status == HttpStatusCode.NotFound || resp.status == HttpStatusCode.Gone -> emptyList()
            else -> throw OsmApiException(resp.status, resp.bodyAsText(), "fetch $ref in full")
        }
    }

    private suspend fun fetchBatch(type: OsmType, ids: List<Long>, out: MutableMap<OsmRef, OsmElement>) {
        val plural = type.apiName + "s"
        val resp = http.get("${target.apiUrl}/$plural?$plural=${ids.joinToString(",")}") { header(HttpHeaders.UserAgent, userAgent) }
        when {
            resp.status.isSuccess() -> OsmXml.parseElements(resp.bodyAsText()).forEach { out[it.ref] = it }
            resp.status == HttpStatusCode.NotFound && ids.size == 1 -> log.debug("{} {} does not exist on {}", type, ids[0], target.target)
            resp.status == HttpStatusCode.NotFound -> ids.chunked((ids.size + 1) / 2).forEach { fetchBatch(type, it, out) }
            else -> throw OsmApiException(resp.status, resp.bodyAsText(), "fetch $plural")
        }
    }

    suspend fun userDetails(token: String): XUser {
        val resp = http.get("${target.apiUrl}/user/details") {
            header(HttpHeaders.UserAgent, userAgent)
            bearerAuth(token)
        }
        resp.ensureOk("user details")
        return OsmXml.parseUser(resp.bodyAsText())
    }

    suspend fun createChangeset(token: String, tags: Map<String, String>): Long {
        val resp = http.put("${target.apiUrl}/changeset/create") {
            header(HttpHeaders.UserAgent, userAgent)
            bearerAuth(token)
            contentType(ContentType.Text.Xml)
            setBody(OsmXml.changesetCreate(tags))
        }
        resp.ensureOk("create changeset")
        return resp.bodyAsText().trim().toLong()
    }

    suspend fun upload(token: String, changesetId: Long, osmChange: String): XDiffResult {
        val resp = http.post("${target.apiUrl}/changeset/$changesetId/upload") {
            header(HttpHeaders.UserAgent, userAgent)
            bearerAuth(token)
            contentType(ContentType.Text.Xml)
            setBody(osmChange)
        }
        resp.ensureOk("upload to changeset $changesetId")
        return OsmXml.parseDiff(resp.bodyAsText())
    }

    suspend fun closeChangeset(token: String, changesetId: Long) {
        val resp = http.put("${target.apiUrl}/changeset/$changesetId/close") {
            header(HttpHeaders.UserAgent, userAgent)
            bearerAuth(token)
        }
        resp.ensureOk("close changeset $changesetId")
    }

    private suspend fun HttpResponse.ensureOk(what: String) {
        if (!status.isSuccess()) throw OsmApiException(status, bodyAsText(), what)
    }

    companion object {
        const val BATCH = 700
    }
}

class OsmApiException(val status: HttpStatusCode, val body: String, what: String) :
    RuntimeException("OSM API $what failed: ${status.value} ${body.take(300)}")
