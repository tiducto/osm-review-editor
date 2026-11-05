package eu.tiducto.osmreview.server.rulesfolder

import eu.tiducto.osmreview.server.core.EventBus
import eu.tiducto.osmreview.server.core.NotFoundException
import eu.tiducto.osmreview.server.core.StateException
import eu.tiducto.osmreview.shared.DeleteRuleFileResult
import eu.tiducto.osmreview.shared.RuleDiagnostic
import eu.tiducto.osmreview.shared.RuleFileContent
import eu.tiducto.osmreview.shared.RuleFileInfo
import eu.tiducto.osmreview.shared.RuleKind
import eu.tiducto.osmreview.shared.RuleOrigin
import eu.tiducto.osmreview.shared.UiEvent
import eu.tiducto.osmreview.shared.ValidateRuleFileResult
import eu.tiducto.osmreview.shared.WriteRuleFileResult
import eu.tiducto.osmreview.shared.hasErrors
import org.slf4j.LoggerFactory
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.streams.asSequence

data class RulesChange(val changed: List<String>, val removed: List<String>) {
    val isEmpty: Boolean get() = changed.isEmpty() && removed.isEmpty()
}

data class RemoteRuleFile(val path: String, val kind: RuleKind, val content: String, val origin: RuleOrigin, val modifiedAt: Instant)

