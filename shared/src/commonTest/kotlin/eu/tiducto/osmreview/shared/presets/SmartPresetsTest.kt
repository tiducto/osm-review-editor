package eu.tiducto.osmreview.shared.presets

import eu.tiducto.osmreview.shared.DiagnosticSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmartPresetsTest {
    private val file = "presets/smart/x.json"

    @Test
    fun exampleIsValid() {
        val r = SmartPresets.parse(file, Fixtures.smartCrossing)
        assertEquals(emptyList(), r.diagnostics)
        val p = assertNotNull(r.preset)
        assertEquals("smart/footway-crossing", p.id)
        assertEquals(listOf(PresetGeometry.LINE), p.geometry)
        assertEquals("way[highway=footway][!footway]", p.match)
        assertEquals(SmartParam(SmartParamType.COMBO, listOf("uncontrolled", "traffic_signals", "unmarked"), "uncontrolled"), p.params["crossing"])
        assertEquals(5, p.steps.size)
    }

    @Test
    fun reportsSemanticErrors() {
        val text = """
            {"id":"smart/x","name":"X","geometry":["line"],"match":"way[highway]",
             "params":{"kind":{"type":"combo","options":["a","b"],"default":"c"}},
             "steps":[
              {"op":"teleport"},
              {"op":"ensureSharedNode","ways":["self","road"],"as":"x"},
              {"op":"splitAround","way":"x","node":"x","distance":"half_width(nope)","as":"piece"},
              {"op":"tag","target":"piece","set":{"crossing":"{crossing}"}},
              {"op":"tag","target":"self"}
             ]}
        """.trimIndent()
        val r = SmartPresets.parse(file, text)
        assertNull(r.preset)
        val msgs = r.diagnostics.map { it.message }
        assertTrue(msgs.any { "Default 'c' is not one of the options" in it }, msgs.toString())
        assertTrue(msgs.any { "Unknown op 'teleport'" in it }, msgs.toString())
        assertTrue(msgs.any { "steps[1].ways[1]: Unknown name 'road'" in it }, msgs.toString())
        assertTrue(msgs.any { "steps[2].way: 'x' is a node, expected a way" in it }, msgs.toString())
        assertTrue(msgs.any { "steps[2].distance: Unknown name 'nope'" in it }, msgs.toString())
        assertTrue(msgs.any { "Unknown parameter {crossing}" in it }, msgs.toString())
        assertTrue(msgs.any { "tag needs \"set\" or \"remove\"" in it }, msgs.toString())
        val op = r.diagnostics.first { "teleport" in it.message }
        assertEquals(4, op.line)
    }

    @Test
    fun selectorHookMapsPositions() {
        val validator = SelectorValidator { sel ->
            if ("!~" in sel) listOf(SelectorProblem(sel.indexOf("!~"), DiagnosticSeverity.ERROR, "regex not supported")) else emptyList()
        }
        val r = SmartPresets.parse(file, Fixtures.smartCrossing, validator)
        val d = r.diagnostics.single()
        assertEquals(5, d.line)
        val lineText = Fixtures.smartCrossing.lines()[4]
        assertEquals(lineText.indexOf("!~") + 1, d.column)
        assertTrue(d.message.startsWith("steps[0].with: regex not supported"))
        assertNull(r.preset)
    }

    @Test
    fun missingTopLevelKeys() {
        val r = SmartPresets.parse(file, """{"id":"smart/y","steps":[]}""")
        val msgs = r.diagnostics.map { it.message }
        assertTrue(msgs.containsAll(listOf("Missing \"name\"", "Missing \"geometry\"", "Missing \"match\"", "steps: Give at least one step")), msgs.toString())
    }
}
