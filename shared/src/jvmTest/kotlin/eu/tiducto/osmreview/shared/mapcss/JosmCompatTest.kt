package eu.tiducto.osmreview.shared.mapcss

import eu.tiducto.osmreview.shared.Severity
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JosmCompatTest {
    private val path = System.getenv("OSM_REVIEW_JOSM_MAPCSS")?.takeIf { it.isNotBlank() }

    @Test
    fun josmValidatorFilesParse() {
        val dir = path?.let(::File) ?: return println("OSM_REVIEW_JOSM_MAPCSS not set, skipping JOSM compatibility test")
        val files = if (dir.isDirectory) dir.listFiles { f -> f.name.endsWith(".mapcss") }!!.sorted() else listOf(dir)
        assertTrue(files.isNotEmpty())
        val problems = mutableListOf<Diagnostic>()
        for (f in files) {
            val r = MapCss.parse(f.readText(), f.name)
            r.diagnostics.forEach { println(it) }
            problems += r.diagnostics.filterNot { it.message.startsWith("Unsupported") || it.severity == Severity.INFO }
            val sheet = r.stylesheet ?: continue
            val failures = sheet.selfTest()
            failures.forEach { println(it) }
            problems += failures
            println("${f.name}: ${sheet.rules.size} rules, ${r.diagnostics.size} unsupported, prefilter ${sheet.toGoqlPrefilter()?.length ?: "none"}")
        }
        assertEquals(emptyList(), problems)
    }
}
