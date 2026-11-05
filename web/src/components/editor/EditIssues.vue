<script setup lang="ts">
import { computed } from 'vue'
import { useEditorStore } from '@/stores/editor'
import { useEditIssuesStore } from '@/stores/editIssues'
import type { EditIssue } from '@/editor/validation/issues'
import Icon from '../Icon.vue'
import Spinner from '../Spinner.vue'

const editor = useEditorStore()
const issues = useEditIssuesStore()

const groups = computed(() =>
  [
    { key: 'error', label: 'Errors', list: issues.errors },
    { key: 'warning', label: 'Warnings', list: issues.warnings },
    { key: 'other', label: 'Other', list: issues.others },
  ].filter((g) => g.list.length),
)

function source(i: EditIssue): string {
  return i.source === 'id' ? `iD · ${i.type}` : `${i.file}:${i.line}${i.group ? ` · ${i.group}` : ''}`
}

function severityClass(i: EditIssue): string {
  return i.severity === 'error' ? 'text-error' : i.severity === 'warning' ? 'text-warning' : 'text-accent'
}
</script>

<template>
  <div
    class="pointer-events-auto flex max-h-[min(60vh,520px)] w-[340px] flex-col overflow-hidden rounded-lg border border-line bg-surface/95 text-[12px] shadow-pop backdrop-blur"
    data-testid="edit-issues"
  >
    <div class="flex items-center gap-2 border-b border-line px-3 py-2">
      <Icon name="alert" :size="14" :class="issues.errors.length ? 'text-error' : issues.warnings.length ? 'text-warning' : 'text-fg-3'" />
      <span class="font-medium text-fg">Issues in your edits</span>
      <Spinner v-if="issues.running" :size="11" />
      <button class="btn btn-ghost btn-icon btn-sm ml-auto" title="Close" @click="issues.panelOpen = false"><Icon name="x" :size="13" /></button>
    </div>
    <div class="min-h-0 flex-1 overflow-y-auto">
      <p v-if="!issues.issues.length" class="px-3 py-3 text-fg-3" data-testid="edit-issues-empty">
        {{ editor.editCount ? 'No issues found in the edited features.' : 'Edit something to see its issues here.' }}
      </p>
      <section v-for="g in groups" :key="g.key">
        <h4 class="bg-surface-2 px-3 py-1 text-[11px] font-semibold tracking-wide text-fg-3 uppercase">{{ g.label }} ({{ g.list.length }})</h4>
        <article
          v-for="i in g.list"
          :key="i.id"
          class="border-b border-line px-3 py-2 last:border-b-0"
          :class="issues.focused === i.id && 'bg-accent/8'"
          data-testid="edit-issue"
        >
          <button class="flex w-full items-start gap-2 text-left" @click="issues.focus(i)">
            <Icon :name="i.severity === 'error' ? 'alert' : i.severity === 'warning' ? 'alert' : 'info'" :size="13" class="mt-px shrink-0" :class="severityClass(i)" />
            <span class="min-w-0">
              <span class="block text-fg">{{ i.message }}</span>
              <span class="block truncate font-mono text-[10.5px] text-fg-3">{{ source(i) }}</span>
            </span>
          </button>
          <div v-if="i.fixes.length" class="mt-1.5 flex flex-wrap gap-1 pl-5">
            <button
              v-for="f in i.fixes"
              :key="f.id"
              class="btn btn-sm"
              :class="f.id === 'ignore' || /ignore/i.test(f.title) ? 'btn-ghost' : ''"
              :disabled="!!f.disabled"
              :title="f.disabled ?? f.title"
              data-testid="edit-issue-fix"
              @click="editor.applyIssueFix(i.id, f.id)"
            >
              <Icon v-if="!(f.id === 'ignore' || /ignore/i.test(f.title))" name="bolt" :size="11" />{{ f.title }}
            </button>
          </div>
        </article>
      </section>
    </div>
    <div v-if="issues.sheetPaths.length || issues.sheetErrors.length" class="border-t border-line px-3 py-1.5 text-[11px] text-fg-3">
      iD validations and {{ issues.sheetPaths.length }} check file{{ issues.sheetPaths.length === 1 ? '' : 's' }}
      <span v-if="issues.sheetErrors.length" class="text-error" :title="issues.sheetErrors.join('\n')">· {{ issues.sheetErrors.length }} failed to load</span>
    </div>
  </div>
</template>
