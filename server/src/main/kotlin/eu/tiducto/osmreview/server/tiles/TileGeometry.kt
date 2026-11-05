package eu.tiducto.osmreview.server.tiles

import kotlin.math.round

const val MAP_WIDTH = 4_294_967_296.0
private const val HALF = 2_147_483_648.0

class TileBounds(val z: Int, val x: Int, val y: Int, val extent: Int) {
    val span: Double = MAP_WIDTH / (1L shl z)
    val minX: Double = -HALF + x * span
    val maxY: Double = HALF - y * span
    val maxX: Double = minX + span
    val minY: Double = maxY - span
    private val scale: Double = extent / span

    fun tx(mx: Double): Double = (mx - minX) * scale
    fun ty(my: Double): Double = (maxY - my) * scale

    fun mercatorUnits(tileUnits: Double): Double = tileUnits / scale
    fun tileUnits(mercator: Double): Double = mercator * scale

    fun project(mercXY: DoubleArray): DoubleArray {
        val out = DoubleArray(mercXY.size)
        var i = 0
        while (i < mercXY.size) {
            out[i] = tx(mercXY[i])
            out[i + 1] = ty(mercXY[i + 1])
            i += 2
        }
        return out
    }

    fun contains(tx: Double, ty: Double): Boolean = tx >= 0 && ty >= 0 && tx < extent && ty < extent
}

class ClipBox(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    companion object {
        fun around(extent: Int, buffer: Double) = ClipBox(-buffer, -buffer, extent + buffer, extent + buffer)
    }
}

object Clip {
    fun line(xy: DoubleArray, box: ClipBox): List<DoubleArray> {
        val parts = ArrayList<DoubleArray>()
        val cur = DoubleList()
        val n = xy.size / 2
        if (n < 2) return parts
        for (i in 0 until n - 1) {
            val x0 = xy[2 * i]
            val y0 = xy[2 * i + 1]
            val x1 = xy[2 * i + 2]
            val y1 = xy[2 * i + 3]
            var t0 = 0.0
            var t1 = 1.0
            val dx = x1 - x0
            val dy = y1 - y0
            var visible = true
            val p = doubleArrayOf(-dx, dx, -dy, dy)
            val q = doubleArrayOf(x0 - box.minX, box.maxX - x0, y0 - box.minY, box.maxY - y0)
            for (k in 0 until 4) {
                if (p[k] == 0.0) {
                    if (q[k] < 0) {
                        visible = false
                        break
                    }
                } else {
                    val r = q[k] / p[k]
                    if (p[k] < 0) {
                        if (r > t1) {
                            visible = false
                            break
                        }
                        if (r > t0) t0 = r
                    } else {
                        if (r < t0) {
                            visible = false
                            break
                        }
                        if (r < t1) t1 = r
                    }
                }
            }
            if (!visible) {
                if (cur.size >= 4) parts += cur.toArray()
                cur.clear()
                continue
            }
            val ax = x0 + t0 * dx
            val ay = y0 + t0 * dy
            val bx = x0 + t1 * dx
            val by = y0 + t1 * dy
            if (cur.size == 0 || t0 > 0.0) {
                if (cur.size >= 4) parts += cur.toArray()
                cur.clear()
                cur.add(ax, ay)
            }
            cur.add(bx, by)
            if (t1 < 1.0) {
                if (cur.size >= 4) parts += cur.toArray()
                cur.clear()
            }
        }
        if (cur.size >= 4) parts += cur.toArray()
        return parts
    }

    fun ring(xy: DoubleArray, box: ClipBox): DoubleArray {
        var pts = xy
        if (pts.size >= 4 && pts[0] == pts[pts.size - 2] && pts[1] == pts[pts.size - 1]) pts = pts.copyOf(pts.size - 2)
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var i = 0
        while (i < pts.size) {
            if (pts[i] < minX) minX = pts[i]
            if (pts[i] > maxX) maxX = pts[i]
            if (pts[i + 1] < minY) minY = pts[i + 1]
            if (pts[i + 1] > maxY) maxY = pts[i + 1]
            i += 2
        }
        if (maxX < box.minX || minX > box.maxX || maxY < box.minY || minY > box.maxY) return DoubleArray(0)
        if (minX >= box.minX && maxX <= box.maxX && minY >= box.minY && maxY <= box.maxY) return pts
        pts = edge(pts, 0, box.minX)
        pts = edge(pts, 1, box.maxX)
        pts = edge(pts, 2, box.minY)
        pts = edge(pts, 3, box.maxY)
        return pts
    }

