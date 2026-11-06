package eu.tiducto.osmreview.server.replication

import eu.tiducto.osmreview.shared.OsmType
import kotlin.test.Test
import kotlin.test.assertEquals

class DiffClipperTest {
    private val xml = """
        <osmChange version="0.6">
          <create>
            <node id="-1" version="1" lat="49.93" lon="17.90" user="someone" uid="1"/>
            <node id="1" version="1" lat="49.93" lon="17.91"/>
            <node id="2" version="1" lat="52.50" lon="13.40"/>
            <node id="3" version="1" lat="52.51" lon="13.41"/>
            <node id="4" version="1" lat="40.00" lon="0.00"/>
          </create>
          <modify>
            <node id="10" version="5" lat="48.00" lon="2.00"><tag k="amenity" v="bench"/></node>
            <node id="11" version="2" lat="45.00" lon="9.00"/>
            <way id="20" version="1"><nd ref="1"/><nd ref="4"/></way>
            <way id="21" version="3"><nd ref="2"/><nd ref="3"/></way>
            <way id="22" version="3"><nd ref="11"/><nd ref="3"/></way>
            <way id="23" version="7"><nd ref="99"/><nd ref="98"/></way>
            <relation id="30" version="1"><member type="relation" ref="31" role=""/></relation>
            <relation id="31" version="1"><member type="way" ref="20" role=""/></relation>
            <relation id="32" version="1"><member type="way" ref="21" role=""/></relation>
            <relation id="33" version="1"><member type="node" ref="50" role=""/></relation>
          </modify>
          <delete>
            <node id="12" version="3" lat="52.0" lon="13.0"/>
            <node id="13" version="3" lat="49.5" lon="15.0"/>
          </delete>
        </osmChange>
    """.trimIndent()

    private val inArea = { lon: Double, lat: Double -> lon in 12.0..19.0 && lat in 48.5..51.1 }

    @Test
    fun keepsChangesTouchingTheMirrorOrTheArea() {
        val elements = OscDiff.parse(xml.toByteArray())
        val inGol = setOf(OsmType.NODE to 11L, OsmType.WAY to 23L, OsmType.NODE to 50L)
        val tracked = setOf(OsmType.NODE to 10L)
        var looked = 0
        val lookup = IdLookup { n, w, r ->
            looked = n.size + w.size + r.size
            { t, id -> (t to id) in inGol }
        }
        val clipper = DiffClipper(inArea, { t, id -> (t to id) in tracked }, lookup)
        val r = clipper.clip(listOf(elements))
        val kept = r.diffs.single().map { "${it.type.apiName[0]}${it.id}" }
        assertEquals(listOf("n-1", "n1", "n3", "n4", "n10", "n11", "w20", "w22", "w23", "r30", "r31", "r33", "n13"), kept)
        assertEquals(elements.size - kept.size, r.dropped)
        assertEquals(kept.size, r.kept)
        assert(looked > 0)
    }

    @Test
    fun roundTripsWithoutUserNames() {
        val elements = OscDiff.parse(xml.toByteArray())
        val again = OscDiff.parseGz(OscDiff.writeGz(elements, "test"))
        assertEquals(elements.map { it.action to it.id }, again.map { it.action to it.id })
        assertEquals(listOf(1L, 4L), again.first { it.id == 20L }.nodeRefs)
        assertEquals("bench", again.first { it.id == 10L }.children.single().attr("v"))
        val text = OscDiff.write(elements, "test").decodeToString()
        assert("user=" !in text && "uid=" !in text)
        assert("<create>" in text && "<delete>" in text)
    }

    @Test
    fun emptyDiffIsValidOsmChange() {
        val text = OscDiff.write(emptyList(), "test").decodeToString()
        assert(text.contains("<osmChange"))
        assertEquals(0, OscDiff.parse(text.toByteArray()).size)
    }
}
