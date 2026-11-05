package eu.tiducto.osmreview.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ProposedChange(
    val ref: OsmRef,
    val edit: TagEdit,
    val reason: String? = null,
    val ruleId: String? = null,
    val confidence: Confidence = Confidence.NEEDS_REVIEW,
    val location: LngLat? = null,
    val action: ChangeAction = ChangeAction.MODIFY,
)

@Serializable
enum class ProposalOutcome {
    @SerialName("accepted")
    ACCEPTED,

    @SerialName("no_op")
    NO_OP,

    @SerialName("duplicate")
    DUPLICATE,

    @SerialName("error")
    ERROR,
}

@Serializable
data class ProposalResult(
    val ref: OsmRef,
    val outcome: ProposalOutcome,
    val changeId: String? = null,
    val message: String? = null,
    val sourceRef: OsmRef? = null,
)

@Serializable
data class OsmChangeImportRequest(
    val osmChange: String,
    val reasons: Map<String, String> = emptyMap(),
    val reason: String? = null,
    val replace: Boolean = false,
    val takeOver: Boolean = false,
)

@Serializable
data class DraftFromOsmChangeRequest(
    val title: String,
    val osmChange: String,
    val description: String = "",
    val area: String? = null,
    val changesetComment: String? = null,
    val changesetSource: String? = null,
    val reasons: Map<String, String> = emptyMap(),
    val reason: String? = null,
    val takeOver: Boolean = false,
)

@Serializable
data class OsmChangeImportResult(val draft: DraftDetail, val results: List<ProposalResult>)

@Serializable
data class CreateDraftRequest(
    val title: String,
    val description: String = "",
    val scope: JsonElement? = null,
    val area: String? = null,
    val changesetComment: String? = null,
    val changesetSource: String? = null,
)

@Serializable
data class DraftFromRuleRequest(
    val ruleId: String,
    val area: String? = null,
    val bbox: BBox? = null,
    val title: String? = null,
    val limit: Int? = null,
    val polygon: JsonElement? = null,
)

@Serializable
data class RuleIssuesRequest(
    val area: String? = null,
    val bbox: BBox? = null,
    val polygon: JsonElement? = null,
    val limit: Int? = null,
)

@Serializable
data class ProposeChangesRequest(val changes: List<ProposedChange>)

@Serializable
data class ReviewRequest(
    val decision: ReviewDecision,
    val comment: String? = null,
    val tags: Map<String, String>? = null,
    val imagery: String? = null,
    val cascade: Boolean = false,
)

@Serializable
data class ResolveConflictRequest(val tags: Map<String, String>, val useLiveGeometry: Boolean = false)

@Serializable
data class ManualEditRequest(
    val ref: OsmRef,
    val tags: Map<String, String>,
    val reason: String? = null,
    val baseTags: Map<String, String>? = null,
)

@Serializable
data class ChangesetGroup(
    val key: String,
    val comment: String,
    val tags: Map<String, String>,
    val changeIds: List<String>,
)

@Serializable
data class MergePreview(
    val draftId: String,
    val groups: List<ChangesetGroup>,
    val conflicts: List<String>,
    val target: OsmTarget,
    val blocked: List<BlockedChange> = emptyList(),
)

@Serializable
data class BlockedChange(val changeId: String, val ref: OsmRef, val message: String)

@Serializable
enum class OsmTarget {
    @SerialName("dev")
    DEV,

    @SerialName("prod")
    PROD,
}

@Serializable
data class MergeProgress(
    val draftId: String,
    val group: String,
    val done: Int,
    val total: Int,
    val changesetId: Long? = null,
    val message: String,
    val finished: Boolean = false,
    val failed: Boolean = false,
)

@Serializable
data class Upload(
    val id: Long,
    val draftId: String,
    val changesetId: Long? = null,
    val comment: String,
    val changeIds: List<String>,
    val status: String,
    val error: String? = null,
    val createdAt: String,
)

@Serializable
data class AccountStatus(
    val target: OsmTarget,
    val connected: Boolean,
    val displayName: String? = null,
    val configured: Boolean,
)

@Serializable
data class MirrorStatus(
    val available: Boolean,
    val file: String? = null,
    val openedAt: String? = null,
    val dataTimestamp: String? = null,
    val message: String? = null,
    val sidecar: SidecarStatus? = null,
)

@Serializable
data class SidecarStatus(
    val lastRunAt: String? = null,
    val lastRunKind: String? = null,
    val result: String? = null,
    val error: String? = null,
    val lastFullBuildAt: String? = null,
    val nextFullBuildDue: String? = null,
    val replicationSequence: Long? = null,
    val replicationTimestamp: String? = null,
    val current: String? = null,
)

@Serializable
data class AppStatus(
    val version: String,
    val mirror: MirrorStatus,
    val account: AccountStatus,
    val mcpUrl: String,
    val remoteControlPort: Int,
)

@Serializable
data class ImagerySource(
    val id: String,
    val name: String,
    val type: String,
    val url: String,
    val attribution: String = "",
    val maxZoom: Int = 20,
    val sourceTag: String = "",
)

@Serializable
sealed interface UiEvent {
    @Serializable
    @SerialName("remote_command")
    data class RemoteCommand(
        val command: String,
        val bbox: BBox? = null,
        val select: List<OsmRef> = emptyList(),
        val draftId: String? = null,
        val imagery: ImagerySource? = null,
        val draftIds: List<String> = emptyList(),
        val message: String? = null,
    ) : UiEvent

    @Serializable
    @SerialName("draft_updated")
    data class DraftUpdated(val draftId: String) : UiEvent

    @Serializable
    @SerialName("merge_progress")
    data class MergeProgressEvent(val progress: MergeProgress) : UiEvent

    @Serializable
    @SerialName("mirror_swapped")
    data class MirrorSwapped(val status: MirrorStatus) : UiEvent

    @Serializable
    @SerialName("rules_changed")
    data class RulesChanged(val changed: List<String> = emptyList(), val removed: List<String> = emptyList()) : UiEvent
}

@Serializable
data class ApiError(
    val error: String,
    val detail: String? = null,
    val code: String? = null,
    val openChanges: List<OpenChangeElsewhere>? = null,
)

@Serializable
data class OpenChangeElsewhere(
    val ref: OsmRef,
    val changeId: String,
    val status: ChangeStatus,
    val draftId: String,
    val draftTitle: String,
)

object ApiErrorCodes {
    const val OPEN_CHANGE_ELSEWHERE = "open_change_elsewhere"
    const val AREA_TOO_LARGE = "area_too_large"
    const val OSM_API_ERROR = "osm_api_error"
}

@Serializable
data class UpdateDraftRequest(
    val title: String? = null,
    val description: String? = null,
    val status: DraftStatus? = null,
    val changesetComment: String? = null,
    val changesetSource: String? = null,
)

@Serializable
data class SetTargetRequest(val target: OsmTarget, val confirm: Boolean = false)

@Serializable
enum class SearchHitKind {
    @SerialName("area")
    AREA,

    @SerialName("object")
    OBJECT,

    @SerialName("query")
    QUERY,
}

@Serializable
data class SearchHit(
    val kind: SearchHitKind,
    val label: String,
    val detail: String? = null,
    val ref: OsmRef? = null,
    val bbox: BBox? = null,
    val center: LngLat? = null,
    val refs: List<OsmRef> = emptyList(),
)

@Serializable
data class EditorLinks(val iD: String, val osm: String, val history: String)

@Serializable
data class McpInfo(val url: String, val token: String, val claudeCodeCommand: String)
