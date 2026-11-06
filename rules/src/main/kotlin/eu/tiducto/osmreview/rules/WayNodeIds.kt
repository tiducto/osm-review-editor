package eu.tiducto.osmreview.rules

import com.geodesk.feature.Feature
import com.geodesk.feature.FeatureLibrary
import com.geodesk.feature.store.StoredWay
import com.geodesk.geom.Box
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet
import java.nio.ByteBuffer
import kotlin.math.floor

object WayNodeIds {
    fun of(f: Feature): LongArray? {
        val way = f as? StoredWay ?: return null
        val buf: ByteBuffer = way.buffer()
        val ppBody = way.pointer() + 12
        var pos = buf.getInt(ppBody) + ppBody
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = buf.get(pos++).toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        val count = varint().toInt()
        if (count <= 0 || count > MAX_NODES) return null
        repeat(count * 2) { varint() }
        val out = LongArray(count)
        var id = 0L
        for (i in 0 until count) {
            val v = varint()
            id += (v ushr 1) xor -(v and 1)
            out[i] = id
        }
        return out
    }

    fun near(gol: FeatureLibrary, points: List<Pair<Double, Double>>, radiusMeters: Double): LongHashSet {
        val out = LongHashSet()
        if (points.isEmpty()) return out
        val cells = points.map { (lon, lat) -> floor(lon / CELL).toLong() to floor(lat / CELL).toLong() }.toSet()
        for ((cx, cy) in cells) {
            val lon = (cx + 0.5) * CELL
            val lat = (cy + 0.5) * CELL
            val box = Box.metersAroundLonLat(radiusMeters + CELL_HALF_METERS, lon, lat)
            for (w in gol.select("w, a").`in`(box)) if (w.isWay) of(w)?.forEach { out.add(it) }
        }
        return out
    }

    private const val MAX_NODES = 2_000_000
    private const val CELL = 0.01
    private const val CELL_HALF_METERS = 800.0
}
