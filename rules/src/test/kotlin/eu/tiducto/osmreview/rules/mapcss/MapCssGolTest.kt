package eu.tiducto.osmreview.rules.mapcss

import com.geodesk.geom.Box
import eu.tiducto.osmreview.rules.GolMirror
import eu.tiducto.osmreview.rules.Scope
import eu.tiducto.osmreview.rules.ref
import eu.tiducto.osmreview.shared.OsmRef
import eu.tiducto.osmreview.shared.Severity
import eu.tiducto.osmreview.shared.mapcss.MapCss
import eu.tiducto.osmreview.shared.mapcss.Stylesheet
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.streams.toList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.measureTimedValue

class MapCssGolTest {
    private val golPath = System.getenv("OSM_REVIEW_TEST_GOL")?.takeIf { it.isNotBlank() }?.let(Path::of)?.takeIf(Files::exists)
    private var mirror: GolMirror? = null

    private val katerinky = Box.ofWSEN(17.89, 49.93, 17.92, 49.945)
    private val opava = Box.ofWSEN(17.85, 49.90, 17.98, 49.97)

    private fun withEngine(block: (MapCssEngine) -> Unit) {
        val path = golPath ?: return println("OSM_REVIEW_TEST_GOL not set, skipping")
        val m = GolMirror(Files.createTempDirectory("gol"), path).also { mirror = it }
        check(m.reloadIfChanged())
        block(MapCssEngine(m))
    }

    @AfterTest
    fun close() {
        mirror?.close()
    }

    private fun sheet(source: String, file: String = "t.mapcss"): Stylesheet {
        val r = MapCss.parse(source, file)
        val errors = r.diagnostics.filter { it.severity == Severity.ERROR }
        if (errors.isNotEmpty()) fail("$file: $errors")
        return assertNotNull(r.stylesheet)
    }

    private val exampleDir = listOf(Path.of("../user-rules.example"), Path.of("user-rules.example")).first { Files.isDirectory(it) }

    private fun exampleSheets(): List<Stylesheet> = Files.walk(exampleDir).use { s ->
        s.toList().filter { it.name.endsWith(".mapcss") }.sorted().map { sheet(it.readText(), exampleDir.relativize(it).toString()) }
    }

    private val selectors = listOf(
        "node[highway=crossing][!crossing]",
        "way[highway=footway][footway=sidewalk][!surface]",
        "way[highway=~/^(footway|path|cycleway)$/][!surface]",
        "way[highway=~/^foot/]",
        "way[highway=~/way$/i]",
        "*[name^=\"Nádraž\"]",
        "*[name$=\"ská\"]",
        "*[name*=\"Horní\"]",
        "way[maxspeed>=50]",
        "way[maxspeed<30]",
        "way[lit?]",
        "way[oneway?!]",
        "node[amenity=bench][backrest=no]",
        "*[\"addr:street\"][!\"addr:housenumber\"]",
        "way[sidewalk:both=separate]",
        "relation[type=route][route=bus]",
        "area[building][!height]",
        "area[landuse=grass]",
        "way[source~=cuzk]",
        "node[!highway][!amenity][shop]",
        "way:closed[building][area=yes]",
        "way[highway][highway!=footway][!name]",
        "*[sport]",
        "node[public_transport=platform], way[railway=platform]",
        "way[highway=residential][name=~/^(Masarykova|Ostrožná)$/]",
    )

    @Test
    fun goqlPrefiltersAreAcceptedAndSupersets() = withEngine { engine ->
        val sheets = selectors.mapIndexed { i, s -> sheet("$s { color: red; throwOther: \"x\"; }", "sel$i.mapcss") } + exampleSheets()
        engine.session { s ->
            val all = s.gol.`in`(katerinky).toList()
            println("Kateřinky features: ${all.size}")
            for (sh in sheets) {
                val goql = sh.toGoqlPrefilter()
                val matched = all.filter { f -> sh.evaluate(GolElement(f), s.host).let { it.style.matched || it.checks.isNotEmpty() } }
                    .map { it.ref }.toSet()
                if (goql == null) {
                    println("${sh.file}: no prefilter (${matched.size} matches)")
                    continue
                }
                val (selected, took) = measureTimedValue {
                    runCatching { s.gol.select(goql).`in`(katerinky).map { it.ref }.toSet() }
                        .getOrElse { fail("${sh.file}: GeoDesk rejected '$goql': $it") }
                }
                val missing: Set<OsmRef> = matched - selected
                println("${sh.file}: goql='$goql' selected=${selected.size} matched=${matched.size} in $took")
                assertTrue(missing.isEmpty(), "${sh.file}: '$goql' misses ${missing.take(10)}")
            }
        }
    }

