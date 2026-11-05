package eu.tiducto.osmreview.server.replication

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

data class ReplicationState(val sequence: Long, val timestamp: Instant) {
    fun format(): String =
        "sequenceNumber=$sequence\ntimestamp=${timestamp.toString().replace(":", "\\:")}\n"

    companion object {
        fun parse(text: String): ReplicationState {
            var seq: Long? = null
            var ts: Instant? = null
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val k = line.substringBefore('=').trim()
                val v = line.substringAfter('=', "").trim().replace("\\", "")
                when (k) {
                    "sequenceNumber" -> seq = v.toLong()
                    "timestamp" -> ts = Instant.parse(v)
                }
            }
            return ReplicationState(seq ?: error("state.txt without sequenceNumber"), ts ?: error("state.txt without timestamp"))
        }

        fun path(sequence: Long): String {
            val s = sequence.toString().padStart(9, '0')
            return "${s.substring(0, 3)}/${s.substring(3, 6)}/${s.substring(6, 9)}"
        }
    }
}

interface ReplicationSource {
    val url: String
    fun latest(): ReplicationState
    fun state(sequence: Long): ReplicationState?
    fun diff(sequence: Long): ByteArray
}

class HttpReplicationSource(baseUrl: String, private val userAgent: String) : ReplicationSource {
    override val url: String = baseUrl.trimEnd('/')
    private val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    override fun latest(): ReplicationState = ReplicationState.parse(get("state.txt")?.decodeToString() ?: throw IOException("$url/state.txt not found"))

    override fun state(sequence: Long): ReplicationState? =
        get("${ReplicationState.path(sequence)}.state.txt")?.decodeToString()?.let(ReplicationState::parse)

    override fun diff(sequence: Long): ByteArray =
        get("${ReplicationState.path(sequence)}.osc.gz") ?: throw IOException("Diff $sequence is not available yet")

    private fun get(path: String): ByteArray? {
        val req = HttpRequest.newBuilder(URI.create("$url/$path"))
            .header("User-Agent", userAgent)
            .timeout(Duration.ofSeconds(120))
            .GET()
            .build()
        val res = client.send(req, HttpResponse.BodyHandlers.ofByteArray())
        return when (res.statusCode()) {
            200 -> res.body()
            404, 403 -> null
            else -> throw IOException("GET $url/$path: HTTP ${res.statusCode()}")
        }
    }
}
