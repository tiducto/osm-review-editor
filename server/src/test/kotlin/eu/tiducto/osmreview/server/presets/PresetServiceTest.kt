package eu.tiducto.osmreview.server.presets

import eu.tiducto.osmreview.server.core.NotFoundException
import eu.tiducto.osmreview.server.core.StateException
import eu.tiducto.osmreview.server.rulesfolder.PresetFileLoader
import eu.tiducto.osmreview.server.rulesfolder.RulesFolder
import eu.tiducto.osmreview.server.rulesfolder.SmartPresetLoader
import eu.tiducto.osmreview.shared.DiagnosticSeverity
import eu.tiducto.osmreview.shared.presets.DetachPresetRequest
import eu.tiducto.osmreview.shared.presets.ImportPresetRequest
import eu.tiducto.osmreview.shared.presets.PresetGeometry
import eu.tiducto.osmreview.shared.presets.PresetOrigin
import eu.tiducto.osmreview.shared.RuleKind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PresetServiceTest {
    private val dir = Files.createTempDirectory("rules")
    private val folder = RulesFolder(dir, null, listOf(PresetFileLoader { data.index }, SmartPresetLoader()))
    private val presets = PresetService(data, folder)

    @AfterTest
    fun tearDown() = folder.close()

    @Test
    fun shipsIdTaggingSchema() {
        assertEquals("7.3.0", data.version)
        assertTrue(data.index.baseIds.size > 1500)
        assertEquals(setOf("en", "cs"), data.index.availableLocales)
        assertTrue(data.file("translations/cs.json")!!.size > 100_000)
        assertNull(data.file("../../logback.xml"))
    }

    @Test
    fun matchesRealPresets() {
        fun m(g: PresetGeometry, vararg tags: Pair<String, String>) = presets.match(tags.toMap(), g, "cs").preset
        assertEquals("highway/footway/sidewalk", m(PresetGeometry.LINE, "highway" to "footway", "footway" to "sidewalk").id)
        assertEquals("Chodník podél vozovky", m(PresetGeometry.LINE, "highway" to "footway", "footway" to "sidewalk").name)
        assertEquals("highway/crossing/zebra", m(PresetGeometry.VERTEX, "highway" to "crossing", "crossing" to "zebra").id)
        assertEquals("building/house", m(PresetGeometry.AREA, "building" to "house").id)
        assertEquals("building", m(PresetGeometry.AREA, "building" to "shed_xyz").id)
        assertEquals("public_transport/platform/bus_point", m(PresetGeometry.POINT, "highway" to "bus_stop", "public_transport" to "platform", "bus" to "yes").id)
        assertEquals("amenity/bench", m(PresetGeometry.POINT, "amenity" to "bench").id)
        assertEquals("point", m(PresetGeometry.POINT, "fixme" to "x").id)
        assertEquals("area", m(PresetGeometry.AREA, "fixme" to "x").id)
    }

    @Test
    fun searchesInCzechWithoutDiacritics() {
        val hits = presets.search("chodnik", "cs", PresetGeometry.LINE, 5)
        assertTrue(hits.any { it.id == "highway/footway/sidewalk" }, hits.toString())
        assertTrue(presets.search("lavicka", "cs", null, 5).any { it.id == "amenity/bench" })
        assertTrue(presets.search("bench", "en", null, 5).first().id == "amenity/bench")
    }

    @Test
    fun importModifyAndDetach() {
        val imported = presets.import(ImportPresetRequest("highway/footway/sidewalk", "custom/sidewalk-cz"))
        assertTrue(imported.saved, imported.toString())
        assertEquals("presets/custom-sidewalk-cz.json", imported.file!!.path)
        assertEquals(
            "{\n  \"id\": \"custom/sidewalk-cz\",\n  \"extends\": \"highway/footway/sidewalk\",\n  \"apply\": \"merge\"\n}\n",
            dir.resolve("presets/custom-sidewalk-cz.json").readText(),
        )
        assertEquals("Chodník podél vozovky", presets.get("custom/sidewalk-cz", "cs").name)
        assertFailsWith<StateException> { presets.import(ImportPresetRequest("highway/footway/sidewalk", "custom/sidewalk-cz", "presets/other.json")) }
        assertFailsWith<NotFoundException> { presets.import(ImportPresetRequest("no/such")) }

        val modified = """
            { "id":"custom/sidewalk-cz", "extends":"highway/footway/sidewalk", "name":"Chodník", "apply":"merge",
              "addTags":{"highway":"footway","footway":"sidewalk","surface":"paving_stones"},
              "fields":["{highway/footway/sidewalk}","width","lit"] }
        """.trimIndent()
        val hash = folder.read("presets/custom-sidewalk-cz.json").file.hash
        assertTrue(folder.write("presets/custom-sidewalk-cz.json", modified, expectedHash = hash).saved)
        val r = presets.get("custom/sidewalk-cz", "cs")
        assertEquals("Chodník", r.name)
        assertEquals(PresetOrigin.USER, r.origin)
        assertEquals(listOf("name", "surface", "width", "structure", "tunnel/name", "access", "incline", "lit"), r.fields)
        val match = presets.match(mapOf("highway" to "footway", "footway" to "sidewalk", "surface" to "paving_stones"), PresetGeometry.LINE, "cs")
        assertEquals("custom/sidewalk-cz", match.preset.id)
        val applied = presets.apply("custom/sidewalk-cz", mapOf("highway" to "footway"), PresetGeometry.LINE, null)
        assertEquals(mapOf("footway" to "sidewalk", "surface" to "paving_stones"), applied.edit.set)

        val detached = presets.detach(DetachPresetRequest(id = "custom/sidewalk-cz"))
        assertTrue(detached.saved, detached.toString())
        val text = dir.resolve("presets/custom-sidewalk-cz.json").readText()
        assertTrue("extends" !in text, text)
        assertTrue("\"geometry\": [\n    \"line\"\n  ]" in text, text)
        assertEquals(r.copy(extends = null, terms = emptyList()), presets.get("custom/sidewalk-cz", "cs").copy(terms = emptyList()))
        assertFailsWith<StateException> { presets.detach(DetachPresetRequest(path = "presets/custom-sidewalk-cz.json")) }
    }

    @Test
    fun brokenPresetFilesAreReportedAndSkipped() {
        val w = folder.write("presets/bad.json", """{"id":"custom/bad","extends":"no/such/preset"}""", force = true)
        assertTrue(w.diagnostics.any { it.severity == DiagnosticSeverity.ERROR && "unknown preset 'no/such/preset'" in it.message })
        assertTrue(folder.info("presets/bad.json")!!.diagnostics.isNotEmpty())
        assertFailsWith<NotFoundException> { presets.get("custom/bad", null) }
    }

    @Test
    fun smartPresetsFromTheFolder() {
        val ok = folder.write(
            "presets/smart/footway-crossing.json",
            """{"id":"smart/footway-crossing","name":"Footway → crossing","geometry":["line"],"match":"way[highway=footway][!footway]",
               "steps":[{"op":"tag","target":"self","set":{"footway":"crossing"}}]}""",
        )
        assertTrue(ok.saved, ok.toString())
        assertEquals(listOf("smart/footway-crossing"), presets.smartPresets().map { it.id })
    }

    @Test
    fun exampleFolderIsValid() {
        val example = Path.of("").toAbsolutePath().parent.resolve("user-rules.example")
        Files.walk(example).use { s ->
            s.filter { Files.isRegularFile(it) }.forEach { f ->
                val target = dir.resolve(example.relativize(f).toString())
                Files.createDirectories(target.parent)
                Files.copy(f, target)
            }
        }
        folder.rescan()
        val files = folder.list()
        assertEquals(RuleKind.entries.toSet(), files.map { it.kind }.toSet())
        files.forEach { assertEquals(emptyList(), it.diagnostics, it.path) }
        assertEquals("Chodník", presets.get("custom/sidewalk-cz", "cs").name)
        assertEquals(listOf("smart/footway-crossing"), presets.smartPresets().map { it.id })
    }

    companion object {
        val data: IdPresetData by lazy { IdPresetData.fromResources() }
    }
}
