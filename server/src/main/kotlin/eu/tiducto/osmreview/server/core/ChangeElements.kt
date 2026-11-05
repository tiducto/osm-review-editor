package eu.tiducto.osmreview.server.core

import eu.tiducto.osmreview.server.osm.ChangeBlock
import eu.tiducto.osmreview.server.osm.OsmChangeXml
import eu.tiducto.osmreview.server.osm.OsmElement
import eu.tiducto.osmreview.server.osm.XMember
import eu.tiducto.osmreview.shared.Change
import eu.tiducto.osmreview.shared.ChangeAction
import eu.tiducto.osmreview.shared.ElementShape
import eu.tiducto.osmreview.shared.LngLat
import eu.tiducto.osmreview.shared.OsmRef
import eu.tiducto.osmreview.shared.OsmType
import eu.tiducto.osmreview.shared.RelationMember
import eu.tiducto.osmreview.shared.withoutPseudoTags
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object ChangeElements {
    val OsmElement.location: LngLat?
        get() = if (lat != null && lon != null) LngLat(lon.toDouble(), lat.toDouble()) else null

    fun shapeOf(e: OsmElement): ElementShape = when (e.type) {
        OsmType.NODE -> ElementShape(location = e.location)
        OsmType.WAY -> ElementShape(nodes = e.nodeIds)
        OsmType.RELATION -> ElementShape(members = e.members.map { RelationMember(OsmType.fromApiName(it.type), it.ref, it.role) })
    }

    fun members(list: List<RelationMember>): List<XMember> = list.map { XMember(it.type.apiName, it.ref, it.role) }

    fun element(c: Change, live: OsmElement?, useLiveVersion: Boolean): OsmElement? {
        val tags = c.newTags.withoutPseudoTags()
        val version = if (useLiveVersion && live != null) live.version else c.baseVersion ?: live?.version ?: 0
        return when (c.action) {
            ChangeAction.CREATE -> {
                val s = c.newShape
                val loc = s?.location ?: c.centroid
                if (c.ref.type == OsmType.NODE && loc == null) return null
                OsmElement(
                    c.ref.type, c.ref.id, 0, true, tags,
                    lat = loc?.takeIf { c.ref.type == OsmType.NODE }?.let { OsmChangeXml.coord(it.lat) },
                    lon = loc?.takeIf { c.ref.type == OsmType.NODE }?.let { OsmChangeXml.coord(it.lng) },
                    nodeIds = s?.nodes.orEmpty(),
                    members = members(s?.members.orEmpty()),
                )
            }
            ChangeAction.MODIFY -> {
                val s = c.newShape ?: return live?.copy(tags = tags, version = version)
                val b = c.baseShape
                val loc = s.location ?: b?.location ?: live?.location
                val nodes = s.nodes ?: b?.nodes ?: live?.nodeIds
                val mems = s.members ?: b?.members ?: live?.let { shapeOf(it).members }
                when (c.ref.type) {
                    OsmType.NODE -> if (loc == null) return null
                    OsmType.WAY -> if (nodes == null) return null
                    OsmType.RELATION -> if (mems == null) return null
                }
                OsmElement(
                    c.ref.type, c.ref.id, version, true, tags,
                    lat = loc?.takeIf { c.ref.type == OsmType.NODE }?.let { OsmChangeXml.coord(it.lat) },
                    lon = loc?.takeIf { c.ref.type == OsmType.NODE }?.let { OsmChangeXml.coord(it.lng) },
                    nodeIds = if (c.ref.type == OsmType.WAY) nodes.orEmpty() else emptyList(),
                    members = if (c.ref.type == OsmType.RELATION) members(mems.orEmpty()) else emptyList(),
                )
            }
            ChangeAction.DELETE -> {
                val loc = c.baseShape?.location ?: live?.location
                OsmElement(
                    c.ref.type, c.ref.id, version, true, emptyMap(),
                    lat = loc?.takeIf { c.ref.type == OsmType.NODE }?.let { OsmChangeXml.coord(it.lat) },
                    lon = loc?.takeIf { c.ref.type == OsmType.NODE }?.let { OsmChangeXml.coord(it.lng) },
                )
            }
        }
    }

    fun blocks(items: List<Pair<Change, OsmElement>>): List<ChangeBlock> {
        fun of(action: ChangeAction, type: OsmType) = items.filter { it.first.action == action && it.second.type == type }
        val createdRelations = of(ChangeAction.CREATE, OsmType.RELATION)
        val creates = of(ChangeAction.CREATE, OsmType.NODE) + of(ChangeAction.CREATE, OsmType.WAY) + relationOrder(createdRelations)
        val modifies = of(ChangeAction.MODIFY, OsmType.NODE) + of(ChangeAction.MODIFY, OsmType.WAY) + of(ChangeAction.MODIFY, OsmType.RELATION)
        val deletes = of(ChangeAction.DELETE, OsmType.RELATION) + of(ChangeAction.DELETE, OsmType.WAY) + of(ChangeAction.DELETE, OsmType.NODE)
        val out = ArrayList<ChangeBlock>()
        if (creates.isNotEmpty()) out += ChangeBlock(ChangeAction.CREATE, creates.map { it.second })
        if (modifies.isNotEmpty()) out += ChangeBlock(ChangeAction.MODIFY, modifies.map { it.second })
        var run = ArrayList<OsmElement>()
        var flag: Boolean? = null
        for ((c, e) in deletes) {
            if (flag != null && flag != c.ifUnused) {
                out += ChangeBlock(ChangeAction.DELETE, run, flag)
                run = ArrayList()
            }
            flag = c.ifUnused
            run += e
        }
        if (run.isNotEmpty()) out += ChangeBlock(ChangeAction.DELETE, run, flag == true)
        return out
    }

    private fun relationOrder(list: List<Pair<Change, OsmElement>>): List<Pair<Change, OsmElement>> {
        val byId = list.associateBy { it.second.id }
        val done = LinkedHashSet<Long>()
        val visiting = HashSet<Long>()
        fun visit(id: Long) {
            if (id in done || !visiting.add(id)) return
            byId.getValue(id).second.members.filter { it.type == "relation" && it.ref in byId }.forEach { visit(it.ref) }
            done += id
        }
        list.forEach { visit(it.second.id) }
        return done.map { byId.getValue(it) }
    }

    fun point(p: LngLat): JsonObject = buildJsonObject {
        put("type", "Point")
        put("coordinates", JsonArray(listOf(JsonPrimitive(p.lng), JsonPrimitive(p.lat))))
    }

    fun line(points: List<LngLat>): JsonObject? = if (points.size < 2) {
        null
    } else {
        buildJsonObject {
            put("type", "LineString")
            put("coordinates", JsonArray(points.map { JsonArray(listOf(JsonPrimitive(it.lng), JsonPrimitive(it.lat))) }))
        }
    }

    fun geometry(type: OsmType, shape: ElementShape?, coords: Map<Long, LngLat>): JsonObject? = when (type) {
        OsmType.NODE -> shape?.location?.let(::point)
        OsmType.WAY -> shape?.nodes?.let { ids -> line(ids.mapNotNull { coords[it] }) }
        OsmType.RELATION -> null
    }

    fun centroid(g: JsonElement?): LngLat? {
        val coords = (g as? JsonObject)?.get("coordinates") ?: return null
        val pts = ArrayList<LngLat>()
        fun walk(e: JsonElement) {
            if (e is JsonArray && e.size >= 2 && e[0] is JsonPrimitive) {
                pts += LngLat((e[0] as JsonPrimitive).content.toDouble(), (e[1] as JsonPrimitive).content.toDouble())
            } else if (e is JsonArray) {
                e.forEach(::walk)
            }
        }
        runCatching { walk(coords) }
        if (pts.isEmpty()) return null
        return LngLat((pts.minOf { it.lng } + pts.maxOf { it.lng }) / 2, (pts.minOf { it.lat } + pts.maxOf { it.lat }) / 2)
    }

    fun sameLocation(a: LngLat?, b: LngLat?): Boolean =
        a == b || (a != null && b != null && kotlin.math.abs(a.lat - b.lat) < 5e-8 && kotlin.math.abs(a.lng - b.lng) < 5e-8)

    fun refOf(m: RelationMember) = OsmRef(m.type, m.ref)
}
