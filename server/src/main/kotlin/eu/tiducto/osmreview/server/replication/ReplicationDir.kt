package eu.tiducto.osmreview.server.replication

import eu.tiducto.osmreview.shared.OsmType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

@Serializable
data class ClipperMeta(
    val source: String,
    val firstSequence: Long,
    val firstTimestamp: String,
    val rebasedAt: String,
    val area: String? = null,
)

class ReplicationDir(val root: Path) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val metaFile = root.resolve("clipper.json")
    private val trackedFile = root.resolve("tracked-ids.bin")

    fun head(): ReplicationState? = root.resolve("state.txt").takeIf { it.exists() }
        ?.let { runCatching { ReplicationState.parse(it.readText()) }.getOrNull() }

    fun meta(): ClipperMeta? = metaFile.takeIf { it.exists() }
        ?.let { runCatching { json.decodeFromString(ClipperMeta.serializer(), it.readText()) }.getOrNull() }

    fun writeMeta(meta: ClipperMeta) = atomic(metaFile, json.encodeToString(ClipperMeta.serializer(), meta).toByteArray())

    fun state(sequence: Long): ReplicationState? = file(sequence, ".state.txt").takeIf { it.exists() }
        ?.let { runCatching { ReplicationState.parse(it.readText()) }.getOrNull() }

    fun file(sequence: Long, suffix: String): Path = root.resolve(ReplicationState.path(sequence) + suffix)

    fun writeSequence(state: ReplicationState, gz: ByteArray) {
        atomic(file(state.sequence, ".osc.gz"), gz)
        atomic(file(state.sequence, ".state.txt"), state.format().toByteArray())
    }

    fun commitHead(state: ReplicationState) = atomic(root.resolve("state.txt"), state.format().toByteArray())

    fun wipe() {
        if (!root.exists()) return
        Files.walk(root).use { s -> s.sorted(Comparator.reverseOrder()).filter { it != root }.forEach { it.deleteIfExists() } }
    }

    fun prune(before: Instant, meta: ClipperMeta): ClipperMeta {
        val head = head() ?: return meta
        var seq = meta.firstSequence
        var firstTs = Instant.parse(meta.firstTimestamp)
        while (seq < head.sequence) {
            val st = state(seq)
            if (st != null && !st.timestamp.isBefore(before)) break
            file(seq, ".osc.gz").deleteIfExists()
            file(seq, ".state.txt").deleteIfExists()
            seq++
            firstTs = state(seq)?.timestamp ?: firstTs
        }
        if (seq == meta.firstSequence) return meta
        removeEmptyDirs()
        return meta.copy(firstSequence = seq, firstTimestamp = firstTs.toString()).also(::writeMeta)
    }

    private fun removeEmptyDirs() {
        if (!root.isDirectory()) return
        Files.walk(root).use { s ->
            s.sorted(Comparator.reverseOrder())
                .filter { it != root && it.isDirectory() && it.listDirectoryEntries().isEmpty() }
                .forEach { it.deleteIfExists() }
        }
    }

    fun loadTracked(): TrackedIds {
        val t = TrackedIds()
        if (!trackedFile.exists()) return t
        DataInputStream(Files.newInputStream(trackedFile).buffered()).use { input ->
            try {
                while (true) {
                    val type = input.readByte().toInt()
                    val id = input.readLong()
                    val ts = input.readLong()
                    t.put(OsmType.entries[type], id, ts)
                }
            } catch (_: EOFException) {
            }
        }
        return t
    }

    fun appendTracked(ids: Map<OsmType, LongHashSet>, epochSecond: Long) {
        root.createDirectories()
        DataOutputStream(Files.newOutputStream(trackedFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND).buffered()).use { out ->
            ids.forEach { (type, set) -> set.forEach { id -> out.writeByte(type.ordinal); out.writeLong(id); out.writeLong(epochSecond) } }
        }
    }

    fun rewriteTracked(t: TrackedIds) {
        root.createDirectories()
        val tmp = root.resolve("tracked-ids.bin.tmp")
        DataOutputStream(Files.newOutputStream(tmp).buffered()).use { out ->
            t.forEach { type, id, ts -> out.writeByte(type.ordinal); out.writeLong(id); out.writeLong(ts) }
        }
        Files.move(tmp, trackedFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun atomic(target: Path, bytes: ByteArray) {
        target.parent.createDirectories()
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        Files.write(tmp, bytes)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

class TrackedIds {
    private val maps = OsmType.entries.associateWith { LongLongHashMap() }

    fun contains(type: OsmType, id: Long): Boolean = maps.getValue(type).containsKey(id)

    fun put(type: OsmType, id: Long, epochSecond: Long) = maps.getValue(type).put(id, epochSecond)

    fun add(ids: Map<OsmType, LongHashSet>, epochSecond: Long) = ids.forEach { (t, s) -> s.forEach { put(t, it, epochSecond) } }

    val size: Int get() = maps.values.sumOf { it.size() }

    fun pruneBefore(epochSecond: Long): Int {
        var removed = 0
        maps.values.forEach { m ->
            val drop = LongHashSet()
            m.forEachKeyValue { k, v -> if (v < epochSecond) drop.add(k) }
            drop.forEach { m.remove(it) }
            removed += drop.size()
        }
        return removed
    }

    fun forEach(block: (OsmType, Long, Long) -> Unit) = maps.forEach { (t, m) -> m.forEachKeyValue { k, v -> block(t, k, v) } }
}
