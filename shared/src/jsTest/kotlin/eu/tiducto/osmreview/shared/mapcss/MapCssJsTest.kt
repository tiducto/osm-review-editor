package eu.tiducto.osmreview.shared.mapcss

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapCssJsTest {
    private fun stringify(v: dynamic): String = JSON.stringify(v)

    @Test
    fun parseReturnsDiagnosticsAndMeta() {
        val r = mapCssParse("meta { title: \"T\"; }\nway[a] { color: red; font-size: 3; }\nnode[", "x.mapcss")
        assertEquals(false, r.ok)
        assertNull(r.stylesheet)
        val d = r.diagnostics
        assertEquals(2, d.length)
        assertEquals("info", d[0].severity)
        assertEquals("error", d[1].severity)
        assertEquals(3, d[1].line)
        assertEquals(6, d[1].column)
        assertEquals("x.mapcss", d[1].file)

        val ok = mapCssParse(Examples.SIDEWALKS, "s.mapcss")
        assertEquals(true, ok.ok)
        assertEquals("Sidewalks", ok.meta.title)
        assertEquals(true, ok.meta["-ore-dim-others"])
        assertEquals(5, ok.legend.length)
        assertEquals("both", ok.legend[0].label)
        assertEquals(6, ok.ruleCount)
    }

    @Test
    fun stylesForPlainFeatures() {
        val sheet = mapCssParse(Examples.SIDEWALKS, "s.mapcss").stylesheet
        val feature: dynamic = js("({ type: 'way', id: 7, tags: { highway: 'primary', sidewalk: 'both' } })")
        val st = mapCssStyles(sheet, feature, null)
        assertEquals("#1a9850", st.properties.color)
        assertEquals("both", st.properties["-ore-legend"])
        assertEquals("[\"road\"]", stringify(st.classes))
        assertEquals("[1]", stringify(st.rules))
    }

    @Test
    fun checksCallTheHost() {
        val sheet = mapCssParse(Examples.CHECKS, "c.validator.mapcss").stylesheet
        val feature: dynamic = js("({ type: 'w', id: 3, tags: { highway: 'residential', name: 'Main' } })")
        val host: dynamic = js(
            "({ seen: [], call: function (name, f, args) { this.seen.push(name + ':' + f.id + ':' + args.join(',')); return name === 'odd_segment' ? 'lit' : undefined } })",
        )
        val checks = mapCssChecks(sheet, feature, host)
        assertEquals(1, checks.length)
        assertEquals("warning", checks[0].severity)
        assertEquals("Differs from connected 'Main': lit", checks[0].message)
        assertEquals("consistency", checks[0].group)
        assertEquals("odd_segment:3:maxspeed;surface;sidewalk;lit", host.seen[0])

        val building: dynamic = js("({ type: 'way', id: 4, closed: true, tags: { building: 'yes', area: 'yes' } })")
        val fix = mapCssChecks(sheet, building, js("({})"))[0].fix
        assertEquals("[\"area\"]", stringify(fix.remove))
        assertEquals(0, mapCssSelfTest(sheet).length)
    }

    @Test
    fun linksAndHostCollections() {
        val sheet = mapCssParse(
            "relation[type=route] >[role=platform] node { color: red; } node ∈ area[landuse] { width: 3; } way:righthandtraffic { opacity: 1; }",
            "",
        ).stylesheet
        val node: dynamic = js(
            "({ type: 'node', id: 1, tags: {}, parents: function () { return [{ element: { type: 'relation', id: 2, tags: { type: 'route' } }, role: 'platform', index: 0 }] } })",
        )
        assertEquals("red", mapCssStyles(sheet, node, null).properties.color)
        val area: dynamic = js("({ type: 'way', id: 5, closed: true, tags: { landuse: 'farmland' } })")
        val host: dynamic = js(
            "({ inside: function (a) { return a.id === 5 ? [{ type: 'node', id: 9, tags: {} }] : [] }, pseudoClass: function (n) { return n === 'righthandtraffic' } })",
        )
        val st = mapCssStyles(sheet, area, host)
        assertEquals(3, st.properties.width)
        assertEquals(1, st.properties.opacity)
    }

    @Test
    fun mapLibreAndGoql() {
        val sheet = mapCssParse(Examples.MISSING_SURFACE, "m.mapcss").stylesheet
        val f = mapCssMapLibreFilter(sheet, 0, js("({ typeProperty: 'osm', tagPrefix: 't.' })"))
        assertEquals(
            """["all",["==",["get","osm"],"way"],["all",["has","t.highway"],["in",["to-string",["get","t.highway"]],["literal",["primary","secondary","tertiary","residential","unclassified"]]]],["!",["has","t.surface"]]]""",
            stringify(f),
        )
        assertNull(mapCssMapLibreFilter(sheet, 5, null))
        assertEquals("[\"has\",\"a\"]", stringify(mapCssSelectorToMapLibre("*[a]", null)))
        assertNull(mapCssSelectorToMapLibre("way > node", null))
        assertNull(mapCssSelectorToMapLibre("way[", null))
        assertTrue(mapCssGoqlPrefilter(sheet)!!.startsWith("wa[highway="))
    }
}
