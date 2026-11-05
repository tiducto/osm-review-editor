package eu.tiducto.osmreview.server.core

import eu.tiducto.osmreview.rules.GolMirror
import eu.tiducto.osmreview.rules.MirrorQueries
import eu.tiducto.osmreview.rules.RuleEngine
import eu.tiducto.osmreview.server.Config
import eu.tiducto.osmreview.server.db.Db
import eu.tiducto.osmreview.server.db.DraftRepo
import eu.tiducto.osmreview.server.db.SettingsRepo
import eu.tiducto.osmreview.server.osm.OsmXml
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
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
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

    @BeforeTest
    fun setUp() {
        if (!docker) return
        pg = PostgreSQLContainer("postgres:18").also { it.start() }
        db = Db(pg.jdbcUrl, pg.username, pg.password)
        val config = Config.fromEnv(mapOf("OSM_CLIENT_ID_DEV" to "client", "TOKEN_ENCRYPTION_KEY" to "k"))
        val http = HttpClient(MockEngine { req -> osm.handle(this, req) })
        val repo = DraftRepo(db)
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

    private class FakeOsm {
        private data class El(val type: String, val id: Long, var version: Int, var tags: Map<String, String>, val extra: String)

        private val els = mutableMapOf(
            "way/55" to El("way", 55, 3, mapOf("highway" to "footway"), """<nd ref="1"/><nd ref="2"/><nd ref="3"/><nd ref="1"/>"""),
            "node/101" to El("node", 101, 4, mapOf("highway" to "crossing", "crossing_ref" to "zebra"), ""),
            "node/102" to El("node", 102, 1, mapOf("highway" to "crossing"), ""),
        )
        val uploads = mutableListOf<String>()
        val closed = mutableListOf<Long>()
        var raceOnNextUpload: Map<String, String>? = null
        private var nextChangeset = 1001L

        fun version(r: OsmRef) = els.getValue("${r.type.apiName}/${r.id}").version
        fun tags(r: OsmRef) = els.getValue("${r.type.apiName}/${r.id}").tags
        fun nodes(r: OsmRef) = OsmXml.parseElements(xml(listOf(els.getValue("${r.type.apiName}/${r.id}")))).single().nodeIds

        fun edit(r: OsmRef, set: Map<String, String>) {
            val e = els.getValue("${r.type.apiName}/${r.id}")
            e.tags = e.tags + set
            e.version++
        }

        private fun xml(list: List<El>) = "<osm version=\"0.6\">" + list.joinToString("") { e ->
            val coords = if (e.type == "node") """ lat="49.9" lon="17.9"""" else ""
            """<${e.type} id="${e.id}" version="${e.version}" visible="true"$coords>${e.extra}""" +
                e.tags.entries.joinToString("") { """<tag k="${it.key}" v="${it.value}"/>""" } + "</${e.type}>"
        } + "</osm>"

        fun handle(scope: MockRequestHandleScope, req: HttpRequestData): HttpResponseData = with(scope) {
            val path = req.url.encodedPath
            val body = (req.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
            when {
                path.endsWith("/oauth2/token") -> respond("""{"access_token":"tok","token_type":"Bearer"}""", headers = headersOf("Content-Type", "application/json"))
                path.endsWith("/user/details") -> respond("""<osm><user id="1" display_name="tester"/></osm>""")
                path.endsWith("/changeset/create") -> respond((nextChangeset++).toString())
                path.endsWith("/close") -> {
                    closed += path.split("/").dropLast(1).last().toLong()
                    respond("")
                }
                path.endsWith("/upload") -> upload(body)
                req.method == HttpMethod.Get -> {
                    val type = path.substringAfterLast("/").removeSuffix("s")
                    val ids = req.url.parameters["${type}s"]!!.split(",")
                    val found = ids.mapNotNull { els["$type/$it"] }
                    if (found.size < ids.size) respond("", HttpStatusCode.NotFound) else respond(xml(found))
                }
                else -> respond("unexpected $path", HttpStatusCode.BadRequest)
            }
        }

        private fun MockRequestHandleScope.upload(body: String): HttpResponseData {
            uploads += body
            val sent = OsmXml.parseElements(body.replace("osmChange", "osm").replace(Regex("</?(modify|create|delete)[^>]*>"), ""))
            raceOnNextUpload?.let { race ->
                raceOnNextUpload = null
                sent.forEach { edit(it.ref, race) }
                return respond("Version mismatch", HttpStatusCode.Conflict)
            }
            sent.forEach { s ->
                val e = els.getValue("${s.type.apiName}/${s.id}")
                if (e.version != s.version) return respond("Version mismatch", HttpStatusCode.Conflict)
            }
            val diff = sent.joinToString("") { s ->
                val e = els.getValue("${s.type.apiName}/${s.id}")
                e.tags = s.tags
                e.version++
                """<${e.type} old_id="${e.id}" new_id="${e.id}" new_version="${e.version}"/>"""
            }
            return respond("<diffResult>$diff</diffResult>")
        }
    }
}
