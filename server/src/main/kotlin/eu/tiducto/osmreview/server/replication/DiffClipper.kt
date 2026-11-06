package eu.tiducto.osmreview.server.replication

import eu.tiducto.osmreview.shared.OsmType
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet

fun interface IdLookup {
    fun existing(nodes: LongArray, ways: LongArray, relations: LongArray): (OsmType, Long) -> Boolean
}

fun interface NodeFetcher {
    fun fetch(ids: LongArray): List<OscElement>
}

class ClipResult(
    val diffs: List<List<OscElement>>,
    val kept: Int,
    val dropped: Int,
    val keptIds: Map<OsmType, LongHashSet>,
    val lookedUp: Int,
    val fetched: Int = 0,
    val unresolved: LongHashSet = LongHashSet(),
)

class DiffClipper(
    private val inArea: (lon: Double, lat: Double) -> Boolean,
    private val tracked: (OsmType, Long) -> Boolean,
    private val lookup: IdLookup,
    private val nearArea: (lon: Double, lat: Double) -> Boolean = { _, _ -> false },
    private val wayNodesNear: (List<Pair<Double, Double>>) -> LongHashSet = { LongHashSet() },
    private val fetcher: NodeFetcher? = null,
) {
    fun clip(diffs: List<List<OscElement>>): ClipResult {
        val all = diffs.flatten()
        val candNodes = LongHashSet()
        val candWays = LongHashSet()
        val candRels = LongHashSet()
        fun cand(t: OsmType, id: Long) {
            if (tracked(t, id)) return
            when (t) {
                OsmType.NODE -> candNodes.add(id)
                OsmType.WAY -> candWays.add(id)
                OsmType.RELATION -> candRels.add(id)
            }
        }
        for (e in all) {
            cand(e.type, e.id)
            when (e.type) {
                OsmType.WAY -> e.nodeRefs.forEach { cand(OsmType.NODE, it) }
                OsmType.RELATION -> e.members.forEach { (t, id) -> cand(t, id) }
                OsmType.NODE -> Unit
            }
        }
        val exists = lookup.existing(candNodes.toArray(), candWays.toArray(), candRels.toArray())
        fun known(t: OsmType, id: Long) = tracked(t, id) || exists(t, id)

        val keepNodes = LongHashSet()
        val keepWays = LongHashSet()
        val keepRels = LongHashSet()
        val outside = ArrayList<OscElement>()
        for (e in all) {
            if (e.type != OsmType.NODE) continue
            val lon = e.lon
            val lat = e.lat
            when {
                known(OsmType.NODE, e.id) || (lon != null && lat != null && inArea(lon, lat)) -> keepNodes.add(e.id)
                lon != null && lat != null && nearArea(lon, lat) -> outside += e
            }
        }
        if (outside.isNotEmpty()) {
            val anonymous = wayNodesNear(outside.map { it.lon!! to it.lat!! })
            outside.forEach { if (anonymous.contains(it.id)) keepNodes.add(it.id) }
        }
        for (e in all) {
            if (e.type != OsmType.WAY) continue
            if (known(OsmType.WAY, e.id) || e.nodeRefs.any { keepNodes.contains(it) || known(OsmType.NODE, it) }) keepWays.add(e.id)
        }
        val batchNodes = LongHashSet()
        val coords = HashMap<Long, Pair<Double, Double>>()
        all.forEach {
            if (it.type == OsmType.NODE) {
                batchNodes.add(it.id)
                val lon = it.lon
                val lat = it.lat
                if (lon != null && lat != null) coords[it.id] = lon to lat
            }
        }
        val missing = LongHashSet()
        val missingWays = ArrayList<OscElement>()
        for (e in all) {
            if (e.type == OsmType.WAY && keepWays.contains(e.id)) {
                e.nodeRefs.forEach {
                    if (batchNodes.contains(it)) {
                        keepNodes.add(it)
                    } else if (!known(OsmType.NODE, it)) {
                        missing.add(it)
                        missingWays += e
                    }
                }
            }
        }
        var fetchedCount = 0
        val fetchedFor = HashMap<Int, MutableList<OscElement>>()
        if (!missing.isEmpty) {
            val points = missingWays.flatMap { w -> w.nodeRefs.mapNotNull { coords[it] } }
            val anonymous = wayNodesNear(points)
            missing.removeIf { anonymous.contains(it) }
        }
        val nodeFetcher = fetcher
        if (!missing.isEmpty && nodeFetcher != null) {
            val fetched = runCatching { nodeFetcher.fetch(missing.toSortedArray()) }.getOrDefault(emptyList())
            val firstUse = HashMap<Long, Int>()
            diffs.forEachIndexed { i, d ->
                d.forEach { e -> if (e.type == OsmType.WAY && keepWays.contains(e.id)) e.nodeRefs.forEach { firstUse.putIfAbsent(it, i) } }
            }
            for (n in fetched) {
                if (!missing.remove(n.id)) continue
                fetchedFor.getOrPut(firstUse[n.id] ?: 0) { ArrayList() } += n
                keepNodes.add(n.id)
                fetchedCount++
            }
        }
        val rels = all.filter { it.type == OsmType.RELATION }
        var changed = true
        while (changed) {
            changed = false
            for (e in rels) {
                if (keepRels.contains(e.id)) continue
                val keep = known(OsmType.RELATION, e.id) || e.members.any { (t, id) ->
                    known(t, id) || when (t) {
                        OsmType.NODE -> keepNodes.contains(id)
                        OsmType.WAY -> keepWays.contains(id)
                        OsmType.RELATION -> keepRels.contains(id)
                    }
                }
                if (keep) {
                    keepRels.add(e.id)
                    changed = true
                }
            }
        }
        fun kept(e: OscElement) = when (e.type) {
            OsmType.NODE -> keepNodes.contains(e.id)
            OsmType.WAY -> keepWays.contains(e.id)
            OsmType.RELATION -> keepRels.contains(e.id)
        }
        val out = diffs.mapIndexed { i, d -> fetchedFor[i].orEmpty() + d.filter(::kept) }
        val keptCount = diffs.sumOf { d -> d.count(::kept) }
        return ClipResult(
            out, keptCount, all.size - keptCount,
            mapOf(OsmType.NODE to keepNodes, OsmType.WAY to keepWays, OsmType.RELATION to keepRels),
            candNodes.size() + candWays.size() + candRels.size(),
            fetchedCount,
            missing,
        )
    }
}
