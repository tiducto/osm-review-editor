package eu.tiducto.osmreview.shared.presets

import eu.tiducto.osmreview.shared.TagEdit
import eu.tiducto.osmreview.shared.sortedByKey
import eu.tiducto.osmreview.shared.withoutPseudoTags

data class UserPreset(val preset: Preset, val file: String? = null)

class PresetCycleException(message: String) : IllegalArgumentException(message)

class PresetIndex(
    private val base: List<Preset>,
    fields: List<PresetField>,
    translations: List<PresetTranslations>,
    user: List<UserPreset> = emptyList(),
) {
    private val baseById: Map<String, Preset> = base.associateBy { it.id }
    private val userById: Map<String, UserPreset> = user.distinctBy { it.preset.id }.associateBy { it.preset.id }
    val fieldsById: Map<String, PresetField> = fields.associateBy { it.id }
    private val locales: Map<String, PresetTranslations> = translations.associateBy { it.locale }

    private data class Ref(val id: String, val user: Boolean)

    private class Merged(
        val id: String,
        val origin: PresetOrigin,
        val file: String?,
        val geometry: List<PresetGeometry>,
        val tags: Map<String, String>,
        val addTags: Map<String, String>,
        val removeTags: Map<String, String>,
        val fields: List<String>,
        val moreFields: List<String>,
        val icon: String?,
        val matchScore: Double,
        val searchable: Boolean,
        val extends: String?,
        val apply: PresetApplyMode,
    )

    private val merged = LinkedHashMap<String, Merged>()
    private val resolveErrors = LinkedHashMap<String, String>()

    init {
        val order = userById.keys +base.map { it.id }.filter { it !in userById }
        for (id in order) {
            val ref = top(id) ?: continue
            try {
                merged[id] = merge(ref)
            } catch (e: PresetCycleException) {
                resolveErrors[id] = e.message.orEmpty()
            }
        }
    }

    val availableLocales: Set<String> get() = locales.keys

    val baseIds: Set<String> get() = baseById.keys

    val userIds: Set<String> get() = userById.keys

    fun withUser(user: List<UserPreset>): PresetIndex = PresetIndex(base, fieldsById.values.toList(), locales.values.toList(), user)

    fun contains(id: String): Boolean = id in baseById || id in userById

    fun isBase(id: String): Boolean = id in baseById

    fun raw(id: String): Preset? = userById[id]?.preset ?: baseById[id]

    fun baseRaw(id: String): Preset? = baseById[id]

    fun userFile(id: String): String? = userById[id]?.file

    fun resolveError(id: String): String? = resolveErrors[id]

    fun locale(requested: String?): String {
        val r = requested?.trim()?.replace('_', '-')?.takeIf { it.isNotEmpty() } ?: return DEFAULT_LOCALE
        locales.keys.firstOrNull { it.equals(r, ignoreCase = true) }?.let { return it }
        val lang = r.substringBefore('-').lowercase()
        return locales.keys.firstOrNull { it.equals(lang, ignoreCase = true) } ?: DEFAULT_LOCALE
    }

    private fun top(id: String): Ref? = when {
        id in userById -> Ref(id, true)
        id in baseById -> Ref(id, false)
        else -> null
    }

    private fun rawOf(ref: Ref): Preset = if (ref.user) userById.getValue(ref.id).preset else baseById.getValue(ref.id)

    private fun parentOf(ref: Ref): Ref? {
        val e = rawOf(ref).extends ?: return null
        if (e == ref.id && ref.user) return baseById[e]?.let { Ref(e, false) }
        return top(e)
    }

    private fun chain(ref: Ref): List<Preset> {
        val out = ArrayList<Preset>()
        val seen = HashSet<Ref>()
        var cur: Ref? = ref
        while (cur != null) {
            if (!seen.add(cur)) throw PresetCycleException("Preset '${ref.id}' extends itself through ${out.joinToString(" → ") { it.id }}")
            val raw = rawOf(cur)
            out += raw
            cur = parentOf(cur)
        }
        return out
    }

    private fun <T> List<Preset>.inherit(get: (Preset) -> T?): T? {
        for (p in this) get(p)?.let { return it }
        return null
    }

    private fun merge(ref: Ref): Merged {
        val chain = chain(ref)
        val own = chain.first()
        val tags = chain.inherit { it.tags } ?: emptyMap()
        val addTags = chain.inherit { it.addTags } ?: tags
        var removeTags: Map<String, String>? = null
        for (p in chain) {
            if (p.removeTags != null) {
                removeTags = p.removeTags
                break
            }
            if (p.addTags != null) {
                removeTags = p.addTags
                break
            }
        }
        return Merged(
            id = own.id,
            origin = if (ref.user) PresetOrigin.USER else PresetOrigin.ID,
            file = if (ref.user) userById[ref.id]?.file else null,
            geometry = chain.inherit { it.geometry } ?: emptyList(),
            tags = tags,
            addTags = addTags,
            removeTags = removeTags ?: addTags,
            fields = resolveFields(ref, chain, more = false, HashSet()),
            moreFields = resolveFields(ref, chain, more = true, HashSet()),
            icon = chain.inherit { it.icon },
            matchScore = chain.inherit { it.matchScore } ?: 1.0,
            searchable = chain.inherit { it.searchable } ?: true,
            extends = own.extends,
            apply = chain.inherit { it.apply } ?: PresetApplyMode.MERGE,
        )
    }

    private fun resolveFields(ref: Ref, chain: List<Preset>, more: Boolean, visiting: MutableSet<Ref>): List<String> {
        if (!visiting.add(ref)) throw PresetCycleException("Field references of '${ref.id}' form a cycle")
        val own = chain.first()
        val list = if (more) own.moreFields else own.fields
        val out = LinkedHashSet<String>()
        when {
            list != null -> for (f in list) {
                val target = referenced(f)
                if (target == null) {
                    out += f
                } else {
                    refTo(target, ref)?.let { t -> out += resolveFields(t, chain(t), more, visiting) }
                }
            }
            own.extends != null -> parentOf(ref)?.let { p -> out += resolveFields(p, chain(p), more, visiting) }
            else -> {
                val cut = ref.id.lastIndexOf('/')
                val parentId = if (cut > 0) ref.id.substring(0, cut) else null
                val pref = parentId?.let { id -> baseById[id]?.let { Ref(id, false) } }
                if (pref != null) {
                    val inherited = resolveFields(pref, chain(pref), more, visiting)
                    val tags = own.tags ?: emptyMap()
                    out += if (more) inherited else inherited.filter { shouldInherit(it, tags) }
                }
            }
        }
        visiting.remove(ref)
        return out.toList()
    }

    private fun refTo(target: String, from: Ref): Ref? =
        if (target == from.id && from.user) baseById[target]?.let { Ref(target, false) } else top(target)

    private fun shouldInherit(fieldId: String, tags: Map<String, String>): Boolean {
        val f = fieldsById[fieldId] ?: return true
        val key = f.key ?: return true
        return tags[key] == null || f.type in MULTI_FIELD_TYPES
    }

    private fun texts(id: String, locale: String): PresetTexts? =
        locales[locale]?.presets?.get(id) ?: locales[DEFAULT_LOCALE]?.presets?.get(id)

    private fun localName(ref: Ref, locale: String, depth: Int = 0): String {
        if (depth > MAX_DEPTH) return ref.id
        val raw = rawOf(ref)
        raw.name?.let { n -> return expandName(n, ref, locale, depth) }
        if (raw.extends != null) parentOf(ref)?.let { return localName(it, locale, depth + 1) }
        return texts(ref.id, locale)?.name ?: ref.id
    }

    private fun expandName(n: String, ref: Ref, locale: String, depth: Int): String {
        val target = referenced(n) ?: return n
        val tref = refTo(target, ref) ?: return n
        return localName(tref, locale, depth + 1)
    }

    private fun localList(ref: Ref, locale: String, aliases: Boolean, depth: Int = 0): List<String> {
        if (depth > MAX_DEPTH) return emptyList()
        val raw = rawOf(ref)
        val own = if (aliases) raw.aliases else raw.terms
        if (own != null) {
            return own.flatMap { t ->
                val target = referenced(t) ?: return@flatMap listOf(t)
                val tref = refTo(target, ref) ?: return@flatMap emptyList()
                localList(tref, locale, aliases, depth + 1)
            }.distinct()
        }
        if (raw.extends != null) parentOf(ref)?.let { return localList(it, locale, aliases, depth + 1) }
        val t = texts(ref.id, locale) ?: return emptyList()
        return if (aliases) t.aliases else t.terms
    }

    private fun fieldInfo(id: String, locale: String): PresetFieldInfo? {
        val f = fieldsById[id] ?: return null
        val t = locales[locale]?.fields?.get(id)
        val en = locales[DEFAULT_LOCALE]?.fields?.get(id)
        return PresetFieldInfo(
            id = id,
            key = f.key,
            keys = f.keys,
            type = f.type,
            label = t?.label ?: en?.label ?: id,
            placeholder = t?.placeholder ?: en?.placeholder,
            options = f.options,
            optionLabels = (en?.options.orEmpty() + t?.options.orEmpty()),
        )
    }

    fun resolve(id: String, locale: String? = null): ResolvedPreset? {
        resolveErrors[id]?.let { throw PresetCycleException(it) }
        val m = merged[id] ?: return null
        val ref = top(id)!!
        val loc = locale(locale)
        return ResolvedPreset(
            id = m.id,
            name = localName(ref, loc),
            geometry = m.geometry,
            tags = m.tags,
            addTags = m.addTags,
            removeTags = m.removeTags,
            fields = m.fields,
            moreFields = m.moreFields,
            icon = m.icon,
            terms = localList(ref, loc, aliases = false),
            aliases = localList(ref, loc, aliases = true),
            matchScore = m.matchScore,
            searchable = m.searchable,
            extends = m.extends,
            apply = m.apply,
            origin = m.origin,
            file = m.file,
            locale = loc,
            fieldInfo = (m.fields + m.moreFields).distinct().mapNotNull { fieldInfo(it, loc) },
        )
    }

    fun summary(id: String, locale: String? = null): PresetSummary? {
        val m = merged[id] ?: return null
        return PresetSummary(m.id, localName(top(id)!!, locale(locale)), m.geometry, m.icon, m.origin, m.extends, m.file)
    }

    fun search(query: String, locale: String? = null, geometry: PresetGeometry? = null, limit: Int = 20): List<PresetSummary> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val loc = locale(locale)
        val scored = ArrayList<Triple<Int, Int, String>>()
        for ((id, m) in merged) {
            if (!m.searchable || id.startsWith("@")) continue
            if (geometry != null && geometry !in m.geometry) continue
            val ref = top(id)!!
            val name = normalize(localName(ref, loc))
            val rank = rank(q, name, localList(ref, loc, aliases = true).map(::normalize), localList(ref, loc, aliases = false).map(::normalize))
                ?: if (loc != DEFAULT_LOCALE) rank(q, normalize(localName(ref, DEFAULT_LOCALE)), emptyList(), emptyList())?.plus(EN_PENALTY) else null
            if (rank != null) scored += Triple(rank + if (m.origin == PresetOrigin.USER) 0 else 1, name.length, id)
        }
        return scored.sortedWith(compareBy({ it.first }, { it.second }, { it.third })).take(limit).mapNotNull { summary(it.third, loc) }
    }

    private fun rank(q: String, name: String, aliases: List<String>, terms: List<String>): Int? = when {
        name == q -> 0
        name.startsWith(q) -> 10
        aliases.any { it == q } -> 20
        aliases.any { it.startsWith(q) } -> 30
        name.split(' ', '-', '/').any { it.startsWith(q) } -> 40
        terms.any { it == q } -> 50
        terms.any { it.startsWith(q) } -> 60
        name.contains(q) -> 70
        aliases.any { it.contains(q) } || terms.any { it.contains(q) } -> 80
        else -> null
    }

    fun score(id: String, tags: Map<String, String>): Double {
        val m = merged[id] ?: return -1.0
        return score(m, tags)
    }

    private fun score(m: Merged, entityTags: Map<String, String>): Double {
        var score = 0.0
        for ((k, v) in m.tags) {
            val actual = entityTags[k]
            score += when {
                actual == v -> m.matchScore
                v == "*" && actual != null -> m.matchScore / 2
                else -> return -1.0
            }
        }
        for ((k, v) in m.addTags) if (k !in m.tags && entityTags[k] == v) score += m.matchScore
        if (!m.searchable) score *= 0.999
        return score
    }

    fun match(tags: Map<String, String>, geometry: PresetGeometry, locale: String? = null): PresetMatch? {
        val clean = tags.withoutPseudoTags()
        var best: Merged? = null
        var bestScore = -1.0
        for (m in merged.values) {
            if (geometry !in m.geometry) continue
            val s = score(m, clean)
            if (s < 0) continue
            val better = s > bestScore || (s == bestScore && best != null && best.origin != PresetOrigin.USER && m.origin == PresetOrigin.USER)
            if (better) {
                best = m
                bestScore = s
            }
        }
        val chosen = best ?: merged[FALLBACKS.getValue(geometry)] ?: return null
        val resolved = resolve(chosen.id, locale) ?: return null
        return PresetMatch(resolved, if (best == null) 0.0 else bestScore)
    }

    fun apply(
        presetId: String,
        tags: Map<String, String>,
        geometry: PresetGeometry? = null,
        fromPresetId: String? = null,
    ): PresetApplyResult {
        val m = merged[presetId] ?: throw NoSuchElementException("Unknown preset '$presetId'")
        if (geometry != null) require(geometry in m.geometry) { "Preset '$presetId' does not apply to geometry ${geometry.wireName}" }
        val from = fromPresetId?.let { merged[it] ?: throw NoSuchElementException("Unknown preset '$it'") }
        val base = tags.withoutPseudoTags()
        val added = m.addTags.mapValues { (k, v) -> if (v == "*") base[k] ?: "yes" else v }
        val edit = when (m.apply) {
            PresetApplyMode.MERGE -> {
                val out = LinkedHashMap(base)
                from?.removeTags?.keys?.forEach { if (it != "area") out.remove(it) }
                if (from != null) out.remove("area")
                out.putAll(added)
                TagEdit(
                    set = out.filter { (k, v) -> base[k] != v }.sortedByKey(),
                    remove = base.keys.filter { it !in out }.sorted(),
                )
            }
            PresetApplyMode.REPLACE -> TagEdit(replaceAll = added.sortedByKey())
            PresetApplyMode.CREATE -> {
                require(base.isEmpty()) { "Preset '$presetId' has apply=create and only applies to new, untagged objects" }
                TagEdit(replaceAll = added.sortedByKey())
            }
        }
        return PresetApplyResult(presetId, edit, edit.applyTo(base))
    }

    fun overlay(baseId: String, newId: String? = null): Preset {
        require(contains(baseId)) { "Unknown preset '$baseId'" }
        val id = newId?.trim()?.takeIf { it.isNotEmpty() } ?: "custom/$baseId"
        return Preset(id = id, extends = baseId, apply = PresetApplyMode.MERGE)
    }

    fun detach(id: String): Preset {
        resolveErrors[id]?.let { throw PresetCycleException(it) }
        val m = merged[id] ?: throw NoSuchElementException("Unknown preset '$id'")
        val ref = top(id)!!
        val raw = rawOf(ref)
        val literalName = raw.name?.takeIf { referenced(it) == null }
        return Preset(
            id = m.id,
            name = literalName ?: localName(ref, DEFAULT_LOCALE),
            geometry = m.geometry,
            tags = m.tags,
            addTags = m.addTags.takeIf { it != m.tags },
            removeTags = m.removeTags.takeIf { it != m.addTags },
            fields = m.fields,
            moreFields = m.moreFields.ifEmpty { null },
            icon = m.icon,
            terms = raw.terms?.let { localList(ref, DEFAULT_LOCALE, aliases = false) }?.ifEmpty { null },
            aliases = raw.aliases?.let { localList(ref, DEFAULT_LOCALE, aliases = true) }?.ifEmpty { null },
            matchScore = m.matchScore.takeIf { it != 1.0 },
            searchable = m.searchable.takeIf { !it },
            extends = null,
            apply = m.apply,
        )
    }

    companion object {
        const val DEFAULT_LOCALE = "en"
        private const val MAX_DEPTH = 32
        private const val EN_PENALTY = 5
        private val MULTI_FIELD_TYPES = setOf("multiCombo", "semiCombo", "manyCombo", "check")
        private val REF = Regex("""^\{(.+)\}$""")

        val FALLBACKS = mapOf(
            PresetGeometry.POINT to "point",
            PresetGeometry.VERTEX to "point",
            PresetGeometry.LINE to "line",
            PresetGeometry.AREA to "area",
            PresetGeometry.RELATION to "relation",
        )

        fun referenced(s: String): String? = REF.matchEntire(s.trim())?.groupValues?.get(1)

        fun normalize(s: String): String = buildString(s.length) {
            for (c in s.lowercase().trim()) append(FOLD[c] ?: c)
        }

        private val FOLD: Map<Char, Char> = buildMap {
            val pairs = listOf(
                "áàâäãåāąă" to 'a', "čćç" to 'c', "ďđ" to 'd', "éèêëěēęė" to 'e', "íìîïī" to 'i',
                "ľĺł" to 'l', "ňńñ" to 'n', "óòôöõőø" to 'o', "řŕ" to 'r', "šśş" to 's', "ťţ" to 't',
                "úùûüůűū" to 'u', "ýÿ" to 'y', "žźż" to 'z',
            )
            pairs.forEach { (from, to) -> from.forEach { put(it, to) } }
        }
    }
}
