package eu.tiducto.osmreview.rules.mapcss

import com.geodesk.feature.FeatureLibrary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostParityTest {
    private val expectedFile = listOf(Path.of("../web/src/editor/validation/fixtures/host-parity.json"), Path.of("web/src/editor/validation/fixtures/host-parity.json"))
        .first { Files.isDirectory(it.parent) }

    private fun actual(): JsonObject {
        val dir = Files.createTempDirectory("parity")
        val gol = dir.resolve("katerinky-highways.gol")
        javaClass.getResourceAsStream("/fixtures/katerinky-highways.gol")!!.use { Files.copy(it, gol, StandardCopyOption.REPLACE_EXISTING) }
        val lib = FeatureLibrary(gol.toString())
        try {
            val host = GolMapCssHost(lib, MapCssCaches(0))
            val ways = lib.select("w[highway]").toList().sortedBy { it.id() }
            return buildJsonObject {
                put("keys", KEYS)
                put(
                    "ways",
                    buildJsonObject {
                        for (w in ways) {
                            put(
                                "w${w.id()}",
                                buildJsonObject {
                                    put("island", host.islandSize(w))
                                    put("odd", host.oddSegment(w, KEYS))
                                },
                            )
                        }
                    },
                )
            }
        } finally {
            lib.close()
        }
    }

    @Test
    fun graphHostFixtureMatchesTheGolHost() {
        val json = Json { prettyPrint = true }
        val now = actual()
        if (System.getenv("UPDATE_HOST_PARITY") == "1") expectedFile.writeText(json.encodeToString(JsonObject.serializer(), now) + "\n")
        val expected = Json.parseToJsonElement(expectedFile.readText()) as JsonObject
        assertEquals(expected, now)
        val ways = now["ways"] as JsonObject
        assertTrue(ways.size > 50)
        assertTrue(ways.values.any { ((it as JsonObject)["odd"] as JsonPrimitive).content.isNotEmpty() })
        assertTrue(ways.values.map { ((it as JsonObject)["island"] as JsonPrimitive).content }.toSet().size > 2)
    }

    private companion object {
        const val KEYS = "maxspeed;surface;sidewalk;lit;lanes"
    }
}
