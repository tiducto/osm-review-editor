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
    val dataSequence: Long? = null,
    val dataSource: String? = null,
    val lagSeconds: Long? = null,
    val replication: ReplicationStatus? = null,
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
    val phase: String? = null,
    val cycleStartedAt: String? = null,
    val lastCycleSeconds: Long? = null,
    val nextCycleAt: String? = null,
    val availableSequence: Long? = null,
    val dataTimestamp: String? = null,
    val lagSeconds: Long? = null,
    val intervalSeconds: Long? = null,
)

@Serializable
data class ReplicationStatus(
    val enabled: Boolean,
    val phase: String = "idle",
    val source: String? = null,
    val area: String? = null,
    val sequence: Long? = null,
    val timestamp: String? = null,
    val lagSeconds: Long? = null,
    val firstSequence: Long? = null,
    val sourceSequence: Long? = null,
    val backlog: Long? = null,
    val lastRunAt: String? = null,
    val lastRunSeconds: Double? = null,
    val lastRunSequences: Int = 0,
    val keptLastRun: Int = 0,
    val droppedLastRun: Int = 0,
    val keptTotal: Long = 0,
    val droppedTotal: Long = 0,
    val nextRunAt: String? = null,
    val rebasedAt: String? = null,
    val error: String? = null,
)

@Serializable
data class OverlayProperties(
    val ref: OsmRef,
    val action: ChangeAction,
    val tags: Map<String, String> = emptyMap(),
    val draftId: String,
    val changeId: String,
    val changesetId: Long? = null,
    val uploadedAt: String,
)

@Serializable
data class OverlayFeature(
    val type: String = "Feature",
    val id: String,
    val geometry: JsonElement? = null,
    val properties: OverlayProperties,
)

@Serializable
data class OverlayCollection(
    val type: String = "FeatureCollection",
    val dataTimestamp: String? = null,
    val features: List<OverlayFeature> = emptyList(),
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
    @SerialName("mirror_status")
    data class MirrorStatusChanged(val status: MirrorStatus) : UiEvent

    @Serializable
    @SerialName("rules_changed")
    data class RulesChanged(val changed: List<String> = emptyList(), val removed: List<String> = emptyList()) : UiEvent

    @Serializable
    @SerialName("scan_finished")
    data class ScanFinished(val scanId: String, val scope: String, val issueCount: Int) : UiEvent

    @Serializable
    @SerialName("push_view")
    data class PushView(
        val bbox: BBox? = null,
        val center: LngLat? = null,
        val zoom: Double? = null,
        val areaName: String? = null,
        val highlights: List<String> = emptyList(),
        val replaceHighlights: Boolean = false,
        val scanId: String? = null,
        val draftId: String? = null,
        val select: List<OsmRef> = emptyList(),
        val message: String? = null,
    ) : UiEvent

    @Serializable
    @SerialName("remote_confirm")
    data class RemoteConfirm(val request: RemoteRequest) : UiEvent

    @Serializable
    @SerialName("remote_confirm_resolved")
    data class RemoteConfirmResolved(val id: String, val accepted: Boolean) : UiEvent
}

@Serializable
data class RemoteRequest(
    val id: String,
    val command: String,
    val url: String? = null,
    val host: String? = null,
    val tags: Map<String, String> = emptyMap(),
    val objects: List<OsmRef> = emptyList(),
    val location: LngLat? = null,
    val title: String? = null,
    val objectCount: Int? = null,
    val changesetComment: String? = null,
    val changesetSource: String? = null,
    val createdAt: String,
    val expiresAt: String,
)

@Serializable
data class RemoteDecision(val accept: Boolean)

@Serializable
data class ApiError(
    val error: String,
    val detail: String? = null,
    val code: String? = null,
    val openChanges: List<OpenChangeElsewhere>? = null,
    val refs: List<OsmRef>? = null,
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
    const val ORPHAN_NODE = "orphan_node"
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
