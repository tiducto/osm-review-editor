package eu.tiducto.osmreview.server.core

import eu.tiducto.osmreview.server.osm.OsmChangeXml
import eu.tiducto.osmreview.server.osm.XMember
import eu.tiducto.osmreview.shared.ChangeAction
import eu.tiducto.osmreview.shared.OsmRef
import eu.tiducto.osmreview.shared.OsmType
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent

class FakeOsm {
    data class El(
        val type: String,
        val id: Long,
        var version: Int,
        var tags: Map<String, String>,
        var lat: String = "49.9",
        var lon: String = "17.9",
        var nodes: List<Long> = emptyList(),
        var members: List<XMember> = emptyList(),
        var visible: Boolean = true,
    )

    private val els = linkedMapOf(
        "way/55" to El("way", 55, 3, mapOf("highway" to "footway"), nodes = listOf(1, 2, 3, 1)),
        "node/101" to El("node", 101, 4, mapOf("highway" to "crossing", "crossing_ref" to "zebra")),
        "node/102" to El("node", 102, 1, mapOf("highway" to "crossing")),
        "node/1" to El("node", 1, 1, emptyMap(), "49.90", "17.90"),
        "node/2" to El("node", 2, 1, emptyMap(), "49.91", "17.92"),
        "node/3" to El("node", 3, 1, emptyMap(), "49.92", "17.91"),
        "relation/7" to El("relation", 7, 2, mapOf("type" to "route"), members = listOf(XMember("way", 55, ""))),
        "node/11" to El("node", 11, 1, emptyMap(), "49.930", "17.930"),
        "node/12" to El("node", 12, 1, emptyMap(), "49.931", "17.932"),
        "node/13" to El("node", 13, 2, emptyMap(), "49.932", "17.934"),
        "node/14" to El("node", 14, 1, mapOf("barrier" to "gate"), "49.933", "17.936"),
        "way/60" to El("way", 60, 5, mapOf("highway" to "residential", "name" to "Dlouhá"), nodes = listOf(11, 12, 13, 14)),
        "relation/8" to El("relation", 8, 1, mapOf("type" to "route", "route" to "bus"), members = listOf(XMember("way", 60, ""))),
        "node/21" to El("node", 21, 3, mapOf("amenity" to "bench"), "49.940", "17.940"),
    )
    val uploads = mutableListOf<String>()
    val closed = mutableListOf<Long>()
    val changesetTags = mutableListOf<String>()
    var raceOnNextUpload: Map<String, String>? = null
    private var nextChangeset = 1001L
    private val nextId = mutableMapOf("node" to 9000L, "way" to 9000L, "relation" to 9000L)

    private fun el(r: OsmRef) = els.getValue("${r.type.apiName}/${r.id}")

    fun version(r: OsmRef) = el(r).version
    fun tags(r: OsmRef) = el(r).tags
    fun nodes(r: OsmRef) = el(r).nodes
    fun members(r: OsmRef) = el(r).members
    fun location(r: OsmRef) = el(r).lat to el(r).lon
    fun visible(r: OsmRef) = els["${r.type.apiName}/${r.id}"]?.visible ?: false
    fun exists(r: OsmRef) = "${r.type.apiName}/${r.id}" in els

    fun edit(r: OsmRef, set: Map<String, String>) {
        val e = el(r)
        e.tags = e.tags + set
        e.version++
    }

    fun move(r: OsmRef, lat: String, lon: String) {
        val e = el(r)
        e.lat = lat
        e.lon = lon
        e.version++
    }

    fun setNodes(r: OsmRef, nodes: List<Long>) {
        val e = el(r)
        e.nodes = nodes
        e.version++
    }

    private fun xml(list: List<El>) = "<osm version=\"0.6\">" + list.joinToString("") { e ->
        val coords = if (e.type == "node" && e.visible) """ lat="${e.lat}" lon="${e.lon}"""" else ""
        """<${e.type} id="${e.id}" version="${e.version}" visible="${e.visible}"$coords>""" +
            e.nodes.joinToString("") { """<nd ref="$it"/>""" } +
            e.members.joinToString("") { """<member type="${it.type}" ref="${it.ref}" role="${it.role}"/>""" } +
            e.tags.entries.joinToString("") { """<tag k="${it.key}" v="${it.value}"/>""" } + "</${e.type}>"
    } + "</osm>"

