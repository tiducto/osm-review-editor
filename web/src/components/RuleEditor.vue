<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { EditorView, keymap } from '@codemirror/view'
import { EditorState, Compartment } from '@codemirror/state'
import { basicSetup } from 'codemirror'
import { json } from '@codemirror/lang-json'
import { HighlightStyle, syntaxHighlighting } from '@codemirror/language'
import { linter, lintGutter, setDiagnostics, type Diagnostic } from '@codemirror/lint'
import { unifiedMergeView } from '@codemirror/merge'
import { tags } from '@lezer/highlight'
import type { RuleDiagnostic } from '@/generated/models'
import { mapcss } from '@/lib/mapcssLanguage'
import { useRuleFilesStore } from '@/stores/ruleFiles'
import { useHighlightsStore } from '@/stores/highlights'
import Icon from './Icon.vue'
import Spinner from './Spinner.vue'
import ConfirmDialog from './ConfirmDialog.vue'
import Modal from './Modal.vue'
import { useI18n, type MessageKey } from '@/i18n'

const store = useRuleFilesStore()
const hl = useHighlightsStore()
const { t, tn, locale } = useI18n()

const CM_PHRASES: [string, MessageKey][] = [
  ['Find', 'ruleEditor.cm.find'],
  ['Replace', 'ruleEditor.cm.replace'],
  ['next', 'ruleEditor.cm.next'],
  ['previous', 'ruleEditor.cm.previous'],
  ['all', 'ruleEditor.cm.all'],
  ['match case', 'ruleEditor.cm.matchCase'],
  ['regexp', 'ruleEditor.cm.regexp'],
  ['by word', 'ruleEditor.cm.byWord'],
  ['replace', 'ruleEditor.cm.replaceOne'],
  ['replace all', 'ruleEditor.cm.replaceAll'],
  ['close', 'ruleEditor.cm.close'],
  ['Go to line', 'ruleEditor.cm.goToLine'],
  ['go', 'ruleEditor.cm.go'],
  ['replaced match on line $', 'ruleEditor.cm.replacedOnLine'],
  ['replaced $ matches', 'ruleEditor.cm.replacedMatches'],
  ['current match', 'ruleEditor.cm.currentMatch'],
  ['on line', 'ruleEditor.cm.onLine'],
  ['Diagnostics', 'ruleEditor.cm.diagnostics'],
  ['No diagnostics', 'ruleEditor.cm.noDiagnostics'],
  ['Control character', 'ruleEditor.cm.controlCharacter'],
  ['$ unchanged lines', 'ruleEditor.cm.unchangedLines'],
  ['Revert this chunk', 'ruleEditor.cm.revertChunk'],
  ['Accept', 'ruleEditor.cm.accept'],
  ['Reject', 'ruleEditor.cm.reject'],
  ['Folded lines', 'ruleEditor.cm.foldedLines'],
  ['Unfolded lines', 'ruleEditor.cm.unfoldedLines'],
  ['to', 'ruleEditor.cm.to'],
  ['folded code', 'ruleEditor.cm.foldedCode'],
  ['unfold', 'ruleEditor.cm.unfold'],
  ['Fold line', 'ruleEditor.cm.foldLine'],
  ['Unfold line', 'ruleEditor.cm.unfoldLine'],
  ['Selection deleted', 'ruleEditor.cm.selectionDeleted'],
  ['Completions', 'ruleEditor.cm.completions'],
]

function phrasesExt() {
  if (locale.value !== 'cs') return []
  return EditorState.phrases.of(Object.fromEntries(CM_PHRASES.map(([k, key]) => [k, t(key)])))
}

const host = ref<HTMLDivElement | null>(null)
const mergeHost = ref<HTMLDivElement | null>(null)
const view = shallowRef<EditorView | null>(null)
let mergeView: EditorView | null = null
const language = new Compartment()
const readOnly = new Compartment()
const phrases = new Compartment()
const mergePhrases = new Compartment()
const confirmDelete = ref(false)
const livePreview = ref(true)

