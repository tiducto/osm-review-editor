package eu.tiducto.osmreview.server.replication

import eu.tiducto.osmreview.rules.ClipArea
import eu.tiducto.osmreview.rules.GolHeader
import eu.tiducto.osmreview.rules.GolMirror
import eu.tiducto.osmreview.rules.WayNodeIds
import eu.tiducto.osmreview.rules.existingIds
import eu.tiducto.osmreview.shared.OsmType
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MissingNodesTest {
    private val recorded = Path.of("src/test/resources/replication/minute")
    private val updatable = System.getenv("OSM_REVIEW_TEST_GOL_UPDATABLE")?.takeIf { it.isNotBlank() }?.let(Path::of)?.takeIf(Files::exists)
    private val poly = ClipArea.parsePoly(javaClass.getResource("/replication/czech-republic.poly")!!.readText())

    private fun diff(seq: Long) = OscDiff.parseGz(recorded.resolve(ReplicationState.path(seq) + ".osc.gz").readBytes())

    private val nothingInGol = IdLookup { _, _, _ -> { _, _ -> false } }

    private class RecordingFetcher : NodeFetcher {
        val asked = LongHashSet()
        override fun fetch(ids: LongArray): List<OscElement> {
            ids.forEach { asked.add(it) }
            return ids.map { OscElement(OscAction.MODIFY, OsmType.NODE, it, listOf("id" to "$it", "version" to "1", "lat" to "50.7", "lon" to "13.5"), emptyList()) }
        }
    }

    private fun unresolvedRefs(result: ClipResult, known: (Long) -> Boolean): Set<Long> {
        val out = result.diffs.flatten()
        val nodes = out.filter { it.type == OsmType.NODE }.map { it.id }.toSet()
        return out.filter { it.type == OsmType.WAY && it.action != OscAction.DELETE }
            .flatMap { it.nodeRefs }
            .filter { it !in nodes && !known(it) }
            .toSet()
    }

    @Test
    fun aBufferBeyondTheExtractPolygonPullsInWaysWhoseNodesNobodyHas() {
        val buffered = ClipArea("czech-republic.poly", poly, 2000.0)
        val result = DiffClipper(buffered::contains, { _, _ -> false }, nothingInGol).clip(listOf(diff(7358755)))
        val kept = result.diffs.single().filter { it.type == OsmType.WAY }.map { it.id }.toSet()
        assertTrue(kept.containsAll(listOf(297092345L, 32044798L, 1346181426L)))
        val unresolved = unresolvedRefs(result) { false }
        assertTrue(unresolved.containsAll(listOf(359691336L, 3009234521L, 3009234508L, 12451284553L)), "$unresolved")
        assertEquals(unresolved, result.unresolved.toArray().toSet())
    }

    @Test
    fun theExtractPolygonWithoutBufferKeepsNothingOfTheGermanChangeset() {
        val area = ClipArea("czech-republic.poly", poly, 0.0)
        val result = DiffClipper(area::contains, { _, _ -> false }, nothingInGol).clip(listOf(diff(7358755)))
        val ways = result.diffs.single().filter { it.type == OsmType.WAY }.map { it.id }.toSet()
        assertTrue(listOf(297092345L, 32044798L, 1346181426L, 163197731L).none { it in ways }, "$ways")
        assertTrue(result.unresolved.isEmpty, "${result.unresolved}")
    }

    @Test
    fun missingWayNodesAreFetchedAndPutBeforeTheirWays() {
        val buffered = ClipArea("czech-republic.poly", poly, 2000.0)
        val fetcher = RecordingFetcher()
        val result = DiffClipper(buffered::contains, { _, _ -> false }, nothingInGol, fetcher = fetcher).clip(listOf(diff(7358755)))
        assertTrue(result.unresolved.isEmpty)
        assertEquals(fetcher.asked.size(), result.fetched)
        assertTrue(unresolvedRefs(result) { false }.isEmpty())
        val out = result.diffs.single()
        assertEquals(OsmType.NODE, out.first().type)
        assertTrue(out.indexOfFirst { it.id == 359691336L && it.type == OsmType.NODE } < out.indexOfFirst { it.id == 32044798L && it.type == OsmType.WAY })
    }

    @Test
    fun everyKeptWayOfTheRecordedDiffsIsComplete() {
        val area = ClipArea("czech-republic.poly", poly, 0.0)
        val fetcher = RecordingFetcher()
        val diffs = (7360200L..7360205L).map(::diff)
        val result = DiffClipper(area::contains, { _, _ -> false }, nothingInGol, fetcher = fetcher).clip(diffs)
        assertTrue(unresolvedRefs(result) { false }.isEmpty())
        println("recorded diffs without a mirror: kept ${result.kept}, dropped ${result.dropped}, fetched ${result.fetched} way nodes")
    }

    @Test
    fun againstTheSpikeGolNoWayLosesNodes() {
        val gol = updatable ?: return println("OSM_REVIEW_TEST_GOL_UPDATABLE not set, skipping")
        assertTrue(GolHeader.read(gol)!!.hasWaynodeIds)
        GolMirror(Files.createTempDirectory("gol"), gol).use { mirror ->
            mirror.reloadIfChanged()
            val area = ClipArea("czech-republic.poly", poly, 0.0)
            val outer = ClipArea("outer", poly, 10_000.0)
            val fetcher = RecordingFetcher()
            val clipper = DiffClipper(
                area::contains, { _, _ -> false },
                { n, w, r -> mirror.query { it.existingIds(n, w, r, true) }::contains },
                outer::contains,
                { points -> mirror.query { WayNodeIds.near(it, points, 200.0) } },
                fetcher,
            )
            val result = clipper.clip(listOf(diff(7358755)))
            println("7358755 against ${gol.fileName}: kept ${result.kept}, dropped ${result.dropped}, fetched ${fetcher.asked.toSortedArray().toList()}")
            assertTrue(result.unresolved.isEmpty)
            assertTrue(result.diffs.single().none { it.type == OsmType.WAY && it.id in setOf(297092345L, 32044798L, 1346181426L) })
            val recordedFetcher = RecordingFetcher()
            val again = DiffClipper(
                area::contains, { _, _ -> false },
                { n, w, r -> mirror.query { it.existingIds(n, w, r, true) }::contains },
                outer::contains,
                { points -> mirror.query { WayNodeIds.near(it, points, 200.0) } },
                recordedFetcher,
            ).clip((7360200L..7360205L).map(::diff))
            println("7360200..205 against ${gol.fileName}: kept ${again.kept}, dropped ${again.dropped}, fetched ${recordedFetcher.asked.toSortedArray().toList()}")
            assertTrue(again.unresolved.isEmpty)
            assertTrue(again.fetched < 20)
        }
    }
}
