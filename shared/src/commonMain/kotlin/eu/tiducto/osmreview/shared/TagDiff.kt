package eu.tiducto.osmreview.shared

import kotlinx.serialization.Serializable

@Serializable
data class TagDiff(
    val added: Map<String, String> = emptyMap(),
    val removed: Map<String, String> = emptyMap(),
    val changed: Map<String, List<String>> = emptyMap(),
    val unchanged: List<String> = emptyList(),
) {
    val isNoOp: Boolean get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()

    val touchedKeys: Set<String> get() = added.keys + removed.keys + changed.keys

    fun summary(): String = buildList {
        added.forEach { (k, v) -> add("+$k=$v") }
        changed.forEach { (k, v) -> add("~$k=${v.getOrElse(1) { "" }}") }
        removed.keys.forEach { add("−$it") }
    }.joinToString(" ")

    fun applyTo(base: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap(base)
        removed.keys.forEach { out.remove(it) }
        added.forEach { (k, v) -> out[k] = v }
        changed.forEach { (k, v) -> out[k] = v[1] }
        return out.sortedByKey()
    }

    companion object {
        fun between(base: Map<String, String>, proposed: Map<String, String>): TagDiff {
            val added = HashMap<String, String>()
            val removed = HashMap<String, String>()
            val changed = HashMap<String, List<String>>()
            val unchanged = mutableListOf<String>()
            for ((k, v) in base) {
                val n = proposed[k]
                when {
                    n == null -> removed[k] = v
                    n != v -> changed[k] = listOf(v, n)
                    else -> unchanged += k
                }
            }
            for ((k, v) in proposed) if (k !in base) added[k] = v
            return TagDiff(added.sortedByKey(), removed.sortedByKey(), changed.sortedByKey(), unchanged.sorted())
        }
    }
}

@Serializable
data class TagEdit(
    val set: Map<String, String> = emptyMap(),
    val remove: List<String> = emptyList(),
    val replaceAll: Map<String, String>? = null,
) {
    fun applyTo(base: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap(replaceAll ?: base)
        remove.forEach { out.remove(it) }
        out.putAll(set)
        return out.withoutPseudoTags().sortedByKey()
    }

    val isEmpty: Boolean get() = set.isEmpty() && remove.isEmpty() && replaceAll == null

    operator fun plus(other: TagEdit): TagEdit = if (other.replaceAll != null) {
        other
    } else {
        TagEdit(
            set = (set - other.remove.toSet()) + other.set,
            remove = (remove - other.set.keys) + other.remove,
            replaceAll = replaceAll,
        )
    }
}

fun <V> Map<String, V>.sortedByKey(): Map<String, V> = keys.sorted().associateWith { getValue(it) }