const file = computed(() => store.open)
const isHighlight = computed(() => file.value?.kind === 'highlight')
const origin = computed(() => file.value?.info?.origin ?? null)
const errors = computed(() => store.diagnostics.filter((d) => d.severity === 'error').length)
const warnings = computed(() => store.diagnostics.filter((d) => d.severity === 'warning').length)

const style = HighlightStyle.define([
  { tag: tags.comment, color: '#888888', fontStyle: 'italic' },
  { tag: tags.string, color: '#008800' },
  { tag: [tags.number, tags.bool], color: '#b15500' },
  { tag: tags.color, color: '#b15500' },
  { tag: tags.keyword, color: '#597be7', fontWeight: 'bold' },
  { tag: tags.typeName, color: '#597be7', fontWeight: 'bold' },
  { tag: tags.className, color: '#0055bb' },
  { tag: tags.labelName, color: '#0055bb' },
  { tag: tags.attributeName, color: '#333333', fontWeight: 'bold' },
  { tag: tags.propertyName, color: '#0055bb' },
  { tag: tags.macroName, color: '#7092ff' },
  { tag: tags.regexp, color: '#b15500' },
  { tag: tags.operator, color: '#666666' },
  { tag: tags.atom, color: '#0055bb' },
])

const lintDot = (c: string) => `url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='10' height='10'%3E%3Ccircle cx='5' cy='5' r='4' fill='${encodeURIComponent(c)}'/%3E%3C/svg%3E")`
const lintWave = (c: string) =>
  `url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='6' height='3'%3E%3Cpath d='m0 3 l2 -2 l1 0 l2 2 l1 0' stroke='${encodeURIComponent(c)}' fill='none' stroke-width='.9'/%3E%3C/svg%3E")`

