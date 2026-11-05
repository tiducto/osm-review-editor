package eu.tiducto.osmreview.server.replication

import eu.tiducto.osmreview.shared.OsmType
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet

fun interface IdLookup {
    fun existing(nodes: LongArray, ways: LongArray, relations: LongArray): (OsmType, Long) -> Boolean
}

class ClipResult(
    val diffs: List<List<OscElement>>,
    val kept: Int,
    val dropped: Int,
    val keptIds: Map<OsmType, LongHashSet>,
    val lookedUp: Int,
)

class DiffClipper(
    private val inArea: (lon: Double, lat: Double) -> Boolean,
    private val tracked: (OsmType, Long) -> Boolean,
    private val lookup: IdLookup,
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
        for (e in all) {
            if (e.type != OsmType.NODE) continue
            val lon = e.lon
            val lat = e.lat
            if (known(OsmType.NODE, e.id) || (lon != null && lat != null && inArea(lon, lat))) keepNodes.add(e.id)
        }
        for (e in all) {
            if (e.type != OsmType.WAY) continue
            if (known(OsmType.WAY, e.id) || e.nodeRefs.any { keepNodes.contains(it) || known(OsmType.NODE, it) }) keepWays.add(e.id)
        }
        val batchNodes = LongHashSet().also { s -> all.forEach { if (it.type == OsmType.NODE) s.add(it.id) } }
        for (e in all) {
            if (e.type == OsmType.WAY && keepWays.contains(e.id)) {
                e.nodeRefs.forEach { if (batchNodes.contains(it)) keepNodes.add(it) }
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
        val out = diffs.map { d -> d.filter(::kept) }
        val keptCount = out.sumOf { it.size }
        return ClipResult(
            out, keptCount, all.size - keptCount,
            mapOf(OsmType.NODE to keepNodes, OsmType.WAY to keepWays, OsmType.RELATION to keepRels),
            candNodes.size() + candWays.size() + candRels.size(),
        )
    }
}
