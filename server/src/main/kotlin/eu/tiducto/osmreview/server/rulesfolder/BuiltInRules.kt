package eu.tiducto.osmreview.server.rulesfolder

import eu.tiducto.osmreview.shared.RuleKind
import eu.tiducto.osmreview.shared.RuleOrigin
import org.slf4j.LoggerFactory
import java.time.Instant

object BuiltInRules {
    const val SOURCE = "built-in"
    const val URL_PREFIX = "builtin:"
    const val LICENCE = "Apache-2.0, shipped with OSM Review Editor"
    private const val RESOURCE_DIR = "/builtin-rules/"
    private val log = LoggerFactory.getLogger(BuiltInRules::class.java)

    fun origin(path: String) = RuleOrigin(source = SOURCE, url = URL_PREFIX + path, licence = LICENCE, builtIn = true)

    fun file(path: String, content: String, modifiedAt: Instant = Instant.EPOCH): RemoteRuleFile {
        val kind = requireNotNull(RuleKind.forPath(path)) { "'$path' is not a rules file path" }
        return RemoteRuleFile(path, kind, content, origin(path), modifiedAt)
    }

    fun fromResources(): List<RemoteRuleFile> {
        val index = resource("index.txt") ?: run {
            log.warn("No built-in rules index on the classpath")
            return emptyList()
        }
        return index.first.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { path ->
            if (RuleKind.forPath(path) == null) {
                log.warn("Ignoring built-in rules file {}: not a rules file path", path)
                return@mapNotNull null
            }
            val (content, modified) = resource(path) ?: run {
                log.warn("Built-in rules file {} is missing", path)
                return@mapNotNull null
            }
            file(path, content, modified)
        }.toList()
    }

    private fun resource(name: String): Pair<String, Instant>? {
        val url = BuiltInRules::class.java.getResource(RESOURCE_DIR + name) ?: return null
        val conn = url.openConnection()
        val modified = conn.lastModified.takeIf { it > 0 }?.let(Instant::ofEpochMilli) ?: Instant.EPOCH
        val text = conn.getInputStream().use { it.readBytes().toString(Charsets.UTF_8) }
        return text to modified
    }
}
