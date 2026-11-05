package eu.tiducto.osmreview.rules

import com.geodesk.feature.FeatureLibrary
import com.geodesk.geom.Box
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.measureTimedValue

/**
 * Milestone 0 (GOL spike), read half: GOQL selects, parents() on platforms and a max-distance
 * sidewalk query from Kotlin. Runs only when OSM_REVIEW_TEST_GOL points at a Czechia GOL.
 * The update half (`gol update`, reopen, changed objects visible) is scripted in infra/gol/spike.sh.
 */
class GolSpikeTest {
    private val golPath = System.getenv("OSM_REVIEW_TEST_GOL")?.takeIf { it.isNotBlank() }?.let(Path::of)

    // Opava city centre and Brno-střed, generous boxes.
    private val opava = Box.ofWSEN(17.85, 49.90, 17.98, 49.97)
    private val brno = Box.ofWSEN(16.56, 49.17, 16.65, 49.22)

    private fun withGol(block: (FeatureLibrary) -> Unit) {
        val path = golPath ?: return println("OSM_REVIEW_TEST_GOL not set, skipping GOL spike")
        check(Files.exists(path)) { "GOL not found: $path" }
        FeatureLibrary(path).use(block)
    }

    @Test
    fun goqlSelectsWork() = withGol { gol ->
        val (platforms, took) = measureTimedValue {
            gol.select("w[public_transport=platform], w[highway=platform], n[public_transport=platform]").`in`(opava).toList()
        }
        println("Opava platforms: ${platforms.size} in $took")
        assertTrue(platforms.isNotEmpty())
        val closedNoArea = gol.select("w[public_transport=platform][!area], w[highway=platform][!area]").`in`(brno)
            .filter { it.isClosedRing() }
        println("Brno closed platform ways without area: ${closedNoArea.size}")
    }

    @Test
    fun parentsOfPlatformsWork() = withGol { gol ->
        val withParents = gol.select("n[public_transport=platform]").`in`(opava).take(200).map { p ->
            p to p.parents().toList()
        }
        val routeMember = withParents.count { (_, parents) -> parents.any { it.isRelation && it.hasTag("type", "route") } }
        println("Opava platform nodes checked: ${withParents.size}, members of a route relation: $routeMember")
        assertTrue(routeMember > 0, "expected some platforms to belong to route relations")
    }

    @Test
    fun maxDistanceSidewalkQueryWorks() = withGol { gol ->
        val sidewalks = gol.select("w[footway=sidewalk]")
        val (roads, took) = measureTimedValue {
            gol.select("w[highway][sidewalk=both,left,right,yes]").`in`(opava)
                .filter { road -> sidewalks.withinMeters(10.0, road).isNotEmpty() }
        }
        println("Opava roads with sidewalk tag and a separate sidewalk within 10 m: ${roads.size} in $took")
    }

    @Test
    fun allRulesRunFastOnOpavaAndBrno() {
        if (golPath == null) return
        val dir = kotlin.io.path.createTempDirectory()
        GolMirror(dir, golPath).use { mirror ->
            check(mirror.reloadIfChanged()) { mirror.status().message ?: "not opened" }
            val engine = RuleEngine(mirror)
            for ((name, box) in listOf("Opava" to opava, "Brno" to brno)) {
                for (rule in engine.rules()) {
                    val r = engine.run(rule.id, Scope(box), name)
                    val fixes = r.issues.count { it.fix != null }
                    println("$name ${rule.id}: ${r.issueCount} issues, $fixes with fix, ${r.tookMs} ms")
                    r.issues.firstOrNull { it.fix != null }?.let { println("    e.g. ${it.ref} ${it.fix} ${it.note ?: ""}") }
                }
            }
        }
    }

    @Test
    fun goqlQuotingOfColonKeys() = withGol { gol ->
        val n = gol.select("w[\"sidewalk:left\"]").`in`(brno).count()
        val m = gol.select("w[sidewalk:left]").`in`(brno).count()
        println("sidewalk:left quoted=$n bare=$m")
    }
}
