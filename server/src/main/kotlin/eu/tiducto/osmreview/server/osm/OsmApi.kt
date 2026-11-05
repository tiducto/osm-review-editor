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

/**
 * OSM API v0.6 for one target. Reads are batched multi-fetches; writes need a bearer token.
 * Live fetches happen only for objects being proposed or uploaded; browsing and rules use the GOL.
 */
class OsmApi(
    private val http: HttpClient,
    val target: OsmTargetConfig,
    private val userAgent: String,
) {
    private val log = LoggerFactory.getLogger(OsmApi::class.java)

    /**
     * Current versions of [refs]. Deleted objects come back with `visible=false`; objects that never
     * existed on this target are absent from the map. Batches of [BATCH] IDs per call; a 404 (the
     * API refuses the whole batch if one ID never existed) splits the batch until the culprit is found.
     */
    suspend fun fetch(refs: Collection<OsmRef>): Map<OsmRef, OsmElement> {
        val out = HashMap<OsmRef, OsmElement>()
        refs.filter { it.id > 0 }.distinct().groupBy { it.type }.forEach { (type, ofType) ->
            ofType.map { it.id }.chunked(BATCH).forEach { ids -> fetchBatch(type, ids, out) }
        }
        return out
    }

    suspend fun fetchOne(ref: OsmRef): OsmElement? = fetch(listOf(ref))[ref]

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

    /** Display name and id of the token's account (`GET /user/details`). */
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

    /** Uploads an osmChange document. Throws [OsmApiException] with status 409 on version conflicts. */
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
        /** Multi-fetch IDs per call; keeps URLs well under common 8 KB limits. */
        const val BATCH = 700
    }
}

class OsmApiException(val status: HttpStatusCode, val body: String, what: String) :
    RuntimeException("OSM API $what failed: ${status.value} ${body.take(300)}")
