package eu.tiducto.osmreview.server.db

import eu.tiducto.osmreview.shared.WireJson
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

private val tagsSerializer = MapSerializer(String.serializer(), String.serializer())

object DraftsTable : Table("app.drafts") {
    val id = javaUUID("id")
    val title = text("title")
    val description = text("description")
    val author = text("author")
    val scopeGeom = jsonb("scope_geom", WireJson, JsonElement.serializer()).nullable()
    val status = text("status")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object ChangesTable : Table("app.changes") {
    val id = javaUUID("id")
    val draftId = javaUUID("draft_id").references(DraftsTable.id)
    val osmType = text("osm_type")
    val osmId = long("osm_id")
    val action = text("action")
    val baseVersion = integer("base_version").nullable()
    val baseTags = jsonb("base_tags", WireJson, tagsSerializer)
    val newTags = jsonb("new_tags", WireJson, tagsSerializer)
    val baseGeom = jsonb("base_geom", WireJson, JsonElement.serializer()).nullable()
    val newGeom = jsonb("new_geom", WireJson, JsonElement.serializer()).nullable()
    val centroidLon = double("centroid_lon").nullable()
    val centroidLat = double("centroid_lat").nullable()
    val ruleId = text("rule_id").nullable()
    val reason = text("reason").nullable()
    val confidence = text("confidence")
    val status = text("status")
    val liveVersion = integer("live_version").nullable()
    val liveTags = jsonb("live_tags", WireJson, tagsSerializer).nullable()
    val changesetId = long("changeset_id").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object ReviewsTable : Table("app.reviews") {
    val id = long("id").autoIncrement()
    val changeId = javaUUID("change_id").references(ChangesTable.id)
    val decision = text("decision")
    val comment = text("comment").nullable()
    val editedTags = jsonb("edited_tags", WireJson, tagsSerializer).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

object UploadsTable : Table("app.uploads") {
    val id = long("id").autoIncrement()
    val draftId = javaUUID("draft_id").references(DraftsTable.id)
    val changesetId = long("changeset_id").nullable()
    val comment = text("comment")
    val changeIds = array<java.util.UUID>("change_ids")
    val status = text("status")
    val error = text("error").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

object RuleRunsTable : Table("app.rule_runs") {
    val id = long("id").autoIncrement()
    val ruleId = text("rule_id")
    val scopeKey = text("scope_key")
    val scopeGeom = jsonb("scope_geom", WireJson, JsonElement.serializer()).nullable()
    val issueCount = integer("issue_count")
    val tookMs = long("took_ms")
    val ranAt = timestampWithTimeZone("ran_at")
    override val primaryKey = PrimaryKey(id)
}

object SettingsTable : Table("app.settings") {
    val key = text("key")
    val value = text("value")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(key)
}
