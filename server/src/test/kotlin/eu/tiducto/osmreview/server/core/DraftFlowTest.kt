package eu.tiducto.osmreview.server.core

import eu.tiducto.osmreview.rules.GolMirror
import eu.tiducto.osmreview.rules.MirrorQueries
import eu.tiducto.osmreview.rules.RuleEngine
import eu.tiducto.osmreview.server.Config
import eu.tiducto.osmreview.server.db.Db
import eu.tiducto.osmreview.server.db.DraftMeta
import eu.tiducto.osmreview.server.db.DraftRepo
import eu.tiducto.osmreview.server.db.SettingsRepo
import eu.tiducto.osmreview.shared.Authors
import eu.tiducto.osmreview.shared.ChangeStatus
import eu.tiducto.osmreview.shared.DraftStatus
import eu.tiducto.osmreview.shared.OsmRef
import eu.tiducto.osmreview.shared.OsmType
import eu.tiducto.osmreview.shared.ProposalOutcome
import eu.tiducto.osmreview.shared.ProposedChange
import eu.tiducto.osmreview.shared.ReviewDecision
import eu.tiducto.osmreview.shared.ReviewRequest
import eu.tiducto.osmreview.shared.TagEdit
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DraftFlowTest {
    private val docker = runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
    private lateinit var pg: PostgreSQLContainer
    private lateinit var db: Db
    private val osm = FakeOsm()

    private lateinit var drafts: DraftService
    private lateinit var merge: MergeService
    private lateinit var account: Account
    private lateinit var repo: DraftRepo

    @BeforeTest
    fun setUp() {
        if (!docker) return
        pg = PostgreSQLContainer("postgres:18").also { it.start() }
        db = Db(pg.jdbcUrl, pg.username, pg.password)
        val config = Config.fromEnv(mapOf("OSM_CLIENT_ID_DEV" to "client", "TOKEN_ENCRYPTION_KEY" to "k"))
        val http = HttpClient(MockEngine { req -> osm.handle(this, req) })
        repo = DraftRepo(db)
        account = Account(config, SettingsRepo(db), http)
        val mirror = GolMirror(Files.createTempDirectory("gol"))
        val rules = RuleEngine(mirror)
        val events = EventBus()
        drafts = DraftService(repo, account, mirror, MirrorQueries(mirror), rules, events)
        merge = MergeService(config, repo, drafts, account, rules, events)
    }

    @AfterTest
    fun tearDown() {
        if (!docker) return
        db.close()
        pg.stop()
    }

    private fun connect(): Unit = runBlocking {
        val state = Regex("state=([^&]+)").find(account.authorizeUrl())!!.groupValues[1]
        account.completeAuth("code", state)
        assertEquals("tester", account.status().displayName)
    }

    @Test
    fun proposeReviewMerge(): Unit = runBlocking {
        if (!docker) return@runBlocking println("Docker not available, skipping")
        connect()
        val d = drafts.createDraft("Test · Opava", "", Authors.CLAUDE)
        val way = OsmRef(OsmType.WAY, 55)
        val node = OsmRef(OsmType.NODE, 101)
        val results = drafts.proposeChanges(
            d.id,
            listOf(
                ProposedChange(way, TagEdit(set = mapOf("surface" to "asphalt")), "connected ways are asphalt"),
                ProposedChange(node, TagEdit(set = mapOf("crossing" to "uncontrolled")), "crossing_ref=zebra"),
                ProposedChange(OsmRef(OsmType.NODE, 102), TagEdit(set = mapOf("highway" to "crossing")), "already tagged"),
                ProposedChange(OsmRef(OsmType.NODE, 999), TagEdit(set = mapOf("a" to "b")), "missing"),
                ProposedChange(way, TagEdit(set = mapOf("lit" to "yes")), "street lamps along it"),
            ),
        )
        assertEquals(
            listOf(ProposalOutcome.ACCEPTED, ProposalOutcome.ACCEPTED, ProposalOutcome.NO_OP, ProposalOutcome.ERROR, ProposalOutcome.DUPLICATE),
            results.map { it.outcome },
        )
        val changes = drafts.detail(d.id).changes
        val wayChange = changes.first { it.ref == way }
        assertEquals(3, wayChange.baseVersion)
        assertEquals(mapOf("highway" to "footway", "lit" to "yes", "surface" to "asphalt"), wayChange.newTags)
        assertEquals("connected ways are asphalt · street lamps along it", wayChange.reason)

        changes.forEach { drafts.review(it.id, ReviewRequest(ReviewDecision.APPROVE)) }
        merge.merge(d.id)

        val after = drafts.detail(d.id)
        assertEquals(DraftStatus.MERGED, after.draft.status)
        assertTrue(after.changes.all { it.status == ChangeStatus.UPLOADED && it.changesetId == 1001L })
        assertEquals(4, osm.version(way))
        assertEquals("asphalt", osm.tags(way)["surface"])
        assertEquals(listOf(1L, 2L, 3L, 1L), osm.nodes(way), "a modify must carry the full node list")
        assertTrue(osm.uploads.single().contains("""changeset="1001""""))
        assertTrue(osm.closed.contains(1001L))
    }

    @Test
    fun autoRebaseThreeWayConflictAnd409(): Unit = runBlocking {
        if (!docker) return@runBlocking println("Docker not available, skipping")
        connect()
        val way = OsmRef(OsmType.WAY, 55)
        val d = drafts.createDraft("Conflicts", "", Authors.USER)
        drafts.proposeChanges(d.id, listOf(ProposedChange(way, TagEdit(set = mapOf("surface" to "asphalt")))))
        val id = drafts.detail(d.id).changes.single().id
        drafts.review(id, ReviewRequest(ReviewDecision.APPROVE))

        osm.edit(way, mapOf("name" to "Pěšina"))
        var c = drafts.refresh(d.id).changes.single()
        assertEquals(ChangeStatus.PENDING, c.status)
        assertEquals(4, c.baseVersion)
        assertEquals("Pěšina", c.newTags["name"])

        osm.edit(way, mapOf("surface" to "gravel"))
        c = drafts.refresh(d.id).changes.single()
        assertEquals(ChangeStatus.CONFLICT, c.status)
        assertEquals(5, c.liveVersion)
        assertEquals("gravel", c.liveTags!!["surface"])
        assertFailsWith<StateException> { drafts.review(id, ReviewRequest(ReviewDecision.APPROVE)) }

        c = drafts.resolveConflict(id, c.liveTags!! + ("surface" to "asphalt"))
        assertEquals(ChangeStatus.PENDING, c.status)
        assertEquals(5, c.baseVersion)
        drafts.review(id, ReviewRequest(ReviewDecision.APPROVE))

        osm.raceOnNextUpload = mapOf("surface" to "concrete")
        merge.merge(d.id)
        c = drafts.detail(d.id).changes.single()
        assertEquals(ChangeStatus.CONFLICT, c.status)
        assertEquals("concrete", c.liveTags!!["surface"])
        assertEquals(DraftStatus.OPEN, drafts.detail(d.id).draft.status)
        assertNotNull(osm.closed.firstOrNull())
    }

    @Test
    fun manualEditOnLiveBaseKeepsUpstreamEdits(): Unit = runBlocking {
        if (!docker) return@runBlocking println("Docker not available, skipping")
        val way = OsmRef(OsmType.WAY, 55)
        val seen = mapOf("highway" to "footway")
        osm.edit(way, mapOf("name" to "Pěšina"))
        val c = drafts.manualEdit(way, seen + ("surface" to "asphalt"), null, baseTags = seen)
        assertEquals(ChangeStatus.PENDING, c.status)
        assertEquals(4, c.baseVersion)
        assertEquals(mapOf("highway" to "footway", "name" to "Pěšina", "surface" to "asphalt"), c.newTags)
    }

    @Test
    fun manualEditOnStaleBaseIsAConflict(): Unit = runBlocking {
        if (!docker) return@runBlocking println("Docker not available, skipping")
        val way = OsmRef(OsmType.WAY, 55)
        val seen = mapOf("highway" to "footway")
        osm.edit(way, mapOf("surface" to "gravel"))
        val c = drafts.manualEdit(way, seen + ("surface" to "asphalt"), null, baseTags = seen)
        assertEquals(ChangeStatus.CONFLICT, c.status)
        assertEquals(seen, c.baseTags)
        assertEquals(seen + ("surface" to "asphalt"), c.newTags)
        assertEquals("gravel", c.liveTags!!["surface"])
        assertEquals(4, c.liveVersion)
        assertFailsWith<StateException> { drafts.review(c.id, ReviewRequest(ReviewDecision.APPROVE)) }
        val resolved = drafts.resolveConflict(c.id, mapOf("highway" to "footway", "surface" to "asphalt"))
        assertEquals(ChangeStatus.PENDING, resolved.status)
        assertEquals(mapOf("surface" to listOf("gravel", "asphalt")), resolved.diff.changed)
    }

    @Test
    fun secondManualEditOnProposedTagsAppliesCleanly(): Unit = runBlocking {
        if (!docker) return@runBlocking println("Docker not available, skipping")
        val way = OsmRef(OsmType.WAY, 55)
        val seen = mapOf("highway" to "footway")
        val first = drafts.manualEdit(way, seen + ("surface" to "asphalt"), null, baseTags = seen)
        assertEquals(ChangeStatus.PENDING, first.status)
        val second = drafts.manualEdit(way, first.newTags + ("surface" to "concrete") + ("lit" to "yes"), null, baseTags = first.newTags)
        assertEquals(first.id, second.id)
        assertEquals(ChangeStatus.PENDING, second.status)
        assertEquals(3, second.baseVersion)
        assertEquals(seen, second.baseTags)
        assertEquals(mapOf("highway" to "footway", "lit" to "yes", "surface" to "concrete"), second.newTags)
        assertEquals(null, second.liveVersion)
    }

    @Test
    fun secondManualEditRemovesKeyAddedByFirst(): Unit = runBlocking {
        if (!docker) return@runBlocking println("Docker not available, skipping")
        val way = OsmRef(OsmType.WAY, 55)
        val seen = mapOf("highway" to "footway")
        val first = drafts.manualEdit(way, seen + ("surface" to "asphalt") + ("lit" to "yes"), null, baseTags = seen)
        val second = drafts.manualEdit(way, first.newTags - "surface", null, baseTags = first.newTags)
        assertEquals(first.id, second.id)
        assertEquals(ChangeStatus.PENDING, second.status)
        assertEquals(mapOf("highway" to "footway", "lit" to "yes"), second.newTags)

        val third = drafts.manualEdit(way, second.newTags - "lit", null, baseTags = second.newTags)
        assertEquals(first.id, third.id)
        assertEquals(ChangeStatus.REJECTED, third.status)
        assertEquals(seen, third.newTags)
        assertEquals(null, repo.openChangeFor(way))
    }

    @Test
    fun secondManualEditStillDetectsLiveConflict(): Unit = runBlocking {
        if (!docker) return@runBlocking println("Docker not available, skipping")
        val way = OsmRef(OsmType.WAY, 55)
        val seen = mapOf("highway" to "footway")
        val first = drafts.manualEdit(way, seen + ("lit" to "yes"), null, baseTags = seen)
        osm.edit(way, mapOf("surface" to "gravel"))
        val stale = drafts.manualEdit(way, seen + ("surface" to "asphalt"), null, baseTags = seen)
        assertEquals(first.id, stale.id)
        assertEquals(ChangeStatus.CONFLICT, stale.status)
        assertEquals("gravel", stale.liveTags!!["surface"])
        assertEquals(4, stale.liveVersion)
    }

    @Test
    fun manualEditWithoutBaseReplacesTags(): Unit = runBlocking {
        if (!docker) return@runBlocking println("Docker not available, skipping")
        val way = OsmRef(OsmType.WAY, 55)
        osm.edit(way, mapOf("name" to "Pěšina"))
        val c = drafts.manualEdit(way, mapOf("highway" to "footway", "surface" to "asphalt"), null)
        assertEquals(mapOf("highway" to "footway", "surface" to "asphalt"), c.newTags)
    }

    @Test
    fun changesetGroupsUseDraftAreaCommentAndSource(): Unit = runBlocking {
        if (!docker) return@runBlocking println("Docker not available, skipping")
        connect()
        val d = drafts.createDraft("Fixes · ignored", "", Authors.CLAUDE, meta = DraftMeta(area = "Opava", changesetSource = "survey"))
        assertEquals("Opava", d.area)
        drafts.proposeChanges(
            d.id,
            listOf(
                ProposedChange(OsmRef(OsmType.NODE, 101), TagEdit(set = mapOf("crossing" to "uncontrolled")), ruleId = "crossing_ref"),
                ProposedChange(OsmRef(OsmType.WAY, 55), TagEdit(set = mapOf("surface" to "asphalt"))),
            ),
        )
        val changes = drafts.detail(d.id).changes
        val nodeChange = changes.first { it.ref.type == OsmType.NODE }
        val wayChange = changes.first { it.ref.type == OsmType.WAY }
        drafts.review(nodeChange.id, ReviewRequest(ReviewDecision.APPROVE, imagery = "Esri World Imagery"))
        drafts.review(wayChange.id, ReviewRequest(ReviewDecision.EDIT, tags = wayChange.newTags + ("lit" to "yes"), imagery = " ČÚZK orthophoto "))
        assertEquals("Esri World Imagery", drafts.detail(d.id).changes.first { it.id == nodeChange.id }.reviews.single().imagery)

        val groups = merge.preview(d.id).groups.associateBy { it.key }
        assertEquals(setOf("crossing_ref", "manual"), groups.keys)
        assertEquals("Opava: crossing_ref (rule crossing_ref), reviewed individually", groups.getValue("crossing_ref").comment)
        assertEquals("Opava: Fixes · ignored, reviewed individually", groups.getValue("manual").comment)
        assertEquals("survey; Esri World Imagery", groups.getValue("crossing_ref").tags["source"])
        assertEquals("survey; ČÚZK orthophoto", groups.getValue("manual").tags["source"])

        merge.merge(d.id)
        assertEquals(2, osm.changesetTags.size)
        assertTrue(osm.changesetTags.any { "survey; Esri World Imagery" in it })

        val custom = drafts.createDraft("Plain", "", Authors.USER, meta = DraftMeta(changesetComment = "Fix crossings after survey"))
        drafts.proposeChanges(custom.id, listOf(ProposedChange(OsmRef(OsmType.NODE, 102), TagEdit(set = mapOf("crossing" to "marked")))))
        val only = drafts.detail(custom.id).changes.single()
        drafts.review(only.id, ReviewRequest(ReviewDecision.APPROVE))
        val g = merge.preview(custom.id).groups.single()
        assertEquals("Fix crossings after survey", g.comment)
        assertEquals("Fix crossings after survey", g.tags["comment"])
        assertTrue("source" !in g.tags)
    }

    @Test
    fun polygonScopedDraftGroupsByRuleAndMetadataIsEditable(): Unit = runBlocking {
        if (!docker) return@runBlocking println("Docker not available, skipping")
        connect()
        val polygon = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"type":"Polygon","coordinates":[[[17.89,49.89],[17.93,49.89],[17.91,49.93],[17.89,49.89]]]}""",
        )
        val d = drafts.createDraft("Crossings · drawn area", "", Authors.rule("crossing_ref"), polygon)
        assertEquals(null, d.area)
        drafts.proposeChanges(
            d.id,
            listOf(
                ProposedChange(OsmRef(OsmType.NODE, 101), TagEdit(set = mapOf("crossing" to "uncontrolled")), ruleId = "crossing_ref"),
                ProposedChange(OsmRef(OsmType.NODE, 102), TagEdit(set = mapOf("crossing" to "marked")), ruleId = "crossing_ref"),
            ),
        )
        drafts.approveRemaining(d.id, null)
        drafts.detail(d.id).changes.filter { it.status == ChangeStatus.PENDING }.forEach {
            drafts.review(it.id, ReviewRequest(ReviewDecision.APPROVE))
        }
        val g = merge.preview(d.id).groups.single()
        assertEquals("crossing_ref", g.key)
        assertEquals(2, g.changeIds.size)
        assertEquals("crossing_ref (rule crossing_ref), reviewed individually", g.comment)
        assertTrue("source" !in g.tags)

        val id = java.util.UUID.fromString(d.id)
        val edited = repo.updateDraft(id, changesetComment = " Crossing tags in Kateřinky ", changesetSource = "survey")!!
        assertEquals("Crossing tags in Kateřinky", edited.changesetComment)
        assertEquals("survey", edited.changesetSource)
        val again = merge.preview(d.id).groups.single()
        assertEquals("Crossing tags in Kateřinky", again.tags["comment"])
        assertEquals("survey", again.tags["source"])

        val cleared = repo.updateDraft(id, changesetComment = "", title = "Renamed")!!
        assertEquals(null, cleared.changesetComment)
        assertEquals("survey", cleared.changesetSource)
        assertEquals("Renamed", cleared.title)
        assertEquals("crossing_ref (rule crossing_ref), reviewed individually", merge.preview(d.id).groups.single().comment)
    }

    @Test
    fun draftStatusAfterMerge(): Unit = runBlocking {
        if (!docker) return@runBlocking println("Docker not available, skipping")
        connect()
        val node = OsmRef(OsmType.NODE, 101)
        val way = OsmRef(OsmType.WAY, 55)

        val d = drafts.createDraft("Partial", "", Authors.USER)
        drafts.proposeChanges(
            d.id,
            listOf(ProposedChange(node, TagEdit(set = mapOf("crossing" to "uncontrolled"))), ProposedChange(way, TagEdit(set = mapOf("surface" to "asphalt")))),
        )
        var changes = drafts.detail(d.id).changes
        drafts.review(changes.first { it.ref == node }.id, ReviewRequest(ReviewDecision.APPROVE))
        merge.merge(d.id)
        assertEquals(DraftStatus.PARTIALLY_MERGED, drafts.detail(d.id).draft.status)

        drafts.review(changes.first { it.ref == way }.id, ReviewRequest(ReviewDecision.REJECT, comment = "not sure"))
        drafts.proposeChanges(d.id, listOf(ProposedChange(OsmRef(OsmType.NODE, 102), TagEdit(set = mapOf("crossing" to "marked")))))
        changes = drafts.detail(d.id).changes
        drafts.review(changes.first { it.ref.id == 102L }.id, ReviewRequest(ReviewDecision.APPROVE))
        merge.merge(d.id)
        val done = drafts.detail(d.id)
        assertEquals(DraftStatus.MERGED, done.draft.status)
        assertEquals(ChangeStatus.REJECTED, done.changes.first { it.ref == way }.status)

        fun change(status: ChangeStatus) = done.changes.first().copy(status = status)
        assertEquals(DraftStatus.OPEN, MergeService.statusAfterMerge(listOf(change(ChangeStatus.APPROVED)), DraftStatus.OPEN))
        assertEquals(DraftStatus.OPEN, MergeService.statusAfterMerge(listOf(change(ChangeStatus.REJECTED)), DraftStatus.OPEN))
        assertEquals(
            DraftStatus.PARTIALLY_MERGED,
            MergeService.statusAfterMerge(listOf(change(ChangeStatus.UPLOADED), change(ChangeStatus.CONFLICT)), DraftStatus.OPEN),
        )
        assertEquals(
            DraftStatus.MERGED,
            MergeService.statusAfterMerge(listOf(change(ChangeStatus.UPLOADED), change(ChangeStatus.REJECTED)), DraftStatus.PARTIALLY_MERGED),
        )
    }
}
