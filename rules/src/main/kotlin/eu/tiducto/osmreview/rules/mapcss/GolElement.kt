package eu.tiducto.osmreview.rules.mapcss

import com.geodesk.feature.Feature
import com.geodesk.feature.FeatureType
import eu.tiducto.osmreview.rules.isClosedRing
import eu.tiducto.osmreview.rules.tagMap
import eu.tiducto.osmreview.shared.OsmRef
import eu.tiducto.osmreview.shared.OsmType
import eu.tiducto.osmreview.shared.mapcss.MapCssElement
import eu.tiducto.osmreview.shared.mapcss.MapCssLink

class GolElement(val feature: Feature) : MapCssElement {
    override val type: OsmType = when (feature.type()) {
        FeatureType.NODE -> OsmType.NODE
        FeatureType.WAY -> OsmType.WAY
        else -> OsmType.RELATION
    }

    override val id: Long = feature.id()

    val ref: OsmRef get() = OsmRef(type, id)

    override val tags: Map<String, String> by lazy { feature.tagMap() }

    override val isClosed: Boolean by lazy {
        when (type) {
            OsmType.WAY -> feature.isClosedRing()
            OsmType.RELATION -> feature.isArea
            OsmType.NODE -> false
        }
    }

    override val parents: List<MapCssLink> by lazy { parentLinks() }

    override val children: List<MapCssLink> by lazy { childLinks() }

    private fun parentLinks(): List<MapCssLink> {
        val out = ArrayList<MapCssLink>()
        for (p in feature.parents()) {
            if (out.size >= MAX_LINKS) break
            if (p.isWay) {
                out += MapCssLink(GolElement(p), "", indexInWay(p))
            } else {
                var i = 0
                var role = ""
                var index = 0
                for (m in p.members()) {
                    if (same(m, feature)) {
                        role = m.role().orEmpty()
                        index = i
                        break
                    }
                    if (++i > MAX_MEMBER_SCAN) break
                }
                out += MapCssLink(GolElement(p), role, index)
            }
        }
        return out
    }

    private fun indexInWay(way: Feature): Int {
        var i = 0
        for (n in way.nodes()) {
            if (same(n, feature)) return i
            i++
        }
        return 0
    }

    private fun childLinks(): List<MapCssLink> {
        val out = ArrayList<MapCssLink>()
        when (type) {
            OsmType.NODE -> Unit
            OsmType.WAY -> for (n in feature.nodes()) {
                if (out.size >= MAX_LINKS) break
                out += MapCssLink(GolElement(n), "", out.size)
            }
            OsmType.RELATION -> for (m in feature.members()) {
                if (out.size >= MAX_LINKS) break
                out += MapCssLink(GolElement(m), m.role().orEmpty(), out.size)
            }
        }
        return out
    }

    override fun equals(other: Any?): Boolean =
        other is GolElement && other.type == type && other.id == id && (id != 0L || same(other.feature, feature))

    override fun hashCode(): Int = if (id != 0L) (id * 31 + type.ordinal).hashCode() else feature.x() xor feature.y()

    override fun toString(): String = if (id != 0L) "$ref" else "node@${feature.x()},${feature.y()}"

    companion object {
        const val MAX_LINKS = 2000
        private const val MAX_MEMBER_SCAN = 20_000

        private fun same(a: Feature, b: Feature): Boolean {
            if (a.type() != b.type()) return false
            val ia = a.id()
            val ib = b.id()
            if (ia != 0L || ib != 0L) return ia == ib
            return a.x() == b.x() && a.y() == b.y()
        }
    }
}
