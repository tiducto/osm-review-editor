package eu.tiducto.osmreview.server.rulesfolder

import eu.tiducto.osmreview.server.core.EventBus
import eu.tiducto.osmreview.server.core.NotFoundException
import eu.tiducto.osmreview.server.core.StateException
import eu.tiducto.osmreview.shared.DiagnosticSeverity
import eu.tiducto.osmreview.shared.RuleDiagnostic
import eu.tiducto.osmreview.shared.RuleKind
import eu.tiducto.osmreview.shared.RuleOrigin
import eu.tiducto.osmreview.shared.UiEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RulesFolderTest {
    private val dir: Path = Files.createTempDirectory("rules")
    private val events = EventBus()
    private val folder = RulesFolder(dir, events, listOf(SmartPresetLoader()), debounceMs = 50)

    @AfterTest
    fun tearDown() = folder.close()

    private fun file(rel: String, text: String) = dir.resolve(rel).also { it.parent.createDirectories(); it.writeText(text) }

    @Test
    fun createsTheLayoutAndIndexesOnlyRuleFiles() {
        listOf("highlights", "checks", "presets", "presets/smart").forEach { assertTrue(dir.resolve(it).exists(), it) }
        file("highlights/a.mapcss", "way[highway] { color: red; }")
        file("checks/b.validator.mapcss", "node[amenity] { throwWarning: tr(\"x\"); }")
        file("presets/p.json", """{"id":"custom/x","extends":"amenity/bench"}""")
        file("presets/smart/s.json", "{}")
        file("sources.json", "[]")
        file("README.md", "# hi")
        file("highlights/notes.txt", "x")
        file(".trash/1/highlights/old.mapcss", "x")
        file("highlights/.hidden.mapcss", "x")
        val change = folder.rescan()
        assertEquals(
            listOf("checks/b.validator.mapcss", "highlights/a.mapcss", "presets/p.json", "presets/smart/s.json", "sources.json"),
            change.changed,
        )
        val byPath = folder.list().associateBy { it.path }
        assertEquals(RuleKind.CHECK, byPath.getValue("checks/b.validator.mapcss").kind)
        assertEquals(RulesFolder.sha256("[]".toByteArray()), byPath.getValue("sources.json").hash)
        assertTrue(byPath.getValue("presets/smart/s.json").diagnostics.any { it.severity == DiagnosticSeverity.ERROR })
        assertEquals(emptyList(), byPath.getValue("highlights/a.mapcss").diagnostics)
        assertTrue(folder.rescan().isEmpty)
    }

    @Test
    fun writeIsAtomicValidatedAndForcible() {
        val bad = folder.write("presets/smart/x.json", """{"id":"smart/x"}""")
        assertFalse(bad.saved)
        assertTrue(bad.diagnostics.any { it.message == "Missing \"name\"" })
        assertFalse(dir.resolve("presets/smart/x.json").exists())

        val forced = folder.write("presets/smart/x.json", """{"id":"smart/x"}""", force = true)
        assertTrue(forced.saved)
        assertTrue(assertNotNull(forced.file).diagnostics.isNotEmpty())
        assertEquals("""{"id":"smart/x"}""", dir.resolve("presets/smart/x.json").readText())
        assertEquals(emptyList(), Files.list(dir.resolve("presets/smart")).use { s -> s.toList().filter { it.fileName.toString().startsWith(".") } })

        val ok = folder.write("highlights/new.mapcss", "way { }")
        assertTrue(ok.saved)
        assertEquals("way { }", folder.read("highlights/new.mapcss").content)
        assertFailsWith<StateException> { folder.write("highlights/new.mapcss", "x", createOnly = true) }
        assertFailsWith<StateException> { folder.write("highlights/new.mapcss", "x", expectedHash = "abc") }
        val hash = folder.read("highlights/new.mapcss").file.hash
        assertTrue(folder.write("highlights/new.mapcss", "node { }", expectedHash = hash).saved)
    }

    @Test
    fun validateDoesNotWrite() {
        val v = folder.validate("presets/smart/y.json", "{")
        assertFalse(v.valid)
        assertEquals(RuleKind.SMART_PRESET, v.kind)
        assertFalse(dir.resolve("presets/smart/y.json").exists())
    }

    @Test
    fun rejectsBadPaths() {
        listOf(
            "../outside.mapcss", "/etc/x.mapcss", "highlights/../../x.mapcss", "highlights/x.txt", "highlights/x.js",
            "README.md", ".trash/x/highlights/a.mapcss", "highlights\\a.mapcss", "", "checks/a.mapcss",
        ).forEach { p ->
            assertFailsWith<IllegalArgumentException>(p) { folder.write(p, "x") }
            assertFailsWith<IllegalArgumentException>(p) { folder.validate(p, "x") }
        }
    }

    @Test
    fun rejectsSymlinksOutOfTheFolder() {
        val outside = Files.createTempDirectory("outside")
        val secret = outside.resolve("secret.mapcss").also { it.writeText("way {}") }
        Files.createSymbolicLink(dir.resolve("highlights/link.mapcss"), secret)
        Files.delete(dir.resolve("checks"))
        Files.createSymbolicLink(dir.resolve("checks"), outside)
        assertFailsWith<IllegalArgumentException> { folder.read("highlights/link.mapcss") }
        assertFailsWith<IllegalArgumentException> { folder.write("highlights/link.mapcss", "x") }
        assertFailsWith<IllegalArgumentException> { folder.write("checks/a.validator.mapcss", "x") }
        assertFalse(outside.resolve("a.validator.mapcss").exists())
        folder.rescan()
        assertNull(folder.info("highlights/link.mapcss"))
        assertEquals("way {}", secret.readText())

        val inside = file("highlights/real.mapcss", "node {}")
        Files.createSymbolicLink(dir.resolve("highlights/alias.mapcss"), inside)
        folder.rescan()
        assertEquals("node {}", folder.read("highlights/alias.mapcss").content)
    }

    @Test
    fun deleteMovesToTrash() {
        file("highlights/a.mapcss", "way {}")
        folder.rescan()
        val r = folder.delete("highlights/a.mapcss")
        assertTrue(r.trashedTo.startsWith(".trash/"), r.trashedTo)
        assertTrue(r.trashedTo.endsWith("/highlights/a.mapcss"), r.trashedTo)
        assertEquals("way {}", dir.resolve(r.trashedTo).readText())
        assertFalse(dir.resolve("highlights/a.mapcss").exists())
        assertNull(folder.info("highlights/a.mapcss"))
        assertFailsWith<NotFoundException> { folder.delete("highlights/a.mapcss") }
        assertFailsWith<NotFoundException> { folder.read("highlights/a.mapcss") }
    }

    @Test
    fun reportsNonUtf8Files() {
        dir.resolve("highlights/bin.mapcss").writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        folder.rescan()
        val d = folder.info("highlights/bin.mapcss")!!.diagnostics.single()
        assertEquals("The file is not valid UTF-8 text", d.message)
    }

    @Test
    fun loadersArePluggable() {
        file("checks/a.validator.mapcss", "broken {")
        folder.rescan()
        assertEquals(emptyList(), folder.info("checks/a.validator.mapcss")!!.diagnostics)
        val seen = mutableListOf<String>()
        folder.onChange { seen += it.changed }
        folder.register(
            object : RuleLoader<String> {
                override val kind = RuleKind.CHECK

                override fun load(file: String, source: String): RuleLoadResult<String> = RuleLoadResult(
                    source,
                    if ("{" in source && "}" !in source) listOf(RuleDiagnostic(file, 1, 8, DiagnosticSeverity.ERROR, "Unclosed block")) else emptyList(),
                )
            },
        )
        assertEquals("Unclosed block", folder.info("checks/a.validator.mapcss")!!.diagnostics.single().message)
        assertEquals(listOf("checks/a.validator.mapcss"), seen)
        assertFalse(folder.write("checks/b.validator.mapcss", "x {").saved)
    }

    @Test
    fun watcherPicksUpExternalEditsAndEmitsAnEvent() = runBlocking {
        folder.rescan()
        folder.startWatching()
        val event = async { withTimeout(30_000) { events.events.filterIsInstance<UiEvent.RulesChanged>().first() } }
        yield()
        Thread.sleep(200)
        file("highlights/external.mapcss", "way { color: blue; }")
        val e = event.await()
        assertEquals(listOf("highlights/external.mapcss"), e.changed)
        assertEquals("way { color: blue; }", folder.read("highlights/external.mapcss").content)
    }

    private fun withBuiltIns(block: (RulesFolder, Path, MutableList<RulesChange>) -> Unit) {
        val d = Files.createTempDirectory("rules")
        val changes = mutableListOf<RulesChange>()
        RulesFolder(d, null, listOf(MapCssLoader(RuleKind.HIGHLIGHT), MapCssLoader(RuleKind.CHECK)), builtInFiles = BuiltInRules.fromResources()).use { f ->
            f.onChange { changes += it }
            f.rescan()
            block(f, d, changes)
        }
    }

    @Test
    fun anEmptyFolderListsTheBuiltInHighlightsAndChecks() = withBuiltIns { f, _, _ ->
        val files = f.list()
        val paths = files.map { it.path }.toSet()
        assertTrue(
            paths.containsAll(listOf("highlights/sidewalks.mapcss", "highlights/footways.mapcss", "checks/surfaces.validator.mapcss", "checks/crossings.validator.mapcss")),
            paths.toString(),
        )
        assertTrue(files.all { it.readOnly && it.origin?.builtIn == true })
        val surfaces = files.first { it.path == "checks/surfaces.validator.mapcss" }
        assertEquals(RuleOrigin("built-in", "builtin:checks/surfaces.validator.mapcss", BuiltInRules.LICENCE, builtIn = true), surfaces.origin)
        assertTrue(f.list(RuleKind.HIGHLIGHT).all { it.kind == RuleKind.HIGHLIGHT && it.path.startsWith("highlights/") })

        val checks = f.loaded(RuleKind.CHECK)
        assertTrue(checks.any { it.path == "checks/surfaces.validator.mapcss" && it.remote && it.value != null }, checks.toString())
        assertTrue(f.loaded(RuleKind.HIGHLIGHT).any { it.path == "highlights/sidewalks.mapcss" })

        val example = listOf(Path.of("../user-rules.example"), Path.of("user-rules.example")).first { Files.isDirectory(it) }
        assertEquals(example.resolve("checks/surfaces.validator.mapcss").readText(), f.read("checks/surfaces.validator.mapcss").content)
        assertTrue(f.validate("checks/surfaces.validator.mapcss", f.read("checks/surfaces.validator.mapcss").content).valid)
        assertFalse(f.validate("checks/surfaces.validator.mapcss", "way {").valid)
    }

    @Test
    fun aLocalFileOverridesABuiltInAndDeletingItRestoresIt() = withBuiltIns { f, d, changes ->
        val path = "checks/surfaces.validator.mapcss"
        val builtIn = f.read(path).content
        changes.clear()
        val saved = f.write(path, "way[highway][!surface] { throwWarning: \"x\"; }")
        assertTrue(saved.saved, saved.toString())
        assertEquals(listOf(RulesChange(listOf(path), emptyList())), changes)
        val listed = f.list().filter { it.path == path }
        assertEquals(1, listed.size)
        assertFalse(listed.single().readOnly)
        assertNull(listed.single().origin)
        assertEquals(1, f.loaded(RuleKind.CHECK).count { it.path == path })
        assertFalse(f.loaded(RuleKind.CHECK).single { it.path == path }.remote)
        assertTrue("throwWarning: \"x\"" in f.read(path).content)

        changes.clear()
        f.delete(path)
        assertFalse(d.resolve(path).exists())
        assertEquals(listOf(RulesChange(listOf(path), emptyList())), changes)
        assertEquals(builtIn, f.read(path).content)
        assertTrue(f.info(path)!!.origin!!.builtIn)

        val refused = assertFailsWith<StateException> { f.delete(path) }
        assertTrue("built in" in refused.message!!, refused.message)
        assertFailsWith<NotFoundException> { f.delete("checks/nothing.validator.mapcss") }
    }

    @Test
    fun copyingABuiltInOrRemoteFileCreatesALocalFile() = withBuiltIns { f, d, _ ->
        val path = "highlights/sidewalks.mapcss"
        val builtIn = f.read(path).content
        val copied = f.copy(path)
        assertTrue(copied.saved)
        assertEquals(path, copied.file!!.path)
        assertFalse(copied.file!!.readOnly)
        assertEquals(builtIn, d.resolve(path).readText())
        assertFailsWith<StateException> { f.copy(path) }

        assertEquals("highlights/mine.mapcss", f.copy(path, "highlights/mine.mapcss").file!!.path)
        assertEquals(builtIn, d.resolve("highlights/mine.mapcss").readText())
        assertFailsWith<NotFoundException> { f.copy("checks/nothing.validator.mapcss") }

        val origin = RuleOrigin("ext", "https://example.org/x", "CC0")
        f.setRemote(
            listOf(
                RemoteRuleFile("remote/ext.validator.mapcss", RuleKind.CHECK, "node[a] { throwWarning: \"a\"; }", origin, Instant.EPOCH),
                RemoteRuleFile("remote/style.mapcss.mapcss", RuleKind.HIGHLIGHT, "way { }", origin, Instant.EPOCH),
            ),
        )
        assertEquals("checks/ext.validator.mapcss", f.copy("remote/ext.validator.mapcss").file!!.path)
        assertEquals("node[a] { throwWarning: \"a\"; }", d.resolve("checks/ext.validator.mapcss").readText())
        assertEquals("highlights/style.mapcss", f.copy("remote/style.mapcss.mapcss").file!!.path)
    }
}
