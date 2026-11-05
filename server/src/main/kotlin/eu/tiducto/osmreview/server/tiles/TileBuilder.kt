package eu.tiducto.osmreview.server.tiles

import org.locationtech.jts.algorithm.construct.MaximumInscribedCircle
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LinearRing
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.operation.buffer.BufferOp
import org.locationtech.jts.operation.buffer.BufferParameters
import org.locationtech.jts.operation.overlayng.OverlayNG
import org.locationtech.jts.operation.overlayng.OverlayNGRobust
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

enum class SourceType(val code: Int, val label: String) { NODE(0, "node"), WAY(1, "way"), RELATION(2, "relation") }

sealed interface SourceGeometry {
    class Point(val x: Double, val y: Double) : SourceGeometry
    class Line(val xy: DoubleArray) : SourceGeometry
    class Area(val polygons: List<List<DoubleArray>>) : SourceGeometry
}

class SourceFeature(
    val type: SourceType,
    val id: Long,
    val tags: Map<String, String>,
    val geometry: SourceGeometry,
    val inRelation: Boolean = false,
)

object Layers {
    const val AREA = "area"
    const val BAND = "band"
    const val LABEL = "label"
    const val LINE = "line"
    const val POINT = "point"
    const val VERTEX = "vertex"
}

object Meta {
    const val ID = "@id"
    const val TYPE = "@type"
    const val FULL_ZOOM = "@fz"
    const val BAND_ZOOM = "@bz"
    const val AREA = "@area"
    const val WIDTH = "@width"
    const val JUNCTION = "@junction"
    const val END = "@end"
    const val BUILDING_ONLY = "@bonly"
    const val CLOSED = "@closed"
}

class TileBuilder {
    private val gf = GeometryFactory()

    fun build(z: Int, x: Int, y: Int, features: Sequence<SourceFeature>, extraKeys: Set<String> = emptySet()): MvtTile {
        val extent = TileContent.extent(z)
        val bounds = TileBounds(z, x, y, extent)
        val buffer = TileContent.bufferPx(z) * extent / TileContent.TILE_PX
        val clip = ClipBox.around(extent, buffer)
        val tolerance = if (z >= TileContent.DETAIL_ZOOM) 0.0 else 0.5
        val minArea = TileContent.minAreaPx(z) * (extent / TileContent.TILE_PX) * (extent / TileContent.TILE_PX)
        val tile = MvtTile()
        val all = ArrayList<SourceFeature>()
        val seen = HashSet<Long>()
        val wayNodes = WayNodeIndex()
        for (f in features) {
            if (!seen.add(f.id * 4 + f.type.code)) continue
            all += f
            if (f.type == SourceType.WAY) {
                when (val g = f.geometry) {
                    is SourceGeometry.Line -> wayNodes.addWay(g.xy, closedIsArea = false, building = isBuilding(f.tags), taggedLine = f.tags.isNotEmpty())
                    is SourceGeometry.Area -> g.polygons.firstOrNull()?.firstOrNull()?.let { wayNodes.addWay(it, closedIsArea = true, building = isBuilding(f.tags)) }
                    else -> {}
                }
            }
        }
        val vertices = TileContent.includeVertices(z)
        val emitted = HashSet<Long>()
        val geom = GeometryWriter()
        for (f in all) {
            val props = properties(f, z, extraKeys)
            val fid = f.id * 4 + f.type.code
            when (val g = f.geometry) {
                is SourceGeometry.Point -> {
                    val key = WayNodeIndex.key(g.x, g.y)
                    val parents = wayNodes.parents(key)
                    val tx = bounds.tx(g.x)
                    val ty = bounds.ty(g.y)
                    if (!bounds.contains(tx, ty)) continue
                    if (parents > 0) {
                        if (!vertices) continue
                        emitted += key
                        props[Meta.JUNCTION] = parents > 1
                        props[Meta.END] = wayNodes.isEnd(key)
                        props[Meta.BUILDING_ONLY] = wayNodes.buildingOnly(key)
                        point(tile.layer(Layers.VERTEX, extent), geom, fid, tx, ty, props)
                    } else {
                        point(tile.layer(Layers.POINT, extent), geom, fid, tx, ty, props)
                    }
                }
                is SourceGeometry.Line -> {
                    if (f.type == SourceType.WAY && f.inRelation && f.tags.isEmpty()) continue
                    if (isClosed(g.xy)) props[Meta.CLOSED] = true
                    val parts = Clip.line(bounds.project(g.xy), clip)
                    geom.reset()
                    for (p in parts) {
                        val s = Simplify.douglasPeucker(p, tolerance, false)
                        val (q, n) = Quantize.points(s, false)
                        geom.line(q, n)
                    }
                    tile.layer(Layers.LINE, extent).add(fid, GeomType.LINESTRING, geom.data, geom.size, props)
                }
                is SourceGeometry.Area -> area(tile, bounds, clip, tolerance, minArea, geom, f, fid, g, props, z)
            }
        }
        if (vertices) {
            val layer = tile.layer(Layers.VERTEX, extent)
            wayNodes.forEachVertex { key, parents, end ->
                if (key in emitted) return@forEachVertex
                val props = linkedMapOf<String, Any>(Meta.JUNCTION to (parents > 1), Meta.END to end, Meta.BUILDING_ONLY to wayNodes.buildingOnly(key))
                val tx = bounds.tx(WayNodeIndex.x(key).toDouble())
                val ty = bounds.ty(WayNodeIndex.y(key).toDouble())
                if (!bounds.contains(tx, ty)) return@forEachVertex
                point(layer, geom, null, tx, ty, props)
            }
        }
        return tile
    }

