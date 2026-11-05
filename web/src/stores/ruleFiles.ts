import { defineStore } from 'pinia'
import { computed, ref, shallowRef } from 'vue'
import type { RuleDiagnostic, RuleFileInfo, RuleKind } from '@/generated/models'
import { rulesApi } from '@/api/rules'
import { ApiError, errorMessage } from '@/api/client'
import { useAppStore } from './app'
import { templateFor, pathFor } from '@/lib/ruleTemplates'

export const KIND_LABEL: Record<RuleKind, string> = {
  highlight: 'Highlights',
  check: 'Checks',
  preset: 'Presets',
  smart_preset: 'Smart presets',
  sources: 'Sources',
}

export const KIND_ORDER: RuleKind[] = ['highlight', 'check', 'preset', 'smart_preset', 'sources']

export interface OpenFile {
  path: string
  kind: RuleKind
  info: RuleFileInfo | null
  saved: string
  content: string
  hash: string | null
  readOnly: boolean
  isNew: boolean
}

export interface Conflict {
  mine: string
  theirs: string
  theirsHash: string | null
}

export const useRuleFilesStore = defineStore('ruleFiles', () => {
  const app = useAppStore()

  const files = ref<RuleFileInfo[]>([])
  const rulesDir = ref('')
  const loaded = ref(false)
  const loading = ref(false)
  const open = shallowRef<OpenFile | null>(null)
  const diagnostics = ref<RuleDiagnostic[]>([])
  const validating = ref(false)
  const saving = ref(false)
  const conflict = shallowRef<Conflict | null>(null)
  const changedOnDisk = ref(false)
  const forceOffer = ref(false)
  const query = ref('')

  const dirty = computed(() => !!open.value && open.value.content !== open.value.saved)

  const grouped = computed(() => {
    const q = query.value.trim().toLowerCase()
    const out = new Map<RuleKind, RuleFileInfo[]>()
    for (const k of KIND_ORDER) out.set(k, [])
    for (const f of files.value) {
      if (q && !f.path.toLowerCase().includes(q)) continue
      out.get(f.kind)?.push(f)
    }
    for (const list of out.values()) list.sort((a, b) => a.path.localeCompare(b.path))
    return out
  })

  async function load() {
    loading.value = true
    try {
      const r = await rulesApi.files()
      files.value = r.files
      rulesDir.value = r.rulesDir
      loaded.value = true
    } catch (e) {
      app.fail(e, 'Rules folder')
    } finally {
      loading.value = false
    }
  }

  function info(path: string) {
    return files.value.find((f) => f.path === path) ?? null
  }

  async function openFile(path: string) {
    if (dirty.value && open.value?.path !== path && !confirm(`Discard unsaved changes to ${open.value?.path}?`)) return
    try {
      const r = await rulesApi.read(path)
      open.value = {
        path,
        kind: r.file.kind,
        info: r.file,
        saved: r.content,
        content: r.content,
        hash: r.file.hash,
        readOnly: r.file.readOnly,
        isNew: false,
      }
      diagnostics.value = r.file.diagnostics
      conflict.value = null
      changedOnDisk.value = false
      forceOffer.value = false
    } catch (e) {
      app.fail(e, `Open ${path}`)
    }
  }

  function close() {
    if (dirty.value && !confirm(`Discard unsaved changes to ${open.value?.path}?`)) return false
    open.value = null
    diagnostics.value = []
    conflict.value = null
    changedOnDisk.value = false
    return true
  }

  function setContent(text: string) {
    if (!open.value || open.value.content === text) return
    open.value = { ...open.value, content: text }
  }

  async function validate(signal?: AbortSignal): Promise<RuleDiagnostic[]> {
    const f = open.value
    if (!f) return []
    validating.value = true
    try {
      const r = await rulesApi.validate(f.path, f.content, signal)
      if (open.value?.path === f.path) diagnostics.value = r.diagnostics
      return r.diagnostics
    } finally {
      validating.value = false
    }
  }

  async function save(force = false, overwrite = false) {
    const f = open.value
    if (!f || f.readOnly) return false
    saving.value = true
    try {
      const r = f.isNew ? await rulesApi.create(f.path, f.content, force) : await rulesApi.write(f.path, f.content, overwrite ? null : f.hash, force)
      diagnostics.value = r.diagnostics
      if (!r.saved || !r.file) {
        forceOffer.value = true
        app.toast('Not saved: the file has errors', 'error')
        return false
      }
      open.value = { ...f, saved: f.content, hash: r.file.hash, info: r.file, isNew: false }
      conflict.value = null
      changedOnDisk.value = false
      forceOffer.value = false
      app.toast(`Saved ${f.path}`, 'success')
      void load()
      return true
    } catch (e) {
      if (e instanceof ApiError && e.status === 422) {
        forceOffer.value = true
        app.toast('Not saved: the file has errors', 'error')
        return false
      }
      if (e instanceof ApiError && e.status === 409 && !f.isNew) {
        try {
          const cur = await rulesApi.read(f.path)
          conflict.value = { mine: f.content, theirs: cur.content, theirsHash: cur.file.hash }
        } catch {
          conflict.value = { mine: f.content, theirs: '', theirsHash: null }
        }
        return false
      }
      app.fail(e, 'Save')
      return false
    } finally {
      saving.value = false
    }
  }

  function takeTheirs() {
    const f = open.value
    const c = conflict.value
    if (!f || !c) return
    open.value = { ...f, content: c.theirs, saved: c.theirs, hash: c.theirsHash }
    conflict.value = null
    changedOnDisk.value = false
  }

  async function keepMine() {
    const f = open.value
    const c = conflict.value
    if (!f || !c) return
    open.value = { ...f, hash: c.theirsHash }
    conflict.value = null
    await save()
  }

  async function create(kind: RuleKind, name: string, content?: string) {
    const path = pathFor(kind, name)
    if (!path) {
      app.toast('Use letters, digits, dots, dashes or underscores', 'error')
      return
    }
    if (info(path)?.origin === null) {
      app.toast(`${path} already exists`, 'error')
      return
    }
    try {
      const body = content ?? templateFor(kind, name)
      const r = await rulesApi.create(path, body, true)
      await load()
      await openFile(path)
      if (r.diagnostics.some((d) => d.severity === 'error')) app.toast('Created with errors; fix them in the editor', 'info')
      else app.toast(`Created ${path}`, 'success')
    } catch (e) {
      app.fail(e, 'Create')
    }
  }

  async function copyToFolder(path: string) {
    try {
      const r = await rulesApi.copy(path)
      await load()
      const to = r.file?.path ?? path
      await openFile(to)
      app.toast(`Copied to ${to}`, 'success')
    } catch (e) {
      app.fail(e, 'Copy')
    }
  }

  async function remove(path: string) {
    try {
      const r = await rulesApi.remove(path)
      if (open.value?.path === path) {
        open.value = null
        diagnostics.value = []
      }
      await load()
      app.toast(`Moved to ${r.trashedTo}`, 'success')
    } catch (e) {
      app.fail(e, 'Delete')
    }
  }

  async function onRulesChanged(changed: string[], removed: string[]) {
    await load()
    const f = open.value
    if (!f || f.isNew) return
    if (removed.includes(f.path) || changed.includes(f.path)) {
      const now = info(f.path)
      if (!now) {
        changedOnDisk.value = true
        return
      }
      if (now.hash === f.hash) return
      if (!dirty.value) {
        await openFile(f.path)
        return
      }
      changedOnDisk.value = true
    }
  }

  return {
    files, rulesDir, loaded, loading, open, diagnostics, validating, saving, conflict, changedOnDisk, forceOffer, query, dirty, grouped,
    load, info, openFile, close, setContent, validate, save, takeTheirs, keepMine, create, copyToFolder, remove, onRulesChanged,
    errorMessage,
  }
})
