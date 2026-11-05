package eu.tiducto.osmreview.server.mcp

import eu.tiducto.osmreview.server.App
import eu.tiducto.osmreview.shared.RuleFileContent
import eu.tiducto.osmreview.shared.RuleFileInfo
import eu.tiducto.osmreview.shared.RuleKind
import eu.tiducto.osmreview.shared.ValidateRuleFileResult
import eu.tiducto.osmreview.shared.WireJson
import eu.tiducto.osmreview.shared.WriteRuleFileResult
import eu.tiducto.osmreview.shared.presets.DetachPresetRequest
import eu.tiducto.osmreview.shared.presets.ImportPresetRequest
import eu.tiducto.osmreview.shared.presets.PresetGeometry
import eu.tiducto.osmreview.shared.presets.PresetMatch
import eu.tiducto.osmreview.shared.presets.PresetSummary
import eu.tiducto.osmreview.shared.presets.ResolvedPreset
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private val KINDS = RuleKind.entries.map { it.wireName }
private val GEOMETRIES = PresetGeometry.entries.map { it.wireName }

internal fun registerRuleFileTools(server: Server, app: App) {
    val rules = app.ruleFiles
    val presets = app.presets

    server.tool(
        "list_rule_files",
        "Files in the user's rules folder (${RuleKind.allowedPatterns}), read-only files from remote sources (remote/...) and " +
            "read-only built-in highlights, checks and smart presets (origin.builtIn; a user file with the same path overrides them), " +
            "with kind, hash and diagnostics.",
        schema { enum("kind", "Only files of this kind", KINDS) },
        readOnly = true,
    ) { args ->
        val kind = args.str("kind")?.let(RuleKind::fromWireName)
        okJson(
            buildJsonObject {
                put("rules_dir", rules.root.toString())
                put("files", WireJson.encodeToJsonElement(ListSerializer(RuleFileInfo.serializer()), rules.list(kind)))
            },
        )
    }

    server.tool(
        "read_rule_file",
        "Content, hash and diagnostics of one rules file. Pass the hash as expected_hash to write_rule_file to avoid overwriting edits made meanwhile.",
        schema(listOf("path")) { str("path", "Path relative to the rules folder, e.g. presets/sidewalk-cz.json") },
        readOnly = true,
    ) { args -> ok(rules.read(args.str("path")!!), RuleFileContent.serializer()) }

    server.tool(
        "validate_rule_file",
        "Check content for a rules file without saving it. Returns diagnostics with line and column.",
        schema(listOf("path", "content")) {
            str("path", "Path relative to the rules folder; its kind decides how the content is checked")
            str("content", "Full file content")
        },
        readOnly = true,
    ) { args -> ok(rules.validate(args.str("path")!!, args.str("content")!!), ValidateRuleFileResult.serializer()) }

    server.tool(
        "write_rule_file",
        "Create or replace a rules file (atomic write). The content is validated first; with errors nothing is written and the " +
            "diagnostics are returned as an error, unless force=true. Paths: ${RuleKind.allowedPatterns}.",
        schema(listOf("path", "content")) {
            str("path", "Path relative to the rules folder")
            str("content", "Full file content")
            bool("force", "Write even when validation finds errors")
            str("expected_hash", "Hash from read_rule_file; the write fails if the file changed since")
        },
    ) { args ->
        val r = rules.write(args.str("path")!!, args.str("content")!!, args.bool("force") == true, args.str("expected_hash"))
        writeResult(r)
    }

    server.tool(
        "copy_rule_file",
        "Copy a built-in or remote (remote/...) rules file into the user's rules folder so it can be edited. A copy of a built-in " +
            "file keeps its path and overrides it; a remote file goes to the folder of its kind. Fails when the target already exists.",
        schema(listOf("path")) {
            str("path", "Path of the file to copy, e.g. checks/surfaces.validator.mapcss or remote/foo.validator.mapcss")
            str("to", "Target path relative to the rules folder (default: the same path for built-in files, <kind folder>/<name> for remote files)")
        },
    ) { args -> writeResult(rules.copy(args.str("path")!!, args.str("to"))) }

    server.tool(
        "delete_rule_file",
        "Move a rules file to the folder's .trash/<timestamp>/ (recoverable by hand). Built-in files cannot be deleted; deleting a local file " +
            "with the same path as a built-in one restores the built-in.",
        schema(listOf("path")) { str("path", "Path relative to the rules folder") },
    ) { args ->
        val r = rules.delete(args.str("path")!!)
        okJson(
            buildJsonObject {
                put("path", r.path)
                put("trashed_to", r.trashedTo)
            },
        )
    }

    server.tool(
        "search_presets",
        "Search iD presets and the user's presets by name, terms and aliases (diacritics-insensitive).",
        schema(listOf("query")) {
            str("query", "Search text, e.g. 'chodník' or 'bus stop'")
            str("locale", "en or cs (default en)")
            enum("geometry", "Only presets for this geometry", GEOMETRIES)
            int("limit", "Max results (default 20)")
        },
        readOnly = true,
    ) { args ->
        val r = presets.search(args.str("query")!!, args.str("locale"), args.str("geometry")?.let(PresetGeometry::fromWireName), args.int("limit") ?: 20)
        ok(r, ListSerializer(PresetSummary.serializer()))
    }

    server.tool(
        "get_preset",
        "One preset fully resolved: overlays applied, {presetId} references expanded, localised name, fields with labels.",
        schema(listOf("id")) {
            str("id", "Preset id, e.g. highway/footway/sidewalk or custom/sidewalk-cz")
            str("locale", "en or cs (default en)")
        },
        readOnly = true,
    ) { args -> ok(presets.get(args.str("id")!!, args.str("locale")), ResolvedPreset.serializer()) }

    server.tool(
        "match_preset",
        "The best preset for an OSM object's tags and geometry, with iD's scoring (exact tag, '*' wildcard at half score, matchScore).",
        schema(listOf("tags", "geometry")) {
            putJsonObject("tags") {
                put("type", "object")
                put("description", "The object's tags")
            }
            enum("geometry", "point (standalone node), vertex (node on a way), line, area or relation", GEOMETRIES)
            str("locale", "en or cs (default en)")
        },
        readOnly = true,
    ) { args ->
        val tags = (args["tags"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.contentOrNull.orEmpty() }
            ?: return@tool err("tags must be an object of key to value")
        ok(presets.match(tags, PresetGeometry.fromWireName(args.str("geometry")!!), args.str("locale")), PresetMatch.serializer())
    }

    server.tool(
        "import_preset",
        "Create an overlay file presets/<name>.json for an iD preset ({id, extends, apply}); edit it with write_rule_file to change keys.",
        schema(listOf("preset_id")) {
            str("preset_id", "The iD (or user) preset to extend")
            str("id", "Id of the new preset (default custom/<preset_id>)")
            str("path", "File path (default presets/<id with dashes>.json)")
        },
    ) { args -> writeResult(presets.import(ImportPresetRequest(args.str("preset_id")!!, args.str("id"), args.str("path")))) }

    server.tool(
        "detach_preset",
        "Turn an overlay preset into a full copy: inherited keys are written out and extends is removed.",
        schema {
            str("id", "Id of the overlay preset")
            str("path", "Or the file path when it holds one preset")
        },
    ) { args -> writeResult(presets.detach(DetachPresetRequest(args.str("id"), args.str("path")))) }
}

private fun writeResult(r: WriteRuleFileResult) = if (r.saved) {
    ok(r, WriteRuleFileResult.serializer())
} else {
    err("Not written: the content has errors (pass force=true to write anyway).\n" + WireJson.encodeToString(WriteRuleFileResult.serializer(), r))
}
