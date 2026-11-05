package eu.tiducto.osmreview.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeprecatedTagsTest {
    @Test
    fun loadsVendoredSchema() {
        assertTrue(DeprecatedTags.entries.size > 400)
    }

    @Test
    fun simpleRename() {
        val (tags, applied) = DeprecatedTags.upgrade(mapOf("access" to "customer", "amenity" to "parking"))!!
        assertEquals(mapOf("access" to "customers", "amenity" to "parking"), tags)
        assertTrue(applied.single().isOneToOne)
    }

    @Test
    fun wildcardCarriesValue() {
        val (tags, applied) = DeprecatedTags.upgrade(mapOf("artwork" to "statue", "tourism" to "artwork"))!!
        assertEquals("statue", tags["artwork_type"])
        assertNull(tags["artwork"])
        assertTrue(applied.none { it.isOneToOne })
    }

    @Test
    fun currentTagsAreLeftAlone() {
        assertNull(DeprecatedTags.upgrade(mapOf("highway" to "footway", "surface" to "asphalt")))
    }

    @Test
    fun goqlCoversEveryEntry() {
        assertEquals(DeprecatedTags.entries.size, DeprecatedTags.goql.split(", *").size)
    }
}
