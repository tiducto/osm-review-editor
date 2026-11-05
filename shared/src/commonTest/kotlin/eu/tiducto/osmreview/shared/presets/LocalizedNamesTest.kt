package eu.tiducto.osmreview.shared.presets

import eu.tiducto.osmreview.shared.DiagnosticSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalizedNamesTest {
    private val names = mapOf("cs" to "Přechod", "en" to "Crossing", "de-AT" to "Übergang")

    @Test
    fun resolvesByLocaleWithFallbacks() {
        assertEquals("Přechod", LocalizedNames.resolve(names, "cs"))
        assertEquals("Přechod", LocalizedNames.resolve(names, "cs-CZ"))
        assertEquals("Přechod", LocalizedNames.resolve(names, "cs_CZ"))
        assertEquals("Übergang", LocalizedNames.resolve(names, "de-AT"))
        assertEquals("Übergang", LocalizedNames.resolve(names, "de"))
        assertEquals("Crossing", LocalizedNames.resolve(names, "fr"))
        assertEquals("Crossing", LocalizedNames.resolve(names, null))
        assertEquals("Přechod", LocalizedNames.resolve(mapOf("cs" to "Přechod", "sk" to "Priechod"), "fr"))
        assertNull(LocalizedNames.resolve(emptyMap(), "en"))
    }

    @Test
    fun smartPresetNameObject() {
        val text = """
            {"id":"smart/x","name":{"cs":"Přechod","en":"Crossing"},"geometry":["line"],"match":"way[highway]",
             "steps":[{"op":"tag","target":"self","set":{"crossing":"yes"}}]}
        """.trimIndent()
        val r = SmartPresets.parse("presets/smart/x.json", text)
        assertEquals(emptyList(), r.diagnostics)
        val p = assertNotNull(r.preset)
        assertEquals("Crossing", p.name)
        assertEquals(mapOf("cs" to "Přechod", "en" to "Crossing"), p.names)
        assertEquals("Přechod", p.localized("cs").name)
        assertEquals("Crossing", p.localized("de").name)
    }

    @Test
    fun smartPresetPlainNameHasNoTranslations() {
        val p = assertNotNull(SmartPresets.parse("presets/smart/x.json", Fixtures.smartCrossing).preset)
        assertEquals(emptyMap(), p.names)
        assertEquals(p, p.localized("cs"))
    }

    @Test
    fun invalidNameObjectsAreReportedWithPositions() {
        val text = """
            {"id":"smart/x","geometry":["line"],"match":"way[highway]",
             "name":{"cs":"","english":"Crossing","en":5},
             "steps":[{"op":"tag","target":"self","set":{"crossing":"yes"}}]}
        """.trimIndent()
        val r = SmartPresets.parse("presets/smart/x.json", text)
        assertNull(r.preset)
        val byMessage = r.diagnostics.associateBy { it.message }
        val empty = assertNotNull(byMessage["name.cs: name must not be empty"], r.diagnostics.toString())
        assertEquals(2, empty.line)
        assertEquals(text.lines()[1].indexOf("\"\"") + 1, empty.column)
        val locale = assertNotNull(byMessage["name.english: Invalid locale 'english'; use a language code like en, cs or cs-CZ"], r.diagnostics.toString())
        assertEquals(text.lines()[1].indexOf("\"english\"") + 1, locale.column)
        assertNotNull(byMessage["name.en: Must be a string"], r.diagnostics.toString())
        assertTrue(r.diagnostics.all { it.severity == DiagnosticSeverity.ERROR })

        val emptyObject = SmartPresets.parse("presets/smart/x.json", text.replace("""{"cs":"","english":"Crossing","en":5}""", "{}"))
        assertTrue(emptyObject.diagnostics.any { it.message.startsWith("name: Give at least one translation") }, emptyObject.diagnostics.toString())
        val number = SmartPresets.parse("presets/smart/x.json", text.replace("""{"cs":"","english":"Crossing","en":5}""", "3"))
        assertTrue(number.diagnostics.any { it.message.startsWith("name: Must be a string or an object") }, number.diagnostics.toString())
    }

    @Test
    fun userPresetNameObjectFeedsResolvedName() {
        val text = """
            {"id":"custom/sidewalk","extends":"highway/footway/sidewalk","name":{"cs":"Chodník","en":"Sidewalk (Czech)"}}
        """.trimIndent()
        val parsed = PresetFiles.parse("presets/sidewalk.json", text)
        assertEquals(emptyList(), parsed.diagnostics)
        val preset = parsed.presets.single().preset
        assertNull(preset.name)
        assertEquals(mapOf("cs" to "Chodník", "en" to "Sidewalk (Czech)"), preset.names)
        val idx = Fixtures.index().withUser(listOf(UserPreset(preset)))
        assertEquals("Chodník", idx.resolve("custom/sidewalk", "cs")!!.name)
        assertEquals("Sidewalk (Czech)", idx.resolve("custom/sidewalk", "en")!!.name)
        assertEquals("Sidewalk (Czech)", idx.resolve("custom/sidewalk")!!.name)
        assertTrue(PresetFiles.encode(preset).contains("\"cs\": \"Chodník\""))
        val detached = idx.detach("custom/sidewalk")
        assertNull(detached.name)
        assertEquals(preset.names, detached.names)
    }

    @Test
    fun userPresetWithoutExtendsAcceptsNameObject() {
        val ok = PresetFiles.parse("presets/a.json", """{"id":"custom/a","name":{"en":"A"},"geometry":["point"],"tags":{"amenity":"bench"}}""")
        assertEquals(emptyList(), ok.diagnostics)
        val bad = PresetFiles.parse("presets/a.json", """{"id":"custom/a","name":{"en":" "},"geometry":["point"],"tags":{"amenity":"bench"}}""")
        val msgs = bad.diagnostics.map { it.message }
        assertTrue("name.en: name must not be empty" in msgs, msgs.toString())
        assertTrue(msgs.any { "needs a \"name\"" in it }, msgs.toString())
    }
}
