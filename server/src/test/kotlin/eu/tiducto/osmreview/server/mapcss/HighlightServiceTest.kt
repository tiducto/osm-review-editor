package eu.tiducto.osmreview.server.mapcss

import eu.tiducto.osmreview.rules.Geo
import eu.tiducto.osmreview.rules.GolMirror
import eu.tiducto.osmreview.rules.Scope
import eu.tiducto.osmreview.rules.mapcss.MapCssEngine
import eu.tiducto.osmreview.server.core.ResolvedScope
import eu.tiducto.osmreview.server.rulesfolder.MapCssLoader
import eu.tiducto.osmreview.server.rulesfolder.RulesFolder
import eu.tiducto.osmreview.shared.BBox
import eu.tiducto.osmreview.shared.RuleKind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.measureTimedValue

class HighlightServiceTest {
    private val gol = System.getenv("OSM_REVIEW_TEST_GOL")?.takeIf { it.isNotBlank() }?.let(Path::of)?.takeIf(Files::exists)
    private val dir: Path = Files.createTempDirectory("rules")
    private val folder = RulesFolder(dir, null, listOf(MapCssLoader(RuleKind.HIGHLIGHT), MapCssLoader(RuleKind.CHECK)))
    private val mirror = GolMirror(Files.createTempDirectory("data"), gol)
    private val service = HighlightService(folder, MapCssEngine(mirror))

    @AfterTest
    fun tearDown() {
        folder.close()
        mirror.close()
    }

    private fun file(rel: String, text: String) = dir.resolve(rel).also { it.parent.createDirectories(); it.writeText(text) }

    private fun example(name: String) = listOf(Path.of("../user-rules.example"), Path.of("user-rules.example"))
        .first { Files.isDirectory(it) }.resolve(name).let(Files::readString)

    @Test
    fun listsFilesWithLegendAndMapLibreFilters() {
        file("highlights/sidewalks.mapcss", example("highlights/sidewalks.mapcss"))
        file("highlights/surface.mapcss", example("highlights/missing-surface.mapcss"))
        file("highlights/broken.mapcss", "way[highway { color: red; }")
        folder.rescan()
        val list = service.list()
        assertEquals("\$type", list.tileProperties.typeProperty)
        val byPath = list.files.associateBy { it.path }

        val sidewalks = byPath.getValue("highlights/sidewalks.mapcss")
        assertEquals("Sidewalks", sidewalks.title)
        assertEquals("length", sidewalks.stats)
        assertTrue(sidewalks.dimOthers)
        assertEquals(listOf("both sides", "one side", "mapped separately", "none", "missing"), sidewalks.legend.map { it.label })
        assertTrue(sidewalks.allCompiled)
        val missing = sidewalks.rules.last()
        assertEquals("missing", missing.legend)
        assertEquals("#d73027", missing.color)
        val filter = missing.filter.toString()
        assertTrue("[\"get\",\"\$type\"]" in filter && "\"way\"" in filter, filter)
        assertTrue("[\"has\",\"sidewalk:both\"]" in filter, filter)
        assertNotNull(sidewalks.goql)

        val surface = byPath.getValue("highlights/surface.mapcss")
        assertFalse(surface.allCompiled)
        assertEquals(listOf(true, true, false), surface.rules.map { it.compiled })
        assertNull(surface.rules.last().filter)

        val broken = byPath.getValue("highlights/broken.mapcss")
        assertFalse(broken.valid)
        assertTrue(broken.diagnostics.isNotEmpty())
    }

    @Test
    fun tileBoundsFollowSlippyMapNumbering() {
        val b = HighlightService.tileBBox("14/9002/5584")
        assertEquals(17.797851, b.west, 1e-5)
        assertEquals(17.819824, b.east, 1e-5)
        assertEquals(360.0 / 16384, b.east - b.west, 1e-9)
        assertTrue(b.south < b.north && b.north in 49.0..51.0, b.toString())
        assertTrue(runCatching { HighlightService.tileBBox("3/9/1") }.isFailure)
    }

    @Test
    fun evaluatesAndCachesOnTheMirror() {
        if (gol == null) return println("OSM_REVIEW_TEST_GOL not set, skipping")
        check(mirror.reloadIfChanged())
        file("highlights/surface.mapcss", example("highlights/missing-surface.mapcss"))
        file("highlights/sidewalks.mapcss", example("highlights/sidewalks.mapcss"))
        folder.rescan()
        val bbox = BBox(17.895, 49.935, 17.915, 49.945)
        val (first, took) = measureTimedValue { service.evaluate("highlights/surface.mapcss", bbox, null) }
        println("missing-surface over $bbox: ${first.features.size} of ${first.candidates} candidates in $took")
        assertFalse(first.cached)
        assertTrue(first.features.isNotEmpty())
        assertTrue(first.features.all { it.style.legend != null && it.style.color != null })
        assertTrue(service.evaluate("highlights/surface.mapcss", bbox, null).cached)
        val tile = service.evaluate("highlights/surface.mapcss", null, "15/18010/11172")
        assertEquals("15/18010/11172", tile.tile)

        val opava = BBox(17.85, 49.90, 17.98, 49.97)
        val scope = ResolvedScope(Scope(Geo.box(opava)), "bbox:opava", null, null, "Opava bbox")
        val (stats, statsTook) = measureTimedValue { service.stats("highlights/sidewalks.mapcss", scope) }
        println("Sidewalks stats over the Opava bbox in $statsTook (service ${stats.tookMs} ms): ${stats.entries.map { "${it.label} ${it.lengthKm} km ${it.percent} %" }}")
        assertEquals("length", stats.stats)
        assertEquals(100.0, stats.entries.sumOf { it.percent }, 0.01)
        assertTrue(stats.total.lengthKm > 50)
    }
}
