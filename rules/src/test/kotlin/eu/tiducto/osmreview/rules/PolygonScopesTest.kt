package eu.tiducto.osmreview.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PolygonScopesTest {
    private val square = """{"type":"Polygon","coordinates":[[[17.88,49.93],[17.92,49.93],[17.92,49.95],[17.88,49.95],[17.88,49.93]]]}"""

    @Test
    fun parsesPolygonIntoBoundsAndShape() {
        val p = PolygonScopes.parse(square)
        assertNotNull(p.scope.polygon)
        assertTrue(p.key.startsWith("polygon:"))
        val b = Geo.bbox(p.scope.bounds)
        assertEquals(17.88, b.west, 1e-6)
        assertEquals(49.95, b.north, 1e-6)
        assertTrue(p.scope.containsLonLat(17.90, 49.94))
        assertFalse(p.scope.containsLonLat(17.95, 49.94))
    }

    @Test
    fun keyIsStableAcrossEquivalentInput() {
        val rotatedReversed = """
            {"coordinates": [[[17.92, 49.95], [17.92, 49.93], [17.88, 49.93], [17.88, 49.95], [17.92, 49.95]]], "type": "Polygon"}
        """
        val feature = """{"type":"Feature","properties":{"name":"x"},"geometry":$square}"""
        val collection = """{"type":"FeatureCollection","features":[$feature]}"""
        val key = PolygonScopes.parse(square).key
        assertEquals(key, PolygonScopes.parse(rotatedReversed).key)
        assertEquals(key, PolygonScopes.parse(feature).key)
        assertEquals(key, PolygonScopes.parse(collection).key)
    }

    @Test
    fun keyDiffersForDifferentPolygons() {
        val other = square.replace("17.92", "17.93")
        assertNotEquals(PolygonScopes.parse(square).key, PolygonScopes.parse(other).key)
    }

    @Test
    fun acceptsMultiPolygon() {
        val multi = """{"type":"MultiPolygon","coordinates":[
            [[[17.88,49.93],[17.89,49.93],[17.89,49.94],[17.88,49.93]]],
            [[[17.90,49.93],[17.91,49.93],[17.91,49.94],[17.90,49.93]]]]}"""
        val p = PolygonScopes.parse(multi)
        assertTrue(p.scope.containsLonLat(17.889, 49.931))
        assertFalse(p.scope.containsLonLat(17.895, 49.931))
    }

    @Test
    fun rejectsInvalidInput() {
        fun message(json: String) = assertFailsWith<InvalidScopeException> { PolygonScopes.parse(json) }.message.orEmpty()

        assertTrue("not valid JSON" in message("{nope"))
        assertTrue("Polygon or MultiPolygon, got Point" in message("""{"type":"Point","coordinates":[17.9,49.9]}"""))
        assertTrue("not closed" in message("""{"type":"Polygon","coordinates":[[[17.88,49.93],[17.92,49.93],[17.92,49.95],[17.88,49.95]]]}"""))
        assertTrue("at least 4" in message("""{"type":"Polygon","coordinates":[[[17.88,49.93],[17.92,49.93],[17.88,49.93]]]}"""))
        assertTrue("outside WGS84" in message("""{"type":"Polygon","coordinates":[[[1988000,6400000],[1990000,6400000],[1990000,6402000],[1988000,6400000]]]}"""))
        assertTrue(
            "invalid" in message("""{"type":"Polygon","coordinates":[[[17.88,49.93],[17.92,49.95],[17.92,49.93],[17.88,49.95],[17.88,49.93]]]}"""),
        )
        assertTrue("limit is 10" in message("""{"type":"Polygon","coordinates":[[[5,40],[20,40],[20,50],[5,40]]]}"""))
        assertTrue("exactly one feature" in message("""{"type":"FeatureCollection","features":[]}"""))
    }

    @Test
    fun rejectsTooManyVertices() {
        val n = PolygonScopes.MAX_VERTICES + 10
        val ring = (0 until n).map { i ->
            val a = 2 * Math.PI * i / n
            "[${17.9 + 0.01 * Math.cos(a)},${49.94 + 0.01 * Math.sin(a)}]"
        }
        val json = """{"type":"Polygon","coordinates":[[${(ring + ring.first()).joinToString(",")}]]}"""
        val e = assertFailsWith<InvalidScopeException> { PolygonScopes.parse(json) }
        assertTrue("vertices" in e.message.orEmpty())
    }

    @Test
    fun simplifiesLargeGeometryForStorage() {
        val n = 5000
        val ring = (0 until n).map { i ->
            val a = 2 * Math.PI * i / n
            "[${17.9 + 0.05 * Math.cos(a)},${49.94 + 0.05 * Math.sin(a)}]"
        }
        val p = PolygonScopes.parse("""{"type":"Polygon","coordinates":[[${(ring + ring.first()).joinToString(",")}]]}""")
        val stored = Geo.fromGeoJson(PolygonScopes.simplifiedGeoJson(p.scope.polygon!!, 500))
        assertTrue(stored.numPoints <= 500)
        assertTrue(stored.isValid)
    }
}
