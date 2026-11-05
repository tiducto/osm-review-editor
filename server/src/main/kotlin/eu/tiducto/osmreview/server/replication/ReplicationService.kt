package eu.tiducto.osmreview.server.replication

import eu.tiducto.osmreview.rules.ClipArea
import eu.tiducto.osmreview.rules.GolMirror
import eu.tiducto.osmreview.rules.existingIds
import eu.tiducto.osmreview.shared.ReplicationStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

data class ReplicationConfig(
    val enabled: Boolean = false,
    val sourceUrl: String = DEFAULT_SOURCE,
    val dir: Path,
    val intervalSeconds: Long = 60,
    val batchSize: Int = 60,
    val area: String? = null,
    val bufferMeters: Double = 2000.0,
    val keepDays: Long = 3,
    val rebaseMarginMinutes: Long = 15,
    val generator: String = "OSM Review Editor clipper",
) {
    companion object {
        const val DEFAULT_SOURCE = "https://planet.openstreetmap.org/replication/minute"
        const val BUNDLED_AREA = "czech-republic.poly"
    }
}

data class RunReport(val sequences: Int, val kept: Int, val dropped: Int, val head: ReplicationState?, val rebased: Boolean)

class ReplicationService(
    private val config: ReplicationConfig,
    private val mirror: GolMirror,
    private val source: ReplicationSource,
    private val onChange: (ReplicationStatus) -> Unit = {},
    private val clock: () -> Instant = Instant::now,
    private val reference: () -> Instant? = { mirror.peerHeaders().mapNotNull { it.timestamp }.minOrNull() },
) {
    private val log = LoggerFactory.getLogger(ReplicationService::class.java)
    private val dir = ReplicationDir(config.dir)
    private val mutex = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val state = AtomicReference(ReplicationStatus(enabled = config.enabled, source = source.url))
    private var area: ClipArea? = null
    private var tracked: TrackedIds? = null
    private var lastPrune: Instant = Instant.EPOCH

    fun status(): ReplicationStatus {
        val s = state.get()
        val ts = s.timestamp?.let(Instant::parse)
        return s.copy(lagSeconds = ts?.let { maxOf(0L, clock().epochSecond - it.epochSecond) })
    }

    private fun update(block: (ReplicationStatus) -> ReplicationStatus) {
        val next = state.updateAndGet(block)
        runCatching { onChange(next) }
    }

    fun requestRun() {
        wake.trySend(Unit)
    }

    fun start(scope: CoroutineScope) {
        if (!config.enabled) return
        dir.head()?.let { h -> update { it.copy(sequence = h.sequence, timestamp = h.timestamp.toString()) } }
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { runOnce() }.onFailure { log.warn("Replication clipper run failed: {}", it.message) }
                val next = clock().plusSeconds(config.intervalSeconds)
                update { it.copy(nextRunAt = next.toString()) }
                withTimeoutOrNull(Duration.ofSeconds(config.intervalSeconds).toMillis()) { wake.receive() }
            }
        }
    }

    suspend fun runOnce(maxSequences: Long = Long.MAX_VALUE): RunReport = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                run(maxSequences).also { update { s -> s.copy(phase = "idle", error = null) } }
            } catch (e: Exception) {
                update { s -> s.copy(phase = "idle", error = e.message ?: e.toString()) }
                throw e
            }
        }
    }

    private fun run(maxSequences: Long): RunReport {
        if (!mirror.isAvailable) {
            update { it.copy(phase = "waiting") }
            throw IllegalStateException("Waiting for the mirror to open")
        }
        val clip = area ?: resolveArea().also { area = it }
        val reference = reference()
            ?: throw IllegalStateException("The GOL has no replication timestamp; it cannot be updated")

        var meta = dir.meta()
        var head = dir.head()
        var rebased = false
        if (meta == null || head == null || meta.source != source.url || !Instant.parse(meta.firstTimestamp).isBefore(reference)) {
            update { it.copy(phase = "rebasing") }
            val start = findStart(reference.minus(Duration.ofMinutes(config.rebaseMarginMinutes)))
            log.info("Rebasing the replication clipper at sequence {} ({}) for GOL data of {}", start.sequence, start.timestamp, reference)
            dir.wipe()
            tracked = TrackedIds()
            val now = clock()
            meta = ClipperMeta(source.url, start.sequence, start.timestamp.toString(), now.toString(), clip.name)
            processBatch(listOf(start), clip)
            dir.writeMeta(meta)
            head = start
            rebased = true
            update { it.copy(rebasedAt = now.toString(), firstSequence = start.sequence) }
        }
        val ids = tracked ?: dir.loadTracked().also { tracked = it }
        val latest = source.latest()
        update { it.copy(sourceSequence = latest.sequence, backlog = latest.sequence - head!!.sequence, firstSequence = meta.firstSequence, area = clip.name) }

        val started = clock()
        var kept = 0
        var dropped = 0
        var done = 0
        var next = head!!.sequence + 1
        val last = minOf(latest.sequence, if (maxSequences == Long.MAX_VALUE) Long.MAX_VALUE else head.sequence + maxSequences)
        while (next <= last) {
            update { it.copy(phase = "clipping") }
            val end = minOf(last, next + config.batchSize - 1)
            val states = (next..end).map { seq ->
                if (seq == latest.sequence) latest else source.state(seq) ?: throw IllegalStateException("State of sequence $seq is missing upstream")
            }
            val r = processBatch(states, clip)
            kept += r.first
            dropped += r.second
            done += states.size
            head = states.last()
            next = end + 1
            val h = head
            update {
                it.copy(
                    sequence = h.sequence, timestamp = h.timestamp.toString(), backlog = latest.sequence - h.sequence,
                    keptTotal = it.keptTotal + r.first, droppedTotal = it.droppedTotal + r.second,
                )
            }
        }
        val secs = Duration.between(started, clock()).toMillis() / 1000.0
        update {
            it.copy(
                lastRunAt = clock().toString(), lastRunSeconds = secs, lastRunSequences = done,
                keptLastRun = kept, droppedLastRun = dropped,
            )
        }
        maybePrune(meta, reference, ids)
        return RunReport(done, kept, dropped, head, rebased)
    }

    private fun processBatch(states: List<ReplicationState>, clip: ClipArea): Pair<Int, Int> {
        val diffs = states.map { OscDiff.parseGz(source.diff(it.sequence)) }
        val ids = tracked ?: TrackedIds().also { tracked = it }
        val clipper = DiffClipper(clip::contains, ids::contains) { n, w, r ->
            val found = mirror.query { gol -> gol.existingIds(n, w, r) }
            found::contains
        }
        val result = clipper.clip(diffs)
        states.zip(result.diffs).forEach { (st, elements) -> dir.writeSequence(st, OscDiff.writeGz(elements, config.generator)) }
        val epoch = states.last().timestamp.epochSecond
        ids.add(result.keptIds, epoch)
        dir.appendTracked(result.keptIds, epoch)
        dir.commitHead(states.last())
        return result.kept to result.dropped
    }

    private fun maybePrune(meta: ClipperMeta, reference: Instant, ids: TrackedIds) {
        val now = clock()
        if (Duration.between(lastPrune, now) < Duration.ofHours(1)) return
        lastPrune = now
        val head = dir.head() ?: return
        val byAge = head.timestamp.minus(Duration.ofDays(config.keepDays))
        val byGol = reference.minus(Duration.ofHours(1))
        val pruned = dir.prune(minOf(byAge, byGol), meta)
        update { it.copy(firstSequence = pruned.firstSequence) }
        if (ids.pruneBefore(reference.minus(Duration.ofDays(1)).epochSecond) > 0) dir.rewriteTracked(ids)
    }

    private fun resolveArea(): ClipArea {
        val spec = config.area?.trim()?.ifEmpty { null }
        return when {
            spec == null -> {
                val text = ReplicationService::class.java.getResource("/replication/${ReplicationConfig.BUNDLED_AREA}")!!.readText()
                ClipArea(ReplicationConfig.BUNDLED_AREA, ClipArea.parsePoly(text), config.bufferMeters)
            }
            spec.startsWith("mirror:") -> mirror.query { gol -> ClipArea.fromMirror(gol, spec.removePrefix("mirror:"), config.bufferMeters) }
                ?: throw IllegalStateException("No admin_level=2 boundary with ISO3166-1=${spec.removePrefix("mirror:")} in the mirror")
            else -> ClipArea.fromFile(Path.of(spec), config.bufferMeters)
        }
    }

    fun findStart(target: Instant): ReplicationState {
        val latest = source.latest()
        if (latest.timestamp.isBefore(target)) return latest
        var guess = latest.sequence - Duration.between(target, latest.timestamp).toMinutes() - 2
        var probe = stateNear(guess)
        while (!probe.timestamp.isBefore(target)) {
            guess = probe.sequence - maxOf(5L, Duration.between(target, probe.timestamp).toMinutes() + 5)
            probe = stateNear(guess)
        }
        while (true) {
            val next = source.state(probe.sequence + 1) ?: return probe
            if (!next.timestamp.isBefore(target)) return probe
            probe = next
        }
    }

    private fun stateNear(sequence: Long): ReplicationState {
        val s = maxOf(1L, sequence)
        val tries = (0L..NEAR).map { s - it }.filter { it >= 1 } + (1L..NEAR).map { s + it }
        for (t in tries) source.state(t)?.let { return it }
        throw IllegalStateException("No replication state near sequence $sequence at ${source.url}")
    }

    private companion object {
        const val NEAR = 10L
    }
}
