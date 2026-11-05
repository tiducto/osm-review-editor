package eu.tiducto.osmreview.server.mcp

import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.header
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val MCP_SESSION_ID_HEADER = "mcp-session-id"

// The latest MCP revision the bundled SDK actually negotiates (verified: it echoes this back, but
// returns a result with `protocolVersion` OMITTED for anything newer).
private const val SERVER_MAX_PROTOCOL_VERSION = "2025-11-25"

private val compatJson = Json { ignoreUnknownKeys = true }

/**
 * Route-scoped fix for a protocol-version negotiation bug in the bundled Kotlin MCP SDK (0.15.0 and 0.16.0, the
 * latest release). On `initialize` the SDK echoes the client's requested `protocolVersion` when it is
 * supported, but for a *newer* version it returns a result with the field **omitted** instead of
 * downgrading to its own latest supported version as the spec requires. A client that advertises a
 * revision past [SERVER_MAX_PROTOCOL_VERSION] (Claude Code does) then rejects the handshake because
 * `protocolVersion` is undefined.
 *
 * We own this `/mcp` route, so we normalise the handshake before the SDK transport reads the body:
 * on the session-less `initialize` POST, clamp a newer requested version down to the version the
 * server can actually negotiate. Older/supported versions and every subsequent (session-bearing)
 * request pass through untouched. Remove once the SDK ships the negotiation fix that is on `main`.
 */
val NormalizeMcpInitialize = createRouteScopedPlugin("NormalizeMcpInitialize") {
    onCallReceive { call ->
        // `initialize` is the only POST that arrives before a session id is minted; tool calls all
        // carry `mcp-session-id`, so skip them and leave their bodies untouched.
        if (call.request.header(MCP_SESSION_ID_HEADER) != null) return@onCallReceive
        transformBody { body ->
            val text = body.readRemaining().readString()
            ByteReadChannel(clampInitializeProtocolVersion(text))
        }
    }
}

private fun clampInitializeProtocolVersion(body: String): String {
    val root = runCatching { compatJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return body
    if (root["method"]?.jsonPrimitive?.contentOrNull != "initialize") return body
    val params = root["params"] as? JsonObject ?: return body
    val requested = params["protocolVersion"]?.jsonPrimitive?.contentOrNull ?: return body
    // MCP revisions are ISO dates, so lexical order is chronological. Only a version newer than the
    // server's max triggers the SDK bug; leave supported/older ones for the SDK to negotiate.
    if (requested <= SERVER_MAX_PROTOCOL_VERSION) return body
    val patchedParams = JsonObject(params + ("protocolVersion" to JsonPrimitive(SERVER_MAX_PROTOCOL_VERSION)))
    return compatJson.encodeToString(JsonObject.serializer(), JsonObject(root + ("params" to patchedParams)))
}
