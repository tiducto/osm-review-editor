package eu.tiducto.osmreview.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TagDiffTest {
    private val base = mapOf("highway" to "footway", "surface" to "gravel", "sidewalk" to "both")

    @Test
    fun diffSeparatesAddedRemovedChangedUnchanged() {
        val proposed = mapOf("highway" to "footway", "surface" to "asphalt", "lit" to "yes")
        val d = TagDiff.between(base, proposed)
        assertEquals(mapOf("lit" to "yes"), d.added)
        assertEquals(mapOf("sidewalk" to "both"), d.removed)
        assertEquals(mapOf("surface" to listOf("gravel", "asphalt")), d.changed)
        assertEquals(listOf("highway"), d.unchanged)
        assertEquals(setOf("lit", "sidewalk", "surface"), d.touchedKeys)
    }

    @Test
    fun identicalTagsAreNoOp() {
        assertTrue(TagDiff.between(base, base.toMap()).isNoOp)
    }

    @Test
    fun applyRebasesOntoNewerTags() {
        val d = TagDiff.between(base, base + ("area" to "yes"))
        val upstream = base + ("name" to "Nástupiště A")
        assertEquals(upstream + ("area" to "yes"), d.applyTo(upstream))
    }

    @Test
    fun jsonShapeMatchesSpec() {
        val d = TagDiff.between(mapOf("a" to "1", "b" to "2"), mapOf("a" to "3", "c" to "4"))
        assertEquals(
            """{"added":{"c":"4"},"removed":{"b":"2"},"changed":{"a":["1","3"]},"unchanged":[]}""",
            WireJson.encodeToString(d),
        )
    }

    @Test
    fun tagEditOrderIsReplaceRemoveSet() {
        val edit = TagEdit(set = mapOf("sidewalk:both" to "separate"), remove = listOf("sidewalk"))
        assertEquals(
            mapOf("highway" to "footway", "sidewalk:both" to "separate", "surface" to "gravel"),
            edit.applyTo(base),
        )
        assertEquals(mapOf("x" to "y"), TagEdit(replaceAll = mapOf("x" to "y")).applyTo(base))
    }

    @Test
    fun laterEditWins() {
        val merged = TagEdit(set = mapOf("a" to "1"), remove = listOf("b")) + TagEdit(set = mapOf("b" to "2"), remove = listOf("a"))
        assertEquals(mapOf("b" to "2"), merged.set)
        assertEquals(listOf("a"), merged.remove)
    }

    @Test
    fun osmRefRoundTrips() {
        assertEquals(OsmRef(OsmType.WAY, 123), OsmRef.parse("w123"))
        assertEquals("n-5", OsmRef(OsmType.NODE, -5).toString())
        assertFailsWith<IllegalArgumentException> { OsmRef.parse("x1") }
    }

    @Test
    fun summaryIsCompact() {
        val d = TagDiff.between(mapOf("a" to "1", "b" to "2"), mapOf("a" to "3", "c" to "4"))
        assertEquals("+c=4 ~a=3 −b", d.summary())
    }
}
