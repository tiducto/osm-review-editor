package eu.tiducto.osmreview.server

import eu.tiducto.osmreview.rules.GolMirror
import eu.tiducto.osmreview.rules.MirrorQueries
import eu.tiducto.osmreview.rules.RuleEngine
import eu.tiducto.osmreview.server.core.Account
import eu.tiducto.osmreview.server.core.DraftService
import eu.tiducto.osmreview.server.core.EventBus
import eu.tiducto.osmreview.server.core.MergeService
import eu.tiducto.osmreview.server.db.Db
import eu.tiducto.osmreview.server.db.DraftRepo
import eu.tiducto.osmreview.server.db.SettingsRepo
import eu.tiducto.osmreview.shared.Area
import eu.tiducto.osmreview.shared.BBox
import eu.tiducto.osmreview.shared.EditorLinks
import eu.tiducto.osmreview.shared.ImagerySource
import eu.tiducto.osmreview.shared.OsmRef
import eu.tiducto.osmreview.shared.SearchHit
import eu.tiducto.osmreview.shared.SearchHitKind
import eu.tiducto.osmreview.shared.UiEvent
import eu.tiducto.osmreview.shared.WireJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.Base64
import kotlin.io.path.readText

class App(val config: Config, httpEngine: HttpClientEngine? = null) : AutoCloseable {
    private val log = LoggerFactory.getLogger(App::class.java)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val http = HttpClient(httpEngine ?: CIO.create()) {
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 15_000
        }
        expectSuccess = false
    }
    val db = Db(config.dbUrl, config.dbUser, config.dbPassword)
    val repo = DraftRepo(db)
    val settings = SettingsRepo(db)
    val events = EventBus()
    val mirror = GolMirror(config.dataDir, config.golFile)
    val queries = MirrorQueries(mirror)
    val rules = RuleEngine(mirror)
    val account = Account(config, settings, http)
    val drafts = DraftService(repo, account, mirror, queries, rules, events)
    val merge = MergeService(config, repo, drafts, account, rules, events)
    val areas = AreaPresets(config, queries)
    val search = Search(queries)
    val imagery: List<ImagerySource> = DEFAULT_IMAGERY

    val mcpToken: String = config.mcpToken ?: settings.get(MCP_TOKEN_KEY) ?: ByteArray(24)
        .also(SecureRandom()::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        .also { settings.put(MCP_TOKEN_KEY, it) }

    fun start() {
        if (mirror.reloadIfChanged()) log.info("Opened GOL {}", mirror.status().file) else log.warn(mirror.status().message)
        scope.launch {
            while (isActive) {
                delay(SWAP_POLL_MS)
                runCatching {
                    if (mirror.reloadIfChanged()) {
                        log.info("Swapped to GOL {}", mirror.status().file)
                        events.emit(UiEvent.MirrorSwapped(mirror.status()))
                    }
                }.onFailure { log.warn("GOL swap check failed", it) }
            }
        }
    }

    fun launchRecord(ruleId: String, scopeKey: String, issues: Int, tookMs: Long) {
        scope.launch { runCatching { repo.recordRuleRun(ruleId, scopeKey, issues, tookMs) } }
    }

    fun editorLinks(ref: OsmRef): EditorLinks {
        val web = config.target(account.target).webUrl
        val path = "${ref.type.apiName}/${ref.id}"
        return EditorLinks(
            iD = "$web/edit?editor=id&${ref.type.apiName}=${ref.id}",
            osm = "$web/$path",
            history = "$web/$path/history",
        )
    }

    override fun close() {
        scope.cancel()
        mirror.close()
        db.close()
        http.close()
    }

    companion object {
        private const val SWAP_POLL_MS = 15_000L
        private const val MCP_TOKEN_KEY = "mcp.token"

        val DEFAULT_IMAGERY = listOf(
            ImagerySource(
                id = "cuzk-ortofoto",
                name = "ČÚZK orthophoto",
                type = "wms",
                url = "https://ags.cuzk.gov.cz/arcgis1/services/ORTOFOTO/MapServer/WMSServer?FORMAT=image/png&TRANSPARENT=TRUE" +
                    "&VERSION=1.3.0&SERVICE=WMS&REQUEST=GetMap&LAYERS=0&STYLES=&CRS=EPSG:3857&WIDTH=256&HEIGHT=256&BBOX={bbox-epsg-3857}",
                attribution = "© ČÚZK",
                maxZoom = 20,
            ),
            ImagerySource(
                id = "cuzk-km",
                name = "ČÚZK cadastral map (overlay)",
                type = "wms",
                url = "https://wms.cuzk.cz/wms.asp?LAYERS=parcelni_cisla_i,obrazy_parcel_i,RST_KMD_I,hranice_parcel_i,DEF_BUDOVY,RST_KN_I,dalsi_p_mapy_i" +
                    "&STYLES=&FORMAT=image/png&TRANSPARENT=TRUE&CRS=EPSG:3857&WIDTH=256&HEIGHT=256&BBOX={bbox-epsg-3857}&VERSION=1.3.0&SERVICE=WMS&REQUEST=GetMap",
                attribution = "© ČÚZK",
                maxZoom = 21,
            ),
            ImagerySource(
                id = "esri-world-imagery",
                name = "Esri World Imagery",
                type = "tms",
                url = "https://server.arcgisonline.com/arcgis/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
                attribution = "Esri, Maxar, Earthstar Geographics",
                maxZoom = 22,
            ),
        )
    }
}

