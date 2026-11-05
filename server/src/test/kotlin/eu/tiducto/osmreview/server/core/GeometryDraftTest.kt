package eu.tiducto.osmreview.server.core

import eu.tiducto.osmreview.rules.GolMirror
import eu.tiducto.osmreview.rules.MirrorQueries
import eu.tiducto.osmreview.rules.RuleEngine
import eu.tiducto.osmreview.server.Config
import eu.tiducto.osmreview.server.db.Db
import eu.tiducto.osmreview.server.db.DraftRepo
import eu.tiducto.osmreview.server.db.SettingsRepo
import eu.tiducto.osmreview.server.osm.OsmChangeXml
import eu.tiducto.osmreview.server.osm.XMember
import eu.tiducto.osmreview.shared.Authors
import eu.tiducto.osmreview.shared.Change
import eu.tiducto.osmreview.shared.ChangeAction
import eu.tiducto.osmreview.shared.ChangeStatus
import eu.tiducto.osmreview.shared.DraftDetail
import eu.tiducto.osmreview.shared.DraftFromOsmChangeRequest
import eu.tiducto.osmreview.shared.DraftStatus
import eu.tiducto.osmreview.shared.LngLat
import eu.tiducto.osmreview.shared.OsmChangeImportRequest
import eu.tiducto.osmreview.shared.OsmRef
import eu.tiducto.osmreview.shared.OsmType
import eu.tiducto.osmreview.shared.ProposalOutcome
import eu.tiducto.osmreview.shared.ProposedChange
import eu.tiducto.osmreview.shared.RelationMember
import eu.tiducto.osmreview.shared.ReviewDecision
import eu.tiducto.osmreview.shared.ReviewRequest
import eu.tiducto.osmreview.shared.TagEdit
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeometryDraftTest {
    private val docker = runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
    private lateinit var pg: PostgreSQLContainer
    private lateinit var db: Db
    private val osm = FakeOsm()

    private lateinit var drafts: DraftService
    private lateinit var merge: MergeService
    private lateinit var account: Account
    private lateinit var repo: DraftRepo
    private lateinit var events: EventBus

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
        events = EventBus()
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
    }

    private fun osmChange(create: String = "", modify: String = "", delete: String = "") =
        """<?xml version="1.0" encoding="UTF-8"?><osmChange version="0.6" generator="iD">""" +
            (if (create.isEmpty()) "<create/>" else "<create>$create</create>") +
            (if (modify.isEmpty()) "<modify/>" else "<modify>$modify</modify>") +
            (if (delete.isEmpty()) """<delete if-unused="true"/>""" else """<delete if-unused="true">$delete</delete>""") +
            "</osmChange>"

    private suspend fun import(xml: String, reasons: Map<String, String> = emptyMap(), replace: Boolean = false, draftId: String? = null): Pair<DraftDetail, Map<OsmRef, Change>> {
        val r = if (draftId == null) {
            drafts.draftFromOsmChange(DraftFromOsmChangeRequest(title = "Editor session", osmChange = xml, reasons = reasons), Authors.USER)
        } else {
            drafts.importOsmChange(draftId, OsmChangeImportRequest(xml, reasons, replace = replace))
        }
        assertTrue(r.results.none { it.outcome == ProposalOutcome.ERROR }, r.results.toString())
        val bySource = r.results.filter { it.changeId != null }.associate { (it.sourceRef ?: it.ref) to r.draft.changes.first { c -> c.id == it.changeId } }
        return r.draft to bySource
    }

    private fun n(id: Long) = OsmRef(OsmType.NODE, id)
    private fun w(id: Long) = OsmRef(OsmType.WAY, id)
    private fun r(id: Long) = OsmRef(OsmType.RELATION, id)

    private suspend fun reload(c: Change) = repo.change(java.util.UUID.fromString(c.id))!!

    @Test
    fun newWayWithNewNodes(): Unit = runBlocking {
        if (!docker) return@runBlocking println("Docker not available, skipping")
        connect()
        val (d, ch) = import(
            osmChange(
                create = """<node id="-1" lon="17.95" lat="49.95" version="0" changeset="0"/>""" +
                    """<node id="-2" lon="17.951" lat="49.951" version="0"><tag k="geodesk:x" v="1"/></node>""" +
                    """<way id="-1" version="0"><nd ref="-1"/><nd ref="-2"/><nd ref="1"/><tag k="highway" v="footway"/><tag k="geodesk:area" v="no"/></way>""",
            ),
            reasons = mapOf("w-1" to "path visible on imagery"),
        )
        assertEquals(3, d.changes.size)
        val way = ch.getValue(w(-1))
        val n1 = ch.getValue(n(-1))
        val n2 = ch.getValue(n(-2))
        assertTrue(n1.ref.id < 0 && n2.ref.id < 0 && n1.ref != n2.ref)
        assertEquals(listOf(n1.ref.id, n2.ref.id, 1L), way.newShape!!.nodes)
        assertEquals(mapOf("highway" to "footway"), way.newTags)
        assertEquals(emptyMap(), n2.newTags)
        assertEquals("path visible on imagery", way.reason)
        assertEquals("new way, 3 nodes", way.geometrySummary)
        assertEquals(setOf(n1.id, n2.id), way.dependsOn.map { it.changeId }.toSet())
        assertEquals(listOf(way.id), n1.requiredBy.map { it.changeId })
        assertEquals("LineString", (way.newGeom as JsonObject)["type"]!!.jsonPrimitive.content)
        assertEquals(3, (way.newGeom as JsonObject)["coordinates"]!!.jsonArray.size)
        assertEquals(LngLat(17.95, 49.95), n1.newShape!!.location)

        drafts.review(way.id, ReviewRequest(ReviewDecision.APPROVE, cascade = true))
        val approved = drafts.detail(d.draft.id).changes
        assertTrue(approved.all { it.status == ChangeStatus.APPROVED }, approved.map { it.status }.toString())

        val preview = merge.preview(d.draft.id)
        assertEquals(1, preview.groups.size)
        assertTrue(preview.blocked.isEmpty())
        merge.merge(d.draft.id)
        val xml = osm.uploads.single()
        assertTrue(xml.indexOf("<node id=\"${n1.ref.id}\"") in 0 until xml.indexOf("<way id=\"${way.ref.id}\""), xml)
        assertFalse("geodesk:" in xml, xml)

        val after = drafts.detail(d.draft.id)
        assertEquals(DraftStatus.MERGED, after.draft.status)
        val upWay = after.changes.first { it.id == way.id }
        val upN1 = after.changes.first { it.id == n1.id }
        assertEquals(way.ref.id, upWay.placeholderId)
        assertTrue(upWay.ref.id > 0)
        assertEquals(listOf(upN1.ref.id, after.changes.first { it.id == n2.id }.ref.id, 1L), upWay.newShape!!.nodes)
        assertEquals(upWay.newShape!!.nodes, osm.nodes(upWay.ref))
    }

    @Test
    fun splitWayWithRelationUpdate(): Unit = runBlocking {
        if (!docker) return@runBlocking println("Docker not available, skipping")
        connect()
        val (d, ch) = import(
            osmChange(
                create = """<way id="-1" version="0"><nd ref="12"/><nd ref="13"/><nd ref="14"/><tag k="highway" v="residential"/><tag k="name" v="Dlouhá"/></way>""",
                modify = """<way id="60" version="5"><nd ref="11"/><nd ref="12"/><tag k="highway" v="residential"/><tag k="name" v="Dlouhá"/></way>""" +
                    """<relation id="8" version="1"><member type="way" ref="60" role=""/><member type="way" ref="-1" role=""/><tag k="type" v="route"/><tag k="route" v="bus"/></relation>""",
            ),
        )
        val old = ch.getValue(w(60))
        val new = ch.getValue(w(-1))
        val rel = ch.getValue(r(8))
        assertEquals("nodes 4 → 2 (+0 −2)", old.geometrySummary)
        assertTrue(old.geometryChanged)
        assertEquals(listOf(11L, 12L, 13L, 14L), old.baseShape!!.nodes)
        assertEquals(4, (old.baseGeom as JsonObject)["coordinates"]!!.jsonArray.size)
        assertEquals(2, (old.newGeom as JsonObject)["coordinates"]!!.jsonArray.size)
        assertEquals(old.baseTags, old.newTags)
        assertEquals(listOf(new.id), rel.dependsOn.map { it.changeId })
        assertTrue(old.dependsOn.isEmpty())
        assertEquals("members 1 → 2 (+1 −0)", rel.geometrySummary)

        d.changes.forEach { drafts.review(it.id, ReviewRequest(ReviewDecision.APPROVE)) }
        merge.merge(d.draft.id)
        val newId = drafts.detail(d.draft.id).changes.first { it.id == new.id }.ref.id
        assertEquals(listOf(11L, 12L), osm.nodes(w(60)))
        assertEquals(listOf(12L, 13L, 14L), osm.nodes(w(newId)))
        assertEquals(listOf(XMember("way", 60, ""), XMember("way", newId, "")), osm.members(r(8)))
        assertEquals(listOf(RelationMember(OsmType.WAY, 60), RelationMember(OsmType.WAY, newId)), reload(rel).newShape!!.members)
    }

    @Test
    fun deleteNodeIfUnusedAfterUnlinking(): Unit = runBlocking {
        if (!docker) return@runBlocking println("Docker not available, skipping")
        connect()
        val (d, ch) = import(
            osmChange(
                modify = """<way id="60" version="5"><nd ref="11"/><nd ref="12"/><nd ref="13"/><tag k="highway" v="residential"/><tag k="name" v="Dlouhá"/></way>""",
                delete = """<node id="14" version="1" lat="49.933" lon="17.936"/>""",
            ),
        )
        val del = ch.getValue(n(14))
        val way = ch.getValue(w(60))
        assertEquals(ChangeAction.DELETE, del.action)
        assertTrue(del.ifUnused)
        assertEquals("deleted if unused", del.geometrySummary)
        assertEquals(listOf(way.id), del.dependsOn.map { it.changeId })
        assertEquals(mapOf("barrier" to "gate"), del.baseTags)

        d.changes.forEach { drafts.review(it.id, ReviewRequest(ReviewDecision.APPROVE)) }
        merge.merge(d.draft.id)
        val xml = osm.uploads.single()
        assertTrue("""<delete if-unused="true"><node id="14" version="1"""" in xml, xml)
        assertTrue(xml.indexOf("<modify>") < xml.indexOf("<delete"), xml)
        assertFalse(osm.visible(n(14)))
        assertEquals(listOf(11L, 12L, 13L), osm.nodes(w(60)))
        assertEquals(DraftStatus.MERGED, drafts.detail(d.draft.id).draft.status)
    }

    @Test
    fun moveNodeAndGeometryConflict(): Unit = runBlocking {
        if (!docker) return@runBlocking println("Docker not available, skipping")
        connect()
        val (d, ch) = import(osmChange(modify = """<node id="21" version="3" lat="49.9401" lon="17.940"><tag k="amenity" v="bench"/></node>"""))
        val move = ch.getValue(n(21))
        assertTrue(move.geometryChanged)
        assertEquals("moved 11 m", move.geometrySummary)
        assertEquals(LngLat(17.94, 49.9401), move.newShape!!.location)
        assertEquals(LngLat(17.94, 49.94), move.baseShape!!.location)
        assertEquals("Point", (move.newGeom as JsonObject)["type"]!!.jsonPrimitive.content)
        assertTrue(move.diff.isNoOp)
        drafts.review(move.id, ReviewRequest(ReviewDecision.APPROVE))

        osm.edit(n(21), mapOf("backrest" to "yes"))
        var c = drafts.refresh(d.draft.id).changes.single()
        assertEquals(ChangeStatus.PENDING, c.status, "a tag-only upstream edit rebases a move")
        assertEquals(4, c.baseVersion)
        assertEquals("yes", c.newTags["backrest"])
        assertEquals(LngLat(17.94, 49.9401), c.newShape!!.location)

        osm.move(n(21), "49.9405", "17.9405")
        c = drafts.refresh(d.draft.id).changes.single()
        assertEquals(ChangeStatus.CONFLICT, c.status)
        assertEquals(5, c.liveVersion)
        assertEquals(LngLat(17.9405, 49.9405), c.liveShape!!.location)
        assertNotNull(c.liveGeom)
        assertFailsWith<StateException> { drafts.review(c.id, ReviewRequest(ReviewDecision.APPROVE)) }

        c = drafts.resolveConflict(c.id, c.newTags)
        assertEquals(ChangeStatus.PENDING, c.status)
        assertEquals(5, c.baseVersion)
        assertEquals(LngLat(17.9405, 49.9405), c.baseShape!!.location)
        assertEquals(LngLat(17.94, 49.9401), c.newShape!!.location)
        assertNull(c.liveShape)

        drafts.review(c.id, ReviewRequest(ReviewDecision.APPROVE))
        osm.move(n(21), "49.95", "17.95")
        merge.merge(d.draft.id)
        assertEquals(ChangeStatus.CONFLICT, drafts.detail(d.draft.id).changes.single().status)
        assertTrue(osm.uploads.isEmpty())
    }

    @Test
    fun relationMemberAddDependsOnNewNode(): Unit = runBlocking {
        if (!docker) return@runBlocking println("Docker not available, skipping")
        connect()
        val (d, ch) = import(
            osmChange(
                create = """<node id="-5" lat="49.935" lon="17.935"><tag k="public_transport" v="platform"/></node>""",
                modify = """<relation id="8" version="1"><member type="way" ref="60" role=""/><member type="node" ref="-5" role="platform"/><tag k="type" v="route"/><tag k="route" v="bus"/></relation>""",
            ),
        )
        val stop = ch.getValue(n(-5))
        val rel = ch.getValue(r(8))
        assertEquals(listOf(stop.id), rel.dependsOn.map { it.changeId })
        assertEquals(listOf(RelationMember(OsmType.WAY, 60), RelationMember(OsmType.NODE, stop.ref.id, "platform")), rel.newShape!!.members)
        assertEquals("members 1 → 2 (+1 −0)", rel.geometrySummary)

        drafts.review(rel.id, ReviewRequest(ReviewDecision.APPROVE))
        val preview = merge.preview(d.draft.id)
        assertEquals(listOf(rel.id), preview.blocked.map { it.changeId })
        assertTrue(preview.groups.isEmpty())

        drafts.review(stop.id, ReviewRequest(ReviewDecision.APPROVE))
        merge.merge(d.draft.id)
        val stopId = drafts.detail(d.draft.id).changes.first { it.id == stop.id }.ref.id
        assertEquals(XMember("node", stopId, "platform"), osm.members(r(8)).last())
    }

    @Test
    fun rejectCascadesToDependents(): Unit = runBlocking {
        if (!docker) return@runBlocking println("Docker not available, skipping")
        val (d, ch) = import(
            osmChange(
                create = """<node id="-1" lon="17.95" lat="49.95"/><node id="-2" lon="17.951" lat="49.951"/>""" +
                    """<way id="-1"><nd ref="-1"/><nd ref="-2"/><tag k="highway" v="footway"/></way>""",
            ),
        )
        val way = ch.getValue(w(-1))
        val node = ch.getValue(n(-1))
        d.changes.forEach { drafts.review(it.id, ReviewRequest(ReviewDecision.APPROVE)) }

        val blocked = assertFailsWith<StateException> { drafts.review(node.id, ReviewRequest(ReviewDecision.REJECT, comment = "no")) }
        assertTrue("cascade" in blocked.message!!, blocked.message)
        assertEquals(ChangeStatus.APPROVED, reload(node).status)

        drafts.review(node.id, ReviewRequest(ReviewDecision.REJECT, comment = "no", cascade = true))
        assertEquals(ChangeStatus.REJECTED, reload(node).status)
        val w = reload(way)
        assertEquals(ChangeStatus.REJECTED, w.status)
        assertTrue("depends on" in w.reviews.last().comment!!)
        assertEquals(ChangeStatus.APPROVED, reload(ch.getValue(n(-2))).status)

        drafts.review(way.id, ReviewRequest(ReviewDecision.RESET))
        assertFailsWith<StateException> { drafts.review(way.id, ReviewRequest(ReviewDecision.APPROVE)) }
        drafts.review(node.id, ReviewRequest(ReviewDecision.RESET))
        drafts.review(way.id, ReviewRequest(ReviewDecision.APPROVE, cascade = true))
        assertEquals(ChangeStatus.APPROVED, reload(node).status)
    }

    @Test
    fun uploadRemapsIdsAcrossChangesetGroupsAndMerges(): Unit = runBlocking {
        if (!docker) return@runBlocking println("Docker not available, skipping")
        connect()
        val (d, ch) = import(
            osmChange(
                create = """<node id="-1" lat="49.9315" lon="17.933"><tag k="traffic_calming" v="bump"/></node>""" +
                    """<way id="-2"><nd ref="-1"/><nd ref="21"/><tag k="highway" v="footway"/></way>""",
                modify = """<way id="60" version="5"><nd ref="11"/><nd ref="12"/><nd ref="-1"/><nd ref="13"/><nd ref="14"/><tag k="highway" v="residential"/><tag k="name" v="Dlouhá"/></way>""",
            ),
        )
        drafts.proposeChanges(d.draft.id, listOf(ProposedChange(n(101), TagEdit(set = mapOf("crossing" to "uncontrolled")), ruleId = "crossing_ref")))
        val bump = ch.getValue(n(-1))
        val street = ch.getValue(w(60))
        val path = ch.getValue(w(-2))
        val rule = drafts.detail(d.draft.id).changes.first { it.ref == n(101) }
        listOf(bump, street, rule).forEach { drafts.review(it.id, ReviewRequest(ReviewDecision.APPROVE)) }

        val groups = merge.preview(d.draft.id).groups
        assertEquals(2, groups.size)
        assertEquals(setOf(bump.id, street.id), groups.first { it.key == "manual" }.changeIds.toSet())
        merge.merge(d.draft.id)
        assertEquals(2, osm.uploads.size)
        assertEquals(listOf(1001L, 1002L), osm.closed.sorted())

        val bumpId = reload(bump).ref.id
        assertEquals(bump.ref.id, reload(bump).placeholderId)
        assertEquals(listOf(11L, 12L, bumpId, 13L, 14L), osm.nodes(w(60)))
        val waiting = reload(path)
        assertEquals(listOf(bumpId, 21L), waiting.newShape!!.nodes)
        assertEquals(ChangeStatus.UPLOADED, waiting.dependsOn.single().status)
        assertEquals(DraftStatus.PARTIALLY_MERGED, drafts.detail(d.draft.id).draft.status)

        drafts.review(path.id, ReviewRequest(ReviewDecision.APPROVE))
        merge.merge(d.draft.id)
        val third = osm.uploads.last()
        assertTrue("""<nd ref="$bumpId"/>""" in third, third)
        assertFalse("ref=\"-" in third, third)
        assertEquals(listOf(bumpId, 21L), osm.nodes(w(reload(path).ref.id)))
        assertEquals(DraftStatus.MERGED, drafts.detail(d.draft.id).draft.status)
    }

    @Test
    fun osmChangeRoundTrip(): Unit = runBlocking {
        if (!docker) return@runBlocking println("Docker not available, skipping")
        val (d, ch) = import(
            osmChange(
                create = """<node id="-1" lat="49.95" lon="17.95"/><node id="-2" lat="49.951" lon="17.951"/>""" +
                    """<way id="-1"><nd ref="-1"/><nd ref="-2"/><nd ref="21"/><tag k="highway" v="path"/></way>""",
                modify = """<node id="21" version="3" lat="49.9402" lon="17.9401"><tag k="amenity" v="bench"/></node>""" +
                    """<way id="60" version="5"><nd ref="11"/><nd ref="12"/><nd ref="13"/><tag k="highway" v="residential"/><tag k="name" v="Dlouhá"/></way>""",
                delete = """<node id="14" version="1" lat="49.933" lon="17.936"/>""",
            ),
        )
        drafts.proposeChanges(d.draft.id, listOf(ProposedChange(n(101), TagEdit(set = mapOf("crossing" to "uncontrolled")))))
        val exported = drafts.exportOsmChange(d.draft.id)
        val entries = OsmChangeXml.parse(exported)
        assertEquals(7, entries.size)
        assertEquals(listOf(ChangeAction.CREATE, ChangeAction.CREATE, ChangeAction.CREATE), entries.take(3).map { it.action })
        assertEquals(listOf(OsmType.NODE, OsmType.NODE, OsmType.WAY), entries.take(3).map { it.element.type })
        val way = entries.first { it.element.type == OsmType.WAY && it.action == ChangeAction.CREATE }.element
        assertEquals(listOf(ch.getValue(n(-1)).ref.id, ch.getValue(n(-2)).ref.id, 21L), way.nodeIds)
        val moved = entries.first { it.element.id == 21L }.element
        assertEquals("49.9402", moved.lat)
        assertEquals(3, moved.version)
        val crossing = entries.first { it.element.id == 101L }.element
        assertEquals("uncontrolled", crossing.tags["crossing"])
        assertEquals(4, crossing.version)
        val del = entries.single { it.action == ChangeAction.DELETE }
        assertTrue(del.ifUnused)
        assertTrue("""<delete if-unused="true">""" in exported, exported)

        val again = drafts.importOsmChange(d.draft.id, OsmChangeImportRequest(exported, replace = true))
        assertTrue(again.results.all { it.outcome == ProposalOutcome.NO_OP }, again.results.toString())
        assertEquals(7, again.draft.changes.size)
        assertEquals(exported, drafts.exportOsmChange(d.draft.id))

        val dropped = exported.replace(Regex("""<delete if-unused="true">.*</delete>"""), "")
        val after = drafts.importOsmChange(d.draft.id, OsmChangeImportRequest(dropped, replace = true))
        assertEquals(ChangeStatus.REJECTED, after.draft.changes.first { it.ref == n(14) }.status)
    }

    @Test
    fun takeOverMovesOpenChangesFromAnotherDraft(): Unit = runBlocking {
        if (!docker) return@runBlocking println("Docker not available, skipping")
        connect()
        val (first, ch) = import(
            osmChange(
                modify = """<way id="60" version="5"><nd ref="11"/><nd ref="12"/><nd ref="14"/><tag k="highway" v="residential"/><tag k="name" v="Dlouhá"/></way>""" +
                    """<node id="21" version="3" lat="49.9401" lon="17.94"><tag k="amenity" v="bench"/></node>""",
                delete = """<node id="13" version="2" lat="49.932" lon="17.934"/>""",
            ),
        )
        val oldWay = ch.getValue(w(60))
        val oldDelete = ch.getValue(n(13))
        val oldBench = ch.getValue(n(21))
        assertEquals(listOf(oldWay.id), oldDelete.dependsOn.map { it.changeId })
        val second = drafts.createDraft("Second session", "", Authors.USER)
        val move = osmChange(modify = """<way id="60" version="5"><nd ref="11"/><nd ref="12"/><nd ref="13"/><tag k="highway" v="residential"/><tag k="name" v="Dlouhá"/></way>""")

        val refused = assertFailsWith<OpenChangeElsewhereException> { drafts.importOsmChange(second.id, OsmChangeImportRequest(move)) }
        assertEquals(listOf(w(60)), refused.openChanges.map { it.ref })
        assertEquals(first.draft.id, refused.openChanges.single().draftId)
        assertEquals("Editor session", refused.openChanges.single().draftTitle)
        assertEquals(oldWay.id, refused.openChanges.single().changeId)
        assertTrue(first.draft.id in refused.message!!, refused.message)
        assertTrue("takeOver" in refused.message!!, refused.message)
        assertTrue(drafts.detail(second.id).changes.isEmpty())
        assertEquals(ChangeStatus.PENDING, reload(oldWay).status)

        val seen = java.util.concurrent.CopyOnWriteArrayList<eu.tiducto.osmreview.shared.UiEvent>()
        val collector = launch(kotlinx.coroutines.Dispatchers.Unconfined) { events.events.collect { seen += it } }
        val r = drafts.importOsmChange(second.id, OsmChangeImportRequest(move, takeOver = true))
        collector.cancel()
        val moved = r.draft.changes.single()
        assertEquals(w(60), moved.ref)
        assertEquals(ChangeStatus.PENDING, moved.status)
        assertEquals(listOf(11L, 12L, 13L), moved.newShape!!.nodes)

        val taken = reload(oldWay)
        assertEquals(ChangeStatus.REJECTED, taken.status)
        assertEquals("moved to draft \"Second session\" (${second.id})", taken.reviews.last().comment)
        assertEquals(ChangeStatus.REJECTED, reload(oldDelete).status)
        assertTrue("depends on w60" in reload(oldDelete).reviews.last().comment.orEmpty())
        assertEquals(ChangeStatus.PENDING, reload(oldBench).status)
        assertTrue(eu.tiducto.osmreview.shared.UiEvent.DraftUpdated(first.draft.id) in seen, seen.toString())

        val fromNew = drafts.draftFromOsmChange(
            DraftFromOsmChangeRequest(
                title = "Third",
                osmChange = osmChange(modify = """<node id="21" version="3" lat="49.9402" lon="17.94"><tag k="amenity" v="bench"/></node>"""),
                takeOver = true,
            ),
            Authors.USER,
        )
        assertEquals(n(21), fromNew.draft.changes.single().ref)
        assertEquals(ChangeStatus.REJECTED, reload(oldBench).status)
        assertFailsWith<OpenChangeElsewhereException> {
            drafts.draftFromOsmChange(DraftFromOsmChangeRequest(title = "Fourth", osmChange = move), Authors.USER)
        }
        assertTrue(repo.listDrafts().none { it.title == "Fourth" })
    }

    @Test
    fun invalidReferencesAreRejected(): Unit = runBlocking {
        if (!docker) return@runBlocking println("Docker not available, skipping")
        val e = assertFailsWith<IllegalArgumentException> {
            drafts.draftFromOsmChange(
                DraftFromOsmChangeRequest(
                    title = "bad",
                    osmChange = osmChange(create = """<way id="-1"><nd ref="-9"/><nd ref="1"/></way>"""),
                ),
                Authors.USER,
            )
        }
        assertTrue("n-9" in e.message!!, e.message)
        assertTrue(repo.listDrafts().isEmpty())

        val (d, _) = import(osmChange(create = """<node id="-1" lat="49.95" lon="17.95"><tag k="amenity" v="bench"/></node>"""))
        val conflictDelete = assertFailsWith<IllegalArgumentException> {
            drafts.importOsmChange(
                d.draft.id,
                OsmChangeImportRequest(osmChange(create = """<way id="-3"><nd ref="1"/><nd ref="2"/></way>""", delete = """<node id="2" version="1"/>""")),
            )
        }
        assertTrue("n2" in conflictDelete.message!!, conflictDelete.message)
        assertFailsWith<IllegalArgumentException> {
            drafts.importOsmChange(d.draft.id, OsmChangeImportRequest(osmChange(modify = """<node id="999" version="1" lat="1" lon="1"/>""")))
        }
        assertEquals(1, drafts.detail(d.draft.id).changes.size)
    }
}
