package eu.tiducto.osmreview.server.replication

import eu.tiducto.osmreview.rules.GolMirror
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordedSource(private val root: Path, private val last: Long = 7360205) : ReplicationSource {
    override val url = "recorded:minute"
    var requests = 0

    override fun latest(): ReplicationState = state(last)!!

    override fun state(sequence: Long): ReplicationState? {
        requests++
        if (sequence > last) return null
        val f = root.resolve(ReplicationState.path(sequence) + ".state.txt")
        return if (f.exists()) ReplicationState.parse(f.readText()) else null
    }

    override fun diff(sequence: Long): ByteArray {
        requests++
        val f = root.resolve(ReplicationState.path(sequence) + ".osc.gz")
        if (!f.exists() || sequence > last) throw IOException("missing $sequence")
        return f.readBytes()
    }
}

class ReplicationServiceTest {
    private val recorded = Path.of("src/test/resources/replication/minute")
    private val fixture = Path.of("../rules/src/test/resources/fixtures/katerinky-highways.gol")
    private val realGol = System.getenv("OSM_REVIEW_TEST_GOL")?.takeIf { it.isNotBlank() }?.let(Path::of)?.takeIf(Files::exists)

    private fun ts(seq: Long) = RecordedSource(recorded).state(seq)!!.timestamp

    private fun service(gol: Path, out: Path, source: ReplicationSource, mirror: GolMirror, batch: Int = 2) = ReplicationService(
        ReplicationConfig(enabled = true, dir = out, batchSize = batch),
        mirror, source,
        reference = { ts(7360201).plus(Duration.ofMinutes(15)).plusSeconds(1) },
    )

    @Test
    fun clipsRecordedDiffsIntoALocalReplicationDirectory() {
        val out = Files.createTempDirectory("replication")
        GolMirror(out.resolve("x"), fixture).use { mirror ->
            mirror.reloadIfChanged()
            val src = RecordedSource(recorded)
            val first = runBlocking { service(fixture, out, src, mirror).runOnce(maxSequences = 2) }
            assertTrue(first.rebased)
            assertEquals(7360203, first.head!!.sequence)
            assertEquals(2, first.sequences)

            val dir = ReplicationDir(out)
            assertEquals(7360203, dir.head()!!.sequence)
            assertEquals(7360201, dir.meta()!!.firstSequence)

            val resumed = runBlocking { service(fixture, out, src, mirror).runOnce() }
            assertEquals(false, resumed.rebased)
            assertEquals(2, resumed.sequences)
            assertEquals(7360205, dir.head()!!.sequence)
            assertEquals(ts(7360205), dir.head()!!.timestamp)

            var total = 0
            var czech = 0
            for (seq in 7360201L..7360205L) {
                assertEquals(ts(seq), dir.state(seq)!!.timestamp)
                val kept = OscDiff.parseGz(dir.file(seq, ".osc.gz").readBytes())
                val orig = OscDiff.parseGz(RecordedSource(recorded).diff(seq))
                total += orig.size
                czech += kept.size
                println("sequence $seq: ${orig.size} changes, kept ${kept.size}")
                assertTrue(kept.size < orig.size)
                kept.forEach { e -> e.lat?.let { lat -> assertTrue(lat in 47.0..52.5, "${e.id} at $lat") } }
            }
            assertTrue(czech > 0, "the recorded diffs contain Czech edits")
            assertTrue(dir.root.resolve("state.txt").readText().contains("sequenceNumber=7360205"))

            val idle = runBlocking { service(fixture, out, src, mirror).runOnce() }
            assertEquals(0, idle.sequences)
        }
    }

    @Test
    fun rebasesWhenTheGolIsOlderThanTheLocalReplication() {
        val out = Files.createTempDirectory("replication")
        GolMirror(out.resolve("x"), fixture).use { mirror ->
            mirror.reloadIfChanged()
            val src = RecordedSource(recorded)
            var reference = ts(7360204).plusSeconds(1)
            val config = ReplicationConfig(enabled = true, dir = out, batchSize = 10, rebaseMarginMinutes = 0)
            runBlocking { ReplicationService(config, mirror, src, reference = { reference }).runOnce() }
            assertEquals(7360204, ReplicationDir(out).meta()!!.firstSequence)
            reference = ts(7360204)
            val again = runBlocking { ReplicationService(config, mirror, src, reference = { reference }).runOnce() }
            assertTrue(again.rebased)
            assertEquals(7360203, ReplicationDir(out).meta()!!.firstSequence)
            assertEquals(7360205, ReplicationDir(out).head()!!.sequence)
        }
    }

    @Test
    fun findsTheStartSequenceByTimestamp() {
        val out = Files.createTempDirectory("replication")
        GolMirror(out.resolve("x"), fixture).use { mirror ->
            val s = ReplicationService(ReplicationConfig(dir = out), mirror, RecordedSource(recorded))
            assertEquals(7360202, s.findStart(ts(7360202).plusSeconds(1)).sequence)
            assertEquals(7360201, s.findStart(ts(7360202)).sequence)
        }
    }

    @Test
    fun clipsAgainstTheRealMirror() {
        val gol = realGol ?: return println("OSM_REVIEW_TEST_GOL not set, skipping")
        val out = Files.createTempDirectory("replication")
        GolMirror(out.resolve("x"), gol).use { mirror ->
            mirror.reloadIfChanged()
            val src = RecordedSource(recorded)
            val started = Instant.now()
            val r = runBlocking { service(gol, out, src, mirror, batch = 5).runOnce() }
            println(
                "real GOL ${gol.fileName}: ${r.sequences} sequences, kept ${r.kept}, dropped ${r.dropped} " +
                    "in ${Duration.between(started, Instant.now()).toMillis()} ms",
            )
            assertEquals(7360205, r.head!!.sequence)
            assertTrue(r.kept > 0)
        }
    }
}
