package eu.tiducto.osmreview.server

import eu.tiducto.osmreview.server.db.SettingsRepo
import eu.tiducto.osmreview.shared.ImagerySource
import eu.tiducto.osmreview.shared.WireJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

class ImageryStore(private val settings: SettingsRepo) {
    @Volatile
    var bing: ImagerySource? = null

    fun configured(): List<ImagerySource> {
        val stored = settings.get(KEY)?.let { runCatching { WireJson.decodeFromString(LIST, it) }.getOrNull() }
        if (stored == null) {
            save(DEFAULTS)
            return DEFAULTS
        }
        if ((settings.get(VERSION_KEY)?.toIntOrNull() ?: 1) >= VERSION) return stored
        val upgraded = upgrade(stored)
        save(upgraded)
        return upgraded
    }

    fun all(): List<ImagerySource> {
        val list = configured()
        val b = bing ?: return list
        return if (list.any { it.id == b.id }) list else list + b
    }

    fun replace(list: List<ImagerySource>): List<ImagerySource> {
        val clean = validate(list)
        save(clean)
        return clean
    }

    fun reset(): List<ImagerySource> {
        save(DEFAULTS)
        return DEFAULTS
    }

    private fun save(list: List<ImagerySource>) {
        settings.put(KEY, WireJson.encodeToString(LIST, list))
        settings.put(VERSION_KEY, VERSION.toString())
    }

    companion object {
        const val KEY = "imagery"
        const val VERSION_KEY = "imagery.version"
        const val VERSION = 2
        val TYPES = setOf("tms", "wms")
        private val LIST = ListSerializer(ImagerySource.serializer())
        private val ID = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")

        fun validate(list: List<ImagerySource>): List<ImagerySource> {
            val clean = list.map {
                it.copy(
                    id = it.id.trim(),
                    name = it.name.trim(),
                    type = it.type.trim().lowercase(),
                    url = it.url.trim(),
                    attribution = it.attribution.trim(),
                    sourceTag = it.sourceTag.trim(),
                )
            }
            val errors = buildList {
                clean.forEachIndexed { i, s ->
                    val at = "imagery[$i]${if (s.id.isNotEmpty()) " (${s.id})" else ""}"
                    if (!ID.matches(s.id)) add("$at: id must be 1-64 chars of a-z, 0-9, '.', '_', '-'")
                    if (s.name.isEmpty()) add("$at: name is required")
                    if (s.type !in TYPES) add("$at: type must be one of ${TYPES.joinToString()}")
                    if (!isHttpUrl(s.url)) add("$at: url must be an absolute http(s) URL")
                    if (s.type == "tms" && !(s.url.contains("{z}") && s.url.contains("{x}") && s.url.contains("{y}")) && !s.url.contains("{quadkey}")) {
                        add("$at: a tms url needs {z}, {x} and {y} or {quadkey}")
                    }
                    if (s.type == "wms" && !s.url.contains("{bbox-epsg-3857}")) add("$at: a wms url needs {bbox-epsg-3857}")
                    if (s.attribution.isEmpty()) add("$at: attribution is required")
                    if (s.maxZoom !in 1..24) add("$at: maxZoom must be between 1 and 24")
                    if (s.sourceTag.length > 255) add("$at: sourceTag is longer than 255 characters")
                }
                clean.groupBy { it.id }.filter { it.key.isNotEmpty() && it.value.size > 1 }.keys
                    .forEach { add("duplicate id '$it'") }
            }
            require(errors.isEmpty()) { errors.joinToString("; ") }
            return clean
        }

        private fun isHttpUrl(url: String): Boolean {
            val probe = url.replace(Regex("\\{[^}]*}"), "x")
            return runCatching {
                val u = URI(probe)
                u.scheme?.lowercase() in setOf("http", "https") && !u.host.isNullOrEmpty()
            }.getOrDefault(false)
        }

        const val DEFAULT_BACKGROUND = "cuzk-ortofoto"

        private const val ORTHO_WMS_URL =
            "https://ags.cuzk.gov.cz/arcgis1/services/ORTOFOTO/MapServer/WMSServer?FORMAT=image/png&TRANSPARENT=TRUE" +
                "&VERSION=1.3.0&SERVICE=WMS&REQUEST=GetMap&LAYERS=0&STYLES=&CRS=EPSG:3857&WIDTH=256&HEIGHT=256&BBOX={bbox-epsg-3857}"

        val ORTHO = ImagerySource(
            id = DEFAULT_BACKGROUND,
            name = "ČÚZK orthophoto",
            type = "tms",
            url = "https://ags.cuzk.gov.cz/arcgis1/rest/services/ORTOFOTO_WM/MapServer/tile/{z}/{y}/{x}",
            attribution = "© ČÚZK",
            maxZoom = 20,
            sourceTag = "ČÚZK ortofoto",
        )

        val ORTHO_WMS = ImagerySource(
            id = "cuzk-ortofoto-wms",
            name = "ČÚZK orthophoto (WMS)",
            type = "wms",
            url = ORTHO_WMS_URL,
            attribution = "© ČÚZK",
            maxZoom = 20,
            sourceTag = "ČÚZK ortofoto",
        )

        val OSM_STANDARD = ImagerySource(
            id = "osm-standard",
            name = "OpenStreetMap (Standard)",
            type = "tms",
            url = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
            attribution = "© OpenStreetMap contributors",
            maxZoom = 19,
            sourceTag = "",
        )

        val CADASTRE = ImagerySource(
            id = "cuzk-km",
            name = "ČÚZK cadastral map",
            type = "wms",
            url = "https://wms.cuzk.cz/wms.asp?LAYERS=parcelni_cisla_i,obrazy_parcel_i,RST_KMD_I,hranice_parcel_i,DEF_BUDOVY,RST_KN_I,dalsi_p_mapy_i" +
                "&STYLES=&FORMAT=image/png&TRANSPARENT=TRUE&CRS=EPSG:3857&WIDTH=256&HEIGHT=256&BBOX={bbox-epsg-3857}&VERSION=1.3.0&SERVICE=WMS&REQUEST=GetMap",
            attribution = "© ČÚZK",
            maxZoom = 21,
            sourceTag = "ČÚZK katastrální mapa",
            overlay = true,
        )

        val ESRI = ImagerySource(
            id = "esri-world-imagery",
            name = "Esri World Imagery",
            type = "tms",
            url = "https://server.arcgisonline.com/arcgis/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
            attribution = "Esri, Maxar, Earthstar Geographics",
            maxZoom = 22,
            sourceTag = "Esri World Imagery",
        )

        val DEFAULTS = listOf(ORTHO, OSM_STANDARD, ESRI, ORTHO_WMS, CADASTRE)

        fun upgrade(list: List<ImagerySource>): List<ImagerySource> {
            val out = list.map {
                when {
                    it.id == ORTHO.id && it.type == "wms" && it.url == ORTHO_WMS_URL -> ORTHO
                    it.id == CADASTRE.id && it.url == CADASTRE.url -> it.copy(name = CADASTRE.name, overlay = true)
                    else -> it
                }
            }.toMutableList()
            if (out.none { it.id == OSM_STANDARD.id }) {
                val at = out.indexOfFirst { it.id == ORTHO.id }
                out.add(if (at >= 0) at + 1 else 0, OSM_STANDARD)
            }
            if (out.any { it.id == ORTHO.id && it.url == ORTHO.url } && out.none { it.id == ORTHO_WMS.id }) {
                val at = out.indexOfLast { !it.overlay }
                out.add(at + 1, ORTHO_WMS)
            }
            return out
        }
    }
}