const theme = EditorView.theme({
  '&': { height: '100%', fontSize: '12px', backgroundColor: '#ffffff', color: '#333333' },
  '.cm-scroller': { fontFamily: "ui-monospace, 'SF Mono', Menlo, Consolas, monospace", lineHeight: '1.6' },
  '.cm-content': { caretColor: '#333333' },
  '.cm-cursor, .cm-dropCursor': { borderLeftColor: '#333333' },
  '.cm-gutters': { backgroundColor: '#f6f6f6', borderRight: '1px solid #cccccc', color: '#888888' },
  '.cm-activeLine': { backgroundColor: '#f6f6f6' },
  '.cm-activeLineGutter': { backgroundColor: '#ececec', color: '#333333' },
  '.cm-selectionBackground, &.cm-focused > .cm-scroller > .cm-selectionLayer .cm-selectionBackground, .cm-content ::selection': {
    backgroundColor: '#e8ebff',
  },
  '.cm-selectionMatch': { backgroundColor: '#e8ebff' },
  '&.cm-focused .cm-matchingBracket': { backgroundColor: '#e8ebff', outline: '1px solid #7092ff' },
  '.cm-searchMatch': { backgroundColor: '#ffffcc', outline: '1px solid #ff9900' },
  '.cm-searchMatch.cm-searchMatch-selected': { backgroundColor: '#e8ebff' },
  '.cm-foldPlaceholder': { backgroundColor: '#ececec', border: '1px solid #cccccc', color: '#666666', borderRadius: '3px' },
  '.cm-panels': { backgroundColor: '#f6f6f6', color: '#333333' },
  '.cm-panels.cm-panels-top': { borderBottom: '1px solid #cccccc' },
  '.cm-panels.cm-panels-bottom': { borderTop: '1px solid #cccccc' },
  '.cm-textfield': { border: '1px solid #cccccc', borderRadius: '4px', backgroundColor: '#ffffff', fontSize: '12px' },
  '.cm-button': { backgroundImage: 'none', backgroundColor: '#ffffff', border: '1px solid #cccccc', borderRadius: '4px', fontWeight: 'bold' },
  '.cm-tooltip': { border: '1px solid #cccccc', backgroundColor: '#ffffff', borderRadius: '4px', boxShadow: '0 0 4px rgb(0 0 0 / 0.18)' },
  '.cm-tooltip-autocomplete > ul > li[aria-selected]': { backgroundColor: '#7092ff', color: '#ffffff' },
  '.cm-diagnostic': { fontFamily: 'inherit' },
  '.cm-diagnostic-error': { borderLeft: '4px solid #ff0000' },
  '.cm-diagnostic-warning': { borderLeft: '4px solid #ff9900' },
  '.cm-diagnostic-info': { borderLeft: '4px solid #0055bb' },
  '.cm-lintRange-error': { backgroundImage: lintWave('#ff0000') },
  '.cm-lintRange-warning': { backgroundImage: lintWave('#ff9900') },
  '.cm-lintRange-info': { backgroundImage: lintWave('#0055bb') },
  '.cm-lint-marker-error': { content: lintDot('#ff0000') },
  '.cm-lint-marker-warning': { content: lintDot('#ff9900') },
  '.cm-lint-marker-info': { content: lintDot('#0055bb') },
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
      phrases.of(phrasesExt()),
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
          mergePhrases.of(phrasesExt()),
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

watch(locale, () => {
  view.value?.dispatch({ effects: phrases.reconfigure(phrasesExt()) })
  mergeView?.dispatch({ effects: mergePhrases.reconfigure(phrasesExt()) })
})

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
    <div class="flex h-10 shrink-0 items-center gap-2 border-b border-line-strong bg-surface px-2">
      <button class="btn btn-ghost btn-icon btn-sm w-7" :title="t('ruleEditor.back')" @click="store.close()"><Icon name="chevron-left" :size="16" /></button>
      <div class="min-w-0 flex-1">
        <div class="truncate font-mono text-[12px] font-bold" :title="file.path">
          {{ file.path }}<span v-if="store.dirty" class="text-changed"> ●</span>
        </div>
      </div>
      <Spinner v-if="store.validating" :size="12" class="text-fg-3" />
      <span v-if="errors" class="chip bg-removed-soft text-[11px] text-removed">{{ tn('ruleEditor.errors', errors) }}</span>
      <span v-else-if="warnings" class="chip bg-changed-soft text-[11px] text-changed">{{ tn('ruleEditor.warnings', warnings) }}</span>
      <span v-else-if="!store.validating" class="chip bg-added-soft text-[11px] text-added">{{ t('ruleEditor.valid') }}</span>
    </div>

    <div class="flex shrink-0 flex-wrap items-center gap-1.5 border-b border-line-strong bg-surface-2 px-2 py-1.5">
      <template v-if="file.readOnly">
        <span class="chip bg-surface-3 text-[11px] text-fg-2">
          <Icon :name="origin?.builtIn ? 'database' : 'remote'" :size="11" />
          {{ origin?.builtIn ? t('ruleEditor.builtInReadOnly') : t('ruleEditor.remoteReadOnly', { source: origin?.source ?? '' }) }}
        </span>
        <button class="btn btn-primary btn-sm" data-testid="copy-to-folder" @click="store.copyToFolder(file.path)">
          <Icon name="copy" :size="12" /> {{ t('ruleEditor.copyToFolder') }}
        </button>
      </template>
      <template v-else>
        <button class="btn btn-primary btn-sm" :disabled="store.saving || (!store.dirty && !file.isNew)" :title="t('ruleEditor.saveTitle')" data-testid="save-rule" @click="store.save()">
          <Spinner v-if="store.saving" :size="11" /> {{ t('common.save') }}
        </button>
        <button v-if="store.forceOffer" class="btn btn-danger btn-sm" @click="store.save(true)">{{ t('ruleEditor.saveWithErrors') }}</button>
        <button class="btn btn-ghost btn-sm" :title="t('ruleEditor.deleteTitle')" @click="confirmDelete = true"><Icon name="x" :size="12" /> {{ t('common.delete') }}</button>
      </template>
      <template v-if="isHighlight">
        <button class="btn btn-sm ml-auto" :class="hl.isActive(file.path) && 'border-accent bg-accent text-white hover:bg-accent-strong'" @click="toggleOnMap">
          <Icon :name="hl.isActive(file.path) ? 'eye' : 'eye-off'" :size="12" /> {{ hl.isActive(file.path) ? t('ruleEditor.onMap') : t('ruleEditor.showOnMap') }}
        </button>
        <label v-if="!file.readOnly" class="flex items-center gap-1 text-[11.5px] font-bold text-fg-2" :title="t('ruleEditor.livePreviewTitle')">
          <input v-model="livePreview" type="checkbox" class="accent-[var(--accent)]" /> {{ t('ruleEditor.live') }}
        </label>
      </template>
    </div>

    <div v-if="store.changedOnDisk" class="flex shrink-0 items-center gap-2 border-b border-line-strong border-l-4 border-l-warning bg-changed-soft px-3 py-1.5 text-[12px] text-fg">
      <Icon name="alert" :size="14" class="text-warning" /> {{ t('ruleEditor.changedOnDisk') }}
      <button class="btn btn-sm ml-auto" @click="store.openFile(file.path)">{{ t('common.reload') }}</button>
      <button class="btn btn-ghost btn-sm" @click="store.changedOnDisk = false">{{ t('ruleEditor.keepMine') }}</button>
    </div>

    <div ref="host" class="min-h-0 flex-1 overflow-hidden" />

    <div class="scroll-thin max-h-[30%] shrink-0 overflow-y-auto border-t border-line-strong bg-surface-2" data-testid="rule-diagnostics">
      <div v-if="!store.diagnostics.length" class="px-3 py-2 text-[12px] text-fg-3">{{ t('ruleEditor.noProblems') }}</div>
      <button
        v-for="(d, i) in store.diagnostics"
        :key="i"
        class="row-btn items-start border-b border-l-4 border-b-line bg-surface px-3 py-1.5 text-[12px]"
        :class="d.severity === 'error' ? 'border-l-error' : d.severity === 'warning' ? 'border-l-warning' : 'border-l-info'"
        @click="jump(d)"
      >
        <span class="mt-1 size-2 shrink-0 rounded-full" :class="d.severity === 'error' ? 'bg-error' : d.severity === 'warning' ? 'bg-warning' : 'bg-info'" />
        <span class="shrink-0 font-mono text-[11px] text-fg-3">{{ d.line }}:{{ d.column }}</span>
        <span class="min-w-0 flex-1 text-fg">{{ d.message }}</span>
      </button>
    </div>

    <ConfirmDialog v-if="confirmDelete" :title="t('ruleEditor.deleteFile')" :confirm-label="t('ruleEditor.moveToTrash')" danger @cancel="confirmDelete = false" @confirm="confirmDelete = false; store.remove(file.path)">
      {{ t('ruleEditor.deleteBefore') }} <span class="font-mono">{{ file.path }}</span> {{ t('ruleEditor.deleteMiddle') }} <span class="font-mono">.trash</span>{{ t('ruleEditor.deleteAfter') }}
    </ConfirmDialog>

    <Modal v-if="store.conflict" :title="t('ruleEditor.conflictTitle')" width="820px" @close="store.conflict = null">
      <div class="px-5 pt-3 pb-2 text-[12px] text-fg-2">
        {{ t('ruleEditor.conflictBefore') }} <span class="font-mono font-bold text-fg">{{ file.path }}</span>{{ t('ruleEditor.conflictAfter') }}
      </div>
      <div ref="mergeHost" class="mx-5 mb-4 h-[50vh] overflow-hidden rounded-md border border-line-strong" data-testid="conflict-diff" />
      <template #footer>
        <button class="btn" @click="store.takeTheirs()">{{ t('ruleEditor.takeTheirs') }}</button>
        <button class="btn btn-primary ml-auto" @click="store.keepMine()">{{ t('ruleEditor.overwrite') }}</button>
      </template>
    </Modal>
  </div>
</template>
