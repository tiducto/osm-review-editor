package eu.tiducto.osmreview.rules

import com.geodesk.feature.FeatureLibrary
import eu.tiducto.osmreview.shared.MirrorStatus
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class GolMirror(private val dataDir: Path, private val fallbackFile: Path? = null) : AutoCloseable {
    private val lock = ReentrantReadWriteLock()
    private var library: FeatureLibrary? = null
    private var file: Path? = null
    private var openedAt: Instant? = null
    private var header: GolHeader? = null
    private var lastError: String? = null
    private val gen = AtomicLong(0)

    val generation: Long get() = gen.get()

    fun <T> query(block: (FeatureLibrary) -> T): T = lock.read {
        val lib = library ?: throw MirrorUnavailableException(lastError ?: "No GOL is open yet")
        block(lib)
    }

    val isAvailable: Boolean get() = lock.read { library != null }

    val openFile: Path? get() = lock.read { file }

    val dataHeader: GolHeader? get() = lock.read { header }

    val dataTimestamp: Instant? get() = lock.read { header?.timestamp }

    fun status(now: Instant = Instant.now()): MirrorStatus = lock.read {
        val ts = header?.timestamp
        MirrorStatus(
            available = library != null,
            file = file?.name,
            openedAt = openedAt?.toString(),
            dataTimestamp = ts?.toString(),
            message = lastError,
            sidecar = SidecarStatusFile.read(dataDir),
            dataSequence = header?.revision?.takeIf { ts != null },
            dataSource = header?.url,
            lagSeconds = ts?.let { maxOf(0L, now.epochSecond - it.epochSecond) },
        )
    }

    fun peerHeaders(): List<GolHeader> {
        val names = (PAIR + listOfNotNull(openFile?.name)).distinct()
        val inDir = names.map { dataDir.resolve(it) }.filter { it.exists() }
        val files = (inDir + listOfNotNull(openFile)).distinct()
        return files.mapNotNull { GolHeader.read(it) }
    }

    val sidecarManaged: Boolean get() = dataDir.resolve(POINTER).exists()

    fun currentTarget(): Path? {
        val pointer = dataDir.resolve(POINTER)
        if (pointer.exists()) {
            val name = pointer.readText().trim()
            if (name.isNotEmpty()) return dataDir.resolve(name).takeIf { it.exists() }
        }
        return fallbackFile?.takeIf { it.exists() }
    }

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
        val freshHeader = GolHeader.read(target)
        val (old, oldFile) = lock.write {
            val prev = library to file
            library = fresh
            file = target
            header = freshHeader
            openedAt = Instant.now()
            lastError = null
            gen.incrementAndGet()
            prev
        }
        old?.close()
        writeAck(target, oldFile?.takeIf { it != target })
        return true
    }

    private fun writeAck(open: Path, released: Path?) {
        if (!sidecarManaged) return
        runCatching {
            val json = buildJsonObject {
                put("open", open.name)
                put("released", released?.name)
                put("generation", gen.get())
                put("at", Instant.now().toString())
            }
            val tmp = dataDir.resolve("$ACK.tmp")
            tmp.writeText(json.toString() + "\n")
            Files.move(tmp, dataDir.resolve(ACK), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { lastError = "Could not write ${dataDir.resolve(ACK)}: ${it.message}" }
    }

    override fun close() {
        lock.write {
            library?.close()
            library = null
        }
    }

    companion object {
        const val POINTER = "current"
        const val ACK = "released"
        val PAIR = listOf("a.gol", "b.gol")

        fun isGol(p: Path) = Files.isRegularFile(p) && p.name.endsWith(".gol")
    }
}

class MirrorUnavailableException(message: String) : RuntimeException(message)
