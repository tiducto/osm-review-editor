package eu.tiducto.osmreview.server

import eu.tiducto.osmreview.rules.GolMirror
import eu.tiducto.osmreview.rules.SidecarStatusFile
import eu.tiducto.osmreview.server.core.StateException
import eu.tiducto.osmreview.server.replication.ReplicationService
import eu.tiducto.osmreview.shared.MirrorStatus
import eu.tiducto.osmreview.shared.UiEvent
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class MirrorControl(
    private val dataDir: Path,
    private val mirror: GolMirror,
    private val replication: () -> ReplicationService?,
    private val emit: (UiEvent) -> Unit,
) {
    private val lastSidecar = AtomicReference<String?>(null)

    fun status(): MirrorStatus = mirror.status().copy(replication = replication()?.status()?.takeIf { it.enabled })

    fun emitStatus() = emit(UiEvent.MirrorStatusChanged(status()))

    fun pollSidecar() {
        val file = dataDir.resolve(SidecarStatusFile.NAME)
        val text = if (file.exists()) runCatching { file.readText() }.getOrNull() else null
        if (lastSidecar.getAndSet(text) != text) emitStatus()
    }

    fun requestUpdate(): MirrorStatus {
        if (!mirror.sidecarManaged) throw StateException("No gol sidecar manages this mirror (no ${dataDir.resolve(GolMirror.POINTER)})")
        val phase = SidecarStatusFile.read(dataDir)?.phase
        if (phase in BUSY) throw StateException("A mirror cycle is already running (phase $phase)")
        val trigger = dataDir.resolve(TRIGGER)
        if (trigger.exists()) throw StateException("An update was already requested and has not started yet")
        trigger.writeText(Instant.now().toString() + "\n")
        replication()?.requestRun()
        val s = status()
        emit(UiEvent.MirrorStatusChanged(s))
        return s
    }

    companion object {
        const val TRIGGER = "update-now"
        val BUSY = setOf("updating", "checking", "swapping", "rebuilding")
    }
}
