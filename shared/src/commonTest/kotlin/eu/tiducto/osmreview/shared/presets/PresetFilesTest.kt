package eu.tiducto.osmreview.shared.presets

import eu.tiducto.osmreview.shared.DiagnosticSeverity
import eu.tiducto.osmreview.shared.RuleDiagnostic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PresetFilesTest {
    private val index = Fixtures.index()

    private fun check(vararg files: Pair<String, String>): Map<String, List<RuleDiagnostic>> {
        val parsed = files.map { (f, t) -> PresetFiles.parse(f, t) }
        val cross = PresetFiles.crossCheck(parsed, index)
        return parsed.associate { it.file to it.diagnostics + cross[it.file].orEmpty() }
    }

    @Test
    fun exampleOverlayIsClean() {
        val parsed = PresetFiles.parse("presets/a.json", Fixtures.sidewalkCz)
        assertEquals(emptyList(), parsed.diagnostics)
        val p = parsed.presets.single().preset
        assertEquals("highway/footway/sidewalk", p.extends)
        assertEquals(PresetApplyMode.MERGE, p.apply)
        assertEquals(emptyMap(), PresetFiles.crossCheck(listOf(parsed), index))
    }

    @Test
    fun invalidJsonReportsPosition() {
        val d = PresetFiles.parse("presets/x.json", "{\n  \"id\": \"a\",\n  \"name\": }").diagnostics.single()
        assertEquals(DiagnosticSeverity.ERROR, d.severity)
        assertEquals(3, d.line)
        assertTrue(d.message.startsWith("Invalid JSON"), d.message)
    }

    @Test
    fun structuralProblemsPointAtTheValue() {
        val text = """
            {
              "id": "custom/x",
              "geometry": ["line", "blob"],
              "tags": {"highway": 5},
              "colour": "red",
              "apply": "squash"
            }
        """.trimIndent()
        val d = PresetFiles.parse("presets/x.json", text).diagnostics
        val geometry = d.first { "geometry[1]" in it.message }
        assertEquals(3 to 24, geometry.line to geometry.column)
        val tag = d.first { "tags.highway" in it.message }
        assertEquals(4, tag.line)
        val unknown = d.first { "colour" in it.message }
        assertEquals(DiagnosticSeverity.WARNING, unknown.severity)
        assertEquals(5 to 3, unknown.line to unknown.column)
        assertTrue(d.any { "apply must be" in it.message })
        assertTrue(d.any { "needs a \"name\"" in it.message })
    }

    @Test
    fun crossChecksReferencesAndDuplicates() {
        val a = """[{"id":"custom/a","extends":"custom/b","fields":["{nope}","no_such_field"]},{"id":"custom/a","extends":"amenity/bench"}]"""
        val b = """{"id":"custom/b","extends":"highway/footway","name":"{highway/missing}"}"""
        val c = """{"id":"custom/c","extends":"custom/missing"}"""
        val d = check("presets/a.json" to a, "presets/b.json" to b, "presets/c.json" to c)
        val da = d.getValue("presets/a.json")
        assertTrue(da.any { "Unknown preset reference {nope}" in it.message }, da.toString())
        assertTrue(da.any { "Unknown field 'no_such_field'" in it.message && it.severity == DiagnosticSeverity.WARNING })
        assertTrue(da.any { "Duplicate preset id 'custom/a'" in it.message })
        assertTrue(da.none { "extends refers" in it.message })
        assertTrue(d.getValue("presets/b.json").any { "{highway/missing}" in it.message })
        assertTrue(d.getValue("presets/c.json").any { "unknown preset 'custom/missing'" in it.message })
    }

    @Test
    fun cyclesAreErrors() {
        val d = check(
            "presets/a.json" to """{"id":"custom/a","extends":"custom/b"}""",
            "presets/b.json" to """{"id":"custom/b","extends":"custom/a"}""",
        )
        assertTrue(d.getValue("presets/a.json").any { "extends itself" in it.message }, d.toString())
    }

    @Test
    fun replacingAnIdPresetIsInfo() {
        val d = check("presets/a.json" to """{"id":"amenity/bench","name":"Bench","geometry":["point"],"tags":{"amenity":"bench"}}""")
        assertEquals(listOf(DiagnosticSeverity.INFO), d.getValue("presets/a.json").map { it.severity })
    }

    @Test
    fun encodeRoundTrips() {
        val p = PresetFiles.parse("presets/a.json", Fixtures.sidewalkCz).presets.single().preset
        val text = PresetFiles.encode(p)
        assertTrue(text.startsWith("{\n  \"id\": \"custom/sidewalk-cz\",\n  \"extends\": \"highway/footway/sidewalk\""), text)
        assertEquals(p, PresetFiles.parse("presets/a.json", text).presets.single().preset)
        assertEquals("presets/custom-sidewalk-cz.json", PresetFiles.fileNameFor("custom/sidewalk-cz"))
    }
}
