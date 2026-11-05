package eu.tiducto.osmreview.rules

import com.geodesk.feature.FeatureLibrary
import eu.tiducto.osmreview.shared.MirrorStatus
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * The read-only local OSM copy: one open GOL, swapped atomically when the `gol` sidecar finishes an
 * update. The sidecar updates the idle file of two, runs `gol check`, then writes the file name into
 * `<dataDir>/current`. [reloadIfChanged] opens the new library, switches under the write lock (so no
 * query is mid-iteration on the old one), closes the old one and bumps [generation], which
 * invalidates rule caches.
 */
class GolMirror(private val dataDir: Path, private val fallbackFile: Path? = null) : AutoCloseable {
    private val lock = ReentrantReadWriteLock()
    private var library: FeatureLibrary? = null
    private var file: Path? = null
    private var openedAt: Instant? = null
    private var lastError: String? = null
    private val gen = AtomicLong(0)

    /** Increments on every swap; caches key on it. */
    val generation: Long get() = gen.get()

    /** Runs [block] against the current GOL. Throws [MirrorUnavailableException] when none is open. */
    fun <T> query(block: (FeatureLibrary) -> T): T = lock.read {
        val lib = library ?: throw MirrorUnavailableException(lastError ?: "No GOL is open yet")
        block(lib)
    }

    val isAvailable: Boolean get() = lock.read { library != null }

    fun status(): MirrorStatus = lock.read {
        MirrorStatus(
            available = library != null,
            file = file?.name,
            openedAt = openedAt?.toString(),
            dataTimestamp = file?.let { runCatching { it.getLastModifiedTime().toInstant().toString() }.getOrNull() },
            message = lastError,
        )
    }

    /** The GOL the sidecar says is current, else the fallback file (local dev without the sidecar). */
    fun currentTarget(): Path? {
        val pointer = dataDir.resolve("current")
        if (pointer.exists()) {
            val name = pointer.readText().trim()
            if (name.isNotEmpty()) return dataDir.resolve(name).takeIf { it.exists() }
        }
        return fallbackFile?.takeIf { it.exists() }
    }

    /** Opens the current target if it differs from the open one. Returns true when it swapped. */
    @Synchronized
    fun reloadIfChanged(): Boolean {
        val target = currentTarget()
        if (target == null) {
            if (library == null) lastError = "No GOL found in $dataDir (waiting for the gol sidecar's first build)"
            return false
        }
        if (target == file && library != null) return false
        val fresh = try {
            FeatureLibrary(target)
        } catch (e: Exception) {
            lastError = "Failed to open ${target.name}: $e"
            return false
        }
        val old = lock.write {
            val prev = library
            library = fresh
            file = target
            openedAt = Instant.now()
            lastError = null
            gen.incrementAndGet()
            prev
        }
        old?.close()
        return true
    }

    override fun close() {
        lock.write {
            library?.close()
            library = null
        }
    }

    companion object {
        fun isGol(p: Path) = Files.isRegularFile(p) && p.name.endsWith(".gol")
    }
}

class MirrorUnavailableException(message: String) : RuntimeException(message)
