package eu.tiducto.osmreview.server.rulesfolder

import eu.tiducto.osmreview.shared.RuleKind
import eu.tiducto.osmreview.shared.RuleOrigin
import eu.tiducto.osmreview.shared.RuleSource
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Duration
import java.time.Instant

class RemoteRuleSources(
    private val folder: RulesFolder,
    private val cacheDir: Path,
    private val http: HttpClient,
    private val userAgent: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(RemoteRuleSources::class.java)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Meta(val url: String, val fetchedAt: String? = null, val error: String? = null, val kind: RuleKind)

    fun sources(): List<RuleSource> {
        val file = folder.loaded(RuleKind.SOURCES).firstOrNull { !it.remote } ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        return (file.value as? List<RuleSource>).orEmpty()
    }

    fun start(scope: CoroutineScope) {
        folder.onChange { change ->
            if (RuleKind.SOURCES_FILE in change.changed || RuleKind.SOURCES_FILE in change.removed) scope.launch { refresh() }
        }
        scope.launch(Dispatchers.IO) {
            publish()
            while (isActive) {
                runCatching { refresh() }.onFailure { log.warn("Rule source refresh failed", it) }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    suspend fun refresh(force: Boolean = false) {
        mutex.withLock {
            Files.createDirectories(cacheDir)
            val sources = sources()
            for (s in sources) {
                val meta = readMeta(s.name)
                val due = force || meta == null || meta.url != s.url || meta.kind != s.kind || !Files.exists(contentFile(s)) ||
                    meta.fetchedAt == null && meta.error != null && olderThan(meta, Duration.ofMinutes(RETRY_MINUTES)) ||
                    meta.fetchedAt != null && Instant.parse(meta.fetchedAt).plus(Duration.ofHours(s.refreshHours.toLong())) <= clock.instant()
                if (due) fetch(s, meta)
            }
            val names = sources.map { it.name }.toSet()
            Files.list(cacheDir).use { files ->
                files.toList().filter { f ->
                    val n = f.fileName.toString()
                    n.endsWith(META_SUFFIX) && n.removeSuffix(META_SUFFIX) !in names
                }.forEach { m ->
                    val name = m.fileName.toString().removeSuffix(META_SUFFIX)
                    RuleKind.entries.forEach { k -> Files.deleteIfExists(cacheDir.resolve(name + k.extension)) }
                    Files.deleteIfExists(m)
                }
            }
            publish()
        }
    }

    private val lastAttempt = HashMap<String, Instant>()

    private fun olderThan(meta: Meta, d: Duration): Boolean {
        val at = lastAttempt[meta.url] ?: return true
        return at.plus(d) <= clock.instant()
    }

    private suspend fun fetch(s: RuleSource, previous: Meta?) {
        lastAttempt[s.url] = clock.instant()
        val meta = try {
            val resp = http.get(s.url) { header(HttpHeaders.UserAgent, userAgent) }
            if (!resp.status.isSuccess()) throw IllegalStateException("HTTP ${resp.status.value} from ${s.url}")
            val bytes = resp.bodyAsBytes()
            if (bytes.size > RulesFolder.MAX_BYTES) throw IllegalStateException("${s.url} is larger than ${RulesFolder.MAX_BYTES / 1024 / 1024} MB")
            atomicWrite(contentFile(s), bytes)
            log.info("Fetched rule source {} ({} bytes)", s.name, bytes.size)
            Meta(s.url, clock.instant().toString(), null, s.kind)
        } catch (e: Exception) {
            log.warn("Could not fetch rule source {} from {}: {}", s.name, s.url, e.message)
            val keep = previous?.takeIf { it.url == s.url && it.kind == s.kind }
            Meta(s.url, keep?.fetchedAt, e.message ?: e.toString(), s.kind)
        }
        atomicWrite(metaFile(s.name), json.encodeToString(Meta.serializer(), meta).toByteArray())
    }

    fun publish() {
        val files = sources().mapNotNull { s ->
            val meta = readMeta(s.name)
            val content = contentFile(s).takeIf { Files.exists(it) && meta?.kind == s.kind && meta.url == s.url }
            val text = content?.let { runCatching { Files.readString(it) }.getOrNull() }
            if (text == null && meta?.error == null) return@mapNotNull null
            RemoteRuleFile(
                path = pathFor(s),
                kind = s.kind,
                content = text.orEmpty(),
                origin = RuleOrigin(s.name, s.url, s.licence ?: licenceNote(s.url), meta?.fetchedAt, meta?.error),
                modifiedAt = content?.let { Files.getLastModifiedTime(it).toInstant() } ?: clock.instant(),
            )
        }
        folder.setRemote(files)
    }

    private fun readMeta(name: String): Meta? = metaFile(name).takeIf { Files.exists(it) }?.let { f ->
        runCatching { json.decodeFromString(Meta.serializer(), Files.readString(f)) }.getOrNull()
    }

    private fun contentFile(s: RuleSource) = cacheDir.resolve(s.name + s.kind.extension)

    private fun metaFile(name: String) = cacheDir.resolve(name + META_SUFFIX)

    private fun atomicWrite(target: Path, bytes: ByteArray) {
        val tmp = Files.createTempFile(cacheDir, ".${target.fileName}.", ".tmp")
        try {
            Files.write(tmp, bytes)
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    companion object {
        const val PREFIX = "remote/"
        private const val META_SUFFIX = ".meta.json"
        private const val CHECK_INTERVAL_MS = 15 * 60 * 1000L
        private const val RETRY_MINUTES = 15L

        fun pathFor(s: RuleSource) = PREFIX + s.name + s.kind.extension

        fun licenceNote(url: String): String {
            val host = runCatching { URI(url).host }.getOrNull() ?: url
            return "Third-party file fetched read-only from $host. It keeps its own licence (JOSM's rule files are GPL-2.0-or-later) " +
                "and is not part of or redistributed with OSM Review Editor."
        }
    }
}
