package eu.tiducto.osmreview.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
enum class ScanStatus {
    @SerialName("running")
    RUNNING,

    @SerialName("done")
    DONE,

    @SerialName("failed")
    FAILED,
}

@Serializable
enum class ScanTrigger {
    @SerialName("manual")
    MANUAL,

    @SerialName("mirror_swap")
    MIRROR_SWAP,
}

@Serializable
data class ScanIssue(
    val id: Long,
    val rule: String,
    val file: String? = null,
    val line: Int? = null,
    val severity: Severity,
    val group: String? = null,
    val message: String,
    val ref: OsmRef,
    val centroid: LngLat,
    val fix: TagEdit? = null,
    val deleteObject: Boolean = false,
)

@Serializable
data class ScanCount(val group: String, val severity: Severity, val count: Int)

@Serializable
data class Scan(
    val id: String,
    val scope: String,
    val areaName: String? = null,
    val geometry: JsonElement? = null,
    val ruleSet: List<String>,
    val ruleSetKey: String,
    val status: ScanStatus,
    val trigger: ScanTrigger,
    val issueCount: Int = 0,
    val errors: Int = 0,
    val warnings: Int = 0,
    val infos: Int = 0,
    val tookMs: Long? = null,
    val startedAt: String,
    val finishedAt: String? = null,
    val mirrorFile: String? = null,
    val error: String? = null,
)

@Serializable
data class ScanReport(
    val scan: Scan,
    val counts: List<ScanCount>,
    val issues: List<ScanIssue>,
    val truncated: Boolean = false,
)

@Serializable
data class ScanDiff(
    val scan: String,
    val against: String? = null,
    val newCount: Int,
    val resolvedCount: Int,
    val unchangedCount: Int,
    val new: List<ScanIssue>,
    val resolved: List<ScanIssue>,
    val truncated: Boolean = false,
)

@Serializable
data class RunScanRequest(
    val area: String? = null,
    val bbox: BBox? = null,
    val polygon: JsonElement? = null,
    val files: List<String>? = null,
    val builtIn: List<String>? = null,
)

@Serializable
data class ScanDraftRequest(
    val issueIds: List<Long>? = null,
    val group: String? = null,
    val rule: String? = null,
    val title: String? = null,
    val draftId: String? = null,
)

@Serializable
data class ScanDraftResult(val draft: DraftDetail, val results: List<ProposalResult>, val skipped: Int = 0)
