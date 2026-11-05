<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { EditorView, keymap } from '@codemirror/view'
import { EditorState, Compartment } from '@codemirror/state'
import { basicSetup } from 'codemirror'
import { json } from '@codemirror/lang-json'
import { HighlightStyle, syntaxHighlighting } from '@codemirror/language'
import { linter, lintGutter, setDiagnostics, type Diagnostic } from '@codemirror/lint'
import { unifiedMergeView } from '@codemirror/merge'
import { tags as t } from '@lezer/highlight'
import type { RuleDiagnostic } from '@/generated/models'
import { mapcss } from '@/lib/mapcssLanguage'
import { useRuleFilesStore } from '@/stores/ruleFiles'
import { useHighlightsStore } from '@/stores/highlights'
import Icon from './Icon.vue'
import Spinner from './Spinner.vue'
import ConfirmDialog from './ConfirmDialog.vue'
import Modal from './Modal.vue'

const store = useRuleFilesStore()
const hl = useHighlightsStore()

const host = ref<HTMLDivElement | null>(null)
const mergeHost = ref<HTMLDivElement | null>(null)
const view = shallowRef<EditorView | null>(null)
let mergeView: EditorView | null = null
const language = new Compartment()
const readOnly = new Compartment()
const confirmDelete = ref(false)
const livePreview = ref(true)

const file = computed(() => store.open)
const isHighlight = computed(() => file.value?.kind === 'highlight')
const origin = computed(() => file.value?.info?.origin ?? null)
const errors = computed(() => store.diagnostics.filter((d) => d.severity === 'error').length)
const warnings = computed(() => store.diagnostics.filter((d) => d.severity === 'warning').length)

const style = HighlightStyle.define([
  { tag: t.comment, color: '#8a8f98', fontStyle: 'italic' },
  { tag: t.string, color: '#0f7b3e' },
  { tag: [t.number, t.bool], color: '#b45309' },
  { tag: t.color, color: '#be185d' },
  { tag: t.keyword, color: '#7c3aed', fontWeight: '600' },
  { tag: t.typeName, color: '#1d4ed8', fontWeight: '600' },
  { tag: t.className, color: '#0e7490' },
  { tag: t.labelName, color: '#0e7490' },
  { tag: t.attributeName, color: '#9a3412' },
  { tag: t.propertyName, color: '#1e40af' },
  { tag: t.macroName, color: '#7c3aed' },
  { tag: t.regexp, color: '#c2410c' },
  { tag: t.operator, color: '#475569' },
  { tag: t.atom, color: '#0f766e' },
])

const theme = EditorView.theme({
  '&': { height: '100%', fontSize: '12.5px', backgroundColor: '#ffffff' },
  '.cm-scroller': { fontFamily: '"JetBrains Mono Variable", ui-monospace, monospace', lineHeight: '1.55' },
  '.cm-gutters': { backgroundColor: '#f8fafc', borderRight: '1px solid #e5e7eb', color: '#94a3b8' },
  '.cm-activeLine': { backgroundColor: '#f1f5f9' },
  '.cm-activeLineGutter': { backgroundColor: '#e2e8f0' },
  '&.cm-focused': { outline: 'none' },
})

function toCm(state: EditorState, list: RuleDiagnostic[]): Diagnostic[] {
  const doc = state.doc
  return list.map((d) => {
    const line = doc.line(Math.min(Math.max(1, d.line), doc.lines))
    const from = Math.min(line.from + Math.max(0, d.column - 1), line.to)
    let to = from
    while (to < line.to && /[^\s;{}]/.test(doc.sliceString(to, to + 1))) to++
    if (to === from) to = Math.min(line.to, from + 1)
    if (to === from && from > line.from) return { from: from - 1, to: from, severity: d.severity, message: d.message }
    return { from, to, severity: d.severity, message: d.message }
  })
}

const lint = linter(
  async (v) => {
    if (!store.open || store.open.readOnly) return toCm(v.state, store.diagnostics)
    try {
      const list = await store.validate()
      return toCm(v.state, list)
    } catch {
      return []
    }
  },
  { delay: 500 },
)

function langFor(path: string) {
  return path.endsWith('.json') ? json() : mapcss
}

