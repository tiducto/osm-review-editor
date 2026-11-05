<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { ImagerySource } from '@/generated/models'
import { ApiError, api, errorMessage } from '@/api/client'
import { useAppStore } from '@/stores/app'
import ConfirmDialog from './ConfirmDialog.vue'
import Icon from './Icon.vue'
import Spinner from './Spinner.vue'

const emit = defineEmits<{ close: [] }>()
const app = useAppStore()

type Field = keyof ImagerySource
interface Row {
  uid: number
  src: ImagerySource
}

const rows = ref<Row[]>([])
const saved = ref('')
const loading = ref(true)
const saving = ref(false)
const loadError = ref<string | null>(null)
const fieldErrors = ref<Record<number, Partial<Record<Field, string[]>>>>({})
const generalErrors = ref<string[]>([])
const confirmReset = ref(false)
const expanded = ref<number | null>(null)
let uid = 0

function toRows(list: ImagerySource[]): Row[] {
  return list.map((src) => ({ uid: ++uid, src: { ...src } }))
}
const plain = () => rows.value.map((r) => r.src)
const dirty = computed(() => JSON.stringify(plain()) !== saved.value)

function setList(list: ImagerySource[]) {
  rows.value = toRows(list)
  saved.value = JSON.stringify(list)
  fieldErrors.value = {}
  generalErrors.value = []
}

onMounted(async () => {
  try {
    setList(await api.imagerySettings())
  } catch (e) {
    loadError.value = errorMessage(e)
  } finally {
    loading.value = false
  }
})

function add() {
  const r: Row = { uid: ++uid, src: { id: '', name: '', type: 'tms', url: 'https://', attribution: '', maxZoom: 20, sourceTag: '' } }
  rows.value.push(r)
  expanded.value = r.uid
}

function remove(i: number) {
  rows.value.splice(i, 1)
  fieldErrors.value = {}
}

const fieldOf: [RegExp, Field][] = [
  [/^id\b/, 'id'],
  [/^name\b/, 'name'],
  [/^type\b/, 'type'],
  [/^(a (tms|wms) )?url\b/, 'url'],
  [/^attribution\b/, 'attribution'],
  [/^maxZoom\b/, 'maxZoom'],
  [/^sourceTag\b/, 'sourceTag'],
]

function parseErrors(detail: string) {
  const byRow: Record<number, Partial<Record<Field, string[]>>> = {}
  const general: string[] = []
  const put = (row: number, field: Field, msg: string) => {
    const r = (byRow[row] ??= {})
    ;(r[field] ??= []).push(msg)
  }
  for (const part of detail.split('; ')) {
    const m = /^imagery\[(\d+)\](?: \(.*?\))?: (.*)$/.exec(part)
    const dup = /^duplicate id '(.*)'$/.exec(part)
    if (m) {
      const field = fieldOf.find(([re]) => re.test(m[2]))?.[1]
      if (field) put(Number(m[1]), field, m[2])
      else general.push(part)
    } else if (dup) {
      rows.value.forEach((r, i) => r.src.id.trim() === dup[1] && put(i, 'id', `duplicate id '${dup[1]}'`))
    } else {
      general.push(part)
    }
  }
  fieldErrors.value = byRow
  generalErrors.value = general
  const first = Object.keys(byRow).map(Number).sort((a, b) => a - b)[0]
  if (first !== undefined && rows.value[first]) expanded.value = rows.value[first].uid
}

async function refreshSwitcher() {
  try {
    app.imagery = await api.imagery()
  } catch {}
}

async function save() {
  if (saving.value) return
  saving.value = true
  fieldErrors.value = {}
  generalErrors.value = []
  try {
    const list = plain().map((s) => ({ ...s, maxZoom: Number(s.maxZoom) }))
    setList(await api.saveImagerySettings(list))
    await refreshSwitcher()
    app.toast('Imagery sources saved', 'success')
  } catch (e) {
    if (e instanceof ApiError && e.status === 400 && e.detail) parseErrors(e.detail)
    else app.fail(e, 'Saving imagery')
  } finally {
    saving.value = false
  }
}

async function reset() {
  confirmReset.value = false
  saving.value = true
  try {
    setList(await api.resetImagerySettings())
    await refreshSwitcher()
    app.toast('Imagery sources reset to the defaults', 'success')
  } catch (e) {
    app.fail(e, 'Reset imagery')
  } finally {
    saving.value = false
  }
}

function errs(i: number, f: Field): string[] {
  return fieldErrors.value[i]?.[f] ?? []
}
function rowHasErrors(i: number): boolean {
  return !!fieldErrors.value[i] && Object.keys(fieldErrors.value[i]).length > 0
}

const textFields: { f: Field; label: string; placeholder: string; mono?: boolean; wide?: boolean }[] = [
  { f: 'name', label: 'Name', placeholder: 'Shown in the imagery switcher' },
  { f: 'id', label: 'ID', placeholder: 'a-z 0-9 . _ -', mono: true },
  { f: 'url', label: 'URL', placeholder: 'https://…/{z}/{x}/{y}.png or WMS with {bbox-epsg-3857}', mono: true, wide: true },
  { f: 'attribution', label: 'Attribution', placeholder: '© Provider' },
  { f: 'sourceTag', label: 'Source tag', placeholder: 'Changeset source value when used' },
]
</script>

