package eu.tiducto.osmreview.server.replication

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class OsmApiNodeFetcher(apiUrl: String, private val userAgent: String) : NodeFetcher {
    private val api = apiUrl.trimEnd('/')
    private val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    override fun fetch(ids: LongArray): List<OscElement> =
        ids.toList().chunked(CHUNK).flatMap { chunk ->
            val (status, body) = get("nodes?nodes=" + chunk.joinToString(","))
            when (status) {
                200 -> OscDiff.parseOsm(body)
                404, 410 -> chunk.flatMap { id ->
                    val (s, b) = get("node/$id")
                    if (s == 200) OscDiff.parseOsm(b) else emptyList()
                }
                else -> throw IOException("GET $api/nodes: HTTP $status")
            }
        }

    private fun get(path: String): Pair<Int, ByteArray> {
        val req = HttpRequest.newBuilder(URI.create("$api/$path"))
            .header("User-Agent", userAgent)
            .timeout(Duration.ofSeconds(60))
            .GET()
            .build()
        val res = client.send(req, HttpResponse.BodyHandlers.ofByteArray())
        return res.statusCode() to res.body()
    }

    private companion object {
        const val CHUNK = 100
    }
}
