package eu.tiducto.osmreview.rules

import com.geodesk.feature.FeatureLibrary
import com.geodesk.geom.Box
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class GolUpdateSpikeTest {
    @Test
    fun updatedGolShowsChangedObjects() {
        val before = System.getenv("OSM_REVIEW_TEST_GOL")?.takeIf { it.isNotBlank() } ?: return
        val after = System.getenv("OSM_REVIEW_TEST_GOL_AFTER")?.takeIf { it.isNotBlank() } ?: return
        val box = Box.ofWSEN(16.56, 49.17, 16.65, 49.22)
        fun snapshot(p: String) = FeatureLibrary(Path.of(p)).use { gol ->
            gol.`in`(box).associate { it.ref to it.tagMap() }
        }
        val a = snapshot(before)
        val b = snapshot(after)
        val added = b.keys - a.keys
        val removed = a.keys - b.keys
        val changed = (a.keys intersect b.keys).filter { a[it] != b[it] }
        println("Brno-střed: ${a.size} -> ${b.size} features; added ${added.size}, removed ${removed.size}, changed ${changed.size}")
        changed.take(5).forEach { println("  changed $it: ${a[it]} -> ${b[it]}") }
        assertTrue(added.size + removed.size + changed.size > 0, "expected the update to change something in Brno-střed")
    }
}
