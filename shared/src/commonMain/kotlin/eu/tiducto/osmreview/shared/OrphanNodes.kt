package eu.tiducto.osmreview.shared

object OrphanNodes {
    private val OPEN = setOf(ChangeStatus.PENDING, ChangeStatus.APPROVED, ChangeStatus.CONFLICT)

    fun isUntaggedNewNode(c: Change, tags: Map<String, String> = c.newTags): Boolean =
        c.action == ChangeAction.CREATE && c.ref.type == OsmType.NODE && tags.isEmpty()

    fun users(changes: List<Change>): Map<OsmRef, List<Change>> {
        val out = HashMap<OsmRef, MutableList<Change>>()
        for (c in changes) {
            if (c.action == ChangeAction.DELETE) continue
            val shape = c.newShape ?: continue
            val refs = LinkedHashSet<OsmRef>()
            shape.nodes?.forEach { refs += OsmRef(OsmType.NODE, it) }
            shape.members?.forEach { refs += OsmRef(it.type, it.ref) }
            refs.remove(c.ref)
            refs.forEach { out.getOrPut(it) { ArrayList() } += c }
        }
        return out
    }

    fun owners(changes: List<Change>, node: Change): List<Change> = users(changes)[node.ref].orEmpty()

    fun unowned(changes: List<Change>, selected: Set<String>): List<Change> {
        val users = users(changes)
        return changes.filter { c ->
            c.id in selected && isUntaggedNewNode(c) &&
                users[c.ref].orEmpty().none { it.id in selected || it.status == ChangeStatus.UPLOADED }
        }
    }

    fun abandoned(changes: List<Change>): List<Change> {
        val users = users(changes)
        return changes.filter { c ->
            if (!isUntaggedNewNode(c) || c.status !in OPEN) return@filter false
            val owners = users[c.ref].orEmpty()
            owners.isNotEmpty() && owners.all { it.status == ChangeStatus.REJECTED }
        }
    }

    fun describe(node: Change, owners: List<Change>): String =
        if (owners.isEmpty()) {
            "${node.ref} is an untagged new node that no way or relation in this draft uses; uploading it would create an orphan node, " +
                "so give it tags or reject it"
        } else {
            "${node.ref} is an untagged new node used only by " + owners.joinToString { "${it.ref} (${it.status.name.lowercase()})" } +
                "; uploading it without ${if (owners.size == 1) "that way" else "one of them"} would create an orphan node, " +
                "so approve ${if (owners.size == 1) "it" else "one of them"} too or approve with cascade"
        }
}
