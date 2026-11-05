package eu.tiducto.osmreview.server.tiles

import com.geodesk.feature.Feature
import com.geodesk.feature.FeatureLibrary
import com.geodesk.feature.Features
import com.geodesk.geom.Box
import eu.tiducto.osmreview.rules.GolMirror
import eu.tiducto.osmreview.rules.tagMap
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Polygon
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPOutputStream
import kotlin.math.ceil
import kotlin.math.floor

class TileResult(val gzipped: ByteArray, val etag: String, val features: Int, val buildMs: Long, val cached: Boolean)

class TileService(private val mirror: GolMirror, private val maxCacheBytes: Long = 192L * 1024 * 1024) {
    private val builder = TileBuilder()
    private val cache = object : LinkedHashMap<String, TileResult>(256, 0.75f, true) {}
    private var cacheBytes = 0L
    private var cacheGeneration = -1L
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<TileResult>>()
    private val relations = ConcurrentHashMap<Long, List<List<DoubleArray>>>()
    private var relationsGeneration = -1L

    fun etag(z: Int, x: Int, y: Int, generation: Long = mirror.generation) = "\"t${TileContent.SCHEMA}-g$generation-$z-$x-$y\""

    fun tile(z: Int, x: Int, y: Int): TileResult {
        require(z in 0..22) { "zoom must be 0-22" }
        val n = 1 shl z
        require(x in 0 until n && y in 0 until n) { "tile $z/$x/$y is out of range" }
        val gen = mirror.generation
        val key = "$gen/$z/$x/$y"
        synchronized(cache) {
            if (cacheGeneration != gen) {
                cache.clear()
                cacheBytes = 0
                cacheGeneration = gen
            }
            cache[key]?.let { return TileResult(it.gzipped, it.etag, it.features, it.buildMs, true) }
        }
        val mine = CompletableFuture<TileResult>()
        val existing = inFlight.putIfAbsent(key, mine)
        if (existing != null) return existing.get()
        try {
            val result = render(gen, z, x, y)
            synchronized(cache) {
                if (cacheGeneration == gen) {
                    cache[key] = result
                    cacheBytes += result.gzipped.size
                    val it = cache.entries.iterator()
                    while (cacheBytes > maxCacheBytes && it.hasNext()) {
                        cacheBytes -= it.next().value.gzipped.size
                        it.remove()
                    }
                }
            }
            mine.complete(result)
            return result
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            inFlight.remove(key)
        }
    }

    private fun render(gen: Long, z: Int, x: Int, y: Int): TileResult {
        val started = System.nanoTime()
        if (z < TileContent.MIN_ZOOM) return TileResult(gzip(ByteArray(0)), etag(z, x, y, gen), 0, 0, false)
        val tile = mirror.query { gol ->
            if (relationsGeneration != mirror.generation) {
                relations.clear()
                relationsGeneration = mirror.generation
            }
            builder.build(z, x, y, features(gol, z, x, y))
        }
        val bytes = tile.encode()
        val ms = (System.nanoTime() - started) / 1_000_000
        return TileResult(gzip(bytes), etag(z, x, y, gen), tile.featureCount(), ms, false)
    }

    fun features(gol: FeatureLibrary, z: Int, x: Int, y: Int): Sequence<SourceFeature> {
        val bounds = TileBounds(z, x, y, TileContent.extent(z))
        val pad = bounds.span * TileContent.bufferPx(z) / TileContent.TILE_PX
        val box = Box(
            floor(bounds.minX - pad).toInt().coerceAtLeast(Int.MIN_VALUE + 1),
            floor(bounds.minY - pad).toInt().coerceAtLeast(Int.MIN_VALUE + 1),
            ceil(bounds.maxX + pad).toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            ceil(bounds.maxY + pad).toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        )
        val query = TileContent.query(z)
        val view: Features = (if (query == null) gol else gol.select(query)).`in`(box)
        val buildings = TileContent.includeBuildings(z)
        return view.asSequence().mapNotNull { f -> convert(f, buildings) }
    }

    private fun convert(f: Feature, buildings: Boolean): SourceFeature? {
        val tags = f.tagMap()
        if (!buildings && ("building" in tags || "building:part" in tags)) return null
        return when {
            f.isNode -> if (tags.isEmpty()) null else SourceFeature(SourceType.NODE, f.id(), tags, SourceGeometry.Point(f.x().toDouble(), f.y().toDouble()))
            f.isWay -> {
                val xy = f.toXY()
                val d = DoubleArray(xy.size) { xy[it].toDouble() }
                val g = if (f.isArea) SourceGeometry.Area(listOf(listOf(d))) else SourceGeometry.Line(d)
                SourceFeature(SourceType.WAY, f.id(), tags, g, f.belongsToRelation())
            }
            f.isRelation -> {
                if (!f.isArea || tags["type"] in TileContent.NON_AREA_RELATION_TYPES) return null
                val polys = relations.getOrPut(f.id()) {
                    if (relations.size > 4000) relations.clear()
                    polygons(f.toGeometry())
                }
                if (polys.isEmpty()) null else SourceFeature(SourceType.RELATION, f.id(), tags, SourceGeometry.Area(polys))
            }
            else -> null
        }
    }

    private fun polygons(g: Geometry): List<List<DoubleArray>> = (0 until g.numGeometries).mapNotNull { i ->
        val p = g.getGeometryN(i) as? Polygon ?: return@mapNotNull null
        listOf(xy(p.exteriorRing.coordinates)) + (0 until p.numInteriorRing).map { xy(p.getInteriorRingN(it).coordinates) }
    }

    private fun xy(cs: Array<org.locationtech.jts.geom.Coordinate>): DoubleArray {
        val out = DoubleArray(cs.size * 2)
        cs.forEachIndexed { i, c ->
            out[2 * i] = c.x
            out[2 * i + 1] = c.y
        }
        return out
    }

    companion object {
        fun gzip(bytes: ByteArray): ByteArray {
            val bos = ByteArrayOutputStream(bytes.size / 3 + 64)
            GZIPOutputStream(bos).use { it.write(bytes) }
            return bos.toByteArray()
        }
    }
}
