package eu.tiducto.osmreview.rules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.copyTo
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GolHeaderTest {
    private val fixture = Path.of("src/test/resources/fixtures/katerinky-highways.gol")

    private fun header(active: Int, revision: Int, millis: Long, url: String): ByteBuffer {
        val b = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        b.put(17, active.toByte())
        b.putInt(128 + 64 * active, revision)
        b.putLong(136 + 64 * active, millis)
        b.put(256, url.length.toByte())
        url.toByteArray().forEachIndexed { i, c -> b.put(257 + i, c) }
        return b
    }

    @Test
    fun readsTheActiveSnapshot() {
        val ts = Instant.parse("2026-11-05T22:16:03Z")
        val h0 = GolHeader.parse(header(0, 7360200, ts.toEpochMilli(), "http://127.0.0.1:8189/replication"))
        assertEquals(GolHeader(7360200, ts, "http://127.0.0.1:8189/replication"), h0)
        val h1 = GolHeader.parse(header(1, 4960, ts.toEpochMilli(), ""))
        assertEquals(4960, h1.revision)
        assertNull(h1.url)
        assertNull(GolHeader.parse(header(0, 1, 0, "")).timestamp)
    }

    @Test
    fun readsAFileWithoutLocking() {
        val h = GolHeader.read(fixture)
        assertNotNull(h)
        assertNull(GolHeader.read(Path.of("does-not-exist.gol")))
        val real = System.getenv("OSM_REVIEW_TEST_GOL")?.takeIf { it.isNotBlank() }?.let(Path::of)?.takeIf { it.exists() } ?: return
        GolMirror(Files.createTempDirectory("gol"), real).use { m ->
            m.reloadIfChanged()
            val s = m.status()
            val rh = GolHeader.read(real)!!
            assertEquals(rh.revision, s.dataSequence)
            assertEquals(rh.timestamp.toString(), s.dataTimestamp)
            assertTrue(s.lagSeconds!! > 0)
            println("${real.fileName}: revision ${rh.revision} at ${rh.timestamp} from ${rh.url}")
        }
    }

    @Test
    fun acknowledgesEveryFlip() {
        val dir = Files.createTempDirectory("gol")
        fixture.copyTo(dir.resolve("a.gol"))
        fixture.copyTo(dir.resolve("b.gol"))
        GolMirror(dir).use { m ->
            assertFalse(m.reloadIfChanged())
            assertFalse(dir.resolve(GolMirror.ACK).exists())
            dir.resolve("current").writeText("a.gol\n")
            assertTrue(m.reloadIfChanged())
            val first = Json.parseToJsonElement(dir.resolve(GolMirror.ACK).readText()).jsonObject
            assertEquals("a.gol", first["open"]!!.jsonPrimitive.content)
            assertEquals("1", first["generation"]!!.jsonPrimitive.content)
            dir.resolve("current").writeText("b.gol\n")
            assertTrue(m.reloadIfChanged())
            val second = Json.parseToJsonElement(dir.resolve(GolMirror.ACK).readText()).jsonObject
            assertEquals("b.gol", second["open"]!!.jsonPrimitive.content)
            assertEquals("a.gol", second["released"]!!.jsonPrimitive.content)
            assertEquals(2, m.peerHeaders().size)
        }
    }
}
