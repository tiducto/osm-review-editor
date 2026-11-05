package eu.tiducto.osmreview.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PseudoTagsTest {
    private val real = mapOf("type" to "boundary", "boundary" to "administrative", "name" to "Česko")

    @Test
    fun geodeskKeysAreStripped() {
        val mirror = real + ("geodesk:missing_members" to "2236") + ("geodesk:anything" to "x")
        assertEquals(real, mirror.withoutPseudoTags())
    }

    @Test
    fun onlyThePrefixCounts() {
        assertTrue(PseudoTags.isPseudo("geodesk:missing_members"))
        assertFalse(PseudoTags.isPseudo("name:geodesk"))
        assertFalse(PseudoTags.isPseudo("geodesk"))
        assertSame(real, real.withoutPseudoTags())
    }

    @Test
    fun tagEditNeverProposesPseudoTags() {
        val withPseudo = real + ("geodesk:missing_members" to "2236")
        assertEquals(real, TagEdit(replaceAll = withPseudo).applyTo(emptyMap()))
        assertEquals(real + ("note" to "x"), TagEdit(set = mapOf("note" to "x")).applyTo(withPseudo))
        assertEquals(real, TagEdit(set = mapOf("geodesk:missing_members" to "1")).applyTo(real))
    }
}
