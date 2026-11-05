package eu.tiducto.osmreview.shared.mapcss

import eu.tiducto.osmreview.shared.Severity
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapCssParserTest {
    private fun diags(source: String) = MapCss.parse(source, "f.mapcss").diagnostics

    private fun single(source: String): Diagnostic = diags(source).single()

    @Test
    fun examplesParseWithoutDiagnostics() {
        for (src in listOf(Examples.MISSING_SURFACE, Examples.SIDEWALKS, Examples.CHECKS)) {
            val r = MapCss.parse(src, "x.mapcss")
            assertEquals(emptyList(), r.diagnostics, src)
            assertNotNull(r.stylesheet)
        }
    }

    @Test
    fun metaIsEvaluated() {
        val s = Examples.sheet(Examples.SIDEWALKS)
        assertEquals("Sidewalks", s.meta.title)
        assertTrue(s.meta.dimOthers)
        assertEquals("length", s.meta.stats)
        assertEquals(6, s.rules.size)
        val m = Examples.sheet(Examples.MISSING_SURFACE).meta
        assertEquals("Missing surface", m.title)
        assertTrue(m.dimOthers)
        assertNull(m.stats)
    }

    @Test
    fun conditionsAreParsed() {
        val s = Examples.sheet(
            """
            node[a][!b][c?][d?!][e=1][f!=2][g^=x][h${'$'}=y][i*=z][j~=k][l=~/m/i][n!~/o/][p<1][q<=2][r>3][s>=4]["t u"='v w'][/^x:/][!/^y/] {}
            """.trimIndent(),
        )
        val conds = s.rules[0].selectors[0].subject.conditions.map { it as TagCondition }
        assertEquals(
            listOf(
                TagOp.PRESENT, TagOp.ABSENT, TagOp.TRUE, TagOp.FALSE, TagOp.EQ, TagOp.NEQ, TagOp.PREFIX, TagOp.SUFFIX,
                TagOp.CONTAINS, TagOp.LIST, TagOp.MATCH, TagOp.NOT_MATCH, TagOp.LT, TagOp.LE, TagOp.GT, TagOp.GE,
                TagOp.EQ, TagOp.PRESENT, TagOp.ABSENT,
            ),
            conds.map { it.op },
        )
        assertTrue(conds[10].ignoreCase)
        assertEquals(KeyRef.Name("t u"), conds[16].key)
        assertEquals("v w", conds[16].value)
        assertIs<KeyRef.Pattern>(conds[17].key)
        assertEquals(3.0, conds[14].number)
    }

    @Test
    fun selectorStructure() {
        val s = Examples.sheet(
            """
            relation[type=route] >[role=platform][index=-1] node.stop:tagged!:closed, way < relation, area ∈ node, way ⧉ way, relation way { }
            """.trimIndent(),
        )
        val sels = s.rules[0].selectors
        assertEquals(5, sels.size)
        assertEquals(LinkKind.CHILD, sels[0].links[0].kind)
        assertEquals(listOf("role", "index"), sels[0].links[0].conditions.map { it.keyName })
        val subject = sels[0].subject.conditions
        assertIs<ClassCondition>(subject[0])
        assertEquals(PseudoClass.TAGGED, (subject[1] as PseudoCondition).pseudo)
        assertTrue((subject[2] as PseudoCondition).negated)
        assertEquals(LinkKind.PARENT, sels[1].links[0].kind)
        assertEquals(LinkKind.INSIDE, sels[2].links[0].kind)
        assertEquals(LinkKind.CROSSING, sels[3].links[0].kind)
        assertEquals(LinkKind.CHILD, sels[4].links[0].kind)
        assertEquals("relation[type=route] >[role=platform][index=-1] node.stop:tagged!:closed", sels[0].text)
    }

    @Test
    fun expressionConditions() {
        val s = Examples.sheet("""*[tag("a")=="b"||tag("c")!="d"][!has_tag_key("e")][(1 + 2) * 3 > 8] {}""")
        assertEquals(3, s.rules[0].selectors[0].subject.conditions.count { it is ExpressionCondition })
    }

    @Test
    fun commentsAreSkipped() {
        val open = "/" + "*"
        val close = "*" + "/"
        val line = "/" + "/"
        val s = Examples.sheet(
            "$open block\n   text $close\nway[a=b], $open inline $close $line to the end\nnode[c] { color: red; $open x $close width: 2 }",
        )
        assertEquals(2, s.rules[0].selectors.size)
    }

    @Test
    fun syntaxErrorPositions() {
        val d = single("way[highway=primary { color: red; }")
        assertEquals(Severity.ERROR, d.severity)
        assertEquals(1, d.line)
        assertEquals(21, d.column)

        val d2 = single("way { color: red; }\nnode[a] { width: ; }")
        assertEquals(2, d2.line)
        assertEquals(18, d2.column)
        assertTrue(d2.message.startsWith("Expected expression"), d2.message)

        val d3 = single("\n\n  wat[a] { }")
        assertEquals(3, d3.line)
        assertEquals(3, d3.column)
        assertEquals("Unknown selector type 'wat'", d3.message)

        val d4 = single("way[a=~/(unclosed/] { }")
        assertEquals(1, d4.line)
        assertEquals(8, d4.column)
        assertTrue(d4.message.startsWith("Invalid regular expression"), d4.message)

        val d5 = diags("way { text: \"abc }").first()
        assertEquals(13, d5.column)
        assertEquals("Unterminated string", d5.message)

        val d6 = single("way[a<x] { }")
        assertEquals(6, d6.column)
    }

    @Test
    fun recoveryKeepsLaterRules() {
        val r = MapCss.parse("way[[ { color: red; }\nnode[a] { color: blue; }\nway { width: 2 }", "f")
        assertNull(r.stylesheet)
        assertEquals(1, r.diagnostics.size)
        val r2 = MapCss.parse("way { color: ; width: 3; }", "f")
        assertEquals(1, r2.diagnostics.size)
    }

    @Test
    fun missingBraceAtEof() {
        val d = single("way { color: red;")
        assertEquals(Severity.ERROR, d.severity)
        assertTrue(d.message.contains("Missing '}'"), d.message)
    }

    @Test
    fun unknownFunctionIsAnError() {
        val d = single("way[foo_bar(1)] { }")
        assertEquals(Severity.ERROR, d.severity)
        assertEquals("Unknown function foo_bar()", d.message)
        assertEquals(5, d.column)
        assertTrue(MapCss.parse("way[foo_bar(1)] { }", "f", setOf("foo_bar")).diagnostics.isEmpty())
    }

    @Test
    fun hostFunctionsAreKnown() {
        val src = """way[odd_segment("a") != ""][network_island_size() < 5][half_width() > 1][inside("CZ")][outside("SK")][waylength() > 3][areasize() > 1] { }"""
        assertEquals(emptyList(), diags(src))
    }

    @Test
    fun unsupportedFunctionIsAWarning() {
        val d = single("way[JOSM_search(\"x\")] { }")
        assertEquals(Severity.WARNING, d.severity)
        assertTrue(d.message.startsWith("Unsupported function"))
    }

    @Test
    fun ignoredFeaturesGiveInfo() {
        val r = MapCss.parse(
            """
            way|z14-[highway]::casing { icon-image: presets/foo.svg; color: red; }
            node:selected { color: blue; }
            way:modified { color: blue; }
            """.trimIndent(),
            "f",
        )
        assertNotNull(r.stylesheet)
        assertEquals(5, r.diagnostics.size, r.diagnostics.toString())
        assertTrue(r.diagnostics.all { it.severity == Severity.INFO })
        assertEquals(listOf(1, 1, 1, 2, 3), r.diagnostics.map { it.line })
        assertEquals(listOf(4, 18, 29, 5, 4), r.diagnostics.map { it.column })
    }

    @Test
    fun unknownPropertiesPassThroughOnce() {
        val r = MapCss.parse("way { font-size: 12; } node { font-size: 10; text-halo-color: white; }", "f")
        assertEquals(2, r.diagnostics.size)
        assertTrue(r.diagnostics.all { it.severity == Severity.INFO && it.message.startsWith("Unknown property") })
        val s = assertNotNull(r.stylesheet)
        assertEquals(JsonPrimitive(12), s.styles(Examples.way()).properties["font-size"])
    }

    @Test
    fun unparsableUnknownPropertyKeptAsRawText() {
        val r = MapCss.parse("way { -x-thing: a b c; color: red; }", "f")
        val s = assertNotNull(r.stylesheet)
        assertEquals(JsonPrimitive("a b c"), s.styles(Examples.way()).properties["-x-thing"])
    }

    @Test
    fun unsupportedSelectorsAreDisabled() {
        val r = MapCss.parse("way[!barrier] >:sameTags node:tagged { throwWarning: \"x\"; } node:foo { color: red; }", "f")
        assertEquals(2, r.diagnostics.size)
        assertTrue(r.diagnostics.all { it.severity == Severity.WARNING && it.message.startsWith("Unsupported") })
        val s = assertNotNull(r.stylesheet)
        assertTrue(s.rules.all { r -> r.selectors.all { it.disabledReason != null } })
    }

    @Test
    fun atRulesAreSkipped() {
        val r = MapCss.parse("@supports (min-josm-version: 9789) { way { color: red; } }\nnode { color: blue; }", "f")
        assertEquals(1, r.diagnostics.size)
        assertEquals(Severity.WARNING, r.diagnostics[0].severity)
        assertEquals(1, assertNotNull(r.stylesheet).rules.size)
    }

    @Test
    fun invalidStatsValueWarns() {
        val d = single("meta { -ore-stats: volume; }")
        assertEquals(Severity.WARNING, d.severity)
    }

    @Test
    fun badAssertionSyntax() {
        val d = single("way[a] { throwWarning: \"x\"; assertMatch: \"thing a=b\"; }")
        assertEquals(Severity.ERROR, d.severity)
        assertEquals(29, d.column)
    }

    @Test
    fun declarationValues() {
        val s = Examples.sheet("way { set .a, .b; set c; dashes: 4, 4; z-index: -5; color: rgb(1, 0, 0); width: eval(2 * 1.5); }")
        assertEquals(listOf("a", "b", "c"), s.rules[0].setClasses)
        val st = s.styles(Examples.way())
        assertEquals("[4,4]", st.properties["dashes"].toString())
        assertEquals("-5", st.properties["z-index"].toString())
        assertEquals("#ff0000", st.color)
        assertEquals(3.0, st.width)
        assertEquals(listOf("a", "b", "c"), st.classes)
    }

    @Test
    fun parseSelectorAlone() {
        val (sel, d) = MapCss.parseSelector("way[highway=primary][!surface]")
        assertTrue(d.isEmpty())
        assertEquals(2, assertNotNull(sel).subject.conditions.size)
        val (bad, d2) = MapCss.parseSelector("way[highway=primary")
        assertNull(bad)
        assertEquals(1, d2.size)
    }
}
