package eu.tiducto.osmreview.server.rulesfolder

import eu.tiducto.osmreview.shared.DiagnosticSeverity
import eu.tiducto.osmreview.shared.RuleKind
import eu.tiducto.osmreview.shared.mapcss.Stylesheet
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.streams.toList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapCssLoaderTest {
    private val dir: Path = Files.createTempDirectory("rules")
    private val folder = RulesFolder(
        dir,
        null,
        listOf(MapCssLoader(RuleKind.HIGHLIGHT), MapCssLoader(RuleKind.CHECK), SmartPresetLoader(MapCssSelectorValidator)),
    )

    @AfterTest
    fun tearDown() = folder.close()

    private fun file(rel: String, text: String) = dir.resolve(rel).also { it.parent.createDirectories(); it.writeText(text) }

    @Test
    fun parseErrorsCarryLineAndColumn() {
        file("highlights/bad.mapcss", "way[highway] { color: red; }\nnode[amenity=bench { color: blue; }\n")
        folder.rescan()
        val info = folder.info("highlights/bad.mapcss")!!
        val error = info.diagnostics.first { it.severity == DiagnosticSeverity.ERROR }
        assertEquals(2, error.line)
        assertTrue(error.column > 1, error.toString())
        assertNull(folder.loaded(RuleKind.HIGHLIGHT).single().value)
    }

    @Test
    fun loadsStylesheetsAndRunsSelfTests() {
        file("checks/a.validator.mapcss", "node[highway=crossing][!crossing] { throwWarning: \"x\"; assertMatch: \"node highway=crossing\"; assertNoMatch: \"node highway=crossing\"; }")
        folder.rescan()
        val loaded = folder.loaded(RuleKind.CHECK).single()
        assertIs<Stylesheet>(loaded.value)
        assertTrue(loaded.hash.isNotEmpty())
        val failed = loaded.diagnostics.single { it.severity == DiagnosticSeverity.ERROR }
        assertTrue("assertNoMatch failed" in failed.message, failed.message)
        assertEquals(1, failed.line)

        val result = folder.validate("checks/a.validator.mapcss", "node[highway=crossing] { throwWarning: \"x\"; assertMatch: \"node highway=crossing\"; }")
        assertTrue(result.valid, result.toString())
        val nothing = folder.validate("checks/b.validator.mapcss", "node[highway] { color: red; }")
        assertTrue(nothing.diagnostics.any { it.severity == DiagnosticSeverity.WARNING && "finds nothing" in it.message })
        val written = folder.write("checks/c.validator.mapcss", "node[x=~/(/] { throwError: \"x\"; }")
        assertFalse(written.saved)
        assertTrue(written.diagnostics.any { it.severity == DiagnosticSeverity.ERROR && it.line == 1 })
    }

    @Test
    fun unknownFunctionsAreErrorsButOurBuiltInsAreKnown() {
        val ok = folder.validate(
            "checks/ok.validator.mapcss",
            "way[odd_segment(\"a\") != \"\"][network_island_size() < 3][half_width() > 1][connected_value(\"surface\") != \"\"][inside(\"CZ\")] { throwOther: \"x\"; }",
        )
        assertTrue(ok.valid, ok.diagnostics.toString())
        val bad = folder.validate("checks/bad.validator.mapcss", "way[nope_fn() > 1] { throwOther: \"x\"; }")
        assertFalse(bad.valid)
    }

    @Test
    fun smartPresetSelectorsAreValidatedWithMapCss() {
        val preset = """
            {"id":"smart/x","name":"X","geometry":["line"],"match":"way[highway=footway",
             "steps":[{"op":"tag","target":"self","set":{"a":"b"}}]}
        """.trimIndent()
        val r = folder.validate("presets/smart/x.json", preset)
        val d = r.diagnostics.firstOrNull { "match" in it.message }
        assertTrue(d != null && d.severity == DiagnosticSeverity.ERROR, r.diagnostics.toString())
        assertEquals(1, d.line)
        assertTrue(d.column > preset.indexOf("\"way["), d.toString())
    }

    @Test
    fun exampleFilesLoadWithoutErrors() {
        val example = listOf(Path.of("../user-rules.example"), Path.of("user-rules.example")).first { Files.isDirectory(it) }
        Files.walk(example).use { s -> s.toList() }.filter { it.name.endsWith(".mapcss") || it.name.endsWith(".json") }.forEach { p ->
            val rel = example.relativize(p).joinToString("/")
            if (RuleKind.forPath(rel) == null) return@forEach
            file(rel, Files.readString(p))
        }
        folder.rescan()
        val errors = folder.list().flatMap { it.diagnostics }.filter { it.severity == DiagnosticSeverity.ERROR }
        assertTrue(errors.isEmpty(), errors.toString())
        assertEquals(4, folder.loaded(RuleKind.CHECK).count { it.value is Stylesheet })
    }
}
