package eu.tiducto.osmreview.server.tiles

class DecodedFeature(val id: Long?, val type: Int, val props: Map<String, Any>, val geometry: List<Int>) {
    fun rings(): List<List<Pair<Int, Int>>> {
        val out = ArrayList<List<Pair<Int, Int>>>()
        var cur = ArrayList<Pair<Int, Int>>()
        var i = 0
        var x = 0
        var y = 0
        while (i < geometry.size) {
            val cmd = geometry[i] and 7
            val count = geometry[i] ushr 3
            i++
            when (cmd) {
                1, 2 -> repeat(count) {
                    if (cmd == 1 && cur.isNotEmpty()) {
                        out += cur
                        cur = ArrayList()
                    }
                    x += unzz(geometry[i])
                    y += unzz(geometry[i + 1])
                    i += 2
                    cur += x to y
                }
                7 -> {
                    out += cur
                    cur = ArrayList()
                }
            }
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    private fun unzz(v: Int) = (v ushr 1) xor -(v and 1)
}

class DecodedLayer(val name: String, val extent: Int, val features: List<DecodedFeature>)

object MvtDecoder {
    fun decode(bytes: ByteArray): Map<String, DecodedLayer> {
        val r = Reader(bytes, 0, bytes.size)
        val out = LinkedHashMap<String, DecodedLayer>()
        while (r.more()) {
            val (field, wire) = r.key()
            if (field == 3 && wire == 2) {
                val l = layer(r.sub())
                out[l.name] = l
            } else {
                r.skip(wire)
            }
        }
        return out
    }

    private fun layer(r: Reader): DecodedLayer {
        var name = ""
        var extent = 4096
        val keys = ArrayList<String>()
        val values = ArrayList<Any>()
        val raw = ArrayList<Reader>()
        while (r.more()) {
            val (field, wire) = r.key()
            when (field) {
                1 -> name = r.string()
                2 -> raw += r.sub()
                3 -> keys += r.string()
                4 -> values += value(r.sub())
                5 -> extent = r.varint().toInt()
                else -> r.skip(wire)
            }
        }
        val features = raw.map { fr ->
            var id: Long? = null
            var type = 0
            var tags = listOf<Int>()
            var geom = listOf<Int>()
            while (fr.more()) {
                val (field, wire) = fr.key()
                when (field) {
                    1 -> id = fr.varint()
                    2 -> tags = fr.packed()
                    3 -> type = fr.varint().toInt()
                    4 -> geom = fr.packed()
                    else -> fr.skip(wire)
                }
            }
            val props = LinkedHashMap<String, Any>()
            for (i in tags.indices step 2) props[keys[tags[i]]] = values[tags[i + 1]]
            DecodedFeature(id, type, props, geom)
        }
        return DecodedLayer(name, extent, features)
    }

    private fun value(r: Reader): Any {
        var v: Any = ""
        while (r.more()) {
            val (field, wire) = r.key()
            v = when (field) {
                1 -> r.string()
                3 -> java.lang.Double.longBitsToDouble(r.fixed64())
                5 -> r.varint()
                6 -> r.varint().let { (it ushr 1) xor -(it and 1) }
                7 -> r.varint() != 0L
                else -> {
                    r.skip(wire)
                    v
                }
            }
        }
        return v
    }

    private class Reader(val b: ByteArray, var pos: Int, val end: Int) {
        fun more() = pos < end
        fun varint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                val x = b[pos++].toInt() and 0xFF
                result = result or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) return result
                shift += 7
            }
        }
        fun key(): Pair<Int, Int> {
            val k = varint().toInt()
            return (k ushr 3) to (k and 7)
        }
        fun sub(): Reader {
            val len = varint().toInt()
            val r = Reader(b, pos, pos + len)
            pos += len
            return r
        }
        fun string(): String {
            val s = sub()
            return String(b, s.pos, s.end - s.pos)
        }
        fun fixed64(): Long {
            var v = 0L
            for (i in 0 until 8) v = v or ((b[pos + i].toLong() and 0xFF) shl (8 * i))
            pos += 8
            return v
        }
        fun packed(): List<Int> {
            val s = sub()
            val out = ArrayList<Int>()
            while (s.more()) out += s.varint().toInt()
            return out
        }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> sub()
                5 -> pos += 4
            }
        }
    }
}
