package eu.tiducto.osmreview.shared.mapcss

import eu.tiducto.osmreview.shared.OsmType
import eu.tiducto.osmreview.shared.mapcss.Examples.node
import eu.tiducto.osmreview.shared.mapcss.Examples.relation
import eu.tiducto.osmreview.shared.mapcss.Examples.sheet
import eu.tiducto.osmreview.shared.mapcss.Examples.way
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapCssStylesTest {
    private fun matches(selector: String, el: MapCssElement, host: MapCssHost = MapCssHost.NONE): Boolean =
        sheet("$selector { color: red; }").styles(el, host).matched

    @Test
    fun missingSurface() {
        val s = sheet(Examples.MISSING_SURFACE)
        val st = s.styles(way("highway" to "residential"))
        assertEquals("#d73027", st.color)
        assertEquals(5.0, st.width)
        assertEquals("no surface", st.text)
        assertEquals(listOf(0), st.rules)
        assertFalse(s.styles(way("highway" to "residential", "surface" to "asphalt")).matched)
        assertFalse(s.styles(way("highway" to "motorway")).matched)
        assertFalse(s.styles(node("highway" to "residential")).matched)
        assertFalse(s.styles(way("highway" to "residential_link")).matched)
    }

    @Test
    fun sidewalksUseClasses() {
        val s = sheet(Examples.SIDEWALKS)
        fun style(vararg tags: Pair<String, String>) = s.styles(way(*tags))
        assertEquals("#1a9850", style("highway" to "primary", "sidewalk" to "both").color)
        assertEquals("both", style("highway" to "primary", "sidewalk" to "both").legend)
        assertEquals("#fee08b", style("highway" to "residential", "sidewalk" to "left").color)
        assertEquals("#4575b4", style("highway" to "residential", "sidewalk" to "separate").color)
        val none = style("highway" to "tertiary", "sidewalk" to "no")
        assertEquals("#777", none.color)
        assertEquals("[4,4]", none.properties["dashes"].toString())
        assertEquals("missing", style("highway" to "unclassified").legend)
        assertFalse(style("highway" to "unclassified", "sidewalk:left" to "yes").matched)
        assertFalse(style("highway" to "footway", "sidewalk" to "both").matched)
        assertEquals(listOf("road"), style("highway" to "primary").classes)
        assertEquals(
            listOf("both", "one side", "separate", "none", "missing"),
            s.legend().map { it.label },
        )
        assertEquals("#777", s.legend()[3].color)
    }

    @Test
    fun laterRulesOverride() {
        val s = sheet("way { color: red; width: 1; } way[highway] { color: blue; }")
        val st = s.styles(way("highway" to "x"))
        assertEquals("blue", st.color)
        assertEquals(1.0, st.width)
        assertEquals(listOf(0, 1), st.rules)
    }

    @Test
    fun classOrderMatters() {
        val s = sheet("way.a { color: red; } way { set .a; } way.a { width: 2; } way!.b { opacity: 0.5; }")
        val st = s.styles(way())
        assertNull(st.color)
        assertEquals(2.0, st.width)
        assertEquals(JsonPrimitive(0.5), st.properties["opacity"])
    }

    @Test
    fun tagOperators() {
        val w = way("a" to "Foo", "n" to "12", "l" to "x; y;z", "b" to "yes", "c" to "no", "k" to "Foo")
        assertTrue(matches("way[a]", w))
        assertFalse(matches("way[!a]", w))
        assertTrue(matches("way[!zz]", w))
        assertTrue(matches("way[b?]", w))
        assertFalse(matches("way[c?]", w))
        assertTrue(matches("way[c?!]", w))
        assertFalse(matches("way[zz?!]", w))
        assertTrue(matches("way[a=Foo]", w))
        assertFalse(matches("way[a=foo]", w))
        assertTrue(matches("way[a!=foo]", w))
        assertTrue(matches("way[zz!=foo]", w))
        assertTrue(matches("way[a^=Fo]", w))
        assertTrue(matches("way[a\$=oo]", w))
        assertTrue(matches("way[a*=o]", w))
        assertFalse(matches("way[zz*=o]", w))
        assertTrue(matches("way[l~=y]", w))
        assertTrue(matches("way[l~=z]", w))
        assertFalse(matches("way[l~=x;]", w))
        assertTrue(matches("way[a=~/^F/]", w))
        assertTrue(matches("way[a=~/o/]", w))
        assertFalse(matches("way[a=~/^foo$/]", w))
        assertTrue(matches("way[a=~/^foo$/i]", w))
        assertTrue(matches("way[a=~/^(?i)foo$/]", w))
        assertTrue(matches("way[a!~/^bar/]", w))
        assertTrue(matches("way[zz!~/x/]", w))
        assertTrue(matches("way[n>11]", w))
        assertTrue(matches("way[n>=12]", w))
        assertTrue(matches("way[n<12.5]", w))
        assertFalse(matches("way[n<=11]", w))
        assertFalse(matches("way[a>1]", w))
        assertTrue(matches("way[/^[ab]$/]", w))
        assertFalse(matches("way[/^q/]", w))
        assertTrue(matches("way[!/^q/]", w))
        assertTrue(matches("way[\"a\"='Foo']", w))
        assertTrue(matches("way[a=*k]", w))
        assertFalse(matches("way[a=*n]", w))
        assertTrue(matches("way[*=Foo]", w))
    }

    @Test
    fun typesAndArea() {
        assertTrue(matches("area", way()))
        assertTrue(matches("area", relation("type" to "multipolygon")))
        assertFalse(matches("area", relation("type" to "route")))
        assertFalse(matches("area", node()))
        assertTrue(matches("*", relation()))
        assertTrue(matches("relation", relation()))
        assertFalse(matches("relation", way()))
    }

    @Test
    fun pseudoClasses() {
        assertTrue(matches("way:closed", way(closed = true)))
        assertFalse(matches("way:closed", way()))
        assertTrue(matches("way!:closed", way()))
        assertTrue(matches("way:unclosed", way()))
        assertTrue(matches("node:tagged", node("amenity" to "bench")))
        assertFalse(matches("node:tagged", node("source" to "survey", "source:date" to "2020")))
        assertTrue(matches("node:untagged", node("created_by" to "x")))
        val lone = node()
        assertTrue(matches("node:unconnected", lone))
        val shared = node(id = 5)
        way(id = 1).addChild(shared)
        way(id = 2).addChild(shared)
        assertTrue(matches("node:connection", shared))
        assertFalse(matches("node:unconnected", shared))
        val single = node(id = 6)
        way(id = 3).addChild(single)
        assertFalse(matches("node:connection", single))
        assertFalse(matches("way:righthandtraffic", way()))
        assertTrue(matches("way:righthandtraffic", way(), FakeHost(pseudo = mapOf("righthandtraffic" to true))))
        assertTrue(matches("way:righthandtraffic", way(), FakeHost(values = mapOf("is_right_hand_traffic" to true))))
        assertFalse(matches("way:selected", way()))
        assertTrue(matches("way!:modified", way()))
    }

    @Test
    fun parentChildLinks() {
        val route = relation("type" to "route", "route" to "bus", id = 10)
        val stop = node("public_transport" to "platform", id = 20)
        val other = node("public_transport" to "stop_position", id = 21)
        val last = node(id = 22)
        route.addChild(stop, "platform").addChild(other, "stop").addChild(last, "")
        assertTrue(matches("relation[type=route] > node", stop))
        assertTrue(matches("relation[type=route] >[role=platform] node", stop))
        assertFalse(matches("relation[type=route] >[role=platform] node", other))
        assertTrue(matches("relation >[role!=platform] node", other))
        assertTrue(matches("relation >[index=1] node", stop))
        assertTrue(matches("relation >[index=2] node", other))
        assertTrue(matches("relation >[index=-1] node", last))
        assertFalse(matches("relation >[index=-1] node", other))
        assertTrue(matches("relation >[index>1] node", other))
        assertFalse(matches("relation[type=multipolygon] > node", stop))
        assertTrue(matches("relation node", stop))
        assertTrue(matches("node[public_transport=platform] < relation[route=bus]", route))
        assertFalse(matches("node[highway=bus_stop] < relation", route))
        assertTrue(matches("node >[role=platform] node < relation", route).not())
    }

    @Test
    fun multiLevelLinks() {
        val r = relation("type" to "multipolygon", id = 1)
        val w = way("x" to "y", id = 2)
        val n = node(id = 3)
        r.addChild(w, "outer")
        w.addChild(n)
        assertTrue(matches("relation[type=multipolygon] >[role=outer] way > node", n))
        assertFalse(matches("relation[type=multipolygon] >[role=inner] way > node", n))
    }

    @Test
    fun classesOnLinkedElements() {
        val s = sheet("way[highway=primary] { set .major; } way.major > node { color: red; }")
        val n = node(id = 2)
        way("highway" to "primary", id = 1).addChild(n)
        assertTrue(s.styles(n).matched)
        val n2 = node(id = 3)
        way("highway" to "service", id = 4).addChild(n2)
        assertFalse(s.styles(n2).matched)
    }

    @Test
    fun insideAndCrossingAreHostProvided() {
        val area = way("landuse" to "residential", closed = true, id = 1)
        val bench = node("amenity" to "bench", id = 2)
        val road = way("highway" to "primary", id = 3)
        val rail = way("railway" to "rail", id = 4)
        val host = FakeHost(inside = mapOf(area to listOf(bench)), crossing = mapOf(road to listOf(rail)))
        assertTrue(matches("node[amenity=bench] ∈ area[landuse]", area, host))
        assertFalse(matches("node[amenity=fountain] ∈ area[landuse]", area, host))
        assertTrue(matches("way[railway] ⧉ way[highway]", road, host))
        assertFalse(matches("way[waterway] ⧉ way[highway]", road, host))
        assertFalse(matches("way[railway] ⧉ way[highway]", road))
    }

    @Test
    fun expressionConditionsAndFunctions() {
        val w = way("name" to "Main Street", "lanes" to "2", "maxspeed" to "50", "ref" to "A;B;C", id = 42)
        assertTrue(matches("way[tag(\"lanes\") + 1 == 3]", w))
        assertTrue(matches("way[to_int(tag(\"maxspeed\")) > 30]", w))
        assertTrue(matches("way[count(split(\";\", tag(\"ref\"))) == 3]", w))
        assertTrue(matches("way[regexp_test(\"^Main\", tag(\"name\"))]", w).not())
        assertTrue(matches("way[regexp_test(\"^Main.*\", tag(\"name\"))]", w))
        assertTrue(matches("way[get(regexp_match(\"(\\\\w+) (\\\\w+)\", tag(\"name\")), 2) == \"Street\"]", w))
        assertTrue(matches("way[number_of_tags() == 4]", w))
        assertTrue(matches("way[osm_id() == 42]", w))
        assertTrue(matches("way[has_tag_key(\"name\") && !has_tag_key(\"x\")]", w))
        assertTrue(matches("way[upper(tag(\"name\")) == \"MAIN STREET\"]", w))
        assertTrue(matches("way[length(tag(\"name\")) == 11]", w))
        assertTrue(matches("way[is_similar(tag(\"name\"), \"Main Streat\")]", w))
        assertFalse(matches("way[is_similar(tag(\"name\"), \"Main Street\")]", w))
        assertTrue(matches("way[cond(tag(\"x\"), 1, 2) == 2]", w))
        assertTrue(matches("way[any(tag(\"x\"), tag(\"lanes\")) == 2]", w))
        assertTrue(matches("way[to_float(\"2.5\") * 2 == 5]", w))
        assertTrue(matches("way[to_boolean(\"yes\")]", w))
        assertTrue(matches("way[trim(\"  a \") == \"a\"]", w))
        assertTrue(matches("way[lower(\"AB\") == \"ab\"]", w))
        assertTrue(matches("way[join(\"-\", \"a\", \"b\") == \"a-b\"]", w))
        assertTrue(matches("way[join(\"-\", split(\";\", tag(\"ref\"))) == \"A-B-C\"]", w))
        assertTrue(matches("way[concat(\"a\", tag(\"x\"), 1) == \"a1\"]", w))
        assertTrue(matches("way[tr(\"{0} and {1}\", \"x\", 2) == \"x and 2\"]", w))
        assertTrue(matches("way[tag(\"lanes\") > 1 ? true : false]", w))
        assertTrue(matches("way[!(tag(\"lanes\") < 1)]", w))
        assertTrue(matches("way[10 / 4 == 2.5]", w))
        assertTrue(matches("way[10 % 4 == 2]", w))
        assertTrue(matches("way[-tag(\"lanes\") == -2]", w))
    }

    @Test
    fun josmExtras() {
        val w = way("lanes" to "3", "height" to "74 m", "h2" to "231'10\"", "placement:forward" to "x", "placement:backward" to "x", "oneway" to "no")
        assertTrue(matches("way[mod(tag(lanes), 2) == 1]", w))
        assertTrue(matches("way[siunit_length(tag(height)) == 74]", w))
        assertTrue(matches("way[siunit_length(\"2 km\") == 2000]", w))
        assertTrue(matches("way[siunit_length(\"10 ft\") > 3.04]", w))
        assertTrue(matches("way[siunit_length(tag(h2)) > 70.6][siunit_length(tag(h2)) < 70.7]", w))
        assertTrue(matches("way[siunit_length(\"abc\") == 0]", w).not())
        assertTrue(matches("way[join_list(\"\", uniq_list(tag_regex(\"^placement:.*$\"))) == \"x\"]", w))
        assertTrue(matches("way[!oneway?]", w))
        assertFalse(matches("way[!oneway?]", way("oneway" to "yes")))
        assertTrue(matches("way[!missing?]", w))
        assertTrue(matches("way[plus(1, 2, 3) == 6][times(2, 3) == 6][minus(5, 2) == 3][divided_by(6, 3) == 2]", w))
        assertTrue(matches("way[URL_decode(\"a%20b%C3%A9\") == \"a bé\"][URL_encode(\"a b\") == \"a+b\"][XML_encode(\"<&>\") == \"&lt;&amp;&gt;\"]", w))
        assertTrue(matches("way[\"lanes\"=*\"lanes\"]", w))
        assertTrue(matches("way:in-downloaded-area", w))
        assertFalse(matches("way:in-downloaded-area", w, FakeHost(pseudo = mapOf("in-downloaded-area" to false))))
    }

    @Test
    fun parentTags() {
        val n = node(id = 1)
        way("highway" to "primary", "name" to "B", id = 2).addChild(n)
        way("highway" to "secondary", "name" to "A10", id = 3).addChild(n)
        way("highway" to "service", "name" to "A9", id = 4).addChild(n)
        val s = sheet("node { text: eval(join(\",\", parent_tags(\"name\"))); width: eval(count(parent_tags(\"highway\"))); color: parent_tag(\"name\"); }")
        val st = s.styles(n)
        assertEquals("A9,A10,B", st.text)
        assertEquals(3.0, st.width)
        assertEquals("B", st.color)
    }

    @Test
    fun hostFunctionsReceiveArguments() {
        val host = FakeHost(values = mapOf("waylength" to 12.5, "odd_segment" to "surface"))
        val s = sheet("way[waylength() > 10] { text: odd_segment(\"a;b\"); }")
        assertEquals("surface", s.styles(way(), host).text)
        assertEquals(listOf("waylength" to emptyList(), "odd_segment" to listOf<Any?>("a;b")), host.calls)
        assertFalse(s.styles(way()).matched)
    }

    @Test
    fun insideAndOutside() {
        val s = sheet("way[inside(\"CZ\")] { color: red; } way[outside(\"CZ\")] { width: 2; }")
        val inCz = s.styles(way(), FakeHost(values = mapOf("inside" to true)))
        assertEquals("red", inCz.color)
        assertNull(inCz.width)
        val out = s.styles(way(), FakeHost(values = mapOf("inside" to false)))
        assertEquals(2.0, out.width)
        assertFalse(s.styles(way()).matched)
    }

    @Test
    fun textProperty() {
        val s = sheet("way { text: name; } node { text: auto; } relation { text: \"literal\"; } area[x] { text: concat(\"#\", tag(\"ref\")); }")
        assertEquals("Foo", s.styles(way("name" to "Foo")).text)
        assertNull(s.styles(way()).text)
        assertEquals("Bar", s.styles(node("name" to "Bar")).text)
        assertEquals("literal", s.styles(relation()).text)
        assertEquals("#7", s.styles(way("x" to "1", "ref" to "7")).text)
    }

    @Test
    fun selectorGroups() {
        val s = sheet("node[a], way[b] { color: red; }")
        assertTrue(s.styles(node("a" to "1")).matched)
        assertTrue(s.styles(way("b" to "1")).matched)
        assertFalse(s.styles(way("a" to "1")).matched)
    }

    @Test
    fun simpleElementLinks() {
        val w = SimpleElement(OsmType.WAY, 1)
        val n1 = SimpleElement(OsmType.NODE, 2)
        w.addChild(n1)
        assertEquals(0, n1.parents.single().index)
        assertEquals(w, n1.parents.single().element)
    }
}