function create() {
  const f = file.value
  if (!host.value || !f) return
  view.value?.destroy()
  const state = EditorState.create({
    doc: f.content,
    extensions: [
      basicSetup,
      language.of(langFor(f.path)),
      readOnly.of([EditorState.readOnly.of(f.readOnly), EditorView.editable.of(!f.readOnly)]),
      syntaxHighlighting(style),
      theme,
      lintGutter(),
      lint,
      EditorView.lineWrapping,
      keymap.of([
        {
          key: 'Mod-s',
          preventDefault: true,
          run: () => {
            void store.save()
            return true
          },
        },
      ]),
      EditorView.updateListener.of((u) => {
        if (u.docChanged) store.setContent(u.state.doc.toString())
      }),
    ],
  })
  view.value = new EditorView({ state, parent: host.value })
  view.value.dispatch(setDiagnostics(view.value.state, toCm(view.value.state, store.diagnostics)))
}

watch(
  () => file.value?.path,
  () => create(),
)

watch(
  () => [file.value?.saved, file.value?.hash],
  () => {
    const v = view.value
    const f = file.value
    if (!v || !f) return
    if (v.state.doc.toString() !== f.content) v.dispatch({ changes: { from: 0, to: v.state.doc.length, insert: f.content } })
    v.dispatch({ effects: readOnly.reconfigure([EditorState.readOnly.of(f.readOnly), EditorView.editable.of(!f.readOnly)]) })
  },
)

watch(
  () => [file.value?.content, livePreview.value, isHighlight.value] as const,
  () => {
    const f = file.value
    if (!f || !isHighlight.value || !livePreview.value || !hl.isActive(f.path) || f.content === f.saved) {
      if (hl.preview) hl.setPreview('', null)
      return
    }
    hl.setPreview(f.path, f.content)
  },
)

watch(
  () => store.conflict,
  (c) => {
    mergeView?.destroy()
    mergeView = null
    if (!c) return
    setTimeout(() => {
      if (!mergeHost.value) return
      mergeView = new EditorView({
        parent: mergeHost.value,
        doc: c.mine,
        extensions: [
          basicSetup,
          langFor(file.value?.path ?? ''),
          syntaxHighlighting(style),
          theme,
          EditorState.readOnly.of(true),
          EditorView.editable.of(false),
          unifiedMergeView({ original: c.theirs, mergeControls: false }),
        ],
      })
    })
  },
)

function jump(d: RuleDiagnostic) {
  const v = view.value
  if (!v) return
  const line = v.state.doc.line(Math.min(Math.max(1, d.line), v.state.doc.lines))
  const pos = Math.min(line.from + Math.max(0, d.column - 1), line.to)
  v.dispatch({ selection: { anchor: pos }, scrollIntoView: true })
  v.focus()
}

function toggleOnMap() {
  if (file.value) hl.toggle(file.value.path)
}

onMounted(create)
onBeforeUnmount(() => {
  view.value?.destroy()
  mergeView?.destroy()
  if (hl.preview) hl.setPreview('', null)
})
</script>

