package eu.tiducto.osmreview.server.mapcss

import eu.tiducto.osmreview.rules.Geo
import eu.tiducto.osmreview.rules.mapcss.MapCssEngine
import eu.tiducto.osmreview.server.core.NotFoundException
import eu.tiducto.osmreview.server.core.ResolvedScope
import eu.tiducto.osmreview.server.core.StateException
import eu.tiducto.osmreview.server.rulesfolder.LoadedRuleFile
import eu.tiducto.osmreview.server.rulesfolder.RulesFolder
import eu.tiducto.osmreview.shared.BBox
import eu.tiducto.osmreview.shared.CoverageEntry
import eu.tiducto.osmreview.shared.CoverageStats
import eu.tiducto.osmreview.shared.HighlightEvaluation
import eu.tiducto.osmreview.shared.HighlightFeature
import eu.tiducto.osmreview.shared.HighlightFile
import eu.tiducto.osmreview.shared.HighlightList
import eu.tiducto.osmreview.shared.HighlightRule
import eu.tiducto.osmreview.shared.RuleKind
import eu.tiducto.osmreview.shared.TileProperties
import eu.tiducto.osmreview.shared.hasErrors
import eu.tiducto.osmreview.shared.mapcss.Lit
import eu.tiducto.osmreview.shared.mapcss.Stylesheet
import java.util.Collections
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.time.measureTimedValue

