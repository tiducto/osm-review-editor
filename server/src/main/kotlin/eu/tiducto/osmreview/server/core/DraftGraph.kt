package eu.tiducto.osmreview.server.core

import eu.tiducto.osmreview.shared.Change
import eu.tiducto.osmreview.shared.ChangeAction
import eu.tiducto.osmreview.shared.ChangeLink
import eu.tiducto.osmreview.shared.ChangeStatus
import eu.tiducto.osmreview.shared.ElementShape
import eu.tiducto.osmreview.shared.LngLat
import eu.tiducto.osmreview.shared.OsmRef
import eu.tiducto.osmreview.shared.OsmType
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object DraftGraph {
    fun referenced(shape: ElementShape?): Set<OsmRef> {
        if (shape == null) return emptySet()
        val out = LinkedHashSet<OsmRef>()
        shape.nodes?.forEach { out += OsmRef(OsmType.NODE, it) }
        shape.members?.forEach { out += OsmRef(it.type, it.ref) }
        return out
    }

    private fun preferred(list: List<Change>): Change =
        list.firstOrNull { it.status != ChangeStatus.REJECTED } ?: list.last()

    fun edges(changes: List<Change>): Map<String, Set<String>> {
        val creates = changes.filter { it.action == ChangeAction.CREATE }.groupBy { it.ref }.mapValues { preferred(it.value) }
        val out = HashMap<String, MutableSet<String>>()
        for (c in changes) {
            if (c.action == ChangeAction.DELETE) continue
            for (r in referenced(c.newShape)) {
                val dep = creates[r] ?: continue
                if (dep.id != c.id) out.getOrPut(c.id) { LinkedHashSet() } += dep.id
            }
        }
        val deletes = changes.filter { it.action == ChangeAction.DELETE }
        if (deletes.isNotEmpty()) {
            val usedBy = HashMap<OsmRef, MutableList<Change>>()
            for (c in changes) {
                if (c.action == ChangeAction.CREATE) continue
                referenced(c.baseShape).forEach { usedBy.getOrPut(it) { ArrayList() } += c }
            }
            for (d in deletes) {
                for (c in usedBy[d.ref].orEmpty()) {
                    if (c.id == d.id) continue
                    val stillUses = c.action == ChangeAction.MODIFY && (c.newShape == null || d.ref in referenced(c.newShape))
                    if (!stillUses) out.getOrPut(d.id) { LinkedHashSet() } += c.id
                }
            }
        }
        return out
    }

    fun annotate(changes: List<Change>): List<Change> {
        if (changes.none { it.action != ChangeAction.MODIFY || it.newShape != null }) return changes
        val e = edges(changes)
        if (e.isEmpty()) return changes
        val byId = changes.associateBy { it.id }
        val reverse = HashMap<String, MutableList<String>>()
        e.forEach { (from, tos) -> tos.forEach { reverse.getOrPut(it) { ArrayList() } += from } }
        fun link(id: String) = byId.getValue(id).let { ChangeLink(it.id, it.ref, it.action, it.status) }
        return changes.map { c ->
            val deps = e[c.id].orEmpty()
            val req = reverse[c.id].orEmpty()
            if (deps.isEmpty() && req.isEmpty()) c else c.copy(dependsOn = deps.map(::link), requiredBy = req.map(::link))
        }
    }

    fun dependencies(changes: List<Change>, id: String): List<Change> = walk(changes, id, edges(changes))

    fun dependents(changes: List<Change>, id: String): List<Change> {
        val reverse = HashMap<String, MutableSet<String>>()
        edges(changes).forEach { (from, tos) -> tos.forEach { reverse.getOrPut(it) { LinkedHashSet() } += from } }
        return walk(changes, id, reverse)
    }

    private fun walk(changes: List<Change>, start: String, next: Map<String, Set<String>>): List<Change> {
        val byId = changes.associateBy { it.id }
        val seen = LinkedHashSet<String>()
        val queue = ArrayDeque(next[start].orEmpty())
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (id == start || !seen.add(id)) continue
            queue += next[id].orEmpty()
        }
        return seen.mapNotNull { byId[it] }
    }

    fun components(changes: List<Change>): List<List<Change>> {
        val ids = changes.map { it.id }.toSet()
        val parent = HashMap<String, String>()
        fun find(x: String): String {
            var r = x
            while (parent[r] != null && parent[r] != r) r = parent.getValue(r)
            parent[x] = r
            return r
        }
        changes.forEach { parent[it.id] = it.id }
        edges(changes).forEach { (from, tos) ->
            tos.filter { it in ids }.forEach { to -> parent[find(from)] = find(to) }
        }
        return changes.groupBy { find(it.id) }.values.toList()
    }

    fun summary(action: ChangeAction, base: ElementShape?, new: ElementShape?, ifUnused: Boolean): String? = when (action) {
        ChangeAction.DELETE -> "deleted" + if (ifUnused) " if unused" else ""
        ChangeAction.CREATE -> {
            val nodes = new?.nodes
            val members = new?.members
            when {
                nodes != null -> "new way, ${nodes.size} nodes"
                members != null -> "new relation, ${members.size} members"
                new?.location != null -> "new node"
                else -> null
            }
        }
        ChangeAction.MODIFY -> if (new == null) null else listOfNotNull(
            new.location?.let { to -> base?.location?.let { from -> "moved ${distance(from, to).roundToInt()} m" } ?: "moved" },
            new.nodes?.let { n ->
                val before = base?.nodes.orEmpty()
                val added = (n.toSet() - before.toSet()).size
                val removed = (before.toSet() - n.toSet()).size
                "nodes ${before.size} → ${n.size} (+$added −$removed)"
            },
            new.members?.let { m ->
                val before = base?.members.orEmpty()
                val keyB = before.map { OsmRef(it.type, it.ref) }.toSet()
                val keyA = m.map { OsmRef(it.type, it.ref) }.toSet()
                val roles = m.count { a -> before.any { b -> b.type == a.type && b.ref == a.ref && b.role != a.role } }
                "members ${before.size} → ${m.size} (+${(keyA - keyB).size} −${(keyB - keyA).size}" +
                    (if (roles > 0) ", $roles role change${if (roles > 1) "s" else ""}" else "") + ")"
            },
        ).joinToString(", ").ifEmpty { null }
    }

    fun distance(a: LngLat, b: LngLat): Double {
        val r = 6_371_008.8
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dp = p2 - p1
        val dl = Math.toRadians(b.lng - a.lng)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * asin(sqrt(h))
    }
}
