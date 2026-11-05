package eu.tiducto.osmreview.server.tiles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TileBuilderTest {
    private val z = 16
    private val tx = 36025
    private val ty = 22243
    private val b = TileBounds(z, tx, ty, TileContent.extent(z))

    private fun mx(f: Double) = b.minX + b.span * f
    private fun my(f: Double) = b.maxY - b.span * f
    private fun pt(fx: Double, fy: Double) = doubleArrayOf(mx(fx), my(fy))
    private fun xy(vararg p: Pair<Double, Double>) = p.flatMap { listOf(mx(it.first), my(it.second)) }.toDoubleArray()

    private fun build(z: Int = this.z, vararg f: SourceFeature) =
        MvtDecoder.decode(TileBuilder().build(z, tx shr (this.z - z), ty shr (this.z - z), f.asSequence()).encode())

    @Test
    fun `encodes layers, ids, tags and meta properties`() {
        val road = SourceFeature(SourceType.WAY, 10, mapOf("highway" to "residential", "name" to "A"), SourceGeometry.Line(xy(0.1 to 0.5, 0.9 to 0.5)))
        val shop = SourceFeature(SourceType.NODE, 7, mapOf("shop" to "bakery"), SourceGeometry.Point(mx(0.3), my(0.3)))
        val tile = build(f = arrayOf(road, shop))
        val line = tile.getValue(Layers.LINE).features.single()
        assertEquals(10L * 4 + 1, line.id)
        assertEquals(2, line.type)
        assertEquals("residential", line.props["highway"])
        assertEquals(10L, line.props[Meta.ID])
        assertEquals("way", line.props[Meta.TYPE])
        assertEquals(8192, tile.getValue(Layers.LINE).extent)
        val p = tile.getValue(Layers.POINT).features.single()
        assertEquals("bakery", p.props["shop"])
        assertEquals(listOf(2458 to 2458), p.rings().single())
    }

    @Test
    fun `junction and endpoint vertices come from way membership`() {
        val a = SourceFeature(SourceType.WAY, 1, mapOf("highway" to "service"), SourceGeometry.Line(xy(0.1 to 0.5, 0.5 to 0.5, 0.9 to 0.5)))
        val c = SourceFeature(SourceType.WAY, 2, mapOf("highway" to "footway"), SourceGeometry.Line(xy(0.5 to 0.5, 0.5 to 0.9)))
        val crossing = SourceFeature(SourceType.NODE, 99, mapOf("highway" to "crossing"), SourceGeometry.Point(mx(0.5), my(0.9)))
        val tile = build(f = arrayOf(a, c, crossing))
        val vertices = tile.getValue(Layers.VERTEX).features
        val junction = vertices.single { it.props[Meta.JUNCTION] == true }
        assertNull(junction.id)
        assertEquals(listOf(4096 to 4096), junction.rings().single())
        val tagged = vertices.single { it.props["highway"] == "crossing" }
        assertEquals(99L * 4, tagged.id)
        assertEquals(false, tagged.props[Meta.JUNCTION])
        assertEquals(true, tagged.props[Meta.END])
        assertEquals(2, vertices.count { it.props[Meta.END] == true && it.id == null })
        assertNull(tile[Layers.POINT])
    }

    @Test
    fun `nodes shared only by areas are not junction vertices, like iD`() {
        val left = SourceFeature(SourceType.WAY, 1, mapOf("building" to "yes"), SourceGeometry.Area(listOf(listOf(xy(0.2 to 0.2, 0.5 to 0.2, 0.5 to 0.5, 0.2 to 0.5, 0.2 to 0.2)))))
        val right = SourceFeature(SourceType.WAY, 2, mapOf("building" to "yes"), SourceGeometry.Area(listOf(listOf(xy(0.5 to 0.2, 0.8 to 0.2, 0.8 to 0.5, 0.5 to 0.5, 0.5 to 0.2)))))
        val entrance = SourceFeature(SourceType.NODE, 3, mapOf("entrance" to "yes"), SourceGeometry.Point(mx(0.5), my(0.5)))
        val vertices = build(f = arrayOf(left, right, entrance)).getValue(Layers.VERTEX).features
        val tagged = vertices.single()
        assertEquals(3L * 4, tagged.id)
        assertEquals(true, tagged.props[Meta.BUILDING_ONLY])
        val path = SourceFeature(SourceType.WAY, 4, mapOf("highway" to "footway"), SourceGeometry.Line(xy(0.5 to 0.2, 0.5 to 0.05)))
        val withPath = build(f = arrayOf(left, right, path)).getValue(Layers.VERTEX).features
        val junction = withPath.single { it.props[Meta.JUNCTION] == true }
        assertEquals(false, junction.props[Meta.BUILDING_ONLY])
    }

    @Test
    fun `no vertices below zoom 15 and way nodes are not shown as points`() {
        val a = SourceFeature(SourceType.WAY, 1, mapOf("highway" to "primary"), SourceGeometry.Line(xy(0.1 to 0.5, 0.9 to 0.5)))
        val stop = SourceFeature(SourceType.NODE, 5, mapOf("highway" to "bus_stop"), SourceGeometry.Point(mx(0.1), my(0.5)))
        val tile = build(14, a, stop)
        assertNull(tile[Layers.VERTEX])
        assertNull(tile[Layers.POINT])
        assertNotNull(tile[Layers.LINE])
    }

    @Test
    fun `curated tags below zoom 15`() {
        val a = SourceFeature(SourceType.WAY, 1, mapOf("highway" to "primary", "maxspeed" to "50", "name" to "X"), SourceGeometry.Line(xy(0.1 to 0.5, 0.9 to 0.5)))
        val props = build(13, a).getValue(Layers.LINE).features.single().props
        assertEquals("primary", props["highway"])
        assertEquals("X", props["name"])
        assertFalse("maxspeed" in props)
        val full = build(15, a).getValue(Layers.LINE).features.single().props
        assertEquals("50", full["maxspeed"])
    }

    @Test
    fun `extra keys below zoom 15 for highlight filters`() {
        val a = SourceFeature(SourceType.WAY, 1, mapOf("highway" to "primary", "sidewalk" to "both", "maxspeed" to "50"), SourceGeometry.Line(xy(0.1 to 0.5, 0.9 to 0.5)))
        val keys = TileContent.extraKeys("sidewalk,highway,geodesk:x", 13)
        assertEquals(setOf("sidewalk"), keys)
        val props = MvtDecoder.decode(TileBuilder().build(13, tx shr (z - 13), ty shr (z - 13), sequenceOf(a), keys).encode())
            .getValue(Layers.LINE).features.single().props
        assertEquals("both", props["sidewalk"])
        assertFalse("maxspeed" in props)
        assertEquals(emptySet(), TileContent.extraKeys("sidewalk", 15))
    }

    @Test
    fun `closed lines carry the closed flag`() {
        val ring = SourceFeature(SourceType.WAY, 2, mapOf("highway" to "service"), SourceGeometry.Line(xy(0.2 to 0.2, 0.6 to 0.2, 0.6 to 0.6, 0.2 to 0.2)))
        val open = SourceFeature(SourceType.WAY, 3, mapOf("highway" to "service"), SourceGeometry.Line(xy(0.1 to 0.8, 0.9 to 0.8)))
        val lines = build(f = arrayOf(ring, open)).getValue(Layers.LINE).features.associateBy { it.props["@id"] }
        assertEquals(true, lines.getValue(2L).props["@closed"])
        assertFalse("@closed" in lines.getValue(3L).props)
    }

    @Test
    fun `lines are clipped to the tile buffer`() {
        val a = SourceFeature(SourceType.WAY, 1, mapOf("highway" to "primary"), SourceGeometry.Line(xy(-1.0 to 0.5, 2.0 to 0.5)))
        val ring = build(f = arrayOf(a)).getValue(Layers.LINE).features.single().rings().single()
        assertEquals(2, ring.size)
        assertEquals(-256, ring[0].first)
        assertEquals(8192 + 256, ring[1].first)
    }

    @Test
    fun `small areas are filled whole, large areas get inset bands per zoom`() {
        val small = SourceFeature(
            SourceType.WAY, 3, mapOf("building" to "yes"),
            SourceGeometry.Area(listOf(listOf(xy(0.40 to 0.40, 0.42 to 0.40, 0.42 to 0.42, 0.40 to 0.42, 0.40 to 0.40)))),
        )
        val big = SourceFeature(
            SourceType.WAY, 4, mapOf("landuse" to "grass", "name" to "Park"),
            SourceGeometry.Area(listOf(listOf(xy(0.1 to 0.1, 0.9 to 0.1, 0.9 to 0.9, 0.1 to 0.9, 0.1 to 0.1)))),
        )
        val tile = build(f = arrayOf(small, big))
        val areas = tile.getValue(Layers.AREA).features
        val smallFz = (areas.single { it.id == 3L * 4 + 1 }.props[Meta.FULL_ZOOM] as Long).toInt()
        val bigFz = (areas.single { it.id == 4L * 4 + 1 }.props[Meta.FULL_ZOOM] as Long).toInt()
        assertTrue(smallFz in 16..20, "small fz $smallFz")
        assertTrue(bigFz < 16, "big fz $bigFz")
        val bands = tile.getValue(Layers.BAND).features
        assertEquals((16..20).toList(), bands.filter { it.props[Meta.ID] == 4L }.map { (it.props[Meta.BAND_ZOOM] as Long).toInt() })
        assertEquals((smallFz + 1..20).toList(), bands.filter { it.props[Meta.ID] == 3L }.map { (it.props[Meta.BAND_ZOOM] as Long).toInt() })
        val band16 = bands.single { it.props[Meta.ID] == 4L && it.props[Meta.BAND_ZOOM] == 16L }.rings()
        assertEquals(2, band16.size)
        val hole = band16[1]
        assertEquals(819 + 480, hole.minOf { it.first })
        val label = tile.getValue(Layers.LABEL).features.single()
        assertEquals("Park", label.props["name"])
        assertEquals(listOf(4096 to 4096), label.rings().single())
    }

    @Test
    fun `polygon rings follow the MVT winding order`() {
        val ccw = SourceFeature(
            SourceType.WAY, 4, mapOf("landuse" to "grass"),
            SourceGeometry.Area(listOf(listOf(xy(0.1 to 0.1, 0.1 to 0.9, 0.9 to 0.9, 0.9 to 0.1, 0.1 to 0.1)))),
        )
        val ring = build(f = arrayOf(ccw)).getValue(Layers.AREA).features.single().rings().single()
        val a = ring.indices.sumOf { i ->
            val (x1, y1) = ring[i]
            val (x2, y2) = ring[(i + 1) % ring.size]
            x1.toLong() * y2 - x2.toLong() * y1
        }
        assertTrue(a > 0)
    }

    @Test
    fun `untagged multipolygon member ways are not drawn as lines`() {
        val member = SourceFeature(SourceType.WAY, 8, emptyMap(), SourceGeometry.Line(xy(0.1 to 0.5, 0.9 to 0.5)), inRelation = true)
        val loose = SourceFeature(SourceType.WAY, 9, emptyMap(), SourceGeometry.Line(xy(0.1 to 0.6, 0.9 to 0.6)))
        val lines = build(f = arrayOf(member, loose)).getValue(Layers.LINE).features
        assertEquals(listOf(9L * 4 + 1), lines.map { it.id })
    }

    @Test
    fun `clip splits a line that leaves and re-enters`() {
        val parts = Clip.line(doubleArrayOf(10.0, 10.0, 200.0, 10.0, 200.0, 50.0, 10.0, 50.0), ClipBox(0.0, 0.0, 100.0, 100.0))
        assertEquals(2, parts.size)
        assertEquals(listOf(10.0, 10.0, 100.0, 10.0), parts[0].toList())
        assertEquals(listOf(100.0, 50.0, 10.0, 50.0), parts[1].toList())
    }

    @Test
    fun `douglas peucker drops collinear points`() {
        val s = Simplify.douglasPeucker(doubleArrayOf(0.0, 0.0, 5.0, 0.1, 10.0, 0.0), 0.5, false)
        assertEquals(listOf(0.0, 0.0, 10.0, 0.0), s.toList())
    }
}