object BingImagery {
    const val ID = "bing"
    const val METADATA_URL = "https://dev.virtualearth.net/REST/v1/Imagery/Metadata/Aerial"
    const val ATTRIBUTION = "© Microsoft Corporation, imagery © its providers (Bing Maps)"

    suspend fun resolve(http: HttpClient, key: String): ImagerySource {
        val res = http.get(METADATA_URL) {
            parameter("key", key)
            parameter("uriScheme", "https")
            parameter("output", "json")
        }
        require(res.status.isSuccess()) { "Bing metadata request failed: ${res.status}" }
        return fromMetadata(res.bodyAsText())
    }

    fun fromMetadata(json: String): ImagerySource {
        val resource = WireJson.parseToJsonElement(json).jsonObject["resourceSets"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("resources")?.jsonArray?.firstOrNull() as? JsonObject
            ?: throw IllegalArgumentException("Bing metadata has no imagery resource")
        val template = resource["imageUrl"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("Bing metadata has no imageUrl")
        val subdomain = resource["imageUrlSubdomains"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull ?: "t0"
        val url = template
            .replace("{subdomain}", subdomain)
            .replace("{culture}", "en-US")
            .replace("http://", "https://")
        require("{quadkey}" in url) { "Bing imageUrl has no {quadkey}: $template" }
        return ImagerySource(
            id = ID,
            name = "Bing aerial imagery",
            type = "tms",
            url = url,
            attribution = ATTRIBUTION,
            maxZoom = resource["zoomMax"]?.jsonPrimitive?.intOrNull ?: 20,
            sourceTag = "Bing",
        )
    }
}
