package eu.tiducto.osmreview.server.core

import eu.tiducto.osmreview.server.osm.OsmXml
import eu.tiducto.osmreview.shared.OsmRef
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf

class FakeOsm {
    private data class El(
        val type: String,
        val id: Long,
        var version: Int,
        var tags: Map<String, String>,
        val extra: String,
        val lat: String = "49.9",
        val lon: String = "17.9",
    )

    private val els = mutableMapOf(
        "way/55" to El("way", 55, 3, mapOf("highway" to "footway"), """<nd ref="1"/><nd ref="2"/><nd ref="3"/><nd ref="1"/>"""),
        "node/101" to El("node", 101, 4, mapOf("highway" to "crossing", "crossing_ref" to "zebra"), ""),
        "node/102" to El("node", 102, 1, mapOf("highway" to "crossing"), ""),
        "node/1" to El("node", 1, 1, emptyMap(), "", "49.90", "17.90"),
        "node/2" to El("node", 2, 1, emptyMap(), "", "49.91", "17.92"),
        "node/3" to El("node", 3, 1, emptyMap(), "", "49.92", "17.91"),
        "relation/7" to El("relation", 7, 2, mapOf("type" to "route"), """<member type="way" ref="55" role=""/>"""),
    )
    val uploads = mutableListOf<String>()
    val closed = mutableListOf<Long>()
    val changesetTags = mutableListOf<String>()
    var raceOnNextUpload: Map<String, String>? = null
    private var nextChangeset = 1001L

    fun version(r: OsmRef) = els.getValue("${r.type.apiName}/${r.id}").version
    fun tags(r: OsmRef) = els.getValue("${r.type.apiName}/${r.id}").tags
    fun nodes(r: OsmRef) = OsmXml.parseElements(xml(listOf(els.getValue("${r.type.apiName}/${r.id}")))).single().nodeIds

    fun edit(r: OsmRef, set: Map<String, String>) {
        val e = els.getValue("${r.type.apiName}/${r.id}")
        e.tags = e.tags + set
        e.version++
    }

    private fun xml(list: List<El>) = "<osm version=\"0.6\">" + list.joinToString("") { e ->
        val coords = if (e.type == "node") """ lat="${e.lat}" lon="${e.lon}"""" else ""
        """<${e.type} id="${e.id}" version="${e.version}" visible="true"$coords>${e.extra}""" +
            e.tags.entries.joinToString("") { """<tag k="${it.key}" v="${it.value}"/>""" } + "</${e.type}>"
    } + "</osm>"

    private fun full(e: El): List<El> = listOf(e) + when (e.type) {
        "way" -> Regex("""<nd ref="(\d+)"/>""").findAll(e.extra).mapNotNull { els["node/${it.groupValues[1]}"] }.distinct().toList()
        "relation" -> Regex("""<member type="(\w+)" ref="(\d+)"""").findAll(e.extra)
            .mapNotNull { els["${it.groupValues[1]}/${it.groupValues[2]}"] }.flatMap { full(it) }.toList()
        else -> emptyList()
    }

    fun handle(scope: MockRequestHandleScope, req: HttpRequestData): HttpResponseData = with(scope) {
        val path = req.url.encodedPath
        val body = (req.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
        when {
            path.endsWith("/oauth2/token") -> respond("""{"access_token":"tok","token_type":"Bearer"}""", headers = headersOf("Content-Type", "application/json"))
            path.endsWith("/user/details") -> respond("""<osm><user id="1" display_name="tester"/></osm>""")
            path.endsWith("/changeset/create") -> {
                changesetTags += body
                respond((nextChangeset++).toString())
            }
            path.endsWith("/close") -> {
                closed += path.split("/").dropLast(1).last().toLong()
                respond("")
            }
            path.endsWith("/upload") -> upload(body)
            req.method == HttpMethod.Get && path.endsWith("/full") -> {
                val parts = path.split("/")
                val e = els["${parts[parts.size - 3]}/${parts[parts.size - 2]}"]
                if (e == null) respond("", HttpStatusCode.NotFound) else respond(xml(full(e).distinct()))
            }
            req.method == HttpMethod.Get -> {
                val type = path.substringAfterLast("/").removeSuffix("s")
                val ids = req.url.parameters["${type}s"]!!.split(",")
                val found = ids.mapNotNull { els["$type/$it"] }
                if (found.size < ids.size) respond("", HttpStatusCode.NotFound) else respond(xml(found))
            }
            else -> respond("unexpected $path", HttpStatusCode.BadRequest)
        }
    }

    private fun MockRequestHandleScope.upload(body: String): HttpResponseData {
        uploads += body
        val sent = OsmXml.parseElements(body.replace("osmChange", "osm").replace(Regex("</?(modify|create|delete)[^>]*>"), ""))
        raceOnNextUpload?.let { race ->
            raceOnNextUpload = null
            sent.forEach { edit(it.ref, race) }
            return respond("Version mismatch", HttpStatusCode.Conflict)
        }
        sent.forEach { s ->
            val e = els.getValue("${s.type.apiName}/${s.id}")
            if (e.version != s.version) return respond("Version mismatch", HttpStatusCode.Conflict)
        }
        val diff = sent.joinToString("") { s ->
            val e = els.getValue("${s.type.apiName}/${s.id}")
            e.tags = s.tags
            e.version++
            """<${e.type} old_id="${e.id}" new_id="${e.id}" new_version="${e.version}"/>"""
        }
        return respond("<diffResult>$diff</diffResult>")
    }
}
