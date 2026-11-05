package eu.tiducto.osmreview.server.core

import eu.tiducto.osmreview.server.db.DraftRepo
import eu.tiducto.osmreview.server.db.UploadedChange
import eu.tiducto.osmreview.shared.BBox
import eu.tiducto.osmreview.shared.ChangeAction
import eu.tiducto.osmreview.shared.OverlayCollection
import eu.tiducto.osmreview.shared.OverlayFeature
import eu.tiducto.osmreview.shared.OverlayProperties
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class OverlayService(
    private val repo: DraftRepo,
    private val dataTimestamp: () -> Instant?,
    private val clock: () -> Instant = Instant::now,
) {
    suspend fun overlay(bbox: BBox? = null): OverlayCollection {
        val data = dataTimestamp()
        val since = clock().minus(MAX_AGE)
        val uploaded = repo.uploadedSince(since.atOffset(ZoneOffset.UTC))
        val pending = uploaded.filter { data == null || !covered(it.uploadedAt, data) }
        val latest = pending.groupBy { it.change.ref }.mapValues { (_, list) -> list.maxBy { it.uploadedAt } }.values
        val features = latest
            .filter { bbox == null || inBox(it, bbox) }
            .sortedBy { it.uploadedAt }
            .map(::feature)
        return OverlayCollection(dataTimestamp = data?.toString(), features = features)
    }

    private fun feature(u: UploadedChange): OverlayFeature {
        val c = u.change
        val geometry = if (c.action == ChangeAction.DELETE) c.baseGeom else c.newGeom ?: c.baseGeom
        return OverlayFeature(
            id = c.ref.toString(),
            geometry = geometry,
            properties = OverlayProperties(
                ref = c.ref,
                action = c.action,
                tags = if (c.action == ChangeAction.DELETE) c.baseTags else c.newTags,
                draftId = c.draftId,
                changeId = c.id,
                changesetId = u.changesetId,
                uploadedAt = u.uploadedAt.toString(),
            ),
        )
    }

    private fun inBox(u: UploadedChange, b: BBox): Boolean {
        val p = u.change.centroid ?: return true
        return p.lng in b.west..b.east && p.lat in b.south..b.north
    }

    companion object {
        val MARGIN: Duration = Duration.ofSeconds(90)
        val MAX_AGE: Duration = Duration.ofDays(14)

        fun covered(uploadedAt: Instant, dataTimestamp: Instant): Boolean = !dataTimestamp.isBefore(uploadedAt.plus(MARGIN))
    }
}
