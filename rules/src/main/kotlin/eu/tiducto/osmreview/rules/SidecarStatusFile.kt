package eu.tiducto.osmreview.rules

import eu.tiducto.osmreview.shared.SidecarStatus
import eu.tiducto.osmreview.shared.WireJson
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

object SidecarStatusFile {
    const val NAME = "status.json"

    fun read(dataDir: Path): SidecarStatus? {
        val file = dataDir.resolve(NAME)
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrElse { return unreadable(it) }
        return parse(text)
    }

    fun parse(text: String): SidecarStatus = runCatching {
        WireJson.decodeFromString(SidecarStatus.serializer(), text)
    }.getOrElse { unreadable(it) }

    private fun unreadable(e: Throwable) = SidecarStatus(
        result = "unknown",
        error = "Unreadable $NAME: ${e.message?.lineSequence()?.firstOrNull() ?: e::class.simpleName}",
    )
}
