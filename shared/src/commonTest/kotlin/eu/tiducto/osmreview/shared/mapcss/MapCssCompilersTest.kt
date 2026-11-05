package eu.tiducto.osmreview.shared.mapcss

import eu.tiducto.osmreview.shared.mapcss.Examples.sheet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MapCssCompilersTest {
    private fun goql(selector: String): String? {
        val (sel, d) = MapCss.parseSelector(selector)
        return MapCss.toGoqlPrefilter(assertNotNull(sel, d.toString()))
    }

    private fun ml(selector: String, options: MapLibreOptions = MapLibreOptions()): String? {
        val (sel, d) = MapCss.parseSelector(selector)
        return MapCss.toMapLibreFilter(assertNotNull(sel, d.toString()), null, options)?.toString()
    }

    private val roads = "\"primary\",\"secondary\",\"tertiary\",\"residential\",\"unclassified\""
    private val notPoint = """["!",["in",["geometry-type"],["literal",["Point","MultiPoint"]]]]"""

    @Test
    fun goqlForExamples() {
        assertEquals(
            "wa[highway=primary,secondary,tertiary,residential,unclassified][!surface]",
            sheet(Examples.MISSING_SURFACE).toGoqlPrefilter(),
        )
        val road = "wa[highway=primary,secondary,tertiary,residential,unclassified]"
        assertEquals(
            listOf(
                "$road[sidewalk=both]",
                "$road[sidewalk=left,right]",
                "$road[sidewalk=separate]",
                "$road[sidewalk=no]",
                "$road[!sidewalk][!\"sidewalk:both\"][!\"sidewalk:left\"][!\"sidewalk:right\"]",
            ).joinToString(", "),
            sheet(Examples.SIDEWALKS).toGoqlPrefilter(),
        )
        val checks = sheet(Examples.CHECKS)
        assertEquals(
            "wa[highway][name], wa[highway][name=no], wa[highway=no][name], wa[highway=no][name=no], " +
                "wa[building][area=yes], wa[building=no][area=yes], " +
                "wa[highway=footway,path,steps,pedestrian,cycleway]",
            checks.toGoqlPrefilter(),
        )
        assertEquals("wa[building][area=yes], wa[building=no][area=yes]", checks.toGoqlPrefilter(1))
    }

    @Test
    fun goqlClauses() {
        assertNull(goql("*"))
        assertNull(goql("*[a!=b]"))
        assertNull(goql("*:closed"))
        assertEquals("wa", goql("way"))
        assertEquals("wa", goql("area"))
        assertEquals("ra", goql("relation"))
        assertEquals("n", goql("node:closed"))
        assertEquals("*[a], *[a=no]", goql("*[a]"))
        assertEquals("n[!a]", goql("node[!a]"))
        assertEquals("n[a=yes,true,1]", goql("node[a?]"))
        assertEquals("n[a=no,false,0]", goql("node[a?!]"))
        assertEquals("wa[a=\"b*\"]", goql("way[a^=b]"))
        assertEquals("wa[a=\"*b\"]", goql("way[a\$=b]"))
        assertEquals("wa[a], wa[a=no]", goql("way[a*=\"b c\"]"))
        assertEquals("wa[a], wa[a=no]", goql("way[a*=\"b*\"]"))
        assertEquals("wa[a], wa[a=no]", goql("way[a=\"x*\"]"))
        assertEquals("n[x], n[x=no]", goql("node[x=~/foo/]"))
        assertEquals("n[x], n[x=no]", goql("node[x=~/^(a|b)$/i]"))
        assertEquals("n[x=a,b]", goql("node[x][x=~/^(a|b)$/]"))
        assertEquals("n[x=\"a b\",c]", goql("node[x=~/^(a b|c)$/]"))
        assertEquals("n[x], n[x=no]", goql("node[x>5]"))
        assertEquals("n[x], n[x=no]", goql("node[x~=a]"))
        assertEquals("n", goql("node[x!~/a/]"))
        assertEquals("n[\"addr:street\"=\"Main St\"]", goql("node[\"addr:street\"=\"Main St\"]"))
        assertEquals("n[a=\"say \\\"hi\\\"\"]", goql("node[a='say \"hi\"']"))
        assertEquals("wa[highway], wa[highway=no]", goql("relation[type=route] > way[highway]"))
        assertEquals("n[a=b]", goql("node[a=b][tag(\"c\") == \"d\"][/^x/]"))
        assertNull(goql("*.foo"))
    }

    @Test
    fun goqlExpansionIsCapped() {
        val g = assertNotNull(goql("node[a][b][c][d][e][f][g]"))
        assertEquals(32, g.split(", ").size)
    }

    @Test
    fun goqlStylesheetFallsBackToNull() {
        assertEquals("wa[a], wa[a=no], *[!b]", sheet("way[a] { color: red; } *[!b] { color: blue; }").toGoqlPrefilter())
        assertNull(sheet("way[a] { color: red; } * { color: blue; }").toGoqlPrefilter())
        assertEquals("n[a=b]", sheet("way[a] { set .x; } node[a=b] { color: red; }").toGoqlPrefilter())
        assertNull(sheet("way:foo { color: red; }").toGoqlPrefilter())
    }

    @Test
    fun mapLibreForExamples() {
        val s = sheet(Examples.MISSING_SURFACE)
        assertEquals(
            """["all",$notPoint,["all",["has","highway"],["in",["to-string",["get","highway"]],["literal",[$roads]]]],["!",["has","surface"]]]""",
            s.toMapLibreFilter(0).toString(),
        )
        val sw = sheet(Examples.SIDEWALKS)
        val o = MapLibreOptions(tagPrefix = "t:", typeProperty = "osm")
        val roadType = """["==",["get","osm"],"way"]"""
        val road = """["all",$roadType,["all",["has","t:highway"],["in",["to-string",["get","t:highway"]],["literal",[$roads]]]]]"""
        assertEquals("""["all",$roadType,$road,["==",["get","t:sidewalk"],"both"]]""", sw.toMapLibreFilter(1, o).toString())
        assertEquals(
            """["all",$roadType,$road,["all",["has","t:sidewalk"],["in",["to-string",["get","t:sidewalk"]],["literal",["left","right"]]]]]""",
            sw.toMapLibreFilter(2, o).toString(),
        )
        assertEquals(
            """["all",$roadType,$road,["!",["has","t:sidewalk"]],["!",["has","t:sidewalk:both"]],["!",["has","t:sidewalk:left"]],["!",["has","t:sidewalk:right"]]]""",
            sw.toMapLibreFilter(5, o).toString(),
        )
        assertNull(sheet(Examples.CHECKS).toMapLibreFilter(0))
    }

    @Test
    fun mapLibreConditions() {
        val node = """["in",["geometry-type"],["literal",["Point","MultiPoint"]]]"""
        assertEquals("true", ml("*"))
        assertEquals(node, ml("node"))
        assertEquals("""["has","a"]""", ml("*[a]"))
        assertEquals("""["==",["get","a"],"b"]""", ml("*[a=b]"))
        assertEquals("""["!=",["get","a"],"b"]""", ml("*[a!=b]"))
        assertEquals("""["all",["has","a"],["in",["to-string",["get","a"]],["literal",["yes","true","1"]]]]""", ml("*[a?]"))
        assertEquals("""["all",["has","a"],["==",["slice",["to-string",["get","a"]],0,2],"ab"]]""", ml("*[a^=ab]"))
        assertEquals(
            """["all",["has","a"],[">=",["length",["to-string",["get","a"]]],2],["==",["slice",["to-string",["get","a"]],["-",["length",["to-string",["get","a"]]],2]],"ab"]]""",
            ml("*[a\$=ab]"),
        )
        assertEquals("""["all",["has","a"],["in","ab",["to-string",["get","a"]]]]""", ml("*[a*=ab]"))
        assertEquals("""["all",["has","n"],[">",["to-number",["get","n"],4],5]]""", ml("*[n>5]"))
        assertEquals("""["all",["has","n"],["<=",["to-number",["get","n"],6],5]]""", ml("*[n<=5]"))
        assertEquals("""["all",["has","a"],["in",["downcase",["to-string",["get","a"]]],["literal",["x","y"]]]]""", ml("*[a=~/^(X|y)$/i]"))
        assertEquals("""["!",["all",["has","a"],["in",["to-string",["get","a"]],["literal",["x"]]]]]""", ml("*[a!~/^x$/]"))
        assertEquals("""["all",["has","a"],["==",["get","a"],["get","b"]]]""", ml("*[a=*b]"))
        assertEquals("""["==",["get","c"],true]""", ml("*:closed", MapLibreOptions(closedProperty = "c")))
        assertEquals("""["==",["get","type"],"relation"]""", ml("relation", MapLibreOptions(typeProperty = "type")))
        assertEquals("false", ml("*:selected"))
    }

    @Test
    fun mapLibreNotCompilable() {
        assertNull(ml("relation"))
        assertNull(ml("*:closed"))
        assertNull(ml("*:tagged"))
        assertNull(ml("*[a~=b]"))
        assertNull(ml("*[a=~/^a.*$/]"))
        assertNull(ml("*[a=~/b/]"))
        assertNull(ml("*[/^a/]"))
        assertNull(ml("*[tag(\"a\") == \"b\"]"))
        assertNull(ml("way > node"))
        assertNull(ml("way.road"))
    }

    @Test
    fun mapLibreClassResolutionRespectsOrder() {
        val s = sheet("way.a[x] { color: red; } way[y] { set .a; } way.a { color: blue; } way!.a { color: green; }")
        assertEquals("""["all",$notPoint,false,["has","x"]]""", s.toMapLibreFilter(0).toString())
        assertEquals("""["all",$notPoint,["all",$notPoint,["has","y"]]]""", s.toMapLibreFilter(2).toString())
        assertEquals("""["all",$notPoint,["!",["all",$notPoint,["has","y"]]]]""", s.toMapLibreFilter(3).toString())
    }

    @Test
    fun selectorGroupsBecomeAny() {
        val s = sheet("node[a], way[b] { color: red; }")
        assertEquals(
            """["any",["all",["in",["geometry-type"],["literal",["Point","MultiPoint"]]],["has","a"]],["all",$notPoint,["has","b"]]]""",
            s.toMapLibreFilter(0).toString(),
        )
    }
}
