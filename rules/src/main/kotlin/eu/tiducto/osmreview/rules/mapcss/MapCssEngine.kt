package eu.tiducto.osmreview.rules.mapcss

import com.geodesk.feature.Feature
import com.geodesk.feature.FeatureLibrary
import com.geodesk.feature.Features
import com.geodesk.geom.Box
import com.geodesk.geom.Mercator
import eu.tiducto.osmreview.rules.Geo
import eu.tiducto.osmreview.rules.GolMirror
import eu.tiducto.osmreview.rules.Scope
import eu.tiducto.osmreview.rules.centroid
import eu.tiducto.osmreview.rules.within
import eu.tiducto.osmreview.shared.LngLat
import eu.tiducto.osmreview.shared.OsmRef
import eu.tiducto.osmreview.shared.mapcss.CheckResult
import eu.tiducto.osmreview.shared.mapcss.StyleResult
import eu.tiducto.osmreview.shared.mapcss.Stylesheet
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.Polygon
import java.util.concurrent.atomic.AtomicReference

data class StyleHit(val ref: OsmRef, val style: StyleResult)

data class CheckHit(val ref: OsmRef, val centroid: LngLat, val tags: Map<String, String>, val results: List<CheckResult>)

data class StyleRun(val hits: List<StyleHit>, val candidates: Int, val truncated: Boolean)

data class CheckRun(val hits: List<CheckHit>, val candidates: Int, val truncated: Boolean)

data class CoverageBucket(
    val label: String,
    val color: String?,
    val rule: Int,
    var count: Int = 0,
    var lengthM: Double = 0.0,
    var areaM2: Double = 0.0,
)

data class CoverageRun(val buckets: List<CoverageBucket>, val candidates: Int, val scopeAreaM2: Double)

class MapCssEngine(private val mirror: GolMirror) {
    private val current = AtomicReference(MapCssCaches(-1))

    val generation: Long get() = mirror.generation

    fun caches(): MapCssCaches {
        val g = mirror.generation
        val c = current.get()
        if (c.generation == g) return c
        val fresh = MapCssCaches(g)
        return if (current.compareAndSet(c, fresh)) fresh else current.get()
    }

    fun <T> session(block: (MapCssSession) -> T): T = mirror.query { gol -> block(MapCssSession(gol, GolMapCssHost(gol, caches()))) }

    fun styles(sheet: Stylesheet, box: Box, limit: Int): StyleRun = session { it.styles(sheet, it.candidates(sheet, box), limit) }

    fun styles(sheet: Stylesheet, scope: Scope, limit: Int): StyleRun = session { it.styles(sheet, it.candidates(sheet, scope), limit) }

    fun checks(sheet: Stylesheet, scope: Scope, limit: Int): CheckRun = session { it.checks(sheet, scope, limit) }

    fun coverage(sheet: Stylesheet, scope: Scope, measure: String?): CoverageRun = session { it.coverage(sheet, scope, measure) }
}

class MapCssSession(val gol: FeatureLibrary, val host: GolMapCssHost) {
    fun candidates(sheet: Stylesheet, scope: Scope): Features = base(sheet).within(scope)

    fun candidates(sheet: Stylesheet, box: Box): Features = base(sheet).`in`(box)

    private fun base(sheet: Stylesheet): Features = sheet.toGoqlPrefilter()?.let { gol.select(it) } ?: gol

    fun styles(sheet: Stylesheet, candidates: Features, limit: Int): StyleRun {
        val out = ArrayList<StyleHit>()
        var n = 0
        for (f in candidates) {
            n++
            val el = GolElement(f)
            val style = sheet.styles(el, host)
            if (!style.matched) continue
            if (out.size >= limit) return StyleRun(out, n, true)
            out += StyleHit(el.ref, style)
        }
        return StyleRun(out, n, false)
    }

    fun checks(sheet: Stylesheet, scope: Scope, limit: Int): CheckRun {
        val out = ArrayList<CheckHit>()
        var n = 0
        for (f in candidates(sheet, scope)) {
            n++
            val el = GolElement(f)
            val results = sheet.checks(el, host)
            if (results.isEmpty()) continue
            if (out.size >= limit) return CheckRun(out, n, true)
            out += CheckHit(el.ref, f.centroid(), el.tags, results)
        }
        return CheckRun(out, n, false)
    }

    fun coverage(sheet: Stylesheet, scope: Scope, measure: String?): CoverageRun {
        val buckets = LinkedHashMap<String, CoverageBucket>()
        sheet.legend().forEach { buckets.getOrPut(it.label) { CoverageBucket(it.label, it.color, it.rule) } }
        val clip: Geometry = scope.polygon ?: Geo.boxPolygon(scope.bounds)
        var n = 0
        for (f in candidates(sheet, scope)) {
            n++
            val style = sheet.styles(GolElement(f), host)
            val label = style.legend ?: continue
            val b = buckets.getOrPut(label) { CoverageBucket(label, style.color, style.rules.lastOrNull() ?: 0) }
            b.count++
            when (measure) {
                "length" -> if (!f.isNode) b.lengthM += clippedLength(f, scope, clip)
                "area" -> if (f.isArea) b.areaM2 += clippedArea(f, scope, clip)
            }
        }
        return CoverageRun(buckets.values.toList(), n, Mercator.area(clip))
    }

    private fun inside(f: Feature, scope: Scope): Boolean = scope.polygon == null && scope.bounds.containsSimple(f.bounds())

    private fun clippedLength(f: Feature, scope: Scope, clip: Geometry): Double {
        if (inside(f, scope)) return f.length()
        val geom = f.toGeometry()
        if (scope.prepared?.covers(geom) == true) return f.length()
        val g = runCatching { geom.intersection(clip) }.getOrNull() ?: return 0.0
        return lineLength(g)
    }

    private fun clippedArea(f: Feature, scope: Scope, clip: Geometry): Double {
        if (inside(f, scope)) return f.area()
        val geom = f.toGeometry()
        if (scope.prepared?.covers(geom) == true) return f.area()
        val g = runCatching { geom.intersection(clip) }.getOrNull() ?: return 0.0
        return Mercator.area(g)
    }

    private fun lineLength(g: Geometry): Double {
        var total = 0.0
        for (i in 0 until g.numGeometries) {
            val part = g.getGeometryN(i)
            val line = when (part) {
                is LineString -> part
                is Polygon -> part.exteriorRing
                else -> continue
            }
            val cs = line.coordinates
            for (j in 1 until cs.size) total += Mercator.distance(cs[j - 1], cs[j])
        }
        return total
    }

    private fun Box.containsSimple(o: com.geodesk.geom.Bounds): Boolean =
        o.minX() >= minX() && o.maxX() <= maxX() && o.minY() >= minY() && o.maxY() <= maxY()
}
