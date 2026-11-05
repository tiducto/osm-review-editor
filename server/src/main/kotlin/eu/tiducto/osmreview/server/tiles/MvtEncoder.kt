package eu.tiducto.osmreview.server.tiles

import kotlin.math.abs

class ProtoWriter(initial: Int = 1024) {
    private var buf = ByteArray(initial)
    var size = 0
        private set

    private fun ensure(n: Int) {
        if (size + n <= buf.size) return
        var cap = buf.size * 2
        while (cap < size + n) cap *= 2
        buf = buf.copyOf(cap)
    }

    fun varint(value: Long) {
        ensure(10)
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            buf[size++] = ((v and 0x7F) or 0x80).toByte()
            v = v ushr 7
        }
        buf[size++] = v.toByte()
    }

    fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

    fun bytes(field: Int, data: ByteArray, len: Int = data.size) {
        tag(field, 2)
        varint(len.toLong())
        ensure(len)
        System.arraycopy(data, 0, buf, size, len)
        size += len
    }

    fun message(field: Int, w: ProtoWriter) = bytes(field, w.buf, w.size)

    fun string(field: Int, s: String) = bytes(field, s.encodeToByteArray())

    fun uint(field: Int, v: Long) {
        tag(field, 0)
        varint(v)
    }

    fun double(field: Int, v: Double) {
        tag(field, 1)
        ensure(8)
        val bits = java.lang.Double.doubleToRawLongBits(v)
        for (i in 0 until 8) buf[size++] = (bits ushr (8 * i)).toByte()
    }

    fun packed(field: Int, values: IntArray, count: Int) {
        if (count == 0) return
        val inner = ProtoWriter(count * 2 + 8)
        for (i in 0 until count) inner.varint(values[i].toLong() and 0xFFFFFFFFL)
        message(field, inner)
    }

    fun raw(other: ProtoWriter) {
        ensure(other.size)
        System.arraycopy(other.buf, 0, buf, size, other.size)
        size += other.size
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

enum class GeomType(val code: Int) { POINT(1), LINESTRING(2), POLYGON(3) }

class MvtLayer(val name: String, val extent: Int) {
    private val keys = LinkedHashMap<String, Int>()
    private val values = LinkedHashMap<Any, Int>()
    private val features = ProtoWriter(4096)
    var count = 0
        private set

    private fun keyIndex(k: String) = keys.getOrPut(k) { keys.size }

    private fun valueIndex(v: Any): Int {
        val norm: Any = when (v) {
            is Int -> v.toLong()
            is Float -> v.toDouble()
            else -> v
        }
        return values.getOrPut(norm) { values.size }
    }

    fun add(id: Long?, type: GeomType, geometry: IntArray, geometryLength: Int, properties: Map<String, Any>) {
        if (geometryLength == 0) return
        val f = ProtoWriter(64 + geometryLength * 2)
        if (id != null && id >= 0) f.uint(1, id)
        val tags = IntArray(properties.size * 2)
        var n = 0
        for ((k, v) in properties) {
            tags[n++] = keyIndex(k)
            tags[n++] = valueIndex(v)
        }
        f.packed(2, tags, n)
        f.uint(3, type.code.toLong())
        f.packed(4, geometry, geometryLength)
        features.message(2, f)
        count++
    }

    fun writeTo(out: ProtoWriter) {
        val w = ProtoWriter(features.size + 1024)
        w.uint(15, 2)
        w.string(1, name)
        w.raw(features)
        for (k in keys.keys) w.string(3, k)
        for (v in values.keys) {
            val vw = ProtoWriter(16)
            when (v) {
                is String -> vw.string(1, v)
                is Double -> vw.double(3, v)
                is Long -> if (v >= 0) vw.uint(5, v) else vw.uint(6, zigzag(v))
                is Boolean -> vw.uint(7, if (v) 1 else 0)
                else -> vw.string(1, v.toString())
            }
            w.message(4, vw)
        }
        w.uint(5, extent.toLong())
        out.message(3, w)
    }
}

fun zigzag(v: Long): Long = (v shl 1) xor (v shr 63)

private fun zz(v: Int): Int = (v shl 1) xor (v shr 31)

class GeometryWriter {
    var data = IntArray(256)
    var size = 0
    private var cx = 0
    private var cy = 0

    fun reset() {
        size = 0
        cx = 0
        cy = 0
    }

    private fun ensure(n: Int) {
        if (size + n > data.size) data = data.copyOf(maxOf(data.size * 2, size + n))
    }

    private fun command(id: Int, count: Int) {
        ensure(1)
        data[size++] = (id and 7) or (count shl 3)
    }

    private fun point(x: Int, y: Int) {
        ensure(2)
        data[size++] = zz(x - cx)
        data[size++] = zz(y - cy)
        cx = x
        cy = y
    }

    fun points(xy: IntArray, count: Int) {
        if (count == 0) return
        command(1, count)
        for (i in 0 until count) point(xy[2 * i], xy[2 * i + 1])
    }

    fun line(xy: IntArray, count: Int) {
        if (count < 2) return
        command(1, 1)
        point(xy[0], xy[1])
        command(2, count - 1)
        for (i in 1 until count) point(xy[2 * i], xy[2 * i + 1])
    }

    fun ring(xy: IntArray, count: Int) {
        if (count < 3) return
        command(1, 1)
        point(xy[0], xy[1])
        command(2, count - 1)
        for (i in 1 until count) point(xy[2 * i], xy[2 * i + 1])
        command(7, 1)
    }
}

object Rings {
    fun signedArea(xy: IntArray, count: Int): Long {
        var a = 0L
        for (i in 0 until count) {
            val j = if (i + 1 == count) 0 else i + 1
            a += xy[2 * i].toLong() * xy[2 * j + 1] - xy[2 * j].toLong() * xy[2 * i + 1]
        }
        return a
    }

    fun reverse(xy: IntArray, count: Int) {
        var i = 0
        var j = count - 1
        while (i < j) {
            val x = xy[2 * i]
            val y = xy[2 * i + 1]
            xy[2 * i] = xy[2 * j]
            xy[2 * i + 1] = xy[2 * j + 1]
            xy[2 * j] = x
            xy[2 * j + 1] = y
            i++
            j--
        }
    }

    fun isDegenerate(xy: IntArray, count: Int): Boolean = count < 3 || abs(signedArea(xy, count)) == 0L
}

class MvtTile {
    private val layers = LinkedHashMap<String, MvtLayer>()

    fun layer(name: String, extent: Int): MvtLayer = layers.getOrPut(name) { MvtLayer(name, extent) }

    fun featureCount(): Int = layers.values.sumOf { it.count }

    fun encode(): ByteArray {
        val out = ProtoWriter(64 * 1024)
        for (l in layers.values) if (l.count > 0) l.writeTo(out)
        return out.toByteArray()
    }
}