    private fun isClosed(xy: DoubleArray): Boolean =
        xy.size >= 8 && xy[0] == xy[xy.size - 2] && xy[1] == xy[xy.size - 1]

    private fun properties(f: SourceFeature, z: Int, extraKeys: Set<String>): LinkedHashMap<String, Any> {
        val out = LinkedHashMap<String, Any>()
        val all = TileContent.allTags(z)
        for ((k, v) in f.tags) if (all || k in TileContent.CURATED_KEYS || k in extraKeys) out[k] = v
        out[Meta.ID] = f.id
        out[Meta.TYPE] = f.type.label
        return out
    }

    private fun point(layer: MvtLayer, geom: GeometryWriter, id: Long?, tx: Double, ty: Double, props: Map<String, Any>) {
        geom.reset()
        geom.points(intArrayOf(Math.round(tx).toInt(), Math.round(ty).toInt()), 1)
        layer.add(id, GeomType.POINT, geom.data, geom.size, props)
    }

    private fun area(
        tile: MvtTile,
        bounds: TileBounds,
        clip: ClipBox,
        tolerance: Double,
        minArea: Double,
        geom: GeometryWriter,
        f: SourceFeature,
        fid: Long,
        g: SourceGeometry.Area,
        props: LinkedHashMap<String, Any>,
        z: Int,
    ) {
        val extent = bounds.extent
        var area = 0.0
        var perimeter = 0.0
        var largest: List<DoubleArray>? = null
        var largestArea = -1.0
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        for (poly in g.polygons) {
            var pa = 0.0
            poly.forEachIndexed { i, ring ->
                val a = abs(ringArea(ring))
                if (i == 0) pa += a else pa -= a
                perimeter += ringLength(ring)
                if (i == 0) {
                    var k = 0
                    while (k < ring.size) {
                        if (ring[k] < minX) minX = ring[k]
                        if (ring[k] > maxX) maxX = ring[k]
                        k += 2
                    }
                }
            }
            area += pa
            if (pa > largestArea) {
                largestArea = pa
                largest = poly
            }
        }
        if (bounds.tileUnits(sqrt(max(area, 0.0))).let { it * it } < minArea) return
        val pxPerUnit0 = 512.0 / MAP_WIDTH
        val tilePxPerUnit0 = TileContent.TILE_PX / MAP_WIDTH
        val r = if (perimeter > 0) 2 * area / perimeter else 0.0
        val fullZoom = if (r <= 0) 30 else floor(ln(TileContent.BAND_PX / (r * tilePxPerUnit0)) / ln(2.0)).toInt().coerceIn(-1, 30)
        props[Meta.FULL_ZOOM] = fullZoom.toLong()

        val projected = g.polygons.map { poly -> poly.map { bounds.project(it) } }
        val clipped = projected.map { poly -> poly.map { Clip.ring(it, clip) } }.filter { it.firstOrNull()?.size ?: 0 >= 6 }
        if (clipped.isEmpty()) return
        geom.reset()
        writePolygons(geom, clipped, tolerance)
        if (geom.size == 0) return
        tile.layer(Layers.AREA, extent).add(fid, GeomType.POLYGON, geom.data, geom.size, props)

        largest?.let { lp ->
            val c = labelPoint(lp)
            val tx = bounds.tx(c.x)
            val ty = bounds.ty(c.y)
            if (bounds.contains(tx, ty) && wantsLabel(f.tags)) {
                val lprops = LinkedHashMap(props)
                lprops.remove(Meta.FULL_ZOOM)
                lprops[Meta.AREA] = area * pxPerUnit0 * pxPerUnit0
                lprops[Meta.WIDTH] = (maxX - minX) * pxPerUnit0
                point(tile.layer(Layers.LABEL, extent), geom, fid, tx, ty, lprops)
            }
        }

        val maxBand = if (z >= TileContent.DETAIL_ZOOM) TileContent.MAX_BAND_ZOOM else z
        val from = max(z, fullZoom + 1)
        if (from > maxBand) return
        val bandLayer = tile.layer(Layers.BAND, extent)
        val bandProps = linkedMapOf<String, Any>(Meta.ID to f.id, Meta.TYPE to f.type.label)
        for (bz in from..maxBand) {
            val w = TileContent.BAND_PX * extent / (TileContent.TILE_PX * (1L shl (bz - z)))
            val outer = ClipBox.around(extent, w + clip.maxX - extent + 4)
            val source = projected.map { poly -> poly.map { Clip.ring(it, outer) } }.filter { it.firstOrNull()?.size ?: 0 >= 6 }
            if (source.isEmpty()) continue
            val band = band(source, w) ?: continue
            val polys = band.map { poly -> poly.map { Clip.ring(it, clip) } }.filter { it.firstOrNull()?.size ?: 0 >= 6 }
            if (polys.isEmpty()) continue
            geom.reset()
            writePolygons(geom, polys, tolerance)
            if (geom.size == 0) continue
            bandProps[Meta.BAND_ZOOM] = bz.toLong()
            bandLayer.add(fid, GeomType.POLYGON, geom.data, geom.size, bandProps)
        }
    }

