package eu.tiducto.osmreview.server

import eu.tiducto.osmreview.rules.GolMirror
import eu.tiducto.osmreview.server.core.OverlayService
import eu.tiducto.osmreview.server.core.StateException
import eu.tiducto.osmreview.shared.UiEvent
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.copyTo
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MirrorControlTest {
    private val fixture = Path.of("../rules/src/test/resources/fixtures/katerinky-highways.gol")

    @Test
    fun updateNowWritesATriggerOnceAndRefusesWhileBusy() {
        val dir = Files.createTempDirectory("gol")
        fixture.copyTo(dir.resolve("a.gol"))
        dir.resolve("current").writeText("a.gol\n")
        val events = ArrayList<UiEvent>()
        GolMirror(dir).use { mirror ->
            mirror.reloadIfChanged()
            val control = MirrorControl(dir, mirror, { null }, { events += it })
            dir.resolve("status.json").writeText("""{"phase":"idle","result":"ok"}""")
            val s = control.requestUpdate()
            assertEquals("a.gol", s.file)
            assertTrue(dir.resolve(MirrorControl.TRIGGER).exists())
            assertTrue(events.last() is UiEvent.MirrorStatusChanged)
            assertFailsWith<StateException> { control.requestUpdate() }
            dir.resolve(MirrorControl.TRIGGER).deleteExisting()
            dir.resolve("status.json").writeText("""{"phase":"updating"}""")
            assertFailsWith<StateException> { control.requestUpdate() }
            assertFalse(dir.resolve(MirrorControl.TRIGGER).exists())
        }
    }

    @Test
    fun updateNowNeedsTheSidecar() {
        val dir = Files.createTempDirectory("gol")
        GolMirror(dir, fixture).use { mirror ->
            mirror.reloadIfChanged()
            assertFailsWith<StateException> { MirrorControl(dir, mirror, { null }, {}).requestUpdate() }
        }
    }

    @Test
    fun pollEmitsOnlyWhenTheSidecarStatusChanges() {
        val dir = Files.createTempDirectory("gol")
        val events = ArrayList<UiEvent>()
        GolMirror(dir, fixture).use { mirror ->
            val control = MirrorControl(dir, mirror, { null }, { events += it })
            control.pollSidecar()
            assertEquals(0, events.size)
            dir.resolve("status.json").writeText("""{"phase":"updating","cycleStartedAt":"2026-11-06T00:00:00Z"}""")
            control.pollSidecar()
            control.pollSidecar()
            assertEquals(1, events.size)
            assertEquals("updating", (events.single() as UiEvent.MirrorStatusChanged).status.sidecar!!.phase)
        }
    }

    @Test
    fun overlayStaysUntilTheMirrorDataPassesTheUpload() {
        val upload = Instant.parse("2026-11-06T10:00:00Z")
        assertFalse(OverlayService.covered(upload, upload))
        assertFalse(OverlayService.covered(upload, upload.plusSeconds(60)))
        assertTrue(OverlayService.covered(upload, upload.plusSeconds(90)))
    }
}
