package eu.tiducto.osmreview.server.osm

import eu.tiducto.osmreview.shared.BBox
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
import java.math.BigDecimal
import java.math.RoundingMode

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

    suspend fun query(query: String, limit: Int, bbox: BBox? = null): Pair<List<Hit>, Boolean> {
        val q = buildQuery(query, limit, bbox)
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

    companion object {
        private const val MAX_RETRIES = 4
        private val SETTINGS = Regex("""^((?:\[[^\]]*\]\s*)+);""")
        private val SETTING = Regex("""\[[^\]]*\]""")
        private val OUT = Regex("""\bout\b""")

        private fun out(v: Double, mode: RoundingMode) = BigDecimal.valueOf(v).setScale(6, mode).stripTrailingZeros().toPlainString()

        fun buildQuery(query: String, limit: Int, bbox: BBox? = null): String {
            val q = query.trim()
            val m = SETTINGS.find(q)
            val settings = m?.groupValues?.get(1)?.let { s -> SETTING.findAll(s).map { it.value }.toMutableList() } ?: mutableListOf()
            val body = (if (m != null) q.substring(m.range.last + 1) else q).trim()
            settings.removeAll { it.startsWith("[out:") || it.startsWith("[bbox:") }
            if (settings.none { it.startsWith("[timeout:") }) settings += "[timeout:60]"
            return buildString {
                append("[out:json]")
                settings.forEach(::append)
                if (bbox != null) {
                    append("[bbox:${out(bbox.south, RoundingMode.FLOOR)},${out(bbox.west, RoundingMode.FLOOR)},")
                    append("${out(bbox.north, RoundingMode.CEILING)},${out(bbox.east, RoundingMode.CEILING)}]")
                }
                append(";")
                append(body)
                if (!OUT.containsMatchIn(body)) append(" out tags center ${limit + 1};")
            }
        }
    }
}
