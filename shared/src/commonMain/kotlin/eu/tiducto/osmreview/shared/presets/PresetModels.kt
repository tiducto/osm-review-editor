package eu.tiducto.osmreview.shared.presets

import eu.tiducto.osmreview.shared.DraftDetail
import eu.tiducto.osmreview.shared.OsmRef
import eu.tiducto.osmreview.shared.ProposalResult
import eu.tiducto.osmreview.shared.TagEdit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class PresetGeometry {
    @SerialName("point")
    POINT,

    @SerialName("vertex")
    VERTEX,

    @SerialName("line")
    LINE,

    @SerialName("area")
    AREA,

    @SerialName("relation")
    RELATION,
    ;

    val wireName: String get() = name.lowercase()

    companion object {
        fun fromWireName(name: String): PresetGeometry = entries.firstOrNull { it.wireName == name.lowercase() }
            ?: throw IllegalArgumentException("Unknown geometry '$name'; use one of ${entries.joinToString { it.wireName }}")
    }
}

@Serializable
enum class PresetApplyMode {
    @SerialName("merge")
    MERGE,

    @SerialName("replace")
    REPLACE,

    @SerialName("create")
    CREATE,
}

@Serializable
enum class PresetOrigin {
    @SerialName("id")
    ID,

    @SerialName("user")
    USER,
}

@Serializable
data class Preset(
    val id: String,
    val name: String? = null,
    val geometry: List<PresetGeometry>? = null,
    val tags: Map<String, String>? = null,
    val addTags: Map<String, String>? = null,
    val removeTags: Map<String, String>? = null,
    val fields: List<String>? = null,
    val moreFields: List<String>? = null,
    val icon: String? = null,
    val terms: List<String>? = null,
    val aliases: List<String>? = null,
    val matchScore: Double? = null,
    val searchable: Boolean? = null,
    val extends: String? = null,
    val apply: PresetApplyMode? = null,
)

@Serializable
data class PresetField(
    val id: String,
    val key: String? = null,
    val keys: List<String> = emptyList(),
    val type: String = "text",
    val options: List<String> = emptyList(),
    val universal: Boolean = false,
    val default: String? = null,
)

@Serializable
data class PresetFieldInfo(
    val id: String,
    val key: String? = null,
    val keys: List<String> = emptyList(),
    val type: String,
    val label: String,
    val placeholder: String? = null,
    val options: List<String> = emptyList(),
    val optionLabels: Map<String, String> = emptyMap(),
)

@Serializable
data class ResolvedPreset(
    val id: String,
    val name: String,
    val geometry: List<PresetGeometry>,
    val tags: Map<String, String>,
    val addTags: Map<String, String>,
    val removeTags: Map<String, String>,
    val fields: List<String>,
    val moreFields: List<String>,
    val icon: String? = null,
    val terms: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
    val matchScore: Double = 1.0,
    val searchable: Boolean = true,
    val extends: String? = null,
    val apply: PresetApplyMode = PresetApplyMode.MERGE,
    val origin: PresetOrigin = PresetOrigin.ID,
    val file: String? = null,
    val locale: String = "en",
    val fieldInfo: List<PresetFieldInfo> = emptyList(),
)

@Serializable
data class PresetSummary(
    val id: String,
    val name: String,
    val geometry: List<PresetGeometry>,
    val icon: String? = null,
    val origin: PresetOrigin,
    val extends: String? = null,
    val file: String? = null,
)

@Serializable
data class PresetMatchRequest(
    val tags: Map<String, String>,
    val geometry: PresetGeometry,
    val locale: String? = null,
)

@Serializable
data class PresetMatch(val preset: ResolvedPreset, val score: Double)

@Serializable
data class PresetApplyRequest(
    val presetId: String,
    val tags: Map<String, String> = emptyMap(),
    val geometry: PresetGeometry? = null,
    val fromPresetId: String? = null,
)

@Serializable
data class PresetApplyResult(val presetId: String, val edit: TagEdit, val tags: Map<String, String>)

@Serializable
data class PresetBulkApplyRequest(
    val presetId: String,
    val refs: List<OsmRef>,
    val dryRun: Boolean = true,
    val title: String? = null,
    val draftId: String? = null,
    val reason: String? = null,
    val area: String? = null,
    val locale: String? = null,
)

@Serializable
data class PresetBulkSkip(val ref: OsmRef, val message: String)

@Serializable
data class PresetBulkItem(
    val ref: OsmRef,
    val name: String? = null,
    val fromPresetId: String? = null,
    val before: Map<String, String>,
    val after: Map<String, String>,
    val summary: String,
)

@Serializable
data class PresetBulkApplyResult(
    val presetId: String,
    val presetName: String,
    val total: Int,
    val changed: Int,
    val unchanged: Int,
    val skipped: List<PresetBulkSkip>,
    val preview: List<PresetBulkItem>,
    val truncated: Boolean,
    val draft: DraftDetail? = null,
    val results: List<ProposalResult> = emptyList(),
)

@Serializable
data class ImportPresetRequest(
    val presetId: String,
    val id: String? = null,
    val path: String? = null,
)

@Serializable
data class DetachPresetRequest(val id: String? = null, val path: String? = null)

@Serializable
data class IdSchemaInfo(
    val version: String,
    val locales: List<String>,
    val files: List<String>,
    val baseUrl: String,
)
