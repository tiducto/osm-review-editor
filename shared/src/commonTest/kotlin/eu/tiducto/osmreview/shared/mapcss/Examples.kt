package eu.tiducto.osmreview.shared.mapcss

import eu.tiducto.osmreview.shared.OsmType
import eu.tiducto.osmreview.shared.Severity
import kotlin.test.assertNotNull
import kotlin.test.fail

object Examples {
    val MISSING_SURFACE = """
        meta { title: "Missing surface"; -ore-dim-others: true; }
        way[highway=~/^(primary|secondary|tertiary|residential|unclassified)$/][!surface] { color:#d73027; width:5; text:"no surface"; }
    """.trimIndent()

    val SIDEWALKS = """
        meta { title:"Sidewalks"; -ore-dim-others:true; -ore-stats:length; }
        way[highway=~/^(primary|secondary|tertiary|residential|unclassified)$/] { set .road; }
        way.road[sidewalk=both]      { color:#1a9850; -ore-legend:"both"; }
        way.road[sidewalk=~/^(left|right)$/] { color:#fee08b; -ore-legend:"one side"; }
        way.road[sidewalk=separate]  { color:#4575b4; -ore-legend:"separate"; }
        way.road[sidewalk=no]        { color:#777; dashes:4,4; -ore-legend:"none"; }
        way.road[!sidewalk][!sidewalk:both][!sidewalk:left][!sidewalk:right] { color:#d73027; -ore-legend:"missing"; }
    """.trimIndent()

    val CHECKS = """
        way[highway][name][odd_segment("maxspeed;surface;sidewalk;lit") != ""] {
          throwWarning: concat("Differs from connected '", tag("name"), "': ", odd_segment("maxspeed;surface;sidewalk;lit"));
          group: "consistency";
        }
        way:closed[building][area=yes] {
          throwWarning: tr("{0} is unnecessary for {1}", "{2.tag}", "{1.key}");
          group: "unnecessary tag"; fixRemove: "{2.key}";
          assertMatch: "way building=yes area=yes";
        }
        way[highway=~/^(footway|path|steps|pedestrian|cycleway)$/][network_island_size() < 5] {
          throwWarning: "Footway not connected to the street network"; group: "connectivity";
        }
    """.trimIndent()

    fun sheet(source: String, file: String = "test.mapcss"): Stylesheet {
        val r = MapCss.parse(source, file)
        val errors = r.diagnostics.filter { it.severity == Severity.ERROR }
        if (errors.isNotEmpty()) fail("Unexpected errors: $errors")
        return assertNotNull(r.stylesheet)
    }

    fun way(vararg tags: Pair<String, String>, closed: Boolean = false, id: Long = 1) =
        SimpleElement(OsmType.WAY, id, mapOf(*tags), closed)

    fun node(vararg tags: Pair<String, String>, id: Long = 1) = SimpleElement(OsmType.NODE, id, mapOf(*tags))

    fun relation(vararg tags: Pair<String, String>, id: Long = 1) = SimpleElement(OsmType.RELATION, id, mapOf(*tags))
}

class FakeHost(
    private val values: Map<String, Any?> = emptyMap(),
    private val pseudo: Map<String, Boolean> = emptyMap(),
    private val inside: Map<MapCssElement, List<MapCssElement>> = emptyMap(),
    private val crossing: Map<MapCssElement, List<MapCssElement>> = emptyMap(),
) : MapCssHost {
    val calls = mutableListOf<Pair<String, List<Any?>>>()

    override fun call(name: String, element: MapCssElement, args: List<Any?>): Any? {
        calls += name to args
        return values[name]
    }

    override fun pseudoClass(name: String, element: MapCssElement): Boolean? = pseudo[name]

    override fun elementsInside(area: MapCssElement): List<MapCssElement> = inside[area] ?: emptyList()

    override fun elementsCrossing(element: MapCssElement): List<MapCssElement> = crossing[element] ?: emptyList()
}
