package eu.tiducto.osmreview.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
enum class DraftStatus {
    @SerialName("open")
    OPEN,

    @SerialName("merged")
    MERGED,

    @SerialName("partially_merged")
    PARTIALLY_MERGED,

    @SerialName("closed")
    CLOSED,
}

@Serializable
enum class ChangeAction {
    @SerialName("create")
    CREATE,

    @SerialName("modify")
    MODIFY,

    @SerialName("delete")
    DELETE,
}

@Serializable
enum class Confidence {
    @SerialName("deterministic")
    DETERMINISTIC,

    @SerialName("needs_review")
    NEEDS_REVIEW,
}

@Serializable
enum class ChangeStatus {
    @SerialName("pending")
    PENDING,

    @SerialName("approved")
    APPROVED,

    @SerialName("rejected")
    REJECTED,

    @SerialName("conflict")
    CONFLICT,

    @SerialName("uploaded")
    UPLOADED,
}

@Serializable
enum class Severity {
    @SerialName("info")
    INFO,

    @SerialName("warning")
    WARNING,

    @SerialName("error")
    ERROR,
}

@Serializable
enum class ReviewDecision {
    @SerialName("approve")
    APPROVE,

    @SerialName("edit")
    EDIT,

    @SerialName("reject")
    REJECT,

    @SerialName("send_to_id")
    SEND_TO_ID,

    @SerialName("reset")
    RESET,
}

object Authors {
    const val USER = "user"
    const val CLAUDE = "claude"
    const val REMOTE = "remote"
    fun rule(ruleId: String) = "rule:$ruleId"
}

@Serializable
data class ChangeCounts(
    val total: Int = 0,
    val pending: Int = 0,
    val approved: Int = 0,
    val rejected: Int = 0,
    val conflict: Int = 0,
    val uploaded: Int = 0,
)

@Serializable
data class Draft(
    val id: String,
    val title: String,
    val description: String = "",
    val author: String,
    val scope: JsonElement? = null,
    val status: DraftStatus,
    val createdAt: String,
    val updatedAt: String,
    val counts: ChangeCounts = ChangeCounts(),
    val area: String? = null,
    val changesetComment: String? = null,
    val changesetSource: String? = null,
)

@Serializable
data class Review(
    val id: Long,
    val changeId: String,
    val decision: ReviewDecision,
    val comment: String? = null,
    val editedTags: Map<String, String>? = null,
    val createdAt: String,
    val imagery: String? = null,
)

@Serializable
data class Change(
    val id: String,
    val draftId: String,
    val ref: OsmRef,
    val action: ChangeAction,
    val baseVersion: Int? = null,
    val baseTags: Map<String, String> = emptyMap(),
    val newTags: Map<String, String> = emptyMap(),
    val diff: TagDiff,
    val baseGeom: JsonElement? = null,
    val newGeom: JsonElement? = null,
    val centroid: LngLat? = null,
    val ruleId: String? = null,
    val reason: String? = null,
    val confidence: Confidence,
    val status: ChangeStatus,
    val liveVersion: Int? = null,
    val liveTags: Map<String, String>? = null,
    val changesetId: Long? = null,
    val reviews: List<Review> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class DraftDetail(val draft: Draft, val changes: List<Change>)

@Serializable
data class Rule(
    val id: String,
    val title: String,
    val description: String = "",
    val severity: Severity,
    val confidence: Confidence,
    val hasFix: Boolean,
    val enabled: Boolean = true,
)

@Serializable
data class Issue(
    val ruleId: String,
    val ref: OsmRef,
    val tags: Map<String, String>,
    val centroid: LngLat,
    val severity: Severity,
    val fix: TagEdit? = null,
    val confidence: Confidence = Confidence.NEEDS_REVIEW,
    val context: List<OsmRef> = emptyList(),
    val note: String? = null,
)

@Serializable
data class RuleRunResult(
    val ruleId: String,
    val issueCount: Int,
    val issues: List<Issue>,
    val truncated: Boolean = false,
    val tookMs: Long,
)

@Serializable
data class Area(val name: String, val bbox: BBox, val geometry: JsonElement? = null)

@Serializable
data class MapFeature(
    val ref: OsmRef,
    val tags: Map<String, String>,
    val geometry: JsonElement,
    val version: Int? = null,
)

@Serializable
data class FeatureCollection(
    val features: List<MapFeature>,
    val truncated: Boolean = false,
)
