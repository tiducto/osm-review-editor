package eu.tiducto.osmreview.rules

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SidewalkSideTest {
    private val gf = GeometryFactory()
    private fun line(vararg xy: Double) = gf.createLineString(xy.toList().chunked(2).map { Coordinate(it[0], it[1]) }.toTypedArray())

    private val road = line(0.0, 0.0, 100.0, 0.0)

    @Test
    fun northOfEastboundIsLeft() = assertEquals(Side.LEFT, SidewalkSide.side(road, line(0.0, 8.0, 100.0, 8.0)))

    @Test
    fun southOfEastboundIsRight() = assertEquals(Side.RIGHT, SidewalkSide.side(road, line(0.0, -8.0, 100.0, -8.0)))

    @Test
    fun sidewalkDrawnTheOtherWayStillCounts() = assertEquals(Side.LEFT, SidewalkSide.side(road, line(100.0, 8.0, 0.0, 8.0)))

    @Test
    fun perpendicularConnectorIsIgnored() = assertNull(SidewalkSide.side(road, line(50.0, 2.0, 50.0, 30.0)))
}
