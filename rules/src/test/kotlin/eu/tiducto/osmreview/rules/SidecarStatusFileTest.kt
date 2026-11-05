package eu.tiducto.osmreview.rules

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SidecarStatusFileTest {
    @Test
    fun absentFileGivesNoSidecarStatus() {
        val dir = Files.createTempDirectory("gol")
        assertNull(SidecarStatusFile.read(dir))
        GolMirror(dir).use { assertNull(it.status().sidecar) }
    }

    @Test
    fun parsesFullStatus() {
        val dir = Files.createTempDirectory("gol")
        dir.resolve("status.json").writeText(
            """
            {"lastRunAt":"2026-11-05T03:00:12Z","lastRunKind":"update","result":"ok","error":null,
             "lastFullBuildAt":"2026-11-01T02:10:00Z","nextFullBuildDue":"2026-11-08T02:10:00Z",
             "replicationSequence":4612,"replicationTimestamp":"2026-11-04T20:21:03Z","current":"b.gol","extra":1}
            """.trimIndent(),
        )
        val s = GolMirror(dir).use { it.status() }.sidecar
        assertNotNull(s)
        assertEquals("ok", s.result)
        assertEquals("update", s.lastRunKind)
        assertEquals(4612L, s.replicationSequence)
        assertEquals("2026-11-04T20:21:03Z", s.replicationTimestamp)
        assertEquals("2026-11-08T02:10:00Z", s.nextFullBuildDue)
        assertEquals("b.gol", s.current)
        assertNull(s.error)
    }

    @Test
    fun parsesFailedRunWithoutReplicationInfo() {
        val s = SidecarStatusFile.parse(
            """{"lastRunAt":"2026-11-05T03:00:12Z","lastRunKind":"build","result":"failed","error":"curl failed (exit 22)","replicationSequence":null}""",
        )
        assertEquals("failed", s.result)
        assertEquals("curl failed (exit 22)", s.error)
        assertNull(s.replicationSequence)
        assertNull(s.lastFullBuildAt)
    }

    @Test
    fun malformedFileIsReportedNotThrown() {
        val dir = Files.createTempDirectory("gol")
        dir.resolve("status.json").writeText("{\"lastRunAt\": ")
        val s = SidecarStatusFile.read(dir)
        assertNotNull(s)
        assertEquals("unknown", s.result)
        assertTrue(s.error!!.startsWith("Unreadable status.json"))
        assertEquals("unknown", SidecarStatusFile.parse("[1,2]").result)
        assertEquals("unknown", SidecarStatusFile.parse("""{"replicationSequence":"abc"}""").result)
    }
}
