package eu.tiducto.osmreview.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RuleKindTest {
    @Test
    fun kindsFromPaths() {
        assertEquals(RuleKind.HIGHLIGHT, RuleKind.forPath("highlights/sidewalks.mapcss"))
        assertEquals(RuleKind.CHECK, RuleKind.forPath("checks/crossings.validator.mapcss"))
        assertEquals(RuleKind.PRESET, RuleKind.forPath("presets/sidewalk-cz.json"))
        assertEquals(RuleKind.SMART_PRESET, RuleKind.forPath("presets/smart/footway-crossing.json"))
        assertEquals(RuleKind.SOURCES, RuleKind.forPath("sources.json"))
    }

    @Test
    fun rejectsEverythingElse() {
        listOf(
            "highlights/a.validator.mapcss", "highlights/.mapcss", "highlights/x.txt", "checks/a.mapcss", "presets/a.yaml",
            "presets/smart/deep/a.json", "README.md", "../x.mapcss", "highlights/../../etc.mapcss", "highlights/.hidden.mapcss",
            "highlights/sub/a.mapcss", "/highlights/a.mapcss", "other/sources.json", ".trash/1/highlights/a.mapcss",
        ).forEach { assertNull(RuleKind.forPath(it), it) }
    }

    @Test
    fun locatorFindsPaths() {
        val text = "{\n  \"a\": [1, {\"b\": \"x\"}],\n  \"c\": true\n}"
        val l = JsonLocator(text)
        assertEquals(JsonLocator.Pos(2, 18), l.value("a[1].b"))
        assertEquals(JsonLocator.Pos(2, 9), l.value("a[0]"))
        assertEquals(JsonLocator.Pos(3, 3), l.key("c"))
        assertEquals(JsonLocator.Pos(2, 8), l.value("a[7]"))
    }
}
