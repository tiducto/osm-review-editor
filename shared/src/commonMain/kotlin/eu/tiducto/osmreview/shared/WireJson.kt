package eu.tiducto.osmreview.shared

import kotlinx.serialization.json.Json

val WireJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    classDiscriminator = "type"
}