    private fun isBuilding(tags: Map<String, String>): Boolean =
        tags["building"].let { it != null && it != "no" } || tags["building:part"].let { it != null && it != "no" }

    private fun wantsLabel(tags: Map<String, String>): Boolean =
        "name" in tags || tags.keys.any { it in TileContent.LABEL_KEYS }

    private fun band(polys: List<List<DoubleArray>>, w: Double): List<List<DoubleArray>>? {
        val shapes = polys.mapNotNull { toJts(it) }
        if (shapes.isEmpty()) return null
        val source: Geometry = if (shapes.size == 1) shapes[0] else gf.createMultiPolygon(shapes.toTypedArray())
        val params = BufferParameters(2, BufferParameters.CAP_ROUND, BufferParameters.JOIN_MITRE, 3.0)
        val inset = try {
            BufferOp.bufferOp(source, -w, params)
        } catch (_: Exception) {
            return polys
        }
        if (inset.isEmpty) return polys
        val simple = shapes.size == 1 && shapes[0].numInteriorRing == 0
        val result: Geometry = if (simple) {
            val holes = (0 until inset.numGeometries).map { (inset.getGeometryN(it) as Polygon).exteriorRing as LinearRing }
            val extra = (0 until inset.numGeometries).flatMap { i ->
                val p = inset.getGeometryN(i) as Polygon
                (0 until p.numInteriorRing).map { gf.createPolygon(p.getInteriorRingN(it).coordinates) }
            }
            val ring = gf.createPolygon(shapes[0].exteriorRing as LinearRing, holes.toTypedArray())
            if (extra.isEmpty()) ring else gf.createMultiPolygon((listOf(ring) + extra).toTypedArray())
        } else {
            try {
                OverlayNGRobust.overlay(source, inset, OverlayNG.DIFFERENCE)
            } catch (_: Exception) {
                return polys
            }
        }
        return fromJts(result)
    }

