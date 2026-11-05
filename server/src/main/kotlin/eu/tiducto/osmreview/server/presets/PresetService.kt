package eu.tiducto.osmreview.server.presets

import eu.tiducto.osmreview.server.core.NotFoundException
import eu.tiducto.osmreview.server.core.StateException
import eu.tiducto.osmreview.server.rulesfolder.RulesFolder
import eu.tiducto.osmreview.shared.RuleKind
import eu.tiducto.osmreview.shared.WriteRuleFileResult
import eu.tiducto.osmreview.shared.hasErrors
import eu.tiducto.osmreview.shared.presets.DetachPresetRequest
import eu.tiducto.osmreview.shared.presets.IdSchemaInfo
import eu.tiducto.osmreview.shared.presets.IdTaggingSchema
import eu.tiducto.osmreview.shared.presets.ImportPresetRequest
import eu.tiducto.osmreview.shared.presets.ParsedPresetFile
import eu.tiducto.osmreview.shared.presets.PresetApplyResult
import eu.tiducto.osmreview.shared.presets.PresetFiles
import eu.tiducto.osmreview.shared.presets.PresetGeometry
import eu.tiducto.osmreview.shared.presets.PresetIndex
import eu.tiducto.osmreview.shared.presets.PresetMatch
import eu.tiducto.osmreview.shared.presets.PresetSummary
import eu.tiducto.osmreview.shared.presets.ResolvedPreset
import eu.tiducto.osmreview.shared.presets.SmartPreset
import eu.tiducto.osmreview.shared.presets.UserPreset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class IdPresetData private constructor(val version: String, private val load: (String) -> ByteArray?) {
    val index: PresetIndex by lazy {
        fun text(name: String) = load(name)?.decodeToString() ?: error("iD tagging schema file $name is missing from the build")
        PresetIndex(
            IdTaggingSchema.presets(text(IdTaggingSchema.PRESETS)),
            IdTaggingSchema.fields(text(IdTaggingSchema.FIELDS)),
            IdTaggingSchema.LOCALES.map { IdTaggingSchema.translations(text(IdTaggingSchema.translationFile(it))) },
        )
    }

    fun file(name: String): ByteArray? = if (name in IdTaggingSchema.files || name == PACKAGE_JSON) load(name) else null

    fun info(baseUrl: String) = IdSchemaInfo(version, IdTaggingSchema.LOCALES, IdTaggingSchema.files, baseUrl)

    companion object {
        const val RESOURCE_DIR = "/id-tagging-schema/dist/"
        const val PACKAGE_JSON = "package.json"

        fun fromResources(): IdPresetData {
            val load = { name: String -> IdPresetData::class.java.getResourceAsStream(RESOURCE_DIR + name)?.use { it.readBytes() } }
            val version = load(PACKAGE_JSON)?.let { Json.parseToJsonElement(it.decodeToString()).jsonObject["version"]?.jsonPrimitive?.content }
                ?: "unknown"
            return IdPresetData(version, load)
        }
    }
}

class PresetService(val data: IdPresetData, private val folder: RulesFolder) {
    @Volatile var index: PresetIndex = data.index
        private set

    init {
        folder.onChange { change ->
            if (change.changed.any(::isPresetPath) || change.removed.any(::isPresetPath)) rebuild()
        }
        rebuild()
    }

    private fun isPresetPath(p: String) = p.endsWith(".json")

    fun rebuild() {
        val files = folder.loaded(RuleKind.PRESET).sortedWith(compareBy({ it.remote }, { it.path }))
        val user = files.filter { !it.diagnostics.hasErrors() }.flatMap { f ->
            (f.value as? ParsedPresetFile)?.presets.orEmpty().map { UserPreset(it.preset, f.path) }
        }
        index = data.index.withUser(user)
    }

    fun search(q: String, locale: String?, geometry: PresetGeometry?, limit: Int): List<PresetSummary> =
        index.search(q, locale, geometry, limit.coerceIn(1, 200))