    private fun inside(x: Double, y: Double, side: Int, v: Double) = when (side) {
        0 -> x >= v
        1 -> x <= v
        2 -> y >= v
        else -> y <= v
    }

    private fun edge(pts: DoubleArray, side: Int, v: Double): DoubleArray {
        val n = pts.size / 2
        if (n == 0) return pts
        val out = DoubleList(pts.size + 8)
        var px = pts[2 * (n - 1)]
        var py = pts[2 * (n - 1) + 1]
        var pIn = inside(px, py, side, v)
        for (i in 0 until n) {
            val x = pts[2 * i]
            val y = pts[2 * i + 1]
            val cIn = inside(x, y, side, v)
            if (cIn != pIn) {
                val t = if (side < 2) (v - px) / (x - px) else (v - py) / (y - py)
                out.add(px + t * (x - px), py + t * (y - py))
            }
            if (cIn) out.add(x, y)
            px = x
            py = y
            pIn = cIn
        }
        return out.toArray()
    }
}

object Simplify {
    fun douglasPeucker(xy: DoubleArray, tolerance: Double, closed: Boolean): DoubleArray {
        val n = xy.size / 2
        if (tolerance <= 0 || n <= (if (closed) 4 else 2)) return xy
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val stack = ArrayDeque<Int>()
        stack.addLast(0)
        stack.addLast(n - 1)
        val tol2 = tolerance * tolerance
        while (stack.isNotEmpty()) {
            val last = stack.removeLast()
            val first = stack.removeLast()
            var maxD = -1.0
            var idx = -1
            for (i in first + 1 until last) {
                val d = segDist2(xy, i, first, last)
                if (d > maxD) {
                    maxD = d
                    idx = i
                }
            }
            if (idx >= 0 && maxD > tol2) {
                keep[idx] = true
                stack.addLast(first)
                stack.addLast(idx)
                stack.addLast(idx)
                stack.addLast(last)
            }
        }
        val out = DoubleList(xy.size)
        for (i in 0 until n) if (keep[i]) out.add(xy[2 * i], xy[2 * i + 1])
        return out.toArray()
    }

    private fun segDist2(xy: DoubleArray, p: Int, a: Int, b: Int): Double {
        val px = xy[2 * p]
        val py = xy[2 * p + 1]
        val ax = xy[2 * a]
        val ay = xy[2 * a + 1]
        val bx = xy[2 * b]
        val by = xy[2 * b + 1]
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return (px - ax) * (px - ax) + (py - ay) * (py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val ex = ax + t * dx - px
        val ey = ay + t * dy - py
        return ex * ex + ey * ey
    }
}

object Quantize {
    fun points(xy: DoubleArray, closed: Boolean): Pair<IntArray, Int> {
        val n = xy.size / 2
        val out = IntArray(xy.size)
        var count = 0
        for (i in 0 until n) {
            val x = round(xy[2 * i]).toInt()
            val y = round(xy[2 * i + 1]).toInt()
            if (count > 0 && out[2 * count - 2] == x && out[2 * count - 1] == y) continue
            out[2 * count] = x
            out[2 * count + 1] = y
            count++
        }
        if (closed && count > 1 && out[0] == out[2 * count - 2] && out[1] == out[2 * count - 1]) count--
        return out to count
    }
}

class DoubleList(capacity: Int = 32) {
    private var data = DoubleArray(capacity)
    var size = 0
        private set

    fun add(x: Double, y: Double) {
        if (size + 2 > data.size) data = data.copyOf(maxOf(data.size * 2, 8))
        data[size++] = x
        data[size++] = y
    }

    fun clear() {
        size = 0
    }

    fun toArray(): DoubleArray = data.copyOf(size)
}