    private fun toJts(rings: List<DoubleArray>): Polygon? {
        val lr = rings.mapNotNull { r ->
            val n = r.size / 2
            if (n < 3) return@mapNotNull null
            val cs = ArrayList<Coordinate>(n + 1)
            for (i in 0 until n) cs += Coordinate(r[2 * i], r[2 * i + 1])
            if (cs.first() != cs.last()) cs += Coordinate(cs.first())
            if (cs.size < 4) null else gf.createLinearRing(cs.toTypedArray())
        }
        if (lr.isEmpty()) return null
        val p = gf.createPolygon(lr[0], lr.drop(1).toTypedArray())
        return if (p.isValid) p else p.buffer(0.0).let { it as? Polygon ?: (if (it.numGeometries > 0) it.getGeometryN(0) as? Polygon else null) }
    }

    private fun fromJts(g: Geometry): List<List<DoubleArray>> = (0 until g.numGeometries).mapNotNull { i ->
        val p = g.getGeometryN(i) as? Polygon ?: return@mapNotNull null
        if (p.isEmpty) return@mapNotNull null
        listOf(coords(p.exteriorRing.coordinates)) + (0 until p.numInteriorRing).map { coords(p.getInteriorRingN(it).coordinates) }
    }

    private fun coords(cs: Array<Coordinate>): DoubleArray {
        val out = DoubleArray(cs.size * 2)
        cs.forEachIndexed { i, c ->
            out[2 * i] = c.x
            out[2 * i + 1] = c.y
        }
        return out
    }

    private fun writePolygons(geom: GeometryWriter, polys: List<List<DoubleArray>>, tolerance: Double) {
        for (poly in polys) {
            var outerOk = false
            poly.forEachIndexed { i, ring ->
                if (i > 0 && !outerOk) return@forEachIndexed
                val s = Simplify.douglasPeucker(ring, tolerance, true)
                val (q, n) = Quantize.points(s, true)
                if (Rings.isDegenerate(q, n)) {
                    if (i == 0) outerOk = false
                    return@forEachIndexed
                }
                val positive = Rings.signedArea(q, n) > 0
                if ((i == 0) != positive) Rings.reverse(q, n)
                geom.ring(q, n)
                if (i == 0) outerOk = true
            }
        }
    }

    private fun ringArea(xy: DoubleArray): Double {
        val n = xy.size / 2
        var a = 0.0
        for (i in 0 until n) {
            val j = if (i + 1 == n) 0 else i + 1
            a += xy[2 * i] * xy[2 * j + 1] - xy[2 * j] * xy[2 * i + 1]
        }
        return a / 2
    }

    private fun ringLength(xy: DoubleArray): Double {
        val n = xy.size / 2
        var l = 0.0
        for (i in 0 until n - 1) {
            val dx = xy[2 * i + 2] - xy[2 * i]
            val dy = xy[2 * i + 3] - xy[2 * i + 1]
            l += sqrt(dx * dx + dy * dy)
        }
        return l
    }

