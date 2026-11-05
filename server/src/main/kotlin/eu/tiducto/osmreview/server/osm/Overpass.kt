package eu.tiducto.osmreview.server.osm

import eu.tiducto.osmreview.shared.LngLat
import eu.tiducto.osmreview.shared.OsmRef
import eu.tiducto.osmreview.shared.OsmType
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Overpass, used only by MCP `query_osm` with an `overpass` query. Backs off on 429. */
class Overpass(private val http: HttpClient, private val url: String, private val userAgent: String) {
    @Serializable
    private data class Resp(val elements: List<El> = emptyList())

    @Serializable
    private data class El(
        val type: String,
        val id: Long,
        val lat: Double? = null,
        val lon: Double? = null,
        val center: Center? = null,
        val tags: Map<String, String> = emptyMap(),
    )

    @Serializable
    private data class Center(val lat: Double, val lon: Double)

    @Serializable
    data class Hit(val ref: OsmRef, val tags: Map<String, String>, val center: LngLat? = null)

    private val json = Json { ignoreUnknownKeys = true }

    /** Runs [query]; adds `[out:json]` and `out tags center` when the query has no output statement. */
    suspend fun query(query: String, limit: Int): Pair<List<Hit>, Boolean> {
        val q = buildString {
            if (!query.trimStart().startsWith("[out:json")) append("[out:json][timeout:60];")
            append(query.trim())
            if (!query.contains(Regex("""\bout\b"""))) append(" out tags center ${limit + 1};")
        }
        var attempt = 0
        while (true) {
            val resp = http.submitForm(url, Parameters.build { append("data", q) }) { header(HttpHeaders.UserAgent, userAgent) }
            if (resp.status == HttpStatusCode.TooManyRequests && attempt < MAX_RETRIES) {
                delay(1000L shl attempt++)
                continue
            }
            if (!resp.status.isSuccess()) throw IllegalStateException("Overpass failed: ${resp.status} ${resp.bodyAsText().take(300)}")
            val els = json.decodeFromString(Resp.serializer(), resp.bodyAsText()).elements
            val hits = els.take(limit).map { e ->
                val c = e.center?.let { LngLat(it.lon, it.lat) } ?: if (e.lat != null && e.lon != null) LngLat(e.lon, e.lat) else null
                Hit(OsmRef(OsmType.fromApiName(e.type), e.id), e.tags, c)
            }
            return hits to (els.size > limit)
        }
    }

    private companion object {
        const val MAX_RETRIES = 4
    }
}
