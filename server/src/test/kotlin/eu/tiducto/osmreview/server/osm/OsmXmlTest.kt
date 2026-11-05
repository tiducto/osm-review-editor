package eu.tiducto.osmreview.server.osm

import eu.tiducto.osmreview.server.OsmTargetConfig
import eu.tiducto.osmreview.shared.OsmRef
import eu.tiducto.osmreview.shared.OsmTarget
import eu.tiducto.osmreview.shared.OsmType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OsmXmlTest {
    private val sample = """
        <?xml version="1.0" encoding="UTF-8"?>
        <osm version="0.6" generator="openstreetmap-cgimap 2.0.1" copyright="OpenStreetMap and contributors">
         <node id="101" visible="true" version="4" changeset="9" timestamp="2024-01-01T00:00:00Z" user="a" uid="1" lat="49.9381102" lon="17.9025917">
          <tag k="highway" v="crossing"/>
          <tag k="crossing_ref" v="zebra"/>
         </node>
         <node id="102" visible="false" version="7" changeset="10" timestamp="2024-01-01T00:00:00Z" user="a" uid="1"/>
         <way id="55" visible="true" version="3" changeset="9" timestamp="2024-01-01T00:00:00Z" user="a" uid="1">
          <nd ref="1"/><nd ref="2"/><nd ref="3"/><nd ref="1"/>
          <tag k="public_transport" v="platform"/>
         </way>
         <relation id="7" visible="true" version="12" changeset="9" timestamp="2024-01-01T00:00:00Z" user="a" uid="1">
          <member type="node" ref="101" role="stop"/>
          <member type="way" ref="55" role="platform"/>
          <tag k="type" v="route"/>
         </relation>
        </osm>
    """.trimIndent()

    @Test
    fun parsesNodesWaysRelations() {
        val els = OsmXml.parseElements(sample).associateBy { it.ref }
        val n = els.getValue(OsmRef(OsmType.NODE, 101))
        assertEquals(4, n.version)
        assertEquals("49.9381102", n.lat)
        assertEquals(mapOf("crossing_ref" to "zebra", "highway" to "crossing"), n.tags)
        assertFalse(els.getValue(OsmRef(OsmType.NODE, 102)).visible)
        assertEquals(listOf(1L, 2L, 3L, 1L), els.getValue(OsmRef(OsmType.WAY, 55)).nodeIds)
        assertEquals("platform", els.getValue(OsmRef(OsmType.RELATION, 7)).members[1].role)
    }

    @Test
    fun buildsOsmChangeWithFullElements() {
        val way = OsmXml.parseElements(sample).first { it.type == OsmType.WAY }
        val modified = way.copy(tags = way.tags + ("area" to "yes"))
        val xml = OsmXml.osmChange(
            XOsmChange(generator = "test", modify = XBlock(ways = listOf(OsmXml.toXml(modified, 42) as XWay))),
        ).replace(" />", "/>")
        assertTrue(xml.startsWith("<?xml version='1.0'"), xml)
        assertTrue("<modify>" in xml, xml)
        assertTrue("""<way id="55" version="3" changeset="42">""" in xml, xml)
        assertTrue("""<nd ref="3"/>""" in xml, xml)
        assertTrue("""<tag k="area" v="yes"/>""" in xml, xml)
        assertFalse("<create" in xml, xml)
    }

    @Test
    fun parsesDiffResultAndUser() {
        val diff = OsmXml.parseDiff(
            """<diffResult version="0.6" generator="x"><node old_id="-1" new_id="900" new_version="1"/><way old_id="55" new_id="55" new_version="4"/></diffResult>""",
        )
        assertEquals(900L, diff.nodes.single().newId)
        assertEquals(4, diff.ways.single().newVersion)
        val user = OsmXml.parseUser(
            """<osm version="0.6"><user id="5" display_name="mapper" account_created="2020-01-01T00:00:00Z"><description/><contributor-terms agreed="true"/><roles/><changesets count="3"/></user></osm>""",
        )
        assertEquals("mapper", user.displayName)
    }

    @Test
    fun changesetCreateBody() {
        val xml = OsmXml.changesetCreate(mapOf("comment" to "Opava: add area=yes", "created_by" to "OSM Review Editor 0.1.0"))
            .replace(" />", "/>")
        assertTrue("<osm><changeset><tag k=\"comment\" v=\"Opava: add area=yes\"/>" in xml, xml)
    }

    @Test
    fun fetchSplitsBatchOn404() = runTest {
        var calls = 0
        val engine = MockEngine { req ->
            calls++
            val ids = req.url.parameters["nodes"]!!.split(",")
            if ("999" in ids) {
                respond("", HttpStatusCode.NotFound)
            } else {
                respond(
                    "<osm>" + ids.joinToString("") { """<node id="$it" version="1" lat="1" lon="2"/>""" } + "</osm>",
                    HttpStatusCode.OK,
                )
            }
        }
        val api = OsmApi(
            HttpClient(engine),
            OsmTargetConfig(OsmTarget.DEV, "https://dev", "https://dev/api/0.6", null, null),
            "test",
        )
        val got = api.fetch(listOf(1L, 2L, 999L, 3L).map { OsmRef(OsmType.NODE, it) })
        assertEquals(setOf(1L, 2L, 3L), got.keys.map { it.id }.toSet())
        assertTrue(calls in 3..6, "calls=$calls")
    }
}