class AreaPresets(private val config: Config, private val queries: eu.tiducto.osmreview.rules.MirrorQueries) {
    private val names: List<String> by lazy {
        val fromFile = config.defaultAreasFile?.let { f ->
            runCatching {
                WireJson.parseToJsonElement(f.readText()).jsonObject["features"]!!.jsonArray
                    .mapNotNull { it.jsonObject["properties"]?.jsonObject?.get("name")?.jsonPrimitive?.content }
            }.getOrElse { emptyList() }
        }.orEmpty()
        (fromFile + DEFAULT_NAMES).distinct()
    }

    fun presets(): List<Area> = names.mapNotNull { n ->
        runCatching { queries.area(n)?.first?.copy(geometry = null) }.getOrNull()
    }

    private companion object {
        val DEFAULT_NAMES = listOf("Opava", "Opava-Kateřinky", "Brno", "Brno-střed", "Ostrava", "Olomouc", "Praha")
    }
}

class Search(private val queries: eu.tiducto.osmreview.rules.MirrorQueries) {
    private val idPatterns = listOf(
        Regex("""^([nwr])\s*(\d+)$""", RegexOption.IGNORE_CASE),
        Regex("""(?:^|/)(node|way|relation)[/ #]+(\d+)""", RegexOption.IGNORE_CASE),
    )

    fun search(q: String, bbox: BBox?): List<SearchHit> {
        val query = q.trim()
        if (query.isEmpty()) return emptyList()
        parseRef(query)?.let { ref ->
            val f = runCatching { queries.mapFeature(ref) }.getOrNull()
            val b = f?.let { bboxOf(it.geometry) }
            val detail = f?.tags?.let { t -> t["name"] ?: t.entries.firstOrNull { it.key in MAIN_KEYS }?.let { "${it.key}=${it.value}" } }
                ?: if (f == null) "not in the mirror" else null
            return listOf(
                SearchHit(
                    SearchHitKind.OBJECT, ref.toString(), detail, ref, b,
                    b?.let { eu.tiducto.osmreview.shared.LngLat((it.west + it.east) / 2, (it.south + it.north) / 2) },
                    listOf(ref),
                ),
            )
        }
        if ('[' in query) {
            val (found, truncated) = queries.goql(query, bbox, QUERY_LIMIT)
            return listOf(
                SearchHit(
                    SearchHitKind.QUERY, query,
                    "${found.size}${if (truncated) "+" else ""} objects" + if (bbox != null) " in view" else "",
                    refs = found.map { it.ref },
                ),
            )
        }
        val areas = queries.searchAreas(query, 6).map {
            SearchHit(SearchHitKind.AREA, it.name, "area", bbox = it.bbox, center = eu.tiducto.osmreview.shared.LngLat((it.bbox.west + it.bbox.east) / 2, (it.bbox.south + it.bbox.north) / 2))
        }
        val named = bbox?.let { b ->
            queries.searchNamed(query, b, 8).map {
                val fb = bboxOf(it.geometry)
                SearchHit(
                    SearchHitKind.OBJECT, it.tags["name"] ?: it.ref.toString(), it.ref.toString(), it.ref, fb,
                    fb?.let { x -> eu.tiducto.osmreview.shared.LngLat((x.west + x.east) / 2, (x.south + x.north) / 2) },
                    listOf(it.ref),
                )
            }
        }.orEmpty()
        return areas + named
    }

    private fun parseRef(q: String): OsmRef? {
        for (p in idPatterns) {
            val m = p.find(q) ?: continue
            val t = m.groupValues[1].lowercase()
            return OsmRef.parse("${t[0]}${m.groupValues[2]}")
        }
        return null
    }

    private fun bboxOf(g: kotlinx.serialization.json.JsonElement): BBox? {
        val pts = ArrayList<Pair<Double, Double>>()
        fun walk(e: kotlinx.serialization.json.JsonElement) {
            if (e is kotlinx.serialization.json.JsonArray) {
                if (e.size >= 2 && e[0] is kotlinx.serialization.json.JsonPrimitive) {
                    pts += e[0].jsonPrimitive.content.toDouble() to e[1].jsonPrimitive.content.toDouble()
                } else {
                    e.forEach(::walk)
                }
            }
        }
        (g as? kotlinx.serialization.json.JsonObject)?.get("coordinates")?.let(::walk)
        if (pts.isEmpty()) return null
        return BBox(pts.minOf { it.first }, pts.minOf { it.second }, pts.maxOf { it.first }, pts.maxOf { it.second })
    }

    private companion object {
        const val QUERY_LIMIT = 500
        val MAIN_KEYS = listOf("public_transport", "highway", "railway", "amenity", "shop", "building", "landuse")
    }
}
