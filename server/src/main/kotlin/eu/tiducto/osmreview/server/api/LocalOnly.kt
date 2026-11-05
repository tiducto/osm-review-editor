package eu.tiducto.osmreview.server.api

import eu.tiducto.osmreview.shared.ApiError
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.host
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respond

/**
 * The UI port has no login (single-user, local). Two guards keep other websites out:
 * - Host must be a local name or the configured public host (stops DNS rebinding).
 * - State-changing requests carrying an Origin must come from that same host (stops CSRF, including
 *   body-less POSTs such as /merge that skip the CORS preflight).
 * MCP has its own bearer token; JOSM remote control lives on its own port and only creates drafts.
 */
class LocalOnlyConfig {
    var allowedHosts: Set<String> = setOf("localhost", "127.0.0.1", "[::1]")
}

val LocalOnly = createRouteScopedPlugin("LocalOnly", ::LocalOnlyConfig) {
    val hosts = pluginConfig.allowedHosts
    onCall { call ->
        val host = call.request.host()
        if (host !in hosts) {
            call.respond(HttpStatusCode.Forbidden, ApiError("Forbidden", "Unexpected Host '$host'; set PUBLIC_URL to the address you use"))
            return@onCall
        }
        val origin = call.request.headers["Origin"] ?: return@onCall
        if (call.request.httpMethod == HttpMethod.Get || call.request.httpMethod == HttpMethod.Head) return@onCall
        val originHost = runCatching { Url(origin).host }.getOrNull()
        if (originHost == null || originHost !in hosts) {
            call.respond(HttpStatusCode.Forbidden, ApiError("Forbidden", "Cross-site request from $origin refused"))
        }
    }
}
