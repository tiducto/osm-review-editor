package eu.tiducto.osmreview.server.tiles

import eu.tiducto.osmreview.rules.GolMirror
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TileServiceGolTest {
    private val gol = System.getenv("OSM_REVIEW_TEST_GOL")?.takeIf { it.isNotBlank() }?.let(Path::of)

    private fun decode(r: TileResult) = MvtDecoder.decode(GZIPInputStream(r.gzipped.inputStream()).readBytes())

    @Test
    fun `opava tiles per zoom with timings`() {
        val path = gol ?: return println("OSM_REVIEW_TEST_GOL not set, skipping tile test")
        GolMirror(Files.createTempDirectory("tiles"), path).use { mirror ->
            mirror.reloadIfChanged()
            val service = TileService(mirror)
            service.tile(16, 36025, 22243)
            val times = ArrayList<Long>()
            for (dx in -1..1) for (dy in -1..1) {
                if (dx == 0 && dy == 0) continue
                val r = service.tile(16, 36025 + dx, 22243 + dy)
                times += r.buildMs
                println("z16 ${36025 + dx}/${22243 + dy}: ${r.buildMs} ms, ${r.features} features, ${r.gzipped.size / 1024} KiB gz")
            }
            times.sort()
            println("z16 median ${times[times.size / 2]} ms, max ${times.last()} ms")
            assertTrue(times[times.size / 2] < 1000)

            val center = decode(service.tile(16, 36025, 22243))
            assertTrue(center.getValue(Layers.LINE).features.any { it.props["highway"] != null })
            assertTrue(center.getValue(Layers.AREA).features.any { it.props["building"] != null })
            val vertices = center.getValue(Layers.VERTEX).features
            assertTrue(vertices.any { it.props[Meta.JUNCTION] == true && it.id == null })
            assertTrue(vertices.any { it.id != null })
            assertTrue(center.values.flatMap { it.features }.none { f -> f.props.keys.any { it.startsWith("geodesk:") } })

            val cached = service.tile(16, 36025, 22243)
            assertTrue(cached.cached)
            assertEquals(service.etag(16, 36025, 22243), cached.etag)

            for ((z, x, y) in listOf(Triple(15, 18012, 11121), Triple(14, 9006, 5560), Triple(13, 4503, 2780), Triple(12, 2251, 1390), Triple(11, 1125, 695), Triple(9, 281, 173))) {
                val r = service.tile(z, x, y)
                val layers = decode(r)
                println("z$z $x/$y: ${r.buildMs} ms, ${r.features} features, ${r.gzipped.size / 1024} KiB gz, layers ${layers.mapValues { it.value.features.size }}")
                if (z < 13) assertTrue(layers[Layers.AREA]?.features.orEmpty().none { it.props["building"] != null })
                if (z < 14) assertTrue(layers[Layers.POINT]?.features.orEmpty().all { it.props["place"] != null })
                assertEquals(z >= 15, layers[Layers.VERTEX] != null)
            }
        }
    }
}
