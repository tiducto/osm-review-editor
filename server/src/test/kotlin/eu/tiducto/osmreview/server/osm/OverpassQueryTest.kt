package eu.tiducto.osmreview.server.osm

import eu.tiducto.osmreview.shared.BBox
import kotlin.test.Test
import kotlin.test.assertEquals

class OverpassQueryTest {
    private val box = BBox(17.88, 49.93, 17.92, 49.95)

    @Test
    fun addsSettingsBboxAndOutput() {
        assertEquals(
            "[out:json][timeout:60][bbox:49.93,17.88,49.95,17.92];node[highway=crossing]; out tags center 501;",
            Overpass.buildQuery("node[highway=crossing];", 500, box),
        )
    }

    @Test
    fun mergesExistingSettingsAndReplacesTheirBbox() {
        assertEquals(
            "[out:json][timeout:25][maxsize:1000000][bbox:49.93,17.88,49.95,17.92];way[highway];out tags;",
            Overpass.buildQuery("[out:xml][timeout:25] [bbox:0,0,1,1][maxsize:1000000];way[highway];out tags;", 500, box),
        )
    }

    @Test
    fun withoutBboxKeepsQuery() {
        assertEquals("[out:json][timeout:60];way(1);out;", Overpass.buildQuery("way(1);out;", 10))
    }
}
