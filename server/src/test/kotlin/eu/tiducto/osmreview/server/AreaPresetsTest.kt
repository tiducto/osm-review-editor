package eu.tiducto.osmreview.server

import eu.tiducto.osmreview.rules.GolMirror
import eu.tiducto.osmreview.rules.MirrorQueries
import eu.tiducto.osmreview.shared.BBox
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AreaPresetsTest {
    @Test
    fun polygonAndMultiPolygonBecomeAreasWithBbox() {
        val defs = AreaPresets.parse(
            """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","properties":{"name":"Box"},"geometry":{"type":"Polygon","coordinates":[[[17.0,49.0],[17.5,49.0],[17.5,49.2],[17.0,49.2],[17.0,49.0]]]}},
              {"type":"Feature","properties":{"name":"Two"},"geometry":{"type":"MultiPolygon","coordinates":[
                [[[16.0,48.0],[16.1,48.0],[16.1,48.1],[16.0,48.0]]],
                [[[18.0,50.0],[18.2,50.0],[18.2,50.3],[18.0,50.0]]]]}}
            ]}
            """,
        )
        assertEquals(listOf("Box", "Two"), defs.map { it.name })
        val box = defs[0].area!!
        assertEquals(BBox(17.0, 49.0, 17.5, 49.2), box.bbox)
        assertEquals("Polygon", (box.geometry as JsonObject)["type"]!!.jsonPrimitive.content)
        assertEquals(BBox(16.0, 48.0, 18.2, 50.3), defs[1].area!!.bbox)
    }

    @Test
    fun namedFeaturesWithoutAreaGeometryFallBackToTheMirror() {
        val defs = AreaPresets.parse(
            """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","properties":{"name":"Brno-střed"},"geometry":null},
              {"type":"Feature","properties":{"name":"Olomouc"}},
              {"type":"Feature","properties":{"name":"Point"},"geometry":{"type":"Point","coordinates":[17.9,49.9]}},
              {"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":[[[0,0],[1,0],[1,1],[0,0]]]}},
              {"type":"Feature","properties":{"name":"brno-STŘED"},"geometry":null}
            ]}
            """,
        )
        assertEquals(listOf("Brno-střed", "Olomouc", "Point"), defs.map { it.name })
        assertTrue(defs.all { it.area == null })
    }

    @Test
    fun badFilesAreRejectedByParseAndIgnoredByLoad() {
        assertFailsWith<Exception> { AreaPresets.parse("not json") }
        assertFailsWith<IllegalArgumentException> { AreaPresets.parse("""{"type":"Feature","properties":{"name":"x"}}""") }
        assertFailsWith<IllegalArgumentException> { AreaPresets.parse("""{"type":"FeatureCollection"}""") }
        assertFailsWith<IllegalArgumentException> { AreaPresets.parse("[]") }
        assertFailsWith<IllegalArgumentException> {
            AreaPresets.parse(
                """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"name":"x"},"geometry":{"type":"Polygon","coordinates":[[[-740000,5000000],[1,1],[2,2]]]}}]}""",
            )
        }

        val bad = Files.createTempFile("areas", ".geojson").also { it.writeText("{ broken") }
        assertEquals(AreaPresets.DEFAULT_NAMES, AreaPresets.load(bad).map { it.name })
        assertEquals(AreaPresets.DEFAULT_NAMES, AreaPresets.load(Path.of("/nonexistent/areas.geojson")).map { it.name })
        assertEquals(AreaPresets.DEFAULT_NAMES, AreaPresets.load(null).map { it.name })
    }

    @Test
    fun exampleFileLoadsAndServesPresetsWithoutAMirror() {
        val example = listOf(Path.of("../config/areas.example.geojson"), Path.of("config/areas.example.geojson")).first { Files.exists(it) }
        val defs = AreaPresets.load(example)
        assertEquals("Opava-Kateřinky", defs[0].name)
        assertNotNull(defs[0].area)
        assertNull(defs.first { it.name == "Brno-střed" }.area)
        assertEquals(1, defs.count { it.name == "Opava-Kateřinky" })
        assertEquals(1, defs.count { it.name == "Brno-střed" })
        assertTrue(defs.map { it.name }.containsAll(AreaPresets.DEFAULT_NAMES))

        val presets = AreaPresets(defs, MirrorQueries(GolMirror(Files.createTempDirectory("gol"))))
        val listed = presets.presets()
        assertEquals(listOf("Opava-Kateřinky"), listed.map { it.name })
        assertNull(listed[0].geometry)
        val kat = presets.find("opava-kateřinky")!!
        assertNotNull(kat.geometry)
        assertTrue(kat.bbox.west < 17.88 && kat.bbox.east > 17.94 && kat.bbox.south < 49.933 && kat.bbox.north > 49.98)
        assertNull(presets.find("Brno-střed"))
    }
}
