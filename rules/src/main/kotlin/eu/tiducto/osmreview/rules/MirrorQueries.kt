package eu.tiducto.osmreview.rules

import com.geodesk.feature.Feature
import com.geodesk.feature.FeatureLibrary
import com.geodesk.feature.Filter
import com.geodesk.geom.Box
import eu.tiducto.osmreview.shared.Area
import eu.tiducto.osmreview.shared.BBox
import eu.tiducto.osmreview.shared.FeatureCollection
import eu.tiducto.osmreview.shared.MapFeature
import eu.tiducto.osmreview.shared.OsmRef
import eu.tiducto.osmreview.shared.OsmType
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicReference

class MirrorQueries(private val mirror: GolMirror) {

    fun features(bbox: BBox, limit: Int): FeatureCollection = mirror.query { gol ->
        val out = ArrayList<MapFeature>()
        var truncated = false
        val view = Geo.box(bbox)
        val maxRelationSpan = 4.0 * maxOf(view.maxX().toDouble() - view.minX(), view.maxY().toDouble() - view.minY())
        for (f in gol.`in`(view)) {
            if (f.isRelation) {
                if (!f.isArea) continue
                val b = f.bounds()
                if (maxOf(b.maxX().toDouble() - b.minX(), b.maxY().toDouble() - b.minY()) > maxRelationSpan) continue
            }
            if (out.size >= limit) {
                truncated = true
                break
            }
            out += f.toMapFeature()
        }
        FeatureCollection(out, truncated)
    }

    fun findIn(gol: FeatureLibrary, refs: Collection<OsmRef>, near: BBox? = null): Map<OsmRef, Feature> {
        if (refs.isEmpty()) return emptyMap()
        val wanted = refs.filter { it.id > 0 }.toHashSet()
        val view = near?.let { gol.`in`(Geo.box(it)) } ?: gol
        return view.select(IdSetFilter(wanted)).associateBy { it.ref }
    }

    fun mapFeature(ref: OsmRef, near: BBox? = null): MapFeature? =
        mirror.query { gol -> findIn(gol, listOf(ref), near)[ref]?.toMapFeature() }

    fun mapFeatures(refs: Collection<OsmRef>, near: BBox? = null): Map<OsmRef, MapFeature> =
        mirror.query { gol -> findIn(gol, refs, near).mapValues { it.value.toMapFeature() } }

    fun objectInfo(ref: OsmRef): ObjectInfo? = mirror.query { gol ->
        val f = findIn(gol, listOf(ref))[ref] ?: return@query null
        ObjectInfo(
            feature = f.toMapFeature(),
            parents = f.parents().take(MAX_PARENTS).map { p ->
                val role = if (p.isRelation) f.roleIn(p) else null
                ParentInfo(p.ref, p.tagMap(), role)
            },
            center = f.centroid(),
            connectedWays = connectedWays(gol, f),
        )
    }

    fun connectedWays(ref: OsmRef): List<ParentInfo> = mirror.query { gol ->
        findIn(gol, listOf(ref))[ref]?.let { connectedWays(gol, it) }.orEmpty()
    }

    private fun connectedWays(gol: FeatureLibrary, f: Feature): List<ParentInfo> =
        if (f.isRelation) {
            emptyList()
        } else {
            gol.select("w").connectedTo(f).asSequence()
                .filter { it.ref != f.ref }
                .take(MAX_PARENTS)
                .map { ParentInfo(it.ref, it.tagMap()) }
                .toList()
        }

    private fun Feature.roleIn(relation: Feature): String? =
        relation.members().firstOrNull { it.ref == ref }?.role()?.takeIf { it.isNotEmpty() }

    private data class AreaEntry(
        val name: String,
        val key: String,
        val adminLevel: Int,
        val ref: OsmRef,
        val bbox: BBox,
        val isPlaceNode: Boolean = false,
    )

    private val areaIndex = AtomicReference<Pair<Long, List<AreaEntry>>?>(null)

    private fun areas(): List<AreaEntry> {
        val gen = mirror.generation
        areaIndex.get()?.takeIf { it.first == gen }?.let { return it.second }
        val list = mirror.query { gol ->
            val boundaries = gol.select("a[boundary=administrative][name]").map { f ->
                AreaEntry(f.tag("name"), fold(f.tag("name")), f.tag("admin_level").toIntOrNull() ?: 99, f.ref, Geo.bbox(f.bounds()))
            }
            val places = gol.select("n[place=suburb,quarter,neighbourhood,village,hamlet][name]").map { f ->
                val radius = PLACE_RADIUS_M[f.tag("place")] ?: 500.0
                AreaEntry(f.tag("name"), fold(f.tag("name")), PLACE_LEVEL, f.ref, Geo.bbox(Box.metersAroundLonLat(radius, f.lon(), f.lat())), true)
            }
            boundaries + places
        }
        areaIndex.set(gen to list)
        return list
    }

    fun searchAreas(q: String, limit: Int = 10): List<Area> {
        val k = fold(q)
        if (k.isBlank()) return emptyList()
        return areas().asSequence()
            .filter { it.key.contains(k) }
            .sortedWith(compareBy({ it.key != k }, { !it.key.startsWith(k) }, { it.adminLevel }, { it.name }))
            .distinctBy { it.name to it.adminLevel }
            .take(limit)
            .map { Area(it.name, it.bbox) }
            .toList()
    }

    fun area(name: String): Pair<Area, Scope>? {
        val entry = resolveArea(name) ?: return null
        if (entry.isPlaceNode) {
            val box = Geo.box(entry.bbox)
            return Area(entry.name, entry.bbox, Geo.toGeoJson(Geo.boxPolygon(box))) to Scope(box)
        }
        return mirror.query { gol ->
            val f = findIn(gol, listOf(entry.ref), entry.bbox)[entry.ref] ?: return@query null
            val geom = f.toGeometry()
            Area(entry.name, entry.bbox, Geo.toGeoJson(geom)) to Scope(Box(f.bounds()), geom)
        }
    }

