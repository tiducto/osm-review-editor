package eu.tiducto.osmreview.rules

import com.geodesk.feature.FeatureLibrary
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WayNodeIdsTest {
    private val gol = System.getenv("OSM_REVIEW_TEST_GOL_UPDATABLE")?.takeIf { it.isNotBlank() }?.let(Path::of)?.takeIf(Files::exists)

    @Test
    fun decodesWayNodeIdsOfAnUpdatableGol() {
        val path = gol ?: return println("OSM_REVIEW_TEST_GOL_UPDATABLE not set, skipping")
        assertTrue(GolHeader.read(path)!!.hasWaynodeIds)
        FeatureLibrary(path).use { lib ->
            var checked = 0
            for (w in lib.select("w[highway]").`in`(Geo.box(eu.tiducto.osmreview.shared.BBox(13.5, 50.70, 13.6, 50.76)))) {
                val ids = WayNodeIds.of(w)!!
                val featureNodes = w.nodes().map { it.id() }.filter { it != 0L }
                assertTrue(ids.toSet().containsAll(featureNodes), "w${w.id()}: ${ids.toList()} vs $featureNodes")
                val xy = w.toXY()
                val stored = if (w.isArea) xy.size / 2 - 1 else xy.size / 2
                assertEquals(stored, ids.size, "w${w.id()}")
                if (w.id() == 32044798L || w.id() == 297092345L) println("w${w.id()}: ${ids.toList()}")
                checked++
            }
            println("checked $checked ways")
            assertTrue(checked > 10)
        }
    }
}
