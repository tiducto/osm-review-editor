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

private const val SERVER_MAX_PROTOCOL_VERSION = "2025-11-25"

private val compatJson = Json { ignoreUnknownKeys = true }

val NormalizeMcpInitialize = createRouteScopedPlugin("NormalizeMcpInitialize") {
    onCallReceive { call ->
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
    if (requested <= SERVER_MAX_PROTOCOL_VERSION) return body
    val patchedParams = JsonObject(params + ("protocolVersion" to JsonPrimitive(SERVER_MAX_PROTOCOL_VERSION)))
    return compatJson.encodeToString(JsonObject.serializer(), JsonObject(root + ("params" to patchedParams)))
}