<template>
  <div class="space-y-2" data-testid="imagery-editor">
    <div v-if="loading" class="flex items-center gap-2 text-fg-3"><Spinner :size="12" /> Loading imagery settings…</div>
    <div v-else-if="loadError" class="flex items-center gap-2 text-error"><Icon name="alert" :size="14" /> {{ loadError }}</div>
    <template v-else>
      <div v-if="generalErrors.length" class="rounded-md border border-error/40 bg-removed-soft px-3 py-2 text-[12px] text-removed">
        <div v-for="g in generalErrors" :key="g">{{ g }}</div>
      </div>
      <div class="overflow-hidden rounded-md border border-line">
        <div v-for="(r, i) in rows" :key="r.uid" class="border-b border-line last:border-b-0" :data-row="i">
          <div class="flex items-center gap-2 px-3 py-1.5">
            <button class="flex min-w-0 flex-1 items-center gap-2 text-left" @click="expanded = expanded === r.uid ? null : r.uid">
              <Icon :name="expanded === r.uid ? 'chevron-down' : 'chevron-right'" :size="13" class="shrink-0 text-fg-3" />
              <span class="truncate" :class="!r.src.name && 'text-fg-3 italic'">{{ r.src.name || 'New source' }}</span>
              <span class="chip shrink-0 bg-surface-3 font-mono text-fg-3 uppercase">{{ r.src.type || '?' }}</span>
              <span v-if="rowHasErrors(i)" class="flex shrink-0 items-center gap-1 text-[11px] text-error"><Icon name="alert" :size="12" /> Fix errors</span>
            </button>
            <button class="btn btn-ghost btn-icon btn-sm hover:text-removed" :title="`Remove ${r.src.name || 'source'}`" @click="remove(i)">
              <Icon name="x" :size="13" />
            </button>
          </div>
          <div v-if="expanded === r.uid" class="grid grid-cols-1 gap-x-3 gap-y-2 border-t border-line bg-surface-2 px-3 py-2.5 sm:grid-cols-2">
            <label v-for="t in textFields" :key="t.f" class="block text-[11.5px]" :class="t.wide && 'sm:col-span-2'">
              <span class="text-fg-3">{{ t.label }}</span>
              <input
                v-model="(r.src[t.f] as string)"
                class="input mt-0.5"
                :class="[t.mono && 'font-mono text-[11.5px]', errs(i, t.f).length > 0 && 'border-error']"
                :placeholder="t.placeholder"
                spellcheck="false"
                :data-field="t.f"
              />
              <span v-for="m in errs(i, t.f)" :key="m" class="mt-0.5 block text-error">{{ m }}</span>
            </label>
            <div class="grid grid-cols-2 gap-3">
              <label class="block text-[11.5px]">
                <span class="text-fg-3">Type</span>
                <select v-model="r.src.type" class="input mt-0.5" :class="errs(i, 'type').length > 0 && 'border-error'" data-field="type">
                  <option value="tms">TMS</option>
                  <option value="wms">WMS</option>
                </select>
                <span v-for="m in errs(i, 'type')" :key="m" class="mt-0.5 block text-error">{{ m }}</span>
              </label>
              <label class="block text-[11.5px]">
                <span class="text-fg-3">Max zoom</span>
                <input
                  v-model.number="r.src.maxZoom"
                  type="number"
                  min="1"
                  max="24"
                  class="input mt-0.5"
                  :class="errs(i, 'maxZoom').length > 0 && 'border-error'"
                  data-field="maxZoom"
                />
                <span v-for="m in errs(i, 'maxZoom')" :key="m" class="mt-0.5 block text-error">{{ m }}</span>
              </label>
            </div>
          </div>
        </div>
        <div v-if="!rows.length" class="px-3 py-3 text-center text-[12px] text-fg-3">No sources. Add one or reset to the defaults.</div>
      </div>
      <div class="flex flex-wrap items-center gap-2">
        <button class="btn btn-sm" @click="add"><Icon name="plus" :size="12" /> Add source</button>
        <button class="btn btn-ghost btn-sm" :disabled="saving" @click="confirmReset = true"><Icon name="undo" :size="12" /> Reset to defaults</button>
        <span class="ml-auto flex gap-2">
          <button class="btn btn-ghost btn-sm" @click="emit('close')">{{ dirty ? 'Cancel' : 'Close' }}</button>
          <button class="btn btn-primary btn-sm" :disabled="!dirty || saving" @click="save">
            <Spinner v-if="saving" :size="11" /> Save
          </button>
        </span>
      </div>
    </template>

    <ConfirmDialog
      v-if="confirmReset"
      title="Reset imagery sources?"
      confirm-label="Reset"
      @cancel="confirmReset = false"
      @confirm="reset"
    >
      This replaces the list with the defaults (ČÚZK orthophoto, ČÚZK cadastral map, Esri World Imagery). Sources you added are removed.
    </ConfirmDialog>
  </div>
</template>
