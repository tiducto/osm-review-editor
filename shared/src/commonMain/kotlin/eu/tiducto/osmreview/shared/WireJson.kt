package eu.tiducto.osmreview.shared

import kotlinx.serialization.json.Json

/**
 * The one JSON configuration for the REST API, WebSocket and MCP: defaults are always encoded, so
 * every response carries every field (the generated TS types mark response fields required).
 */
val WireJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    classDiscriminator = "type"
}
