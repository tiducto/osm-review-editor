package eu.tiducto.osmreview.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HalfWidthTest {
    @Test
    fun widthTagWins() {
        assertEquals(3.5, HalfWidth.metres(mapOf("highway" to "residential", "width" to "7", "lanes" to "4")))
        assertEquals(2.0, HalfWidth.metres(mapOf("width" to "4,0 m")))
        assertEquals(0.3048, HalfWidth.width(mapOf("width" to "1'")))
    }

    @Test
    fun lanesThenClassDefault() {
        assertEquals(6.5, HalfWidth.metres(mapOf("highway" to "primary", "lanes" to "4")))
        assertEquals(3.0, HalfWidth.metres(mapOf("highway" to "residential", "lanes" to "2")))
        assertEquals(6.5, HalfWidth.metres(mapOf("highway" to "primary_link", "lanes" to "4")))
        assertEquals(2.75, HalfWidth.metres(mapOf("highway" to "residential")))
        assertEquals(2.75, HalfWidth.metres(mapOf("highway" to "residential", "lanes" to "0", "width" to "wide")))
        assertNull(HalfWidth.metres(mapOf("building" to "yes")))
    }
}