    private fun full(e: El): List<El> = listOf(e) + when (e.type) {
        "way" -> e.nodes.mapNotNull { els["node/$it"] }.distinct()
        "relation" -> e.members.mapNotNull { els["${it.type}/${it.ref}"] }.flatMap { full(it) }
        else -> emptyList()
    }

    fun handle(scope: MockRequestHandleScope, req: HttpRequestData): HttpResponseData = with(scope) {
        val path = req.url.encodedPath
        val body = (req.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
        when {
            path.endsWith("/oauth2/token") -> respond("""{"access_token":"tok","token_type":"Bearer"}""", headers = io.ktor.http.headersOf("Content-Type", "application/json"))
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
        val entries = OsmChangeXml.parse(body)
        raceOnNextUpload?.let { race ->
            raceOnNextUpload = null
            entries.filter { it.element.id > 0 }.forEach { edit(it.element.ref, race) }
            return respond("Version mismatch", HttpStatusCode.Conflict)
        }
        val state = LinkedHashMap<String, El>()
        els.forEach { (k, v) -> state[k] = v.copy() }
        val placeholders = HashMap<String, Long>()
        val diff = StringBuilder()
        fun real(type: String, id: Long): Long = if (id < 0) placeholders["$type/$id"] ?: error("unknown placeholder $type/$id") else id
        fun usedBy(type: String, id: Long): Boolean = state.values.any { e ->
            e.visible && ((type == "node" && e.type == "way" && id in e.nodes) || e.members.any { it.type == type && it.ref == id })
        }
        for (entry in entries) {
            val s = entry.element
            val type = s.type.apiName
            when (entry.action) {
                ChangeAction.CREATE -> {
                    val id = nextId.getValue(type).also { nextId[type] = it + 1 }
                    placeholders["$type/${s.id}"] = id
                    state["$type/$id"] = El(
                        type, id, 1, s.tags, s.lat ?: "0", s.lon ?: "0",
                        s.nodeIds.map { real("node", it) },
                        s.members.map { it.copy(ref = real(it.type, it.ref)) },
                    )
                    diff.append("""<$type old_id="${s.id}" new_id="$id" new_version="1"/>""")
                }
                ChangeAction.MODIFY -> {
                    val e = state["$type/${s.id}"] ?: return respond("Not found", HttpStatusCode.NotFound)
                    if (e.version != s.version) return respond("Version mismatch", HttpStatusCode.Conflict)
                    if (s.type == OsmType.WAY && s.nodeIds.any { n -> state["node/${real("node", n)}"]?.visible != true }) {
                        return respond("Way ${s.id} requires missing nodes", HttpStatusCode.PreconditionFailed)
                    }
                    e.tags = s.tags
                    if (s.type == OsmType.NODE) {
                        e.lat = s.lat!!
                        e.lon = s.lon!!
                    }
                    if (s.type == OsmType.WAY) e.nodes = s.nodeIds.map { real("node", it) }
                    if (s.type == OsmType.RELATION) e.members = s.members.map { it.copy(ref = real(it.type, it.ref)) }
                    e.version++
                    diff.append("""<$type old_id="${s.id}" new_id="${s.id}" new_version="${e.version}"/>""")
                }
                ChangeAction.DELETE -> {
                    val e = state["$type/${s.id}"] ?: return respond("Not found", HttpStatusCode.NotFound)
                    if (e.version != s.version) return respond("Version mismatch", HttpStatusCode.Conflict)
                    if (usedBy(type, s.id)) {
                        if (!entry.ifUnused) return respond("$type ${s.id} is still used", HttpStatusCode.PreconditionFailed)
                        diff.append("""<$type old_id="${s.id}" new_id="${s.id}" new_version="${e.version}"/>""")
                    } else {
                        e.visible = false
                        e.version++
                        diff.append("""<$type old_id="${s.id}"/>""")
                    }
                }
            }
        }
        els.clear()
        els.putAll(state)
        return respond("<diffResult>$diff</diffResult>")
    }
}
