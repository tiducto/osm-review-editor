package eu.tiducto.osmreview.shared.mapcss

import eu.tiducto.osmreview.shared.Severity
import eu.tiducto.osmreview.shared.TagEdit
import eu.tiducto.osmreview.shared.mapcss.Examples.node
import eu.tiducto.osmreview.shared.mapcss.Examples.sheet
import eu.tiducto.osmreview.shared.mapcss.Examples.way
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapCssChecksTest {
    private val checks = sheet(Examples.CHECKS, "rules/example.validator.mapcss")

    @Test
    fun oddSegment() {
        val host = FakeHost(values = mapOf("odd_segment" to "maxspeed"))
        val r = checks.checks(way("highway" to "residential", "name" to "Main"), host).single()
        assertEquals(Severity.WARNING, r.severity)
        assertEquals("Differs from connected 'Main': maxspeed", r.message)
        assertEquals("consistency", r.group)
        assertNull(r.fix)
        assertEquals("rules/example.validator.mapcss", r.file)
        assertEquals(1, r.line)
        assertEquals(0, r.rule)
        assertTrue(checks.checks(way("highway" to "residential", "name" to "Main"), FakeHost(values = mapOf("odd_segment" to ""))).isEmpty())
        assertTrue(checks.checks(way("highway" to "residential", "name" to "Main")).isEmpty())
        assertTrue(checks.checks(way("highway" to "residential"), host).isEmpty())
    }

    @Test
    fun unnecessaryArea() {
        val r = checks.checks(way("building" to "yes", "area" to "yes", closed = true)).single()
        assertEquals("area=yes is unnecessary for building", r.message)
        assertEquals("unnecessary tag", r.group)
        assertEquals(TagEdit(remove = listOf("area")), r.fix)
        assertEquals(TagEdit(remove = listOf("area")).applyTo(mapOf("building" to "yes", "area" to "yes")), mapOf("building" to "yes"))
        assertTrue(checks.checks(way("building" to "yes", "area" to "yes")).isEmpty())
        assertTrue(checks.checks(way("building" to "yes", "area" to "no", closed = true)).isEmpty())
    }

    @Test
    fun networkIsland() {
        val footway = way("highway" to "footway")
        val r = checks.checks(footway, FakeHost(values = mapOf("network_island_size" to 3))).single()
        assertEquals("Footway not connected to the street network", r.message)
        assertEquals("connectivity", r.group)
        assertTrue(checks.checks(footway, FakeHost(values = mapOf("network_island_size" to 12))).isEmpty())
        assertTrue(checks.checks(footway).isEmpty())
    }

    @Test
    fun checksAndStylesAreSeparate() {
        assertTrue(checks.hasChecks)
        assertFalse(checks.hasStyles)
        val style = checks.styles(way("building" to "yes", "area" to "yes", closed = true))
        assertTrue(style.properties.isEmpty())
        val both = sheet("way[a] { color: red; throwError: \"x\"; }")
        val e = both.evaluate(way("a" to "1"))
        assertEquals("red", e.style.color)
        assertEquals(Severity.ERROR, e.checks.single().severity)
    }

    @Test
    fun severities() {
        val s = sheet("node[a] { throwError: \"e\"; } node[b] { throwWarning: \"w\"; } node[c] { throwOther: \"o\"; } node[d] { group: \"g\"; }")
        val r = s.checks(node("a" to "1", "b" to "1", "c" to "1", "d" to "1"))
        assertEquals(listOf(Severity.ERROR, Severity.WARNING, Severity.INFO), r.map { it.severity })
        assertEquals(listOf("e", "w", "o"), r.map { it.message })
    }

    @Test
    fun placeholders() {
        val s = sheet(
            """
            *[motor_vehicle?][!vehicle][!access][bicycle_road!=yes][highway =~ /^(motorway|trunk)$/] {
              throwOther: tr("{0} is optional on {1}", "{0.tag}", "{4.tag}");
              group: "{4.key} {4.value} {1.key} {9.key} {0.nothing}";
            }
            node[/^gpx:/] { throwWarning: tr("{0} should not be uploaded", "{0.key}"); fixRemove: "{0.key}"; }
            way:closed[building][area?] { throwWarning: "{0.key}|{1.tag}|{2.tag}"; }
            """.trimIndent(),
        )
        val r = s.checks(way("highway" to "motorway", "motor_vehicle" to "yes")).single()
        assertEquals("motor_vehicle=yes is optional on highway=motorway", r.message)
        assertEquals("highway motorway vehicle {9.key} {0.nothing}", r.group)
        val g = s.checks(node("gpx:time" to "2018", "name" to "x")).single()
        assertEquals("gpx:time should not be uploaded", g.message)
        assertEquals(TagEdit(remove = listOf("gpx:time")), g.fix)
        val c = s.checks(way("building" to "house", "area" to "yes", closed = true)).single()
        assertEquals("{0.key}|building=house|area=yes", c.message)
    }

    @Test
    fun fixes() {
        val s = sheet(
            """
            node[emergency=permissive] { throwWarning: "x"; fixAdd: "emergency=yes"; fixAdd: concat("note=", tag("emergency")); }
            node[old] { throwWarning: "y"; fixChangeKey: "old=>new"; suggestAlternative: "new"; suggestAlternative: "newer"; }
            node[junk] { throwError: "z"; fixDeleteObject: this; }
            node[k=v] { throwWarning: "a"; fixAdd: "{0.key}=w"; fixRemove: "other"; }
            """.trimIndent(),
        )
        val a = s.checks(node("emergency" to "permissive")).single()
        assertEquals(TagEdit(set = mapOf("emergency" to "yes", "note" to "permissive")), a.fix)
        val b = s.checks(node("old" to "1")).single()
        assertEquals(TagEdit(set = mapOf("new" to "1"), remove = listOf("old")), b.fix)
        assertEquals(listOf("new", "newer"), b.alternatives)
        val c = s.checks(node("junk" to "1")).single()
        assertTrue(c.deleteObject)
        assertNull(c.fix)
        val d = s.checks(node("k" to "v")).single()
        assertEquals(TagEdit(set = mapOf("k" to "w"), remove = listOf("other")), d.fix)
    }

    @Test
    fun selfTestPassesForExamples() {
        assertEquals(emptyList(), checks.selfTest())
    }

    @Test
    fun selfTestReportsFailures() {
        val s = sheet(
            """
            *[bridge=no],
            *[layer="0"] {
              throwWarning: tr("{0} is unnecessary", "{0.tag}");
              assertMatch: "way layer=0";
              assertMatch: "way bridge=yes";
              assertNoMatch: "node bridge=no";
              assertNoMatch: "node layer=1";
            }
            way[highway] { set .road; }
            way.road[name=~/^(?i)(street)$/] {
              throwWarning: "descriptive";
              assertMatch: "way highway=residential name=Street";
              assertNoMatch: "way name=Street";
              assertMatch: "node gnis:Class=\"Populated Place\" name=street";
            }
            way:unclosed[x] { throwWarning: "u"; assertMatch: "way x=1"; assertNoMatch: "closedway x=1"; }
            """.trimIndent(),
        )
        val failures = s.selfTest()
        assertEquals(3, failures.size, failures.toString())
        assertEquals(listOf(5, 6, 14), failures.map { it.line })
        assertTrue(failures.all { it.severity == Severity.ERROR })
        assertEquals("assertMatch failed: rule does not match \"way bridge=yes\"", failures[0].message)
        assertEquals("assertNoMatch failed: rule matches \"node bridge=no\"", failures[1].message)
        assertEquals(3, failures[0].column)
    }

    @Test
    fun linkedChecks() {
        val s = sheet("way[highway] > node[highway=crossing][!crossing] { throwWarning: \"crossing without type\"; fixAdd: \"crossing=unmarked\"; }")
        val n = node("highway" to "crossing", id = 3)
        way("highway" to "residential").addChild(n)
        val r = assertNotNull(s.checks(n).singleOrNull())
        assertEquals(TagEdit(set = mapOf("crossing" to "unmarked")), r.fix)
        assertTrue(s.checks(node("highway" to "crossing", id = 9)).isEmpty())
    }
}