    fun get(id: String, locale: String?): ResolvedPreset = index.resolve(id, locale) ?: throw NotFoundException("Unknown preset '$id'")

    fun userPresets(locale: String?): List<ResolvedPreset> {
        val idx = index
        return idx.userIds.mapNotNull { idx.resolve(it, locale) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, ResolvedPreset::name).thenBy(ResolvedPreset::id))
    }

    fun match(tags: Map<String, String>, geometry: PresetGeometry, locale: String?): PresetMatch =
        index.match(tags, geometry, locale) ?: throw NotFoundException("No preset matches")

    fun apply(id: String, tags: Map<String, String>, geometry: PresetGeometry?, from: String?): PresetApplyResult = try {
        index.apply(id, tags, geometry, from)
    } catch (e: NoSuchElementException) {
        throw NotFoundException(e.message.orEmpty())
    }

    fun smartPresets(): List<SmartPreset> = folder.loaded(RuleKind.SMART_PRESET)
        .filter { !it.diagnostics.hasErrors() }
        .mapNotNull { it.value as? SmartPreset }
        .distinctBy { it.id }

    fun import(req: ImportPresetRequest): WriteRuleFileResult {
        if (!index.contains(req.presetId)) throw NotFoundException("Unknown preset '${req.presetId}'")
        val overlay = index.overlay(req.presetId, req.id)
        if (index.contains(overlay.id) && !index.isBase(overlay.id)) {
            throw StateException("A preset '${overlay.id}' already exists in ${index.userFile(overlay.id)}")
        }
        val path = req.path?.trim()?.takeIf { it.isNotEmpty() } ?: PresetFiles.fileNameFor(overlay.id)
        require(RuleKind.forPath(path) == RuleKind.PRESET) { "Imported presets go to presets/<name>.json, not '$path'" }
        return folder.write(path, PresetFiles.encode(overlay), createOnly = true)
    }

    fun detach(req: DetachPresetRequest): WriteRuleFileResult {
        val (path, id) = locate(req)
        val raw = index.raw(id)
        if (raw?.extends == null) throw StateException("Preset '$id' in $path does not extend another preset; nothing to detach")
        val full = try {
            index.detach(id)
        } catch (e: IllegalArgumentException) {
            throw StateException(e.message.orEmpty())
        }
        val current = folder.read(path)
        val root = Json.parseToJsonElement(current.content)
        val replaced: JsonElement = when (root) {
            is JsonArray -> JsonArray(root.map { e -> if ((e as? JsonObject)?.get("id")?.jsonPrimitive?.content == id) PresetFiles.toJson(full) else e })
            else -> PresetFiles.toJson(full)
        }
        val text = if (root is JsonArray) PRETTY.encodeToString(JsonElement.serializer(), replaced) + "\n" else PresetFiles.encode(full)
        return folder.write(path, text, expectedHash = current.file.hash)
    }

    private fun locate(req: DetachPresetRequest): Pair<String, String> {
        val path = req.path?.takeIf { it.isNotBlank() }
        val id = req.id?.takeIf { it.isNotBlank() }
        if (id != null) {
            val file = index.userFile(id) ?: throw NotFoundException("'$id' is not a preset in the rules folder")
            require(path == null || path == file) { "Preset '$id' lives in $file, not $path" }
            return file to id
        }
        path ?: throw IllegalArgumentException("Give id or path")
        val parsed = folder.loaded(RuleKind.PRESET).firstOrNull { it.path == path }?.value as? ParsedPresetFile
            ?: throw NotFoundException("No preset file '$path'")
        val only = parsed.presets.singleOrNull() ?: throw IllegalArgumentException("$path holds ${parsed.presets.size} presets; give id")
        return path to only.preset.id
    }

    private companion object {
        val PRETTY = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }
    }
}