    private fun resolveArea(name: String): AreaEntry? {
        val k = fold(name)
        areas().filter { it.key == k }.minByOrNull { it.adminLevel }?.let { return it }
        val (parent, child) = when {
            ',' in name -> name.substringAfter(',').trim() to name.substringBefore(',').trim()
            '-' in name -> name.substringBefore('-').trim() to name.substringAfter('-').trim()
            else -> return null
        }
        val p = areas().filter { it.key == fold(parent) }.minByOrNull { it.adminLevel } ?: return null
        return areas().filter { it.key == fold(child) && it.bbox.centerIn(p.bbox) }.minByOrNull { it.adminLevel }
            ?.let { it.copy(name = name) }
    }

    private fun BBox.centerIn(o: BBox): Boolean {
        val x = (west + east) / 2
        val y = (south + north) / 2
        return x in o.west..o.east && y in o.south..o.north
    }

    fun searchNamed(q: String, bbox: BBox, limit: Int = 10): List<MapFeature> = mirror.query { gol ->
        val k = fold(q)
        gol.select("*[name]").`in`(Geo.box(bbox)).asSequence()
            .filter { fold(it.tag("name")).contains(k) }
            .take(limit)
            .map { it.toMapFeature() }
            .toList()
    }

    fun goql(query: String, bbox: BBox?, limit: Int): Pair<List<MapFeature>, Boolean> =
        goql(query, bbox?.let { Scope(Geo.box(it)) }, limit)

    fun goql(query: String, scope: Scope?, limit: Int): Pair<List<MapFeature>, Boolean> = mirror.query { gol ->
        val sel = gol.select(query).let { s -> scope?.let { s.within(it) } ?: s }
        val out = ArrayList<MapFeature>()
        for (f in sel) {
            if (out.size >= limit) return@query out to true
            out += f.toMapFeature()
        }
        out to false
    }

    private fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(DIACRITICS, "").lowercase().trim()

    companion object {
        private val DIACRITICS = Regex("\\p{M}+")

        private const val PLACE_LEVEL = 50
        private val PLACE_RADIUS_M = mapOf(
            "suburb" to 1200.0, "quarter" to 800.0, "neighbourhood" to 400.0, "village" to 1000.0, "hamlet" to 400.0,
        )
    }
}

@kotlinx.serialization.Serializable
data class ParentInfo(val ref: OsmRef, val tags: Map<String, String>, val role: String? = null)

@kotlinx.serialization.Serializable
data class ObjectInfo(
    val feature: MapFeature,
    val parents: List<ParentInfo>,
    val center: eu.tiducto.osmreview.shared.LngLat,
    val connectedWays: List<ParentInfo> = emptyList(),
)

private const val MAX_PARENTS = 50

fun Feature.toMapFeature(): MapFeature = MapFeature(ref, tagMap(), Geo.featureGeoJson(this))

class ExistingIds(
    private val nodes: LongHashSet,
    private val ways: LongHashSet,
    private val relations: LongHashSet,
    val wayNodes: Map<Long, LongArray> = emptyMap(),
) {
    private val anonymous = LongHashSet().also { s -> wayNodes.values.forEach { ids -> ids.forEach { s.add(it) } } }

    fun contains(type: OsmType, id: Long): Boolean = when (type) {
        OsmType.NODE -> nodes.contains(id) || anonymous.contains(id)
        OsmType.WAY -> ways.contains(id)
        OsmType.RELATION -> relations.contains(id)
    }

    val size: Int get() = nodes.size() + ways.size() + relations.size()
}

fun FeatureLibrary.existingIds(nodes: LongArray, ways: LongArray, relations: LongArray, withWayNodes: Boolean = false): ExistingIds {
    val filter = IdSetFilter(LongHashSet.newSetWith(*nodes), LongHashSet.newSetWith(*ways), LongHashSet.newSetWith(*relations))
    val found = mapOf(OsmType.NODE to LongHashSet(), OsmType.WAY to LongHashSet(), OsmType.RELATION to LongHashSet())
    val wayNodes = HashMap<Long, LongArray>()
    if (filter.acceptedTypes() != 0) {
        for (f in select(filter)) {
            found.getValue(f.ref.type).add(f.id())
            if (withWayNodes && f.isWay) WayNodeIds.of(f)?.let { wayNodes[f.id()] = it }
        }
    }
    return ExistingIds(found.getValue(OsmType.NODE), found.getValue(OsmType.WAY), found.getValue(OsmType.RELATION), wayNodes)
}

private class IdSetFilter(
    private val nodes: LongHashSet,
    private val ways: LongHashSet,
    private val relations: LongHashSet,
) : Filter {
    constructor(refs: Set<OsmRef>) : this(ids(refs, OsmType.NODE), ids(refs, OsmType.WAY), ids(refs, OsmType.RELATION))

    override fun acceptedTypes(): Int =
        (if (nodes.isEmpty) 0 else NODES) or (if (ways.isEmpty) 0 else WAYS) or (if (relations.isEmpty) 0 else RELATIONS)

    override fun accept(f: Feature): Boolean = when {
        f.isNode -> nodes.contains(f.id())
        f.isWay -> ways.contains(f.id())
        else -> relations.contains(f.id())
    }

    companion object {
        private const val NODES = 327685
        private const val WAYS = 15728880
        private const val RELATIONS = 251662080

        private fun ids(refs: Set<OsmRef>, type: OsmType) = LongHashSet().also { s -> refs.forEach { if (it.type == type) s.add(it.id) } }
    }
}