<template>
  <div v-if="file" class="flex h-full min-h-0 flex-col" data-testid="rule-editor">
    <div class="flex h-10 shrink-0 items-center gap-2 border-b border-line px-2">
      <button class="btn btn-ghost btn-icon btn-sm w-7" title="Back to files" @click="store.close()"><Icon name="chevron-left" :size="14" /></button>
      <div class="min-w-0 flex-1">
        <div class="truncate font-mono text-[12px] font-medium" :title="file.path">
          {{ file.path }}<span v-if="store.dirty" class="text-changed"> ●</span>
        </div>
      </div>
      <Spinner v-if="store.validating" :size="12" class="text-fg-3" />
      <span v-if="errors" class="chip bg-removed-soft text-[11px] text-removed">{{ errors }} error{{ errors === 1 ? '' : 's' }}</span>
      <span v-else-if="warnings" class="chip bg-changed-soft text-[11px] text-changed">{{ warnings }} warning{{ warnings === 1 ? '' : 's' }}</span>
      <span v-else-if="!store.validating" class="chip bg-added-soft text-[11px] text-added">valid</span>
    </div>

    <div class="flex shrink-0 flex-wrap items-center gap-1.5 border-b border-line px-2 py-1.5">
      <template v-if="file.readOnly">
        <span class="chip bg-surface-3 text-[11px] text-fg-2">
          <Icon :name="origin?.builtIn ? 'database' : 'remote'" :size="11" />
          {{ origin?.builtIn ? 'Built-in' : `Remote · ${origin?.source ?? ''}` }}, read-only
        </span>
        <button class="btn btn-primary btn-sm" data-testid="copy-to-folder" @click="store.copyToFolder(file.path)">
          <Icon name="copy" :size="12" /> Copy to my folder
        </button>
      </template>
      <template v-else>
        <button class="btn btn-primary btn-sm" :disabled="store.saving || (!store.dirty && !file.isNew)" title="Save (Ctrl/⌘ S)" data-testid="save-rule" @click="store.save()">
          <Spinner v-if="store.saving" :size="11" /> Save
        </button>
        <button v-if="store.forceOffer" class="btn btn-danger btn-sm" @click="store.save(true)">Save with errors</button>
        <button class="btn btn-ghost btn-sm" title="Move to the folder's .trash" @click="confirmDelete = true"><Icon name="x" :size="12" /> Delete</button>
      </template>
      <template v-if="isHighlight">
        <button class="btn btn-sm ml-auto" :class="hl.isActive(file.path) && 'bg-accent-soft text-accent'" @click="toggleOnMap">
          <Icon :name="hl.isActive(file.path) ? 'eye' : 'eye-off'" :size="12" /> {{ hl.isActive(file.path) ? 'On map' : 'Show on map' }}
        </button>
        <label v-if="!file.readOnly" class="flex items-center gap-1 text-[11.5px] text-fg-3" title="Preview unsaved edits on the map">
          <input v-model="livePreview" type="checkbox" class="accent-[var(--accent)]" /> live
        </label>
      </template>
    </div>

    <div v-if="store.changedOnDisk" class="flex shrink-0 items-center gap-2 border-b border-changed/30 bg-changed-soft px-3 py-1.5 text-[12px] text-changed">
      <Icon name="alert" :size="13" /> Changed on disk
      <button class="btn btn-sm ml-auto" @click="store.openFile(file.path)">Reload</button>
      <button class="btn btn-ghost btn-sm" @click="store.changedOnDisk = false">Keep mine</button>
    </div>

    <div ref="host" class="min-h-0 flex-1 overflow-hidden" />

    <div class="scroll-thin max-h-[30%] shrink-0 overflow-y-auto border-t border-line" data-testid="rule-diagnostics">
      <div v-if="!store.diagnostics.length" class="px-3 py-2 text-[12px] text-fg-3">No problems.</div>
      <button
        v-for="(d, i) in store.diagnostics"
        :key="i"
        class="flex w-full items-start gap-2 px-3 py-1 text-left text-[12px] hover:bg-surface-2"
        @click="jump(d)"
      >
        <span class="mt-1.5 size-2 shrink-0 rounded-full" :class="d.severity === 'error' ? 'bg-error' : d.severity === 'warning' ? 'bg-warning' : 'bg-info'" />
        <span class="shrink-0 font-mono text-[11px] text-fg-3">{{ d.line }}:{{ d.column }}</span>
        <span class="min-w-0 flex-1 text-fg-2">{{ d.message }}</span>
      </button>
    </div>

    <ConfirmDialog v-if="confirmDelete" title="Delete file" confirm-label="Move to trash" danger @cancel="confirmDelete = false" @confirm="confirmDelete = false; store.remove(file.path)">
      Move <span class="font-mono">{{ file.path }}</span> to the rules folder's <span class="font-mono">.trash</span>? You can restore it from there.
    </ConfirmDialog>

    <Modal v-if="store.conflict" title="The file changed since you opened it" width="820px" @close="store.conflict = null">
      <div class="px-5 pt-3 pb-2 text-[12.5px] text-fg-2">
        Someone (another editor, Claude or a sync) saved <span class="font-mono">{{ file.path }}</span>. Lines in red are on disk, green are yours.
      </div>
      <div ref="mergeHost" class="mx-5 mb-4 h-[50vh] overflow-hidden rounded-md border border-line" data-testid="conflict-diff" />
      <template #footer>
        <button class="btn" @click="store.takeTheirs()">Reload from disk (discard mine)</button>
        <button class="btn btn-primary ml-auto" @click="store.keepMine()">Overwrite with mine</button>
      </template>
    </Modal>
  </div>
</template>
