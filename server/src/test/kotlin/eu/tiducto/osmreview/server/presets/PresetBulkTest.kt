package eu.tiducto.osmreview.server.presets

import eu.tiducto.osmreview.rules.GolMirror
import eu.tiducto.osmreview.rules.MirrorQueries
import eu.tiducto.osmreview.rules.RuleEngine
import eu.tiducto.osmreview.server.Config
import eu.tiducto.osmreview.server.core.Account
import eu.tiducto.osmreview.server.core.DraftService
import eu.tiducto.osmreview.server.core.EventBus
import eu.tiducto.osmreview.server.core.FakeOsm
import eu.tiducto.osmreview.server.core.NotFoundException
import eu.tiducto.osmreview.server.db.Db
import eu.tiducto.osmreview.server.db.DraftRepo
import eu.tiducto.osmreview.server.db.SettingsRepo
import eu.tiducto.osmreview.server.rulesfolder.PresetFileLoader
import eu.tiducto.osmreview.server.rulesfolder.RulesFolder
import eu.tiducto.osmreview.server.rulesfolder.SmartPresetLoader
import eu.tiducto.osmreview.shared.Authors
import eu.tiducto.osmreview.shared.Confidence
import eu.tiducto.osmreview.shared.OsmRef
import eu.tiducto.osmreview.shared.OsmType
import eu.tiducto.osmreview.shared.ProposalOutcome
import eu.tiducto.osmreview.shared.presets.PresetBulkApplyRequest
import eu.tiducto.osmreview.shared.presets.PresetBulkSkip
import eu.tiducto.osmreview.shared.presets.PresetGeometry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.runBlocking
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PresetBulkTest {
    private val docker = runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
    private lateinit var pg: PostgreSQLContainer
    private lateinit var db: Db
    private val osm = FakeOsm()
    private val dir = Files.createTempDirectory("rules")
    private val folder = RulesFolder(dir, null, listOf(PresetFileLoader { PresetServiceTest.data.index }, SmartPresetLoader()))
    private val presets = PresetService(PresetServiceTest.data, folder)
    private lateinit var account: Account
    private lateinit var drafts: DraftService
    private lateinit var bulk: PresetBulkService

    private val w55 = OsmRef(OsmType.WAY, 55)
    private val w60 = OsmRef(OsmType.WAY, 60)
    private val w70 = OsmRef(OsmType.WAY, 70)
    private val n21 = OsmRef(OsmType.NODE, 21)
    private val w999 = OsmRef(OsmType.WAY, 999)

    private val mirror = mapOf(
        w55 to PresetTarget(mapOf("highway" to "footway"), PresetGeometry.LINE),
        w60 to PresetTarget(mapOf("highway" to "residential", "name" to "Dlouhá"), PresetGeometry.LINE),
        w70 to PresetTarget(mapOf("highway" to "footway", "footway" to "sidewalk"), PresetGeometry.LINE),
        n21 to PresetTarget(mapOf("amenity" to "bench"), PresetGeometry.POINT),
    )
    private val lookups = mutableListOf<Collection<OsmRef>>()

    @BeforeTest
    fun setUp() {
        if (!docker) return
        pg = PostgreSQLContainer("postgres:18").also { it.start() }
        db = Db(pg.jdbcUrl, pg.username, pg.password)
        val config = Config.fromEnv(mapOf("OSM_CLIENT_ID_DEV" to "client", "TOKEN_ENCRYPTION_KEY" to "k"))
        val http = HttpClient(MockEngine { req -> osm.handle(this, req) })
        account = Account(config, SettingsRepo(db), http)
        val gol = GolMirror(Files.createTempDirectory("gol"))
        drafts = DraftService(DraftRepo(db), account, gol, MirrorQueries(gol), RuleEngine(gol), EventBus())
        bulk = PresetBulkService(presets, drafts) { refs ->
            lookups += refs
            mirror.filterKeys { it in refs }
        }
    }

    @AfterTest
    fun tearDown() {
        folder.close()
        if (!docker) return
        db.close()
        pg.stop()
    }

    private fun connect(): Unit = runBlocking {
        val state = Regex("state=([^&]+)").find(account.authorizeUrl())!!.groupValues[1]
        account.completeAuth("code", state)
    }

    @Test
    fun dryRunCountsSkipsAndPreviews(): Unit = runBlocking {
        if (!docker) return@runBlocking println("Docker not available, skipping")
        val r = bulk.apply(PresetBulkApplyRequest("highway/footway/sidewalk", listOf(w55, w60, w70, n21, w999, w55), locale = "cs"))
        assertEquals(1, lookups.size, "one batched lookup")
        assertEquals("Chodník podél vozovky", r.presetName)
        assertEquals(5, r.total)
        assertEquals(2, r.changed)
        assertEquals(1, r.unchanged)
        assertEquals(
            listOf(PresetBulkSkip(n21, "preset does not apply to point"), PresetBulkSkip(w999, "not in the mirror")),
            r.skipped,
        )
        assertFalse(r.truncated)
        assertNull(r.draft)
        assertEquals(listOf(w55, w60), r.preview.map { it.ref })
        val first = r.preview[0]
        assertEquals("highway/footway", first.fromPresetId)
        assertEquals(mapOf("highway" to "footway", "footway" to "sidewalk"), first.after)
        assertEquals("+footway=sidewalk", first.summary)
        val street = r.preview[1]
        assertEquals("Dlouhá", street.name)
        assertEquals("highway/residential", street.fromPresetId)
        assertEquals(mapOf("footway" to "sidewalk", "highway" to "footway", "name" to "Dlouhá"), street.after)

        folder.write("presets/new-bench.json", """{"id":"custom/new-bench","extends":"amenity/bench","apply":"create"}""", force = true)
        val create = bulk.apply(PresetBulkApplyRequest("custom/new-bench", listOf(n21)))
        assertEquals(listOf(PresetBulkSkip(n21, "preset only applies to new, untagged objects")), create.skipped)

        assertFailsWith<IllegalArgumentException> {
            bulk.apply(PresetBulkApplyRequest("highway/footway/sidewalk", (1L..5001L).map { OsmRef(OsmType.NODE, it) }))
        }
        assertFailsWith<NotFoundException> { bulk.apply(PresetBulkApplyRequest("no/such", listOf(w55))) }
    }

    @Test
    fun createsADraftAndAppendsToIt(): Unit = runBlocking {
        if (!docker) return@runBlocking println("Docker not available, skipping")
        connect()
        val r = bulk.apply(PresetBulkApplyRequest("highway/footway/sidewalk", listOf(w55, w60, n21), dryRun = false, area = "Opava"))
        val draft = r.draft!!
        assertEquals("Preset Sidewalk · 2 objects", draft.draft.title)
        assertEquals(Authors.USER, draft.draft.author)
        assertEquals("Opava", draft.draft.area)
        assertEquals(listOf(ProposalOutcome.ACCEPTED, ProposalOutcome.ACCEPTED), r.results.map { it.outcome })
        assertEquals(2, draft.changes.size)
        assertTrue(draft.changes.all { it.reason == "Preset \"Sidewalk\"" && it.confidence == Confidence.NEEDS_REVIEW })
        val wayChange = draft.changes.first { it.ref == w55 }
        assertEquals(mapOf("footway" to "sidewalk", "highway" to "footway"), wayChange.newTags)
        assertTrue(osm.uploads.isEmpty(), "nothing is uploaded")

        val again = bulk.apply(PresetBulkApplyRequest("highway/footway/sidewalk", listOf(w55), dryRun = false, draftId = draft.draft.id, reason = "sidewalks"))
        assertEquals(draft.draft.id, again.draft!!.draft.id)
        assertEquals(listOf(ProposalOutcome.NO_OP), again.results.map { it.outcome })

        val none = bulk.apply(PresetBulkApplyRequest("highway/footway/sidewalk", listOf(n21), dryRun = false))
        assertNull(none.draft)
        assertEquals(0, none.changed)

        assertFailsWith<NotFoundException> {
            bulk.apply(PresetBulkApplyRequest("highway/footway/sidewalk", listOf(w55), dryRun = false, draftId = "00000000-0000-0000-0000-000000000000"))
        }
    }
}
