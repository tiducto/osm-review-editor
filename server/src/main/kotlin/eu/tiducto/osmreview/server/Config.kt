package eu.tiducto.osmreview.server

import eu.tiducto.osmreview.shared.OsmTarget
import java.nio.file.Path

data class OsmTargetConfig(
    val target: OsmTarget,
    val webUrl: String,
    val apiUrl: String,
    val clientId: String?,
    val clientSecret: String?,
) {
    val isConfigured: Boolean get() = !clientId.isNullOrBlank()
}

data class Config(
    val version: String,
    val port: Int,
    val remoteControlPort: Int,
    val publicUrl: String,
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val dataDir: Path,
    val golFile: Path?,
    val initialTarget: OsmTarget,
    val dev: OsmTargetConfig,
    val prod: OsmTargetConfig,
    val tokenEncryptionKey: String?,
    val mcpToken: String?,
    val overpassUrl: String,
    val defaultAreasFile: Path?,
    val contact: String,
    val bingKey: String? = null,
    val rulesDir: Path = Path.of("user-rules"),
) {
    fun target(t: OsmTarget) = if (t == OsmTarget.PROD) prod else dev

    val userAgent: String get() = "OSMReviewEditor/$version (+$contact)"

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            fun get(k: String) = env[k]?.takeIf { it.isNotBlank() }
            val version = Config::class.java.getResource("/version.txt")?.readText()?.trim() ?: "dev"
            return Config(
                version = version,
                port = get("PORT")?.toInt() ?: 3000,
                remoteControlPort = get("REMOTE_CONTROL_PORT")?.toInt() ?: 8111,
                publicUrl = (get("PUBLIC_URL") ?: "http://localhost:3000").trimEnd('/'),
                dbUrl = get("DB_URL") ?: "jdbc:postgresql://localhost:5432/osmreview",
                dbUser = get("DB_USER") ?: "osmreview",
                dbPassword = get("DB_PASSWORD") ?: "osmreview",
                dataDir = Path.of(get("DATA_DIR") ?: "data"),
                golFile = get("GOL_FILE")?.let(Path::of),
                initialTarget = if (get("OSM_TARGET") == "prod") OsmTarget.PROD else OsmTarget.DEV,
                dev = OsmTargetConfig(
                    OsmTarget.DEV,
                    webUrl = "https://master.apis.dev.openstreetmap.org",
                    apiUrl = "https://master.apis.dev.openstreetmap.org/api/0.6",
                    clientId = get("OSM_CLIENT_ID_DEV"),
                    clientSecret = get("OSM_CLIENT_SECRET_DEV"),
                ),
                prod = OsmTargetConfig(
                    OsmTarget.PROD,
                    webUrl = "https://www.openstreetmap.org",
                    apiUrl = "https://api.openstreetmap.org/api/0.6",
                    clientId = get("OSM_CLIENT_ID_PROD"),
                    clientSecret = get("OSM_CLIENT_SECRET_PROD"),
                ),
                tokenEncryptionKey = get("TOKEN_ENCRYPTION_KEY"),
                mcpToken = get("MCP_TOKEN"),
                overpassUrl = get("OVERPASS_URL") ?: "https://overpass-api.de/api/interpreter",
                defaultAreasFile = get("DEFAULT_AREAS")?.let(Path::of),
                contact = get("CONTACT") ?: "https://github.com/tiducto/osm-review-editor",
                bingKey = get("BING_KEY"),
                rulesDir = Path.of(get("RULES_DIR") ?: "user-rules"),
            )
        }
    }
}
