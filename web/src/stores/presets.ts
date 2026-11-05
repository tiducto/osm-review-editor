import { defineStore } from 'pinia'
import { computed, ref, shallowRef, watch } from 'vue'
import type { ResolvedPreset, SmartPreset } from '@/generated/models'
import { presetsApi } from '@/api/presets'
import { ApiError } from '@/api/client'
import { PresetCatalog, type RawSchema, type UserPresetData } from '@/presets/catalog'
import { locale as appLocale, setLocale, translate, type MessageKey } from '@/i18n'
import type { Geometry, Locale } from '@/presets/types'
import { useAppStore } from './app'
import { setPresetNamer } from '@/lib/osm'

const RECENT_KEY = 'ore.recentPresets'
const MAX_RECENT = 12

function readRecent(): string[] {
  try {
    const v = JSON.parse(localStorage.getItem(RECENT_KEY) ?? '[]')
    if (Array.isArray(v)) return v.filter((x) => typeof x === 'string')
  } catch {}
  return []
}

export function toUserData(list: Partial<Record<Locale, ResolvedPreset[]>>, locale: Locale): UserPresetData[] {
  const main = list[locale] ?? list.en ?? list.cs ?? []
  return main.map((p) => {
    const names: Partial<Record<Locale, string>> = {}
    for (const l of ['en', 'cs'] as Locale[]) {
      const n = list[l]?.find((x) => x.id === p.id)?.name
      if (n) names[l] = n
    }
    return {
      id: p.id,
      name: p.name,
      names,
      geometry: p.geometry as Geometry[],
      tags: p.tags,
      addTags: p.addTags,
      removeTags: p.removeTags,
      fields: p.fields,
      moreFields: p.moreFields,
      icon: p.icon,
      terms: p.terms,
      aliases: p.aliases,
      matchScore: p.matchScore,
      searchable: p.searchable,
      extends: p.extends,
      apply: p.apply,
      file: p.file,
    }
  })
}

export function localizedName(names: Record<string, string> | null | undefined, l: string): string | null {
  if (!names) return null
  return names[l] ?? Object.entries(names).find(([k]) => k.split('-')[0] === l)?.[1] ?? names.en ?? Object.values(names)[0] ?? null
}

export const usePresetsStore = defineStore('presets', () => {
  const app = useAppStore()
  const locale = computed<Locale>({
    get: () => appLocale.value,
    set: (l) => setLocale(l),
  })
  const raw = shallowRef<RawSchema | null>(null)
  const user = shallowRef<Partial<Record<Locale, ResolvedPreset[]>>>({})
  const smartRaw = shallowRef<SmartPreset[]>([])
  const smart = computed<SmartPreset[]>(() => smartRaw.value.map((p) => ({ ...p, name: localizedName(p.names, locale.value) ?? p.name })))
  const recent = ref<string[]>(readRecent())
  const loading = ref(false)
  const error = ref<string | null>(null)
  let loadPromise: Promise<void> | null = null

  const catalog = computed<PresetCatalog | null>(() => {
    const r = raw.value
    if (!r) return null
    return new PresetCatalog(r, toUserData(user.value, locale.value), locale.value)
  })

  setPresetNamer((ref, tags) => {
    const cat = catalog.value
    if (!cat) return null
    let p
    if (ref.type === 'node') {
      const point = cat.match(tags, 'point')
      const vertex = cat.match(tags, 'vertex')
      p = cat.scoreOf(vertex, tags) > cat.scoreOf(point, tags) ? vertex : point
    } else if (ref.type === 'relation') {
      p = cat.match(tags, tags.type === 'multipolygon' ? 'area' : 'relation')
    } else {
      p = cat.match(tags, cat.isArea(tags) ? 'area' : 'line')
      if (cat.isFallback(p)) p = cat.match(tags, 'area')
    }
    return cat.isFallback(p) ? null : p.name
  })

  watch(locale, () => {
    if (raw.value) void loadUser()
  })

  watch(
    recent,
    (r) => {
      try {
        localStorage.setItem(RECENT_KEY, JSON.stringify(r))
      } catch {}
    },
    { deep: true },
  )

  async function loadRaw(): Promise<RawSchema> {
    const [presets, fields, categories, defaults, en, cs] = await Promise.all([
      presetsApi.schemaFile<RawSchema['presets']>('presets.json'),
      presetsApi.schemaFile<RawSchema['fields']>('fields.json'),
      presetsApi.schemaFile<RawSchema['categories']>('preset_categories.json'),
      presetsApi.schemaFile<RawSchema['defaults']>('preset_defaults.json'),
      presetsApi.schemaFile<Record<string, unknown>>('translations/en.json'),
      presetsApi.schemaFile<Record<string, unknown>>('translations/cs.json'),
    ])
    return { presets, fields, categories, defaults, translations: { en, cs } }
  }

  async function loadUser() {
    try {
      const [en, cs] = await Promise.all([presetsApi.user('en'), presetsApi.user('cs')])
      user.value = { en, cs }
    } catch (e) {
      if (!(e instanceof ApiError && e.status === 404)) app.fail(e, translate(locale.value, 'library.loadUserFailed'))
    }
  }

  async function loadSmart() {
    try {
      smartRaw.value = await presetsApi.smart()
    } catch (e) {
      app.fail(e, translate(locale.value, 'library.loadSmartFailed'))
    }
  }

  function ensure(): Promise<void> {
    loadPromise ??= (async () => {
      loading.value = true
      try {
        const [r] = await Promise.all([loadRaw(), loadUser(), loadSmart()])
        raw.value = r
        error.value = null
      } catch (e) {
        error.value = e instanceof Error ? e.message : String(e)
        loadPromise = null
        throw e
      } finally {
        loading.value = false
      }
    })()
    return loadPromise
  }

  async function onRulesChanged(paths: string[]) {
    if (!raw.value) return
    if (paths.some((p) => p.startsWith('presets/smart/'))) await loadSmart()
    if (paths.some((p) => p.startsWith('presets/') && !p.startsWith('presets/smart/'))) await loadUser()
  }

  async function reloadUser() {
    await Promise.all([loadUser(), loadSmart()])
  }

  function useRecent(id: string) {
    recent.value = [id, ...recent.value.filter((x) => x !== id)].slice(0, MAX_RECENT)
  }

  const t = (key: MessageKey | (string & {}), vars?: Record<string, string | number>) => translate(locale.value, key, vars)

  return { locale, raw, user, smart, recent, loading, error, catalog, ensure, loadUser, onRulesChanged, reloadUser, useRecent, t }
})