class HighlightService(
    private val files: RulesFolder,
    private val engine: MapCssEngine,
    val tileProperties: TileProperties = TileProperties.DEFAULT,
) {
    private data class CacheKey(val path: String, val hash: String, val generation: Long, val area: String)

    private val cache: MutableMap<CacheKey, HighlightEvaluation> = Collections.synchronizedMap(
        object : LinkedHashMap<CacheKey, HighlightEvaluation>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, HighlightEvaluation>?) = size > CACHE_SIZE
        },
    )

    fun list(): HighlightList = HighlightList(tileProperties, files.loaded(RuleKind.HIGHLIGHT).sortedBy { it.path }.map(::describe))

    fun describe(path: String): HighlightFile = describe(loaded(path))

    private fun describe(f: LoadedRuleFile): HighlightFile {
        val sheet = f.value as? Stylesheet
        val readOnly = f.remote
        if (sheet == null) {
            return HighlightFile(f.path, f.hash, f.path, valid = false, readOnly = readOnly, diagnostics = f.diagnostics)
        }
        val legend = sheet.legend()
        val byRule = legend.associateBy { it.rule }
        val opts = tileProperties.mapLibreOptions()
        val rules = sheet.rules.filter { it.hasStyle }.map { rule ->
            val filter = sheet.toMapLibreFilter(rule.index, opts)
            val color = byRule[rule.index]?.color ?: listOf("color", "fill-color").firstNotNullOfOrNull { name ->
                (rule.properties.firstOrNull { it.name == name }?.values?.singleOrNull() as? Lit)?.value as? String
            }
            HighlightRule(rule.index, rule.line, rule.selectors.map { it.text }, byRule[rule.index]?.label, color, filter != null, filter, sheet.staticStyle(rule.index))
        }
        return HighlightFile(
            path = f.path,
            hash = f.hash,
            title = sheet.meta.title ?: f.path.substringAfterLast('/').removeSuffix(".mapcss"),
            description = sheet.meta.description,
            dimOthers = sheet.meta.dimOthers,
            stats = sheet.meta.stats,
            legend = legend,
            rules = rules,
            allCompiled = rules.all { it.compiled },
            goql = sheet.toGoqlPrefilter(),
            valid = !f.diagnostics.hasErrors(),
            readOnly = readOnly,
            diagnostics = f.diagnostics,
        )
    }

    private fun loaded(path: String): LoadedRuleFile =
        files.loaded(RuleKind.HIGHLIGHT).firstOrNull { it.path == path } ?: throw NotFoundException("No highlight file '$path'")

    private fun stylesheet(path: String): Pair<LoadedRuleFile, Stylesheet> {
        val f = loaded(path)
        val sheet = f.value as? Stylesheet ?: throw StateException("'$path' has errors and cannot be evaluated: ${f.diagnostics.firstOrNull()?.message}")
        return f to sheet
    }

    fun evaluate(path: String, bbox: BBox?, tile: String?, limit: Int = DEFAULT_LIMIT): HighlightEvaluation {
        require((bbox == null) != (tile == null)) { "Give a bbox or a tile z/x/y" }
        val box = bbox ?: tileBBox(tile!!)
        require((box.east - box.west) * (box.north - box.south) <= MAX_BBOX_AREA) { "bbox too large for server-side evaluation; use zoom 13 or more" }
        val (f, sheet) = stylesheet(path)
        val key = CacheKey(path, f.hash, engine.generation, "$limit@" + (tile ?: "${box.west},${box.south},${box.east},${box.north}"))
        cache[key]?.let { return it.copy(cached = true) }
        val (run, took) = measureTimedValue { engine.styles(sheet, Geo.box(box), limit.coerceIn(1, MAX_LIMIT)) }
        val result = HighlightEvaluation(
            path = path,
            hash = f.hash,
            bbox = box,
            tile = tile,
            features = run.hits.map { HighlightFeature(it.ref, it.style) },
            truncated = run.truncated,
            candidates = run.candidates,
            tookMs = took.inWholeMilliseconds,
        )
        cache[key] = result
        return result
    }

    fun evaluate(path: String, resolved: ResolvedScope, limit: Int): HighlightEvaluation {
        val (f, sheet) = stylesheet(path)
        val key = CacheKey(path, f.hash, engine.generation, "$limit@${resolved.key}")
        cache[key]?.let { return it.copy(cached = true) }
        val (run, took) = measureTimedValue { engine.styles(sheet, resolved.scope, limit.coerceIn(1, MAX_LIMIT)) }
        val result = HighlightEvaluation(
            path = path,
            hash = f.hash,
            bbox = Geo.bbox(resolved.scope.bounds),
            features = run.hits.map { HighlightFeature(it.ref, it.style) },
            truncated = run.truncated,
            candidates = run.candidates,
            tookMs = took.inWholeMilliseconds,
        )
        cache[key] = result
        return result
    }

    fun stats(path: String, resolved: ResolvedScope): CoverageStats {
        val (_, sheet) = stylesheet(path)
        val measure = sheet.meta.stats ?: "count"
        val (run, took) = measureTimedValue { engine.coverage(sheet, resolved.scope, measure) }
        fun value(b: eu.tiducto.osmreview.rules.mapcss.CoverageBucket): Double = when (measure) {
            "length" -> b.lengthM
            "area" -> b.areaM2
            else -> b.count.toDouble()
        }
        val total = run.buckets.sumOf(::value)
        val entries = run.buckets.map { b ->
            CoverageEntry(
                label = b.label,
                color = b.color,
                rule = b.rule,
                count = b.count,
                lengthKm = round3(b.lengthM / 1000),
                areaKm2 = round3(b.areaM2 / 1e6),
                percent = if (total > 0) round3(100 * value(b) / total) else 0.0,
            )
        }
        return CoverageStats(
            path = path,
            title = sheet.meta.title ?: path,
            stats = measure,
            scope = resolved.key,
            areaName = resolved.areaName,
            scopeAreaKm2 = round3(run.scopeAreaM2 / 1e6),
            entries = entries,
            total = CoverageEntry(
                label = "total",
                count = run.buckets.sumOf { it.count },
                lengthKm = round3(run.buckets.sumOf { it.lengthM } / 1000),
                areaKm2 = round3(run.buckets.sumOf { it.areaM2 } / 1e6),
                percent = if (total > 0) 100.0 else 0.0,
            ),
            candidates = run.candidates,
            tookMs = took.inWholeMilliseconds,
        )
    }

    fun clear() = cache.clear()

    companion object {
        const val CACHE_SIZE = 512
        const val DEFAULT_LIMIT = 20_000
        const val MAX_LIMIT = 100_000
        const val MAX_BBOX_AREA = 0.1 * 0.06

        private fun round3(v: Double) = kotlin.math.round(v * 1000) / 1000

        fun tileBBox(tile: String): BBox {
            val p = tile.trim().split('/').map { it.toIntOrNull() ?: throw IllegalArgumentException("tile must be z/x/y") }
            require(p.size == 3) { "tile must be z/x/y" }
            val (z, x, y) = p
            require(z in 0..22) { "tile zoom must be 0 to 22" }
            val n = 2.0.pow(z)
            require(x in 0 until n.toInt() && y in 0 until n.toInt()) { "tile $tile is outside the world" }
            fun lon(x: Int) = x / n * 360.0 - 180.0
            fun lat(y: Int) = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / n))))
            return BBox(lon(x), lat(y + 1), lon(x + 1), lat(y))
        }
    }
}
