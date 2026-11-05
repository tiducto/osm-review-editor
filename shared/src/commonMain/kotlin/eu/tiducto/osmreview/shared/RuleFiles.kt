package eu.tiducto.osmreview.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class RuleKind(val pattern: String) {
    @SerialName("highlight")
    HIGHLIGHT("highlights/*.mapcss"),

    @SerialName("check")
    CHECK("checks/*.validator.mapcss"),

    @SerialName("preset")
    PRESET("presets/*.json"),

    @SerialName("smart_preset")
    SMART_PRESET("presets/smart/*.json"),

    @SerialName("sources")
    SOURCES("sources.json"),
    ;

    val wireName: String get() = name.lowercase()

    val extension: String get() = when (this) {
        HIGHLIGHT -> ".mapcss"
        CHECK -> ".validator.mapcss"
        PRESET, SMART_PRESET, SOURCES -> ".json"
    }

    companion object {
        const val SOURCES_FILE = "sources.json"
        const val MAX_NAME_LENGTH = 128
        private val NAME = Regex("^[A-Za-z0-9_][A-Za-z0-9_.-]*$")

        fun fromWireName(name: String): RuleKind = entries.firstOrNull { it.wireName == name.lowercase() }
            ?: throw IllegalArgumentException("Unknown rule kind '$name'; use one of ${entries.joinToString { it.wireName }}")

        fun forPath(path: String): RuleKind? {
            if (path == SOURCES_FILE) return SOURCES
            val parts = path.split('/')
            val file = parts.last()
            if (file.length > MAX_NAME_LENGTH || !NAME.matches(file)) return null
            return when {
                parts.size == 2 && parts[0] == "highlights" && file.endsWith(".mapcss") && !file.endsWith(".validator.mapcss") &&
                    file.length > ".mapcss".length -> HIGHLIGHT
                parts.size == 2 && parts[0] == "checks" && file.endsWith(".validator.mapcss") &&
                    file.length > ".validator.mapcss".length -> CHECK
                parts.size == 2 && parts[0] == "presets" && file.endsWith(".json") && file.length > ".json".length -> PRESET
                parts.size == 3 && parts[0] == "presets" && parts[1] == "smart" && file.endsWith(".json") &&
                    file.length > ".json".length -> SMART_PRESET
                else -> null
            }
        }

        val allowedPatterns: String get() = entries.joinToString(", ") { it.pattern }
    }
}

@Serializable
enum class DiagnosticSeverity {
    @SerialName("error")
    ERROR,

    @SerialName("warning")
    WARNING,

    @SerialName("info")
    INFO,
}

@Serializable
data class RuleDiagnostic(
    val file: String,
    val line: Int,
    val column: Int,
    val severity: DiagnosticSeverity,
    val message: String,
)

fun List<RuleDiagnostic>.hasErrors(): Boolean = any { it.severity == DiagnosticSeverity.ERROR }

@Serializable
data class RuleSource(
    val name: String,
    val url: String,
    val kind: RuleKind,
    val refreshHours: Int = 24,
    val licence: String? = null,
)

@Serializable
data class RuleOrigin(
    val source: String,
    val url: String,
    val licence: String,
    val fetchedAt: String? = null,
    val error: String? = null,
    val builtIn: Boolean = false,
)

@Serializable
data class RuleFileInfo(
    val path: String,
    val kind: RuleKind,
    val hash: String,
    val modifiedAt: String,
    val size: Long,
    val readOnly: Boolean = false,
    val diagnostics: List<RuleDiagnostic> = emptyList(),
    val origin: RuleOrigin? = null,
)

@Serializable
data class RuleFileList(val rulesDir: String, val files: List<RuleFileInfo>)

@Serializable
data class RuleFileContent(val file: RuleFileInfo, val content: String)

@Serializable
data class WriteRuleFileRequest(
    val content: String,
    val force: Boolean = false,
    val expectedHash: String? = null,
)

@Serializable
data class CreateRuleFileRequest(
    val path: String,
    val content: String,
    val force: Boolean = false,
)

@Serializable
data class CopyRuleFileRequest(
    val path: String,
    val to: String? = null,
)

@Serializable
data class ValidateRuleFileRequest(val path: String, val content: String)

@Serializable
data class ValidateRuleFileResult(
    val path: String,
    val kind: RuleKind,
    val valid: Boolean,
    val diagnostics: List<RuleDiagnostic>,
)

@Serializable
data class WriteRuleFileResult(
    val saved: Boolean,
    val file: RuleFileInfo? = null,
    val diagnostics: List<RuleDiagnostic> = emptyList(),
)

@Serializable
data class DeleteRuleFileResult(val path: String, val trashedTo: String)
