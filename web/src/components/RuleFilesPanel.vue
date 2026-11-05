<script setup lang="ts">
import { ref } from 'vue'
import type { RuleFileInfo, RuleKind } from '@/generated/models'
import { useRuleFilesStore, KIND_LABEL, KIND_ORDER } from '@/stores/ruleFiles'
import { TEMPLATE_KINDS, pathFor } from '@/lib/ruleTemplates'
import Icon from './Icon.vue'
import Spinner from './Spinner.vue'
import Modal from './Modal.vue'

const store = useRuleFilesStore()
const collapsed = ref(new Set<RuleKind>())
const creating = ref<RuleKind | null>(null)
const newName = ref('')

function toggle(k: RuleKind) {
  const s = new Set(collapsed.value)
  if (s.has(k)) s.delete(k)
  else s.add(k)
  collapsed.value = s
}

function name(f: RuleFileInfo) {
  return f.path.replace(/^(highlights|checks|presets\/smart|presets|remote)\//, '')
}

function problems(f: RuleFileInfo) {
  const e = f.diagnostics.filter((d) => d.severity === 'error').length
  const w = f.diagnostics.filter((d) => d.severity === 'warning').length
  return { e, w }
}

function startCreate(k: RuleKind) {
  creating.value = k
  newName.value = ''
}

async function confirmCreate() {
  const k = creating.value
  if (!k || !newName.value.trim()) return
  creating.value = null
  await store.create(k, newName.value)
}
</script>

<template>
  <div class="flex h-full flex-col" data-testid="rule-files">
    <div class="space-y-2 border-b border-line px-3 py-3">
      <div class="flex items-center gap-2">
        <span class="section-title">Rules folder</span>
        <Spinner v-if="store.loading" :size="11" class="text-fg-3" />
        <button class="btn btn-ghost btn-icon btn-sm ml-auto w-6" title="Reload" @click="store.load()"><Icon name="refresh" :size="12" /></button>
      </div>
      <p class="truncate font-mono text-[11px] text-fg-3" :title="store.rulesDir">{{ store.rulesDir || '…' }}</p>
      <input v-model="store.query" class="input" placeholder="Find a file…" />
      <div class="flex flex-wrap gap-1">
        <button v-for="t in TEMPLATE_KINDS" :key="t.kind" class="btn btn-sm" :title="t.hint" :data-testid="`new-${t.kind}`" @click="startCreate(t.kind)">
          <Icon name="plus" :size="11" /> {{ t.label }}
        </button>
      </div>
    </div>

    <div class="scroll-thin min-h-0 flex-1 overflow-y-auto py-1">
      <section v-for="k in KIND_ORDER" :key="k">
        <button class="flex w-full items-center gap-1.5 px-3 py-1.5 text-left" @click="toggle(k)">
          <Icon :name="collapsed.has(k) ? 'chevron-right' : 'chevron-down'" :size="12" class="text-fg-3" />
          <span class="section-title">{{ KIND_LABEL[k] }}</span>
          <span class="text-[11px] text-fg-3">{{ store.grouped.get(k)?.length ?? 0 }}</span>
        </button>
        <ul v-if="!collapsed.has(k)">
          <li v-for="f in store.grouped.get(k)" :key="f.path">
            <button
              class="flex w-full items-center gap-2 py-1 pr-3 pl-7 text-left hover:bg-surface-2"
              :class="store.open?.path === f.path && 'bg-accent-soft'"
              :data-testid="`rule-file-${f.path}`"
              @click="store.openFile(f.path)"
            >
              <span class="min-w-0 flex-1 truncate font-mono text-[12px]" :title="f.path">{{ name(f) }}</span>
              <span v-if="f.origin?.builtIn" class="chip shrink-0 bg-surface-3 text-[10px] text-fg-3" title="Shipped with the app; copy it to edit">built-in</span>
              <span v-else-if="f.origin" class="chip shrink-0 bg-surface-3 text-[10px] text-fg-3" :title="f.origin.url">remote</span>
              <span v-if="problems(f).e" class="chip shrink-0 bg-removed-soft text-[10px] text-removed">{{ problems(f).e }}</span>
              <span v-else-if="problems(f).w" class="chip shrink-0 bg-changed-soft text-[10px] text-changed">{{ problems(f).w }}</span>
            </button>
          </li>
          <li v-if="!store.grouped.get(k)?.length" class="py-1 pl-7 text-[11.5px] text-fg-3">None</li>
        </ul>
      </section>
    </div>

    <Modal v-if="creating" :title="`New ${TEMPLATE_KINDS.find((t) => t.kind === creating)?.label.toLowerCase()}`" width="420px" @close="creating = null">
      <form class="space-y-2 px-5 py-4" @submit.prevent="confirmCreate">
        <label class="block text-[12px] text-fg-2">Name</label>
        <input v-model="newName" class="input" autofocus placeholder="e.g. Cycleways without surface" data-testid="new-rule-name" />
        <p class="font-mono text-[11px] text-fg-3">{{ pathFor(creating, newName) ?? '…' }}</p>
        <p class="text-[12px] text-fg-3">{{ TEMPLATE_KINDS.find((t) => t.kind === creating)?.hint }}</p>
      </form>
      <template #footer>
        <button class="btn ml-auto" @click="creating = null">Cancel</button>
        <button class="btn btn-primary" :disabled="!newName.trim()" data-testid="create-rule" @click="confirmCreate">Create</button>
      </template>
    </Modal>
  </div>
</template>