    private fun labelPoint(poly: List<DoubleArray>): Coordinate {
        val ring = poly[0]
        val n = ring.size / 2
        var a = 0.0
        var cx = 0.0
        var cy = 0.0
        val ox = ring[0]
        val oy = ring[1]
        for (i in 0 until n) {
            val j = if (i + 1 == n) 0 else i + 1
            val x1 = ring[2 * i] - ox
            val y1 = ring[2 * i + 1] - oy
            val x2 = ring[2 * j] - ox
            val y2 = ring[2 * j + 1] - oy
            val cr = x1 * y2 - x2 * y1
            a += cr
            cx += (x1 + x2) * cr
            cy += (y1 + y2) * cr
        }
        if (a != 0.0) {
            val c = Coordinate(cx / (3 * a) + ox, cy / (3 * a) + oy)
            if (poly.withIndex().all { (i, r) -> inRing(r, c.x, c.y) == (i == 0) }) return c
        }
        val p = toJts(poly) ?: return Coordinate(ox, oy)
        return try {
            MaximumInscribedCircle.getCenter(p, max(p.envelopeInternal.width, p.envelopeInternal.height) / 100).coordinate
        } catch (_: Exception) {
            p.interiorPoint.coordinate
        }
    }

    private fun inRing(xy: DoubleArray, x: Double, y: Double): Boolean {
        val n = xy.size / 2
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = xy[2 * i]
            val yi = xy[2 * i + 1]
            val xj = xy[2 * j]
            val yj = xy[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }
}

class WayNodeIndex {
    private val counts = LongIntMap()
    private val ends = LongIntMap()
    private val other = LongIntMap()
    private val lines = LongIntMap()

    fun addWay(xy: DoubleArray, closedIsArea: Boolean, building: Boolean = false, taggedLine: Boolean = false) {
        val n = xy.size / 2
        if (n == 0) return
        val closed = n > 1 && xy[0] == xy[2 * n - 2] && xy[1] == xy[2 * n - 1]
        val last = if (closed) n - 1 else n
        var prev = Long.MIN_VALUE
        for (i in 0 until last) {
            val k = key(xy[2 * i], xy[2 * i + 1])
            if (k == prev) continue
            prev = k
            counts.increment(k)
            if (!building) other.increment(k)
            if (taggedLine) lines.increment(k)
        }
        if (!closed && !closedIsArea) {
            ends.increment(key(xy[0], xy[1]))
            ends.increment(key(xy[2 * n - 2], xy[2 * n - 1]))
        }
    }

    fun parents(key: Long): Int = counts.get(key)

    fun buildingOnly(key: Long): Boolean = counts.get(key) > 0 && other.get(key) == 0

    fun isEnd(key: Long): Boolean = ends.get(key) > 0 && counts.get(key) <= 1

    fun forEachVertex(block: (Long, Int, Boolean) -> Unit) {
        counts.forEach { k, c ->
            val end = ends.get(k) > 0
            if ((c > 1 && lines.get(k) > 0) || end) block(k, c, end && c <= 1)
        }
    }

    companion object {
        fun key(x: Double, y: Double): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
        fun x(key: Long): Int = (key shr 32).toInt()
        fun y(key: Long): Int = key.toInt()
    }
}

class LongIntMap(capacity: Int = 1024) {
    private var keys = LongArray(capacity)
    private var values = IntArray(capacity)
    private var used = BooleanArray(capacity)
    private var size = 0

    private fun slot(k: Long, ks: LongArray, us: BooleanArray): Int {
        val mask = ks.size - 1
        var h = (k * -0x61c8864680b583ebL ushr 32).toInt() and mask
        while (us[h] && ks[h] != k) h = (h + 1) and mask
        return h
    }

    fun increment(k: Long) {
        if ((size + 1) * 2 > keys.size) grow()
        val s = slot(k, keys, used)
        if (!used[s]) {
            used[s] = true
            keys[s] = k
            size++
        }
        values[s]++
    }

    fun get(k: Long): Int {
        val s = slot(k, keys, used)
        return if (used[s]) values[s] else 0
    }

    fun forEach(block: (Long, Int) -> Unit) {
        for (i in keys.indices) if (used[i]) block(keys[i], values[i])
    }

    private fun grow() {
        val ok = keys
        val ov = values
        val ou = used
        keys = LongArray(ok.size * 2)
        values = IntArray(ok.size * 2)
        used = BooleanArray(ok.size * 2)
        for (i in ok.indices) {
            if (!ou[i]) continue
            val s = slot(ok[i], keys, used)
            used[s] = true
            keys[s] = ok[i]
            values[s] = ov[i]
        }
    }
}