    @Test
    fun elementAdapterAndHost() = withEngine { engine ->
        engine.session { s ->
            val way = s.gol.select("w[highway=residential][name]").`in`(katerinky).first()
            val el = GolElement(way)
            assertEquals(way.id(), el.id)
            assertTrue(el.children.size >= 2)
            assertTrue(el.children.all { it.element.parents.any { p -> p.element == el } })
            assertEquals(listOf(0, 1), el.children.take(2).map { it.index })
            val node = el.children[1].element
            assertEquals(1, node.parents.first { it.element == el }.index)

            val member = s.gol.select("w").`in`(katerinky).first { it.belongsToRelation() }
            val link = GolElement(member).parents.first()
            println("way ${member.id()} in relation ${link.element.id} as '${link.role}' at ${link.index}")
            assertEquals(link.index, link.element.children.indexOfFirst { it.element == GolElement(member) })

            val host = s.host
            assertEquals(true, host.call("inside", el, listOf("CZ")))
            assertEquals(false, host.call("outside", el, listOf("CZ")))
            assertEquals(false, host.call("inside", el, listOf("SK")))
            assertEquals(true, host.call("inside", el, listOf("CZ-80")))
            assertEquals(true, host.pseudoClass("righthandtraffic", el))
            val len = host.call("waylength", el, emptyList()) as Double
            assertTrue(len > 1, "$len")
            assertTrue((host.call("half_width", el, emptyList()) as Double) > 1)
            val island = host.call("network_island_size", el, emptyList()) as Double
            assertEquals(GolMapCssHost.ISLAND_CAP.toDouble(), island)
        }
    }

    @Test
    fun builtInsOnRealData() = withEngine { engine ->
        val odd = sheet("way[highway][name][odd_segment(\"maxspeed;surface;lit\") != \"\"] { throwWarning: odd_segment(\"maxspeed;surface;lit\"); }")
        val (run, took) = measureTimedValue { engine.checks(odd, Scope(opava), 10_000) }
        println("odd_segment in Opava: ${run.hits.size} of ${run.candidates} candidates in $took; e.g. ${run.hits.take(3).map { it.ref to it.results.single().message }}")
        engine.session { s ->
            for (h in run.hits.take(20)) {
                val f = s.gol.select("w").`in`(Box.ofWSEN(h.centroid.lng - 0.0001, h.centroid.lat - 0.0001, h.centroid.lng + 0.0001, h.centroid.lat + 0.0001))
                    .firstOrNull { it.ref == h.ref } ?: continue
                val keys = h.results.single().message.split(';')
                val name = f.tag("name")
                val neighbours = s.gol.select("w[name]").connectedTo(f).filter { it.tag("name") == name }
                assertTrue(neighbours.size >= 2, "${h.ref} has ${neighbours.size} neighbours")
                keys.forEach { k -> assertTrue(neighbours.all { it.tag(k) != f.tag(k) }, "${h.ref} $k") }
            }
        }
        val islands = sheet("way[highway=footway][network_island_size() < 3] { throwOther: concat(\"island \", network_island_size()); }")
        val (isl, took2) = measureTimedValue { engine.checks(islands, Scope(opava), 10_000) }
        println("footway islands in Opava: ${isl.hits.size} of ${isl.candidates} in $took2")
        assertTrue(isl.hits.all { it.results.single().message.removePrefix("island ").toDouble() < 3 })
    }

    @Test
    fun sidewalkCoverageInOpava() = withEngine { engine ->
        val sidewalks = exampleSheets().first { it.meta.title == "Sidewalks" }
        val (cov, took) = measureTimedValue { engine.coverage(sidewalks, Scope(opava), sidewalks.meta.stats) }
        val total = cov.buckets.sumOf { it.lengthM }
        cov.buckets.forEach { println("  ${it.label}: ${it.count} ways, ${"%.1f".format(it.lengthM / 1000)} km, ${"%.1f".format(100 * it.lengthM / total)} %") }
        println("Sidewalks coverage over the Opava bbox: ${cov.candidates} candidates in $took")
        assertTrue(total > 50_000, "$total m")
        assertEquals(listOf("both sides", "one side", "mapped separately", "none", "missing"), cov.buckets.map { it.label })
    }
}
