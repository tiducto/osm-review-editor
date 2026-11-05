package eu.tiducto.osmreview.server.mcp

import eu.tiducto.osmreview.rules.MirrorUnavailableException
import eu.tiducto.osmreview.server.App
import eu.tiducto.osmreview.server.presets.PresetBulkService
import eu.tiducto.osmreview.shared.Authors
import eu.tiducto.osmreview.shared.OsmRef
import eu.tiducto.osmreview.shared.OsmType
import eu.tiducto.osmreview.shared.ProposalOutcome
import eu.tiducto.osmreview.shared.RuleFileContent
import eu.tiducto.osmreview.shared.RuleFileInfo
import eu.tiducto.osmreview.shared.RuleKind
import eu.tiducto.osmreview.shared.ValidateRuleFileResult
import eu.tiducto.osmreview.shared.WireJson
import eu.tiducto.osmreview.shared.WriteRuleFileResult
import eu.tiducto.osmreview.shared.presets.DetachPresetRequest
import eu.tiducto.osmreview.shared.presets.ImportPresetRequest
import eu.tiducto.osmreview.shared.presets.PresetBulkApplyRequest
import eu.tiducto.osmreview.shared.presets.PresetBulkApplyResult
import eu.tiducto.osmreview.shared.presets.PresetGeometry
import eu.tiducto.osmreview.shared.presets.PresetMatch
import eu.tiducto.osmreview.shared.presets.PresetSummary
import eu.tiducto.osmreview.shared.presets.ResolvedPreset
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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

    server.tool(
        "apply_preset_bulk",
        "Apply one preset to many OSM objects from the local mirror, like the Presets bulk apply in the editor. Each object's " +
            "geometry comes from the mirror and its current best preset is replaced (merge keeps other tags). dry_run (default true) " +
            "returns counts and a preview of the first $MCP_BULK_PREVIEW changed objects; dry_run=false puts the changes into a new " +
            "draft authored by Claude (or the open draft draft_id), one proposal per object, for the user to review. " +
            "Smart presets (presets/smart/) run in the editor only and cannot be applied here.",
        schema(listOf("preset_id", "refs")) {
            str("preset_id", "Preset id from search_presets, e.g. highway/footway/sidewalk or custom/sidewalk-cz")
            putJsonObject("refs") {
                put("type", "array")
                put("description", "Up to ${PresetBulkService.MAX_REFS} objects: \"n123\", \"w45\", \"r6\" or {type, id}")
                putJsonObject("items") {
                    putJsonArray("anyOf") {
                        add(buildJsonObject { put("type", "string") })
                        add(
                            buildJsonObject {
                                put("type", "object")
                                putJsonObject("properties") {
                                    enum("type", "node, way or relation", listOf("node", "way", "relation"))
                                    long("id", "OSM id")
                                }
                            },
                        )
                    }
                }
            }
            bool("dry_run", "Only count and preview (default true)")
            str("title", "Title of the new draft (default 'Preset <name> · <n> objects')")
            str("draft_id", "Add the changes to this open draft instead of creating one")
            str("reason", "Reason on every change (default 'Preset \"<name>\"')")
            str("area", "Area name stored on a new draft, e.g. Opava")
            str("locale", "en or cs (default en), for the preset name")
        },
    ) { args ->
        val presetId = args.str("preset_id")?.trim()?.takeIf { it.isNotEmpty() } ?: return@tool err("preset_id is required")
        val rawRefs = args["refs"] as? JsonArray ?: return@tool err("refs must be an array of \"n123\", \"w45\", \"r6\" or {type, id}")
        if (rawRefs.isEmpty()) return@tool err("Give at least one ref")
        if (rawRefs.size > PresetBulkService.MAX_REFS) {
            return@tool err("Too many refs: ${rawRefs.size}; at most ${PresetBulkService.MAX_REFS} per call, split the list")
        }
        val refs = rawRefs.mapIndexed { i, e ->
            parseRef(e) ?: return@tool err("refs[$i]: ${e} is not an OSM ref; use \"n123\", \"w45\", \"r6\" or {\"type\": \"way\", \"id\": 45}")
        }
        if (!presets.index.contains(presetId)) {
            return@tool err("Unknown preset '$presetId'; find preset ids with search_presets")
        }
        val dryRun = args.bool("dry_run") ?: true
        val req = PresetBulkApplyRequest(
            presetId = presetId,
            refs = refs,
            dryRun = dryRun,
            title = args.str("title"),
            draftId = args.str("draft_id"),
            reason = args.str("reason"),
            area = args.str("area"),
            locale = args.str("locale"),
        )
        val r = try {
            app.presetBulk.apply(req, Authors.CLAUDE)
        } catch (e: MirrorUnavailableException) {
            return@tool err("No GOL mirror is open (${e.message}); bulk apply reads the objects from the local mirror")
        }
        okJson(bulkJson(r, dryRun))
    }
}

private const val MCP_BULK_PREVIEW = 50
private const val MCP_BULK_LIST = 50

private fun parseRef(e: JsonElement): OsmRef? = runCatching {
    when (e) {
        is JsonPrimitive -> if (e.isString) OsmRef.parse(e.content) else null
        is JsonObject -> OsmRef(OsmType.fromApiName(e.str("type")!!), e.long("id")!!)
        else -> null
    }
}.getOrNull()

private fun bulkJson(r: PresetBulkApplyResult, dryRun: Boolean): JsonObject = buildJsonObject {
    put("preset_id", r.presetId)
    put("preset_name", r.presetName)
    put("dry_run", dryRun)
    put("total", r.total)
    put("changed", r.changed)
    put("unchanged", r.unchanged)
    put("skipped", r.skipped.size)
    putJsonArray("skipped_objects") {
        r.skipped.take(MCP_BULK_LIST).forEach { s ->
            add(
                buildJsonObject {
                    put("ref", s.ref.toString())
                    put("message", s.message)
                },
            )
        }
    }
    putJsonArray("preview") {
        r.preview.take(MCP_BULK_PREVIEW).forEach { p ->
            add(
                buildJsonObject {
                    put("ref", p.ref.toString())
                    p.name?.let { put("name", it) }
                    p.fromPresetId?.let { put("from_preset_id", it) }
                    put("summary", p.summary)
                },
            )
        }
    }
    put("truncated", r.truncated || r.preview.size > MCP_BULK_PREVIEW || r.skipped.size > MCP_BULK_LIST)
    val draft = r.draft
    if (draft != null) {
        put("draft_id", draft.draft.id)
        put("title", draft.draft.title)
        put("author", draft.draft.author)
        putJsonObject("results") {
            ProposalOutcome.entries.forEach { o ->
                put(WireJson.encodeToJsonElement(ProposalOutcome.serializer(), o).jsonPrimitive.content, r.results.count { it.outcome == o })
            }
        }
        putJsonArray("errors") {
            r.results.filter { it.outcome == ProposalOutcome.ERROR }.take(MCP_BULK_LIST).forEach { res ->
                add(
                    buildJsonObject {
                        put("ref", res.ref.toString())
                        res.message?.let { put("message", it) }
                    },
                )
            }
        }
    } else if (!dryRun) {
        put("message", "Nothing to change; no draft was created")
    }
}

private fun writeResult(r: WriteRuleFileResult) = if (r.saved) {
    ok(r, WriteRuleFileResult.serializer())
} else {
    err("Not written: the content has errors (pass force=true to write anyway).\n" + WireJson.encodeToString(WriteRuleFileResult.serializer(), r))
}