class RulesFolder(
    configuredRoot: Path,
    private val events: EventBus? = null,
    loaders: List<RuleLoader<*>> = emptyList(),
    private val debounceMs: Long = 300,
    builtInFiles: List<RemoteRuleFile> = emptyList(),
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(RulesFolder::class.java)
    val root: Path = configuredRoot.toAbsolutePath().normalize().also { r ->
        Files.createDirectories(r)
        SUBDIRS.forEach { runCatching { Files.createDirectories(r.resolve(it)) } }
    }.toRealPath()

    private val lock = Any()
    private val loaders = ConcurrentHashMap<RuleKind, RuleLoader<*>>()
    private val listeners = CopyOnWriteArrayList<(RulesChange) -> Unit>()

    private class Entry(
        val path: String,
        val kind: RuleKind,
        val hash: String,
        val mtime: FileTime,
        val size: Long,
        val source: String,
        val value: Any?,
        val diagnostics: List<RuleDiagnostic>,
        val origin: RuleOrigin? = null,
    ) {
        fun loaded() = LoadedRuleFile(path, kind, source, value, diagnostics, origin != null, hash)
    }

    @Volatile private var local: Map<String, Entry> = emptyMap()

    @Volatile private var remote: Map<String, Entry> = emptyMap()

    @Volatile private var cross: Map<String, List<RuleDiagnostic>> = emptyMap()

    @Volatile private var watcher: Thread? = null

    @Volatile private var watchService: WatchService? = null

    init {
        listOf(PlainTextLoader(RuleKind.HIGHLIGHT), PlainTextLoader(RuleKind.CHECK), SourcesLoader()).forEach { this.loaders[it.kind] = it }
        loaders.forEach { this.loaders[it.kind] = it }
    }

    @Volatile private var builtIn: Map<String, Entry> = builtInFiles.sortedBy { it.path }.associate { f ->
        val (value, diags) = load(f.path, f.kind, f.content)
        val bytes = f.content.toByteArray()
        f.path to Entry(f.path, f.kind, sha256(bytes), FileTime.from(f.modifiedAt), bytes.size.toLong(), f.content, value, diags, f.origin)
    }

    init {
        if (builtIn.isNotEmpty()) recomputeCross()
    }

    private fun effective(): List<Entry> {
        val l = local
        return l.values + remote.values + builtIn.values.filter { it.path !in l }
    }

    private fun builtInOnly(path: String): Entry? = if (path in local) null else builtIn[path]

    fun register(loader: RuleLoader<*>) {
        val change = synchronized(lock) {
            loaders[loader.kind] = loader
            local = local.mapValues { (_, e) -> if (e.kind == loader.kind) reload(e) else e }
            remote = remote.mapValues { (_, e) -> if (e.kind == loader.kind) reload(e) else e }
            builtIn = builtIn.mapValues { (_, e) -> if (e.kind == loader.kind) reload(e) else e }
            recomputeCross()
            RulesChange(effective().filter { it.kind == loader.kind }.map { it.path }, emptyList())
        }
        publish(change)
    }

    fun onChange(listener: (RulesChange) -> Unit) {
        listeners += listener
    }

    private fun reload(e: Entry): Entry {
        val (value, diags) = load(e.path, e.kind, e.source)
        return Entry(e.path, e.kind, e.hash, e.mtime, e.size, e.source, value, diags, e.origin)
    }

    private fun load(path: String, kind: RuleKind, source: String): Pair<Any?, List<RuleDiagnostic>> {
        val loader = loaders[kind] ?: return source to emptyList()
        return try {
            val r = loader.load(path, source)
            r.value to r.diagnostics
        } catch (e: Exception) {
            log.warn("Loader for {} failed on {}", kind, path, e)
            null to listOf(RuleDiagnostic(path, 1, 1, eu.tiducto.osmreview.shared.DiagnosticSeverity.ERROR, "Could not load: ${e.message}"))
        }
    }

    private fun recomputeCross() {
        cross = crossFor(effective().map { it.loaded() })
    }

    private fun crossFor(all: List<LoadedRuleFile>): Map<String, List<RuleDiagnostic>> {
        val out = HashMap<String, List<RuleDiagnostic>>()
        for ((kind, loader) in loaders) {
            val files = all.filter { it.kind == kind }
            if (files.isEmpty()) continue
            runCatching { loader.crossCheck(files) }
                .onFailure { log.warn("Cross-check for {} failed", kind, it) }
                .getOrNull()
                ?.forEach { (p, d) -> out[p] = out[p].orEmpty() + d }
        }
        return out
    }

    fun rescan(): RulesChange {
        val change = synchronized(lock) { scanLocked() }
        publish(change)
        return change
    }

    private fun scanLocked(): RulesChange {
        val found = LinkedHashMap<String, Path>()
        Files.walk(root).use { stream ->
            stream.asSequence()
                .filter { it != root && (it.isRegularFile(LinkOption.NOFOLLOW_LINKS) || it.isSymbolicLink()) }
                .forEach { p ->
                    val rel = root.relativize(p).joinToString("/")
                    if (rel.split('/').any { it.startsWith(".") }) return@forEach
                    if (RuleKind.forPath(rel) == null) return@forEach
                    if (p.isSymbolicLink()) {
                        val real = runCatching { p.toRealPath() }.getOrNull()
                        if (real == null || !real.startsWith(root) || !real.isRegularFile()) {
                            log.warn("Ignoring {}: symlink to outside the rules folder or to a missing file", rel)
                            return@forEach
                        }
                    }
                    found[rel] = p
                }
        }
        val old = local
        val next = LinkedHashMap<String, Entry>()
        val changed = ArrayList<String>()
        for ((rel, p) in found.toSortedMap()) {
            val attrs = runCatching { Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes::class.java) }.getOrNull() ?: continue
            val prev = old[rel]
            if (prev != null && prev.mtime == attrs.lastModifiedTime() && prev.size == attrs.size()) {
                next[rel] = prev
                continue
            }
            val bytes = runCatching { Files.readAllBytes(p) }.getOrNull() ?: continue
            val hash = sha256(bytes)
            if (prev != null && prev.hash == hash) {
                next[rel] = Entry(rel, prev.kind, hash, attrs.lastModifiedTime(), attrs.size(), prev.source, prev.value, prev.diagnostics)
                continue
            }
            val kind = RuleKind.forPath(rel)!!
            val text = decode(bytes)
            val entry = if (text == null) {
                Entry(rel, kind, hash, attrs.lastModifiedTime(), attrs.size(), "", null, listOf(error(rel, "The file is not valid UTF-8 text")))
            } else if (bytes.size > MAX_BYTES) {
                Entry(rel, kind, hash, attrs.lastModifiedTime(), attrs.size(), "", null, listOf(error(rel, "The file is larger than ${MAX_BYTES / 1024 / 1024} MB and is ignored")))
            } else {
                val (value, diags) = load(rel, kind, text)
                Entry(rel, kind, hash, attrs.lastModifiedTime(), attrs.size(), text, value, diags)
            }
            next[rel] = entry
            changed += rel
        }
        val gone = old.keys.filter { it !in next }
        val restored = gone.filter { it in builtIn }
        val removed = gone - restored.toSet()
        local = next
        if (changed.isNotEmpty() || gone.isNotEmpty()) recomputeCross()
        return RulesChange(changed + restored, removed)
    }

    fun setRemote(files: List<RemoteRuleFile>) {
        val change = synchronized(lock) {
            val old = remote
            val next = LinkedHashMap<String, Entry>()
            val changed = ArrayList<String>()
            for (f in files.sortedBy { it.path }) {
                val hash = sha256(f.content.toByteArray())
                val prev = old[f.path]
                next[f.path] = if (prev != null && prev.hash == hash && prev.kind == f.kind) {
                    if (prev.origin != f.origin) changed += f.path
                    Entry(f.path, f.kind, hash, prev.mtime, prev.size, prev.source, prev.value, prev.diagnostics, f.origin)
                } else {
                    changed += f.path
                    val (value, diags) = load(f.path, f.kind, f.content)
                    Entry(f.path, f.kind, hash, FileTime.from(f.modifiedAt), f.content.toByteArray().size.toLong(), f.content, value, diags, f.origin)
                }
            }
            val removed = old.keys.filter { it !in next }
            remote = next
            if (changed.isNotEmpty() || removed.isNotEmpty()) recomputeCross()
            RulesChange(changed, removed)
        }
        publish(change)
    }

    private fun publish(change: RulesChange) {
        if (change.isEmpty) return
        log.info("Rules changed: {} changed, {} removed", change.changed.size, change.removed.size)
        listeners.forEach { l -> runCatching { l(change) }.onFailure { log.warn("Rules listener failed", it) } }
        events?.emit(UiEvent.RulesChanged(change.changed, change.removed))
    }

    private fun info(e: Entry): RuleFileInfo = RuleFileInfo(
        path = e.path,
        kind = e.kind,
        hash = e.hash,
        modifiedAt = e.mtime.toInstant().toString(),
        size = e.size,
        readOnly = e.origin != null,
        diagnostics = e.diagnostics + cross[e.path].orEmpty(),
        origin = e.origin,
    )

    fun list(kind: RuleKind? = null): List<RuleFileInfo> =
        effective().filter { kind == null || it.kind == kind }.map(::info)

    fun loaded(kind: RuleKind): List<LoadedRuleFile> {
        val c = cross
        return effective().filter { it.kind == kind }.map { e ->
            e.loaded().copy(diagnostics = e.diagnostics + c[e.path].orEmpty())
        }
    }

    fun info(path: String): RuleFileInfo? = (local[path] ?: remote[path] ?: builtIn[path])?.let(::info)

    fun read(path: String): RuleFileContent {
        remote[path]?.let { return RuleFileContent(info(it), it.source) }
        resolve(path)
        val e = local[path] ?: run {
            rescan()
            local[path]
        } ?: builtIn[path] ?: throw NotFoundException("No rules file '$path'")
        return RuleFileContent(info(e), e.source)
    }

    fun validate(path: String, content: String): ValidateRuleFileResult {
        val kind = (remote[path] ?: builtIn[path])?.kind ?: kindOf(path)
        val diags = diagnosticsFor(path, kind, content)
        return ValidateRuleFileResult(path, kind, !diags.hasErrors(), diags)
    }

    private fun diagnosticsFor(path: String, kind: RuleKind, content: String): List<RuleDiagnostic> {
        if (content.toByteArray().size > MAX_BYTES) return listOf(error(path, "The file is larger than ${MAX_BYTES / 1024 / 1024} MB"))
        val (value, diags) = load(path, kind, content)
        val others = effective().filter { it.kind == kind && it.path != path }.map { it.loaded() }
        val crossDiags = crossFor(others + LoadedRuleFile(path, kind, content, value, diags, false))[path].orEmpty()
        return diags + crossDiags
    }

    fun write(path: String, content: String, force: Boolean = false, expectedHash: String? = null, createOnly: Boolean = false): WriteRuleFileResult {
        if (path in remote) throw StateException("'$path' comes from a remote source and is read-only; edit ${RuleKind.SOURCES_FILE} instead")
        val result = synchronized(lock) {
            val target = resolve(path)
            val exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS)
            if (createOnly && exists) throw StateException("'$path' already exists")
            if (expectedHash != null) {
                val current = if (exists) sha256(Files.readAllBytes(target)) else null
                if (current != expectedHash) throw StateException("'$path' changed since it was read (hash ${current ?: "none"}); reload it first")
            }
            val kind = kindOf(path)
            val diags = diagnosticsFor(path, kind, content)
            if (!force && diags.hasErrors()) return WriteRuleFileResult(saved = false, file = null, diagnostics = diags)
            require(content.toByteArray().size <= MAX_BYTES) { "The file is larger than ${MAX_BYTES / 1024 / 1024} MB" }
            Files.createDirectories(target.parent)
            val tmp = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
            try {
                Files.writeString(tmp, content)
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(tmp)
            }
            diags
        }
        rescan()
        return WriteRuleFileResult(saved = true, file = info(path), diagnostics = result)
    }

    fun delete(path: String): DeleteRuleFileResult {
        if (path in remote) throw StateException("'$path' comes from a remote source; remove it from ${RuleKind.SOURCES_FILE} instead")
        val dest = synchronized(lock) {
            val target = resolve(path)
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (path in builtIn) {
                    throw StateException("'$path' is built in and cannot be deleted; save a file with the same path to override it, or copy it")
                }
                throw NotFoundException("No rules file '$path'")
            }
            val stamp = TRASH_STAMP.format(Instant.now())
            var dir = root.resolve(TRASH).resolve(stamp)
            var n = 1
            while (Files.exists(dir.resolve(path))) dir = root.resolve(TRASH).resolve("$stamp-${n++}")
            val dest = dir.resolve(path)
            Files.createDirectories(dest.parent)
            Files.move(target, dest)
            dest
        }
        rescan()
        return DeleteRuleFileResult(path, root.relativize(dest).joinToString("/"))
    }

    fun copy(path: String, to: String? = null): WriteRuleFileResult {
        val source = remote[path] ?: builtIn[path] ?: local[path] ?: throw NotFoundException("No rules file '$path'")
        val target = to?.takeIf { it.isNotBlank() } ?: defaultCopyTarget(source)
        return write(target, source.source, force = true, createOnly = true)
    }

    private fun defaultCopyTarget(e: Entry): String {
        if (!e.path.startsWith(RemoteRuleSources.PREFIX)) return e.path
        var name = e.path.removePrefix(RemoteRuleSources.PREFIX).removeSuffix(e.kind.extension)
        while (name.endsWith(e.kind.extension)) name = name.removeSuffix(e.kind.extension)
        if (e.kind == RuleKind.CHECK) name = name.removeSuffix(".validator")
        if (e.kind == RuleKind.HIGHLIGHT) name = name.removeSuffix(".mapcss")
        return e.kind.pattern.replace("*", name)
    }

    private fun kindOf(path: String): RuleKind = RuleKind.forPath(path)
        ?: throw IllegalArgumentException("'$path' is not an allowed rules file; use ${RuleKind.allowedPatterns}")

    fun resolve(path: String): Path {
        require(path.isNotBlank()) { "path is required" }
        require('\u0000' !in path && '\\' !in path) { "Invalid characters in path '$path'" }
        require(!path.startsWith("/")) { "Paths are relative to the rules folder: '$path'" }
        val segments = path.split('/')
        require(segments.none { it.isEmpty() || it == "." || it == ".." }) { "Path '$path' must not contain empty, '.' or '..' segments" }
        require(segments.none { it.startsWith(".") }) { "Hidden files and folders are not rules files: '$path'" }
        kindOf(path)
        val p = root.resolve(path).normalize()
        require(p.startsWith(root)) { "Path '$path' escapes the rules folder" }
        var cur: Path? = p
        while (cur != null && cur.startsWith(root) && !Files.exists(cur, LinkOption.NOFOLLOW_LINKS)) cur = cur.parent
        if (cur != null && cur != root) {
            val real = runCatching { cur.toRealPath() }.getOrNull()
                ?: throw IllegalArgumentException("'$path' is a symlink to a missing file")
            require(real.startsWith(root)) { "'$path' resolves through a symlink to outside the rules folder" }
        }
        return p
    }

    fun startWatching() {
        if (watcher != null) return
        val ws = runCatching { FileSystems.getDefault().newWatchService() }.getOrElse {
            log.warn("Rules folder watching unavailable: {}", it.message)
            return
        }
        watchService = ws
        val t = Thread({ watchLoop(ws) }, "rules-watch").apply { isDaemon = true }
        watcher = t
        t.start()
        log.info("Watching rules folder {}", root)
    }

    private fun register(ws: WatchService, dir: Path) {
        if (!dir.isDirectory(LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(dir).use { s ->
            s.asSequence()
                .filter { it.isDirectory(LinkOption.NOFOLLOW_LINKS) }
                .filter { d -> root.relativize(d).none { it.toString().startsWith(".") } }
                .forEach { d ->
                    runCatching {
                        d.register(ws, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY)
                    }
                }
        }
    }

    private fun watchLoop(ws: WatchService) {
        register(ws, root)
        var due = 0L
        try {
            while (true) {
                val key = ws.poll(100, TimeUnit.MILLISECONDS)
                if (key != null) {
                    val dir = key.watchable() as Path
                    for (ev in key.pollEvents()) {
                        val name = ev.context() as? Path ?: continue
                        val child = dir.resolve(name)
                        if (ev.kind() == StandardWatchEventKinds.ENTRY_CREATE && child.isDirectory(LinkOption.NOFOLLOW_LINKS)) register(ws, child)
                    }
                    key.reset()
                    due = System.currentTimeMillis() + debounceMs
                }
                if (due != 0L && System.currentTimeMillis() >= due) {
                    due = 0L
                    runCatching { rescan() }.onFailure { log.warn("Rules rescan failed", it) }
                }
            }
        } catch (_: ClosedWatchServiceException) {
        } catch (_: InterruptedException) {
        }
    }

    override fun close() {
        runCatching { watchService?.close() }
        watcher?.interrupt()
        watcher = null
    }

    companion object {
        const val TRASH = ".trash"
        const val MAX_BYTES = 2 * 1024 * 1024
        val SUBDIRS = listOf("highlights", "checks", "presets", "presets/smart")
        private val TRASH_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSS'Z'").withZone(ZoneOffset.UTC)

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        private fun decode(bytes: ByteArray): String? = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
                .removePrefix("﻿")
        } catch (_: CharacterCodingException) {
            null
        }

        private fun error(path: String, msg: String) =
            RuleDiagnostic(path, 1, 1, eu.tiducto.osmreview.shared.DiagnosticSeverity.ERROR, msg)
    }
}
